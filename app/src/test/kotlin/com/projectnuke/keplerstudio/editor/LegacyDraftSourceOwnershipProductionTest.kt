package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import com.projectnuke.keplerstudio.bridge.installNativeSessionFactoryForTest
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Production ownership tests for the legacy Draft compatibility source registry.
 *
 * Phase 1 foundation: the registry must represent multi-owner claims on one
 * canonical file, scope every release to its own owner, and keep in-flight
 * save/restore roots alive for exactly the operation lifetime. No aggressive
 * reclamation is enabled here; deletion observations use the registry's own
 * linearized delete boundary.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LegacyDraftSourceOwnershipProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun setUp() {
        resetRestoredWorkingSourceSandboxForTest(context)
        resetDraftSandboxForTest(context)
        LegacyDraftSourceOwnership.clearForTest()
        harness = OwnedEditorViewModelHarness(context, installBitmapCopySeam = true)
    }

    @After
    fun tearDown() {
        val failures = CleanupFailureAggregator()
        failures.attempt { harness.close() }
        failures.attempt { resetRestoredWorkingSourceSandboxForTest(context) }
        failures.attempt { resetDraftSandboxForTest(context) }
        failures.attempt { LegacyDraftSourceOwnership.clearForTest() }
        failures.throwIfAny()
    }

    // ------------------------------------------------------------------
    // Registry semantics
    // ------------------------------------------------------------------

    @Test
    fun singleOwnerClaimProtectsAndReleases() {
        val file = newLegacySourceFile("single-owner")
        val owner = LegacyDraftSourceOwnership.OwnerKey.create()
        val path = file.absolutePath

        assertEquals(
            "unclaimed candidate must be deletable",
            LegacyDraftSourceOwnership.DeleteResult.DELETED,
            LegacyDraftSourceOwnership.deleteIfUnowned(file),
        )
        assertTrue(file.createNewFile())

        LegacyDraftSourceOwnership.acquire(owner, LegacyDraftSourceOwnership.RootKind.VISIBLE_DRAFT, path)
        assertTrue(LegacyDraftSourceOwnership.isProtectedForTest(file))
        assertEquals(
            setOf(LegacyDraftSourceOwnership.RootKind.VISIBLE_DRAFT),
            LegacyDraftSourceOwnership.kindsForTest(file),
        )

        LegacyDraftSourceOwnership.release(owner, LegacyDraftSourceOwnership.RootKind.VISIBLE_DRAFT, path)
        assertFalse(LegacyDraftSourceOwnership.isProtectedForTest(file))
    }

    @Test
    fun twoOwnersShareOneCanonicalFileUntilBothRelease() {
        val file = newLegacySourceFile("shared")
        val ownerA = LegacyDraftSourceOwnership.OwnerKey.create()
        val ownerB = LegacyDraftSourceOwnership.OwnerKey.create()

        LegacyDraftSourceOwnership.acquire(ownerA, LegacyDraftSourceOwnership.RootKind.VISIBLE_DRAFT, file.absolutePath)
        // A different spelling of the same canonical file must dedupe to one
        // canonical entry with two independent claims.
        LegacyDraftSourceOwnership.acquire(
            ownerB,
            LegacyDraftSourceOwnership.RootKind.DOCUMENT,
            File(file.parentFile, "./${file.name}").absolutePath,
        )
        assertEquals(1, LegacyDraftSourceOwnership.protectedPathCountForTest())

        // Owner A releases while B still owns the file.
        LegacyDraftSourceOwnership.release(ownerA, LegacyDraftSourceOwnership.RootKind.VISIBLE_DRAFT, file.absolutePath)
        assertTrue("B's claim must survive A's release", LegacyDraftSourceOwnership.isProtectedForTest(file))
        assertEquals(
            setOf(LegacyDraftSourceOwnership.RootKind.DOCUMENT),
            LegacyDraftSourceOwnership.kindsForTest(file),
        )
        assertEquals(
            LegacyDraftSourceOwnership.DeleteResult.PRESERVED_LIVE_DOCUMENT,
            LegacyDraftSourceOwnership.deleteIfUnowned(file),
        )

        LegacyDraftSourceOwnership.release(ownerB, LegacyDraftSourceOwnership.RootKind.DOCUMENT, file.absolutePath)
        assertFalse(LegacyDraftSourceOwnership.isProtectedForTest(file))
    }

    @Test
    fun replaceMovesOnlyThatOwnerRoot() {
        val fileA = newLegacySourceFile("replace-a")
        val fileB = newLegacySourceFile("replace-b")
        val ownerA = LegacyDraftSourceOwnership.OwnerKey.create()
        val ownerB = LegacyDraftSourceOwnership.OwnerKey.create()

        LegacyDraftSourceOwnership.acquire(ownerA, LegacyDraftSourceOwnership.RootKind.VISIBLE_DRAFT, fileA.absolutePath)
        LegacyDraftSourceOwnership.acquire(ownerB, LegacyDraftSourceOwnership.RootKind.VISIBLE_DRAFT, fileA.absolutePath)

        // VM A moves its visible root A -> B while VM B still owns A.
        LegacyDraftSourceOwnership.replace(ownerA, LegacyDraftSourceOwnership.RootKind.VISIBLE_DRAFT, fileA.absolutePath, fileB.absolutePath)

        assertTrue("old file must stay protected by VM B", LegacyDraftSourceOwnership.isProtectedForTest(fileA))
        assertTrue("new file must be protected by VM A", LegacyDraftSourceOwnership.isProtectedForTest(fileB))
        assertEquals(2, LegacyDraftSourceOwnership.protectedPathCountForTest())

        // Releasing VM B must not touch VM A's new root.
        LegacyDraftSourceOwnership.releaseOwner(ownerB)
        assertFalse(LegacyDraftSourceOwnership.isProtectedForTest(fileA))
        assertTrue(LegacyDraftSourceOwnership.isProtectedForTest(fileB))

        LegacyDraftSourceOwnership.releaseOwner(ownerA)
        assertEquals(0, LegacyDraftSourceOwnership.protectedPathCountForTest())
    }

    @Test
    fun classificationPrefersOperationOverDocumentOverVisibleRoot() {
        val file = newLegacySourceFile("precedence")
        val vmOwner = LegacyDraftSourceOwnership.OwnerKey.create()
        val opOwner = LegacyDraftSourceOwnership.OwnerKey.create()

        LegacyDraftSourceOwnership.acquire(vmOwner, LegacyDraftSourceOwnership.RootKind.VISIBLE_DRAFT, file.absolutePath)
        assertEquals(
            LegacyDraftSourceOwnership.DeleteResult.PRESERVED_LIVE_VIEWMODEL,
            LegacyDraftSourceOwnership.deleteIfUnowned(file),
        )
        LegacyDraftSourceOwnership.acquire(vmOwner, LegacyDraftSourceOwnership.RootKind.DOCUMENT, file.absolutePath)
        assertEquals(
            LegacyDraftSourceOwnership.DeleteResult.PRESERVED_LIVE_DOCUMENT,
            LegacyDraftSourceOwnership.deleteIfUnowned(file),
        )
        LegacyDraftSourceOwnership.acquire(opOwner, LegacyDraftSourceOwnership.RootKind.OPERATION, file.absolutePath)
        assertEquals(
            LegacyDraftSourceOwnership.DeleteResult.PRESERVED_OPERATION,
            LegacyDraftSourceOwnership.deleteIfUnowned(file),
        )
        assertEquals(
            "mixed kinds must report every live kind",
            setOf(
                LegacyDraftSourceOwnership.RootKind.VISIBLE_DRAFT,
                LegacyDraftSourceOwnership.RootKind.DOCUMENT,
                LegacyDraftSourceOwnership.RootKind.OPERATION,
            ),
            LegacyDraftSourceOwnership.kindsForTest(file),
        )
        LegacyDraftSourceOwnership.releaseOwner(opOwner)
        LegacyDraftSourceOwnership.releaseOwner(vmOwner)
        assertEquals(0, LegacyDraftSourceOwnership.protectedPathCountForTest())
    }

    @Test
    fun deleteReportsAbsentAndFailedTruthfully() {
        val directory = context.filesDir.resolve("drafts/current").apply { mkdirs() }
        val missing = directory.resolve("source_never-created.img")
        missing.delete()
        assertEquals(
            LegacyDraftSourceOwnership.DeleteResult.ALREADY_ABSENT,
            LegacyDraftSourceOwnership.deleteIfUnowned(missing),
        )
        directory.resolve("source_not-a-file.img").mkdirs()
        try {
            assertEquals(
                "directory delete failure must not be reported as deleted",
                LegacyDraftSourceOwnership.DeleteResult.FAILED,
                LegacyDraftSourceOwnership.deleteIfUnowned(directory),
            )
            assertTrue(directory.isDirectory)
        } finally {
            directory.delete()
        }
    }

    @Test
    fun staleCleanupCannotDeleteAcquiredPath() {
        val file = newLegacySourceFile("linearized")
        val owner = LegacyDraftSourceOwnership.OwnerKey.create()
        val acquired = CountDownLatch(1)
        val cleanupDone = CountDownLatch(1)
        val results = arrayOfNulls<LegacyDraftSourceOwnership.DeleteResult>(1)

        LegacyDraftSourceOwnership.acquire(owner, LegacyDraftSourceOwnership.RootKind.OPERATION, file.absolutePath)
        acquired.countDown()
        Thread {
            // A cleanup pass that lists the file and only then consults the
            // registry must observe the live claim: listing and deletion are
            // separated by the acquire above.
            runCatching { acquired.await(10, TimeUnit.SECONDS) }
            results[0] = LegacyDraftSourceOwnership.deleteIfUnowned(file)
            cleanupDone.countDown()
        }.start()
        assertTrue(cleanupDone.await(15, TimeUnit.SECONDS))
        assertEquals(
            LegacyDraftSourceOwnership.DeleteResult.PRESERVED_OPERATION,
            results[0],
        )
        assertTrue("file must survive", file.isFile)
        LegacyDraftSourceOwnership.releaseOwner(owner)
    }

    @Test
    fun concurrentReleaseAndDeleteRemainLinearized() {
        repeat(STRESS_ITERATIONS) { iteration ->
            val file = newLegacySourceFile("stress-$iteration")
            val holder = LegacyDraftSourceOwnership.OwnerKey.create()
            val released = CountDownLatch(1)
            val deleted = CountDownLatch(1)
            var deleteOutcome: LegacyDraftSourceOwnership.DeleteResult? = null

            val deleter = Thread {
                runCatching { released.await(10, TimeUnit.SECONDS) }
                deleteOutcome = LegacyDraftSourceOwnership.deleteIfUnowned(file)
                deleted.countDown()
            }
            deleter.start()
            LegacyDraftSourceOwnership.acquire(holder, LegacyDraftSourceOwnership.RootKind.OPERATION, file.absolutePath)
            released.countDown()
            LegacyDraftSourceOwnership.releaseOwner(holder)
            assertTrue(deleted.await(15, TimeUnit.SECONDS))
            when (deleteOutcome) {
                LegacyDraftSourceOwnership.DeleteResult.PRESERVED_OPERATION -> assertTrue(file.isFile)
                LegacyDraftSourceOwnership.DeleteResult.DELETED -> assertFalse(file.exists())
                else -> failWithOutcome(deleteOutcome)
            }
            file.takeIf { it.exists() }?.delete()
        }
    }

    private fun failWithOutcome(outcome: LegacyDraftSourceOwnership.DeleteResult?): Nothing =
        throw AssertionError("unexpected delete outcome: $outcome")

    // ------------------------------------------------------------------
    // Production save-flow integration
    // ------------------------------------------------------------------

    @Test
    fun inFlightSaveKeepsPreviousLegacySourceAliveUntilSettlement() = runBlocking {
        seedLegacyDraft(exposure = 0.27f)
        // Gate the restore render so the save seam can be installed before the
        // adoption-time forced save is launched: that first post-adoption save
        // is the one whose previousVisibleDraftPath is the legacy source.
        val renderReached = CompletableDeferred<Unit>()
        val renderRelease = CompletableDeferred<Unit>()
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (request.operation == RenderOperation.DraftRestore) {
                    renderReached.complete(Unit)
                    renderRelease.await()
                }
                successRestoreOutput()
            }
        var sessionFactory: AutoCloseable? = null
        try {
            sessionFactory = installNativeSessionFactoryForTest { 8801L }
            val vm = harness.createEditor()
            awaitSignal(renderReached, "legacy restore must reach its render")

            // The migration has completed by now (snapshot precedes render), so
            // the protected path is the migrated source_*.img.
            val decodedSource = File(LegacyDraftSourceOwnership.protectedPathsForTest().single())
            assertEquals(
                setOf(LegacyDraftSourceOwnership.RootKind.OPERATION),
                LegacyDraftSourceOwnership.kindsForTest(decodedSource),
            )

            val seam = DraftSaveTestSeam(parkAt = DraftSaveStage.StorageTransactionAcquired)
            DraftSaveTestSeam.Registry.lastFailureReasonForTest = null
            val seamHandle = harness.ownSeam(DraftSaveTestSeam.install(vm, seam))
            renderRelease.complete(Unit)

            awaitSignal(seam.reached, "adoption save must reach the storage transaction gate")

            // While the adoption save transaction is parked, its captured
            // previous visible Draft source carries DOCUMENT + VISIBLE_DRAFT
            // roots of the adopting ViewModel plus the save's operation root.
            assertEquals(
                "parked adoption save must keep every live claim on the legacy source",
                setOf(
                    LegacyDraftSourceOwnership.RootKind.DOCUMENT,
                    LegacyDraftSourceOwnership.RootKind.VISIBLE_DRAFT,
                    LegacyDraftSourceOwnership.RootKind.OPERATION,
                ),
                LegacyDraftSourceOwnership.kindsForTest(decodedSource),
            )

            seam.releaseGate.complete(Unit)
            awaitInit(vm)
            seamHandle.close()

            // The adoption save may still be unwinding when startup completes;
            // gate on its terminal state before judging the outcome.
            awaitMainUntil {
                !vm.hasActiveDraftSaveJobForTest() &&
                    LegacyDraftSourceOwnership.kindsForTest(decodedSource) ==
                    setOf(LegacyDraftSourceOwnership.RootKind.DOCUMENT)
            }
            assertNotNull(
                "save must publish a generation; reason=${DraftSaveTestSeam.Registry.lastFailureReasonForTest}",
                vm.uiState.value.draftGenerationId,
            )
            assertTrue("legacy source must survive settlement", decodedSource.isFile)
        } finally {
            renderRelease.complete(Unit)
            sessionFactory?.close()
            renderer.close()
        }
    }

    @Test
    fun cancelledSaveReleasesItsOperationRoots() = runBlocking {
        seedLegacyDraft(exposure = 0.31f)
        val renderer = EditorRenderer.installRendererOverrideForTest { successRestoreOutput() }
        var sessionFactory: AutoCloseable? = null
        try {
            sessionFactory = installNativeSessionFactoryForTest { 8802L }
            val vm = harness.createEditor()
            awaitInit(vm)
            val legacyPath = awaitLegacyDocumentSteadyState(vm)

            val cancellationCaught = CompletableDeferred<Unit>()
            val seam = DraftSaveTestSeam(
                parkAt = DraftSaveStage.PointerPersistedBeforeSettlement,
                cancellationCaught = cancellationCaught,
            )
            val seamHandle = harness.ownSeam(DraftSaveTestSeam.install(vm, seam))
            val saveScope = CoroutineScope(Dispatchers.Default)
            val save = saveScope.async { vm.persistDraftSnapshotNow() }
            awaitSignal(seam.reached, "save must reach the pointer-persisted gate")

            seam.cancelParkedOwner(kotlinx.coroutines.CancellationException("test cancel"))
            awaitSignal(cancellationCaught, "cancelled save must surface its cancellation")

            awaitMainUntil {
                !vm.hasActiveDraftSaveJobForTest() &&
                    LegacyDraftSourceOwnership.kindsForTest(File(legacyPath)) ==
                    setOf(LegacyDraftSourceOwnership.RootKind.DOCUMENT)
            }
            runCatching { awaitDeferred(save, "cancelled save caller must complete") }
            vm.acknowledgeEditorLeave()
            seamHandle.close()
            saveScope.cancel()

            assertTrue("legacy source must survive the cancelled save", File(legacyPath).isFile)
        } finally {
            sessionFactory?.close()
            renderer.close()
        }
    }

    // ------------------------------------------------------------------
    // Production restore-flow integration
    // ------------------------------------------------------------------

    @Test
    fun legacyRestoreKeepsSourceAliveWhileDecodingThenTransfersOnAdoption() = runBlocking {
        val legacyFile = seedLegacyDraft(exposure = 0.42f)
        val decodeReached = CompletableDeferred<Unit>()
        val decodeRelease = CompletableDeferred<Unit>()
        val restoreSeam = DraftRestoreTestSeam(
            onStage = { stage, _ ->
                if (stage == DraftRestoreTestStage.SourceDecoded) {
                    decodeReached.complete(Unit)
                    decodeRelease.await()
                }
            },
        )
        val seamHandle = harness.ownSeam(DraftRestoreTestSeam.install(restoreSeam))
        val renderer = EditorRenderer.installRendererOverrideForTest { successRestoreOutput() }
        var sessionFactory: AutoCloseable? = null
        try {
            sessionFactory = installNativeSessionFactoryForTest { 8811L }
            val vm = harness.createEditor()
            awaitSignal(decodeReached, "legacy restore must reach the decoded stage")

            assertTrue(legacyFile.isFile)
            // resolveDraftRecovery migrates the fixed-name legacy source into
            // an owned source_*.img before decoding; the registry must protect
            // that exact migrated file while the restore decodes it.
            val protectedPaths = LegacyDraftSourceOwnership.protectedPathsForTest()
            assertEquals("exactly the decoded source must be protected", 1, protectedPaths.size)
            val decodedSource = File(protectedPaths.single())
            assertTrue(decodedSource.canonicalPath != legacyFile.canonicalPath)
            assertEquals(
                "decoding restore must hold the operation root",
                setOf(LegacyDraftSourceOwnership.RootKind.OPERATION),
                LegacyDraftSourceOwnership.kindsForTest(decodedSource),
            )

            decodeRelease.complete(Unit)
            awaitInit(vm)
            assertEquals(
                "restore must adopt the migrated source",
                decodedSource.canonicalPath,
                File(checkNotNull(vm.uiState.value.sourcePath)).canonicalPath,
            )
            // The adoption-time forced save settles next and moves the
            // visible Draft root onto the new generation payload; the
            // document root must remain on the adopted legacy source.
            awaitMainUntil {
                !vm.hasActiveDraftSaveJobForTest() &&
                    LegacyDraftSourceOwnership.kindsForTest(decodedSource) ==
                    setOf(LegacyDraftSourceOwnership.RootKind.DOCUMENT)
            }
        } finally {
            decodeRelease.complete(Unit)
            seamHandle.close()
            sessionFactory?.close()
            renderer.close()
        }
    }

    @Test
    fun cancelledLegacyRestoreReleasesTheOperationRoot() = runBlocking {
        val legacyFile = seedLegacyDraft(exposure = 0.53f)
        val decodeReached = CompletableDeferred<Unit>()
        val decodeRelease = CompletableDeferred<Unit>()
        val restoreSeam = DraftRestoreTestSeam(
            onStage = { stage, _ ->
                if (stage == DraftRestoreTestStage.SourceDecoded) {
                    decodeReached.complete(Unit)
                    decodeRelease.await()
                }
            },
        )
        val seamHandle = harness.ownSeam(DraftRestoreTestSeam.install(restoreSeam))
        val renderer = EditorRenderer.installRendererOverrideForTest { successRestoreOutput() }
        var sessionFactory: AutoCloseable? = null
        try {
            sessionFactory = installNativeSessionFactoryForTest { 8812L }
            val vm = harness.createEditor()
            awaitSignal(decodeReached, "legacy restore must park inside decoding")

            val protectedPaths = LegacyDraftSourceOwnership.protectedPathsForTest()
            assertEquals(1, protectedPaths.size)
            val decodedSource = File(protectedPaths.single())
            assertTrue(decodedSource.canonicalPath != legacyFile.canonicalPath)
            assertEquals(
                "decoding restore must hold the operation root",
                setOf(LegacyDraftSourceOwnership.RootKind.OPERATION),
                LegacyDraftSourceOwnership.kindsForTest(decodedSource),
            )

            // A superseding openImage invalidates the replaceable restore; the
            // parked decode unwinds through the normal cancellation path.
            harness.ownSeam(
                OpenImageTestSeam.install(
                    OpenImageTestSeam(
                        sourceTransactionFactory = { _, _ ->
                            throw IOException("superseding open intentionally fails")
                        },
                    ),
                ),
            )
            vm.openImage(Uri.parse("content://incoming/supersede-restore"))

            awaitMainUntil {
                LegacyDraftSourceOwnership.protectedPathCountForTest() == 0 &&
                    !vm.openImageJobActiveForTest()
            }

            assertTrue("legacy source must survive a cancelled restore", legacyFile.isFile)
            assertTrue("migrated source must survive a cancelled restore", decodedSource.isFile)
            assertEquals(
                "cancellation must release the restore's operation root",
                emptySet<LegacyDraftSourceOwnership.RootKind>(),
                LegacyDraftSourceOwnership.kindsForTest(decodedSource),
            )
        } finally {
            decodeRelease.complete(Unit)
            seamHandle.close()
            sessionFactory?.close()
            renderer.close()
        }
    }

    // ------------------------------------------------------------------
    // ViewModel teardown scoping
    // ------------------------------------------------------------------

    @Test
    fun viewModelTeardownReleasesOnlyItsOwnRoots() = runBlocking {
        val vmA = harness.createEditor()
        val vmB = harness.createEditor()
        awaitInit(vmA)
        awaitInit(vmB)
        val keyA = vmA.legacyDraftSourceOwnerKeyForTest()
        val keyB = vmB.legacyDraftSourceOwnerKeyForTest()
        val external = LegacyDraftSourceOwnership.OwnerKey.create()
        val sharedFile = newLegacySourceFile("teardown-shared")

        LegacyDraftSourceOwnership.acquire(keyA, LegacyDraftSourceOwnership.RootKind.VISIBLE_DRAFT, sharedFile.absolutePath)
        LegacyDraftSourceOwnership.acquire(keyB, LegacyDraftSourceOwnership.RootKind.VISIBLE_DRAFT, sharedFile.absolutePath)
        LegacyDraftSourceOwnership.acquire(external, LegacyDraftSourceOwnership.RootKind.OPERATION, sharedFile.absolutePath)
        assertEquals(1, LegacyDraftSourceOwnership.protectedPathCountForTest())

        harness.clearViewModels()

        assertTrue(
            "external operation root must survive ViewModel teardown",
            LegacyDraftSourceOwnership.isProtectedForTest(sharedFile),
        )
        assertEquals(
            setOf(LegacyDraftSourceOwnership.RootKind.OPERATION),
            LegacyDraftSourceOwnership.kindsForTest(sharedFile),
        )
        LegacyDraftSourceOwnership.releaseOwner(external)
        assertFalse(LegacyDraftSourceOwnership.isProtectedForTest(sharedFile))
        sharedFile.delete()
        Unit
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun newLegacySourceFile(name: String): File {
        val directory = context.filesDir.resolve("drafts/current").apply { mkdirs() }
        return directory.resolve("source_${name}.img").apply { createNewFile() }
    }

    private fun seedLegacyDraft(exposure: Float): File {
        val payload = context.cacheDir.resolve("legacy-ownership-${exposure.toString().replace('.', '-')}.png")
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            payload.outputStream().use { out ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
        } finally {
            bitmap.recycle()
        }
        val legacyDirectory = context.filesDir.resolve("drafts/current").apply { mkdirs() }
        val legacySource = legacyDirectory.resolve("source.img")
        payload.copyTo(legacySource, overwrite = true)
        context
            .getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DRAFT_SOURCE, legacySource.absolutePath)
            .putFloat("draft_exposure", exposure)
            .commit()
        return legacySource
    }

    private fun successRestoreOutput(): RenderResult.Success {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff00ff00.toInt())
        return RenderResult.Success(
            operation = RenderOperation.DraftRestore,
            requestedRoute = NativeRenderRoute.V1,
            output = bitmap,
            actualRoute = NativeRenderRoute.V1,
            decision = RenderRouteDecision.FollowDocument,
            usedDebugOverride = false,
            algorithmVersion = AlgorithmContracts.NATIVE_V1,
            participation = RenderParticipation(),
            durationMillis = 0L,
            knownTransientBytes = 0L,
        )
    }

    private fun awaitInit(vm: EditorViewModel) {
        awaitMainUntil { vm.startupInitCompletion.isCompleted && !vm.uiState.value.isBusy }
    }

    private fun awaitSignal(signal: CompletableDeferred<Unit>, description: String) {
        awaitEditorCompletionForTest(description, signal, timeoutMillis = 20_000L, pumpMain = ::pumpMain)
    }

    private suspend fun awaitDeferred(deferred: Deferred<Boolean>, description: String) {
        awaitEditorCompletionForTest(description, deferred, timeoutMillis = 20_000L, pumpMain = ::pumpMain)
        runBlocking { deferred.await() }
    }

    private fun awaitMainUntil(predicate: () -> Boolean) {
        awaitMainUntilWithDiagnostic(predicate) { "" }
    }

    private fun awaitMainUntilWithDiagnostic(
        predicate: () -> Boolean,
        diagnostic: () -> String,
    ) {
        repeat(4000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
        }
        assertTrue("awaitMainUntil timeout: ${diagnostic()}", predicate())
    }

    /** Drains the adoption-time forced save and verifies document-root steady state. */
    private fun awaitLegacyDocumentSteadyState(vm: EditorViewModel): String {
        val legacyPath = checkNotNull(vm.uiState.value.sourcePath)
        awaitMainUntilWithDiagnostic(
            {
                !vm.hasActiveDraftSaveJobForTest() &&
                    LegacyDraftSourceOwnership.kindsForTest(File(legacyPath)) ==
                    setOf(LegacyDraftSourceOwnership.RootKind.DOCUMENT)
            },
        ) {
            "paths=" + LegacyDraftSourceOwnership.refsForTest().entries.joinToString("|") { (path, refs) ->
                "${File(path).name}:$refs"
            } +
                " job=" + vm.hasActiveDraftSaveJobForTest() +
                " gen=" + vm.uiState.value.draftGenerationId +
                " src=" + vm.uiState.value.sourcePath +
                " draftSrc=" + vm.uiState.value.draftSourcePath +
                " msg=" + vm.uiState.value.message
        }
        return legacyPath
    }

    private fun pumpMain() {
        shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
    }

    private companion object {
        const val STRESS_ITERATIONS = 64
    }
}
