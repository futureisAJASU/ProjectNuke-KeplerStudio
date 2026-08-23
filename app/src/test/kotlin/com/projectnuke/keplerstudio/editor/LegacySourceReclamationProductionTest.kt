package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import com.projectnuke.keplerstudio.bridge.installNativeSessionFactoryForTest
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Ownership-aware reclamation races for obsolete legacy Draft compatibility
 * sources (`filesDir/drafts/current/source_*.img`).
 *
 * Every physical deletion decision must be made against authoritative state
 * AT DELETE TIME: the persistent pointer reread under the Coordinator write
 * lock plus the multi-owner registry boundary. A stale directory snapshot is
 * never sufficient.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LegacySourceReclamationProductionTest {
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
    // Required race 1: startup snapshots A as stale -> VM B acquires A ->
    // deletion preserves A.
    // ------------------------------------------------------------------

    @Test
    fun startupSnapshotCannotDeleteSourceAcquiredAfterListing() = runBlocking {
        // Live ViewModel instance created BEFORE the candidate exists, so its
        // own startup reconcile never observes the orphan.
        val liveVm = harness.createEditor()
        awaitMainUntil { liveVm.startupInitCompletion.isCompleted }
        val keyB = liveVm.legacyDraftSourceOwnerKeyForTest()

        val orphan = legacyFile("source_snapshot-race.img")
        val reconcileGate = StartupReconcileTestSeam()
        reconcileGate.workingSnapshotReached = CompletableDeferred()
        reconcileGate.workingSnapshotRelease = CompletableDeferred()
        val gateHandle = harness.ownSeam(StartupReconcileTestSeam.install(reconcileGate))

        val reconcileJob = CoroutineScope(Dispatchers.Default).async {
            reconcileStartupArtifacts(context, null)
        }
        awaitSignal(reconcileGate.workingSnapshotReached!!, "reconcile must reach the legacy listing gate")

        // The live ViewModel acquires the candidate AFTER the reconciler
        // listed the directory tree but BEFORE the legacy pass classifies it.
        LegacyDraftSourceOwnership.acquire(
            keyB,
            LegacyDraftSourceOwnership.RootKind.VISIBLE_DRAFT,
            orphan.absolutePath,
        )

        reconcileGate.workingSnapshotRelease!!.complete(Unit)
        val outcome = awaitOutcome(reconcileJob, "reconcile caller")
        gateHandle.close()

        assertTrue("live ViewModel claim must preserve A", orphan.exists())
        assertTrue(
            "classification must report a preserved-live outcome, got=" +
                outcome.entries.filter { it.path.contains(orphan.name) },
            outcome.entries.any {
                it.path.endsWith(orphan.name) &&
                    it.disposition in setOf(
                        StartupReconcileDisposition.PRESERVED_DOCUMENT,
                        StartupReconcileDisposition.PRESERVED_LIVE_TRANSACTION,
                        StartupReconcileDisposition.PRESERVED_LIVE_VIEWMODEL,
                    )
            },
        )
        LegacyDraftSourceOwnership.releaseOwner(keyB)
        orphan.delete()
        Unit
    }

    // ------------------------------------------------------------------
    // Required races 5 + 6: an operation root preserves A; once released,
    // a later cleaner may delete it.
    // ------------------------------------------------------------------

    @Test
    fun operationRootPreservesCandidateUntilReleased() = runBlocking {
        val pointerTarget = legacyFile("source_pointer-target.img")
        val candidate = legacyFile("source_operation-candidate.img")
        seedPointer(pointerTarget)

        val opOwner = LegacyDraftSourceOwnership.OwnerKey.create()
        LegacyDraftSourceOwnership.acquire(
            opOwner,
            LegacyDraftSourceOwnership.RootKind.OPERATION,
            candidate.absolutePath,
        )
        val preserved =
            DraftStorageCoordinator.withWriteLock {
                LegacyDraftSourceReclamation.sweepObsoleteSourcesUnsafe(context)
            }
        assertTrue(candidate.exists())
        assertEquals(
            LegacySourceReclaimDisposition.PRESERVED_OPERATION,
            preserved.single { it.path == candidate.canonicalPath }.disposition,
        )
        assertEquals(
            "current pointer target must never be swept",
            LegacySourceReclaimDisposition.PRESERVED_PERSISTENT_POINTER,
            preserved.single { it.path == pointerTarget.canonicalPath }.disposition,
        )

        LegacyDraftSourceOwnership.releaseOwner(opOwner)
        val afterRelease =
            DraftStorageCoordinator.withWriteLock {
                LegacyDraftSourceReclamation.sweepObsoleteSourcesUnsafe(context)
            }
        assertFalse("released candidate becomes reclaimable", candidate.exists())
        assertEquals(
            LegacySourceReclaimDisposition.DELETED,
            afterRelease.single { it.path == candidate.canonicalPath }.disposition,
        )
        Unit
    }

    // ------------------------------------------------------------------
    // Required race 3: the current pointer target survives even when an
    // older cleanup pass runs against a stale snapshot.
    // ------------------------------------------------------------------

    @Test
    fun pointerMoveKeepsNewerTargetUntouchable() = runBlocking {
        val oldSource = legacyFile("source_old.img")
        val newSource = legacyFile("source_new.img")
        seedPointer(newSource)

        val swept =
            DraftStorageCoordinator.withWriteLock {
                LegacyDraftSourceReclamation.sweepObsoleteSourcesUnsafe(context)
            }

        assertTrue("newer pointer target must survive", newSource.exists())
        assertFalse("older source is reclaimable", oldSource.exists())
        assertEquals(
            LegacySourceReclaimDisposition.PRESERVED_PERSISTENT_POINTER,
            swept.single { it.path == newSource.canonicalPath }.disposition,
        )
        assertEquals(
            LegacySourceReclaimDisposition.DELETED,
            swept.single { it.path == oldSource.canonicalPath }.disposition,
        )
    }

    // ------------------------------------------------------------------
    // Required race 4: two ViewModels own A; one clears the Draft; A
    // survives for the other.
    // ------------------------------------------------------------------

    @Test
    fun clearDraftByOneViewModelPreservesOtherViewModelsLegacySource() = runBlocking {
        // Second live ViewModel created BEFORE any draft exists: its startup
        // observes nothing and it later holds its own document root on A
        // through the same production ownership boundary used by adoption.
        val viewer = harness.createEditor()
        awaitMainUntil { viewer.startupInitCompletion.isCompleted }
        val viewerKey = viewer.legacyDraftSourceOwnerKeyForTest()

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
            sessionFactory = installNativeSessionFactoryForTest { 8901L }
            seedLegacyDraft(exposure = 0.27f)

            // VM1 restores and saves: pointer points back at the migrated A.
            val vm1 = harness.createEditor()
            awaitSignal(renderReached, "vm1 restore must reach render")
            renderRelease.complete(Unit)
            awaitInit(vm1)
            val adoptedSource = File(awaitLegacyDocumentSteadyState(vm1))
            assertTrue(
                "vm1 must adopt a legacy family source",
                adoptedSource.parentFile == context.filesDir.resolve("drafts/current") &&
                    adoptedSource.name.startsWith("source_"),
            )

            // The second ViewModel now exposes A as its own document root,
            // exactly as commitUiState registration would.
            LegacyDraftSourceOwnership.acquire(
                viewerKey,
                LegacyDraftSourceOwnership.RootKind.DOCUMENT,
                adoptedSource.absolutePath,
            )

            vm1.clearDraft()
            awaitMainUntilWithDiagnostic(
                { !vm1.uiState.value.maintenanceBusy && vm1.uiState.value.draftSourcePath == null },
            ) { "clear: ${vmDiagnostic(vm1, viewer)}" }

            assertTrue(
                "cleared Draft must not destroy the other ViewModel's legacy source",
                adoptedSource.isFile,
            )
            assertEquals(
                "viewer must still hold its document claim",
                setOf(LegacyDraftSourceOwnership.RootKind.DOCUMENT),
                LegacyDraftSourceOwnership.kindsForTest(adoptedSource),
            )
            // With the viewer's claim released, the clearing VM still exposes
            // the document: reclamation must stay preserved-live.
            context
                .getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_DRAFT_SOURCE)
                .commit()
            val sweptWhileOpen =
                DraftStorageCoordinator.withWriteLock {
                    LegacyDraftSourceReclamation.sweepObsoleteSourcesUnsafe(context)
                }
            assertTrue("open document keeps the source alive", adoptedSource.isFile)
            assertEquals(
                LegacySourceReclaimDisposition.PRESERVED_LIVE_DOCUMENT,
                sweptWhileOpen.single { it.path == adoptedSource.canonicalPath }.disposition,
            )

            // Only once EVERY owner released (VM teardown boundaries) does
            // the orphan become reclaimable.
            LegacyDraftSourceOwnership.releaseOwner(viewerKey)
            LegacyDraftSourceOwnership.releaseOwner(vm1.legacyDraftSourceOwnerKeyForTest())
            val sweptAfterRelease =
                DraftStorageCoordinator.withWriteLock {
                    LegacyDraftSourceReclamation.sweepObsoleteSourcesUnsafe(context)
                }
            assertFalse(
                "unclaimed orphan becomes reclaimable; refs=" +
                    LegacyDraftSourceOwnership.refsForTest().entries.joinToString("|") { (p, r) ->
                        "${File(p).name}:$r"
                    } + " swept=" + sweptAfterRelease,
                adoptedSource.exists(),
            )
            assertEquals(
                LegacySourceReclaimDisposition.DELETED,
                sweptAfterRelease.single { it.path == adoptedSource.canonicalPath }.disposition,
            )
        } finally {
            renderRelease.complete(Unit)
            sessionFactory?.close()
            renderer.close()
        }
    }

    // ------------------------------------------------------------------
    // Required race 7: a legacy restore parked on A keeps A alive against a
    // concurrent cleaner; the cleaner reports PRESERVED_OPERATION.
    // ------------------------------------------------------------------

    @Test
    fun parkedLegacyRestorePreservesSourceAgainstCleaner() = runBlocking {
        seedLegacyDraft(exposure = 0.42f)
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
            sessionFactory = installNativeSessionFactoryForTest { 8902L }
            val vm = harness.createEditor()
            awaitSignal(decodeReached, "restore must park inside decoding")

            val decodedSource = File(LegacyDraftSourceOwnership.protectedPathsForTest().single())
            val cleaner = CoroutineScope(Dispatchers.Default).async {
                DraftStorageCoordinator.withWriteLock {
                    LegacyDraftSourceReclamation.sweepObsoleteSourcesUnsafe(context)
                }
            }
            val swept = awaitOutcome(cleaner, "concurrent cleaner")

            assertTrue("decoding restore must keep its source alive", decodedSource.isFile)
            // The migrated source is also the current persistent pointer, so
            // the authoritative pointer reread wins the classification; the
            // safety property is preservation. (Operation-only protection is
            // proven explicitly by operationRootPreservesCandidateUntilReleased.)
            assertTrue(
                "cleaner must preserve the decoding source, got=" +
                    swept.single { it.path == decodedSource.canonicalPath }.disposition,
                swept.single { it.path == decodedSource.canonicalPath }.disposition in setOf(
                    LegacySourceReclaimDisposition.PRESERVED_OPERATION,
                    LegacySourceReclaimDisposition.PRESERVED_PERSISTENT_POINTER,
                ),
            )

            decodeRelease.complete(Unit)
            awaitInit(vm)
            assertEquals(decodedSource.canonicalPath, File(checkNotNull(vm.uiState.value.sourcePath)).canonicalPath)
        } finally {
            decodeRelease.complete(Unit)
            seamHandle.close()
            sessionFactory?.close()
            renderer.close()
        }
    }

    // ------------------------------------------------------------------
    // Required race 8: after the restore terminates and its pointer claim is
    // gone, a later cleanup pass may delete the orphaned source.
    // ------------------------------------------------------------------

    @Test
    fun terminatedRestoreOrphanBecomesReclaimable() = runBlocking {
        seedLegacyDraft(exposure = 0.53f)
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
            sessionFactory = installNativeSessionFactoryForTest { 8903L }
            val vm = harness.createEditor()
            awaitSignal(decodeReached, "restore must park inside decoding")
            val decodedSource = File(LegacyDraftSourceOwnership.protectedPathsForTest().single())

            // Supersede the restore: openImage invalidates it and the parked
            // decode unwinds through the normal cancellation path.
            harness.ownSeam(
                OpenImageTestSeam.install(
                    OpenImageTestSeam(
                        sourceTransactionFactory = { _, _ ->
                            throw IOException("superseding open intentionally fails")
                        },
                    ),
                ),
            )
            vm.openImage(Uri.parse("content://incoming/supersede"))
            awaitMainUntil { LegacyDraftSourceOwnership.protectedPathCountForTest() == 0 }

            // Model the draft being gone: no persistent pointer protects the
            // orphan any more, so reclamation may proceed.
            context
                .getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_DRAFT_SOURCE)
                .commit()

            val swept =
                DraftStorageCoordinator.withWriteLock {
                    LegacyDraftSourceReclamation.sweepObsoleteSourcesUnsafe(context)
                }
            assertFalse("terminated orphan must become reclaimable", decodedSource.exists())
            assertEquals(
                LegacySourceReclaimDisposition.DELETED,
                swept.single { it.path == decodedSource.canonicalPath }.disposition,
            )
        } finally {
            decodeRelease.complete(Unit)
            seamHandle.close()
            sessionFactory?.close()
            renderer.close()
        }
    }

    // ------------------------------------------------------------------
    // Required race 9: cleanup never rewrites compatibility preferences.
    // ------------------------------------------------------------------

    @Test
    fun reclamationNeverRewritesCompatibilityPreferences() = runBlocking {
        val pointerTarget = legacyFile("source_prefs-target.img")
        val orphan = legacyFile("source_prefs-orphan.img")
        seedPointer(pointerTarget)
        val before = draftPrefsSnapshot()

        val swept =
            DraftStorageCoordinator.withWriteLock {
                LegacyDraftSourceReclamation.sweepObsoleteSourcesUnsafe(context)
            }

        assertFalse(orphan.exists())
        assertTrue(pointerTarget.exists())
        assertEquals(before, draftPrefsSnapshot())
        assertEquals(
            LegacySourceReclaimDisposition.PRESERVED_PERSISTENT_POINTER,
            swept.single { it.path == pointerTarget.canonicalPath }.disposition,
        )
        Unit
    }

    // ------------------------------------------------------------------
    // Required race 10: a physical delete failure is reported truthfully and
    // leaves persistent state untouched.
    // ------------------------------------------------------------------

    @Test
    fun deleteFailureIsReportedWithoutCorruptingPersistentState() = runBlocking {
        val pointerTarget = legacyFile("source_fail-pointer.img")
        seedPointer(pointerTarget)
        // An undeletable candidate: a non-empty DIRECTORY carrying the owned
        // naming contract. delete() returns false -> FAILED, nothing corrupts.
        val stubborn = context.filesDir.resolve("drafts/current/source_stubborn.img")
        stubborn.mkdirs()
        File(stubborn, "payload").writeText("x")

        val result = LegacyDraftSourceReclamation.reclaimCandidateUnsafe(context, stubborn)

        assertEquals(LegacySourceReclaimDisposition.FAILED, result.disposition)
        assertTrue(stubborn.isDirectory)
        assertEquals(pointerTarget.absolutePath, draftPrefsSnapshot()[KEY_DRAFT_SOURCE])
        stubborn.resolve("payload").delete()
        stubborn.delete()
        Unit
    }

    // ------------------------------------------------------------------
    // Post-save sweep: after a newer source is durably committed, obsolete
    // older compatibility sources are reclaimed while every live/persistent
    // claim is honored.
    // ------------------------------------------------------------------

    @Test
    fun postCommitSweepReclaimsOnlyUnclaimedObsoleteSources() = runBlocking {
        val stale = legacyFile("source_stale.img")
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
            sessionFactory = installNativeSessionFactoryForTest { 8904L }
            seedLegacyDraft(exposure = 0.61f)
            val vm = harness.createEditor()
            awaitSignal(renderReached, "restore must reach render")
            renderRelease.complete(Unit)
            awaitInit(vm)
            val legacyPath = awaitLegacyDocumentSteadyState(vm)

            assertNotNull("adoption save must publish a generation", vm.uiState.value.draftGenerationId)
            assertTrue("adopted source must survive its own save", File(legacyPath).isFile)
            assertTrue(
                "unrelated stale source must be reclaimed by the post-commit sweep",
                !stale.exists(),
            )
            assertEquals(
                setOf(LegacyDraftSourceOwnership.RootKind.DOCUMENT),
                LegacyDraftSourceOwnership.kindsForTest(File(legacyPath)),
            )
        } finally {
            renderRelease.complete(Unit)
            sessionFactory?.close()
            renderer.close()
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private val renderReached = CompletableDeferred<Unit>()
    private val renderRelease = CompletableDeferred<Unit>()

    private fun legacyFile(name: String): File {
        val directory = context.filesDir.resolve("drafts/current").apply { mkdirs() }
        return directory.resolve(name).apply { writeText(name) }
    }

    private fun seedPointer(target: File) {
        context
            .getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DRAFT_SOURCE, target.absolutePath)
            .commit()
    }

    private fun draftPrefsSnapshot(): Map<String, Any?> =
        context
            .getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
            .all
            .toSortedMap()

    private fun seedLegacyDraft(exposure: Float): File {
        val payload = context.cacheDir.resolve("reclaim-seed-$exposure.png")
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

    private suspend fun <T> awaitOutcome(deferred: Deferred<T>, description: String): T {
        awaitEditorCompletionForTest(description, deferred, timeoutMillis = 20_000L, pumpMain = ::pumpMain)
        return runBlocking { deferred.await() }
    }

    private fun awaitSignal(signal: CompletableDeferred<Unit>, description: String) {
        awaitEditorCompletionForTest(description, signal, timeoutMillis = 20_000L, pumpMain = ::pumpMain)
    }

    private fun awaitInit(vm: EditorViewModel) {
        awaitMainUntil { vm.startupInitCompletion.isCompleted && !vm.uiState.value.isBusy }
    }

    private fun awaitLegacyDocumentSteadyState(vm: EditorViewModel): String {
        val legacyPath = checkNotNull(vm.uiState.value.sourcePath)
        awaitMainUntil {
            !vm.hasActiveDraftSaveJobForTest() &&
                LegacyDraftSourceOwnership.kindsForTest(File(legacyPath)) ==
                setOf(LegacyDraftSourceOwnership.RootKind.DOCUMENT)
        }
        return legacyPath
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

    private fun vmDiagnostic(vararg vms: EditorViewModel): String =
        vms.joinToString(" || ") { vm ->
            "job=${vm.hasActiveDraftSaveJobForTest()} " +
                "busy=${vm.uiState.value.isBusy} " +
                "maint=${vm.uiState.value.maintenanceBusy} " +
                "src=${vm.uiState.value.sourcePath} " +
                "draftSrc=${vm.uiState.value.draftSourcePath} " +
                "gen=${vm.uiState.value.draftGenerationId} " +
                "msg=${vm.uiState.value.message}"
        } + " refs=" + LegacyDraftSourceOwnership.refsForTest().entries.joinToString("|") { (path, refs) ->
            "${File(path).name}:$refs"
        } + " stacks=" + Thread.getAllStackTraces().entries
            .filter { (t, _) -> t.name.contains("worker") || t.name.contains("main") }
            .joinToString(";;") { (t, frames) ->
                t.name + ":" + frames.take(30)
                    .filter { it.className.contains("keplerstudio") }
                    .take(4)
                    .joinToString("<") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            }

    private fun pumpMain() {
        shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
    }
}
