package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.projectnuke.keplerstudio.bridge.installNativeSessionFactoryForTest
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StartupStorageReconcilerProductionTest {
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    private lateinit var harness: OwnedEditorViewModelHarness

    private fun prefs() = context.getSharedPreferences(PREF_NAME_DRAFT, Context.MODE_PRIVATE)

    @Before
    fun cleanStorage() {
        IncomingSourceLiveOwnership.clearForTest()
        RestoredWorkingSourceOwnership.clearForTest()
        harness = OwnedEditorViewModelHarness(context, installBitmapCopySeam = true)
        deleteDirectoryIfPresentForTest(draftGenerationsRoot(context))
        deleteDirectoryIfPresentForTest(context.filesDir.resolve("editor_sources"))
        deleteDirectoryIfPresentForTest(context.filesDir.resolve("drafts/current"))
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("source_") || file.name.endsWith(".img.staging")) file.delete()
        }
        prefs().edit().clear().commit()
    }

    @After
    fun cleanStorageAfter() {
        harness.close()
        IncomingSourceLiveOwnership.clearForTest()
        RestoredWorkingSourceOwnership.clearForTest()
    }

    // Orphan .staging_* directories (crash before finalize) are reclaimed while
    // the pointer generation is preserved.
    @Test
    fun orphanStagingDirsDeletedWhilePointerGenerationPreserved() {
        val generations = draftGenerationsRoot(context)
        val pointerDir = File(generations, "gen_current").apply { mkdirs() }
        File(pointerDir, "complete").writeText("ok")
        val stagingA = File(generations, ".staging_aaa").apply { mkdirs() }
        File(stagingA, "source.uuid.tmp").writeText("partial")
        File(generations, ".staging_bbb").mkdirs()
        prefs().edit().putString(KEY_DRAFT_GENERATION_ID, "gen_current").commit()

        val outcome = reconcileStartupArtifacts(context, inProcessSourcePath = null)

        assertFalse("staging dir with partial payload must be deleted", stagingA.exists())
        assertFalse("empty staging dir must be deleted", File(generations, ".staging_bbb").exists())
        assertTrue("pointer generation must exist", pointerDir.exists())
        assertTrue("pointer payload must be intact", File(pointerDir, "complete").isFile)
        assertEquals(2, outcome.entries.count { it.disposition == StartupReconcileDisposition.DELETED_STAGING })
        assertEquals(1, outcome.entries.count { it.disposition == StartupReconcileDisposition.PRESERVED_POINTER })
        assertEquals(0, outcome.failedCount)
    }

    // A finalized-but-unpublished generation (crash between finalize and pointer
    // publish) is reclaimed; the pointer generation survives.
    @Test
    fun unreferencedFinalGenerationDeletedWhilePointerPreserved() {
        val generations = draftGenerationsRoot(context)
        val pointerDir = File(generations, "gen_current").apply { mkdirs() }
        File(pointerDir, "complete").writeText("ok")
        val oldDir = File(generations, "gen_old").apply { mkdirs() }
        File(oldDir, "complete").writeText("ok")
        prefs().edit().putString(KEY_DRAFT_GENERATION_ID, "gen_current").commit()

        val outcome = reconcileStartupArtifacts(context, null)

        assertFalse("unreferenced generation must be deleted", oldDir.exists())
        assertTrue("pointer generation must exist", pointerDir.exists())
        assertEquals(1, outcome.entries.count { it.disposition == StartupReconcileDisposition.DELETED_UNREFERENCED })
        assertEquals(1, outcome.entries.count { it.disposition == StartupReconcileDisposition.PRESERVED_POINTER })
        assertEquals(0, outcome.failedCount)
    }

    // A pointer naming a missing directory never suppresses cleanup and never crashes.
    @Test
    fun brokenPointerDeletesOrphansWithoutCrash() {
        val generations = draftGenerationsRoot(context)
        val orphan = File(generations, "gen_orphan").apply { mkdirs() }
        File(orphan, "complete").writeText("ok")
        prefs().edit().putString(KEY_DRAFT_GENERATION_ID, "gen_missing").commit()

        val outcome = reconcileStartupArtifacts(context, null)

        assertFalse("orphan must be deleted", orphan.exists())
        assertEquals(0, outcome.preservedCount)
        assertEquals(0, outcome.failedCount)
    }

    // Cache staging files are always reclaimed even when the final file is referenced.
    @Test
    fun cacheStagingDeletedEvenWhenFinalIsReferenced() {
        val referenced = File(context.cacheDir, "source_keep.img").apply { writeText("final") }
        val staging = File(context.cacheDir, "source_keep.img.staging").apply { writeText("partial") }
        prefs().edit().putString(KEY_DRAFT_SOURCE, referenced.absolutePath).commit()

        val outcome = reconcileStartupArtifacts(context, null)

        assertFalse("staging must be deleted", staging.exists())
        assertTrue("referenced final must exist", referenced.exists())
        assertEquals(1, outcome.entries.count { it.disposition == StartupReconcileDisposition.DELETED_STAGING })
        assertEquals(1, outcome.entries.count { it.disposition == StartupReconcileDisposition.PRESERVED_REFERENCED })
        assertEquals(0, outcome.failedCount)
    }

    // Unreferenced cache finals and restore working copies are reclaimed.
    @Test
    fun unreferencedCacheFinalsAndWorkingSourcesDeleted() {
        val cacheA = File(context.cacheDir, "source_a.img").apply { writeText("a") }
        val cacheB = File(context.cacheDir, "source_b.img").apply { writeText("b") }
        val working = File(context.filesDir.resolve("editor_sources"), "restored_x.img")
        working.parentFile!!.mkdirs()
        working.writeText("w")

        val outcome = reconcileStartupArtifacts(context, null)

        assertFalse("unreferenced cache final must be deleted", cacheA.exists())
        assertFalse("unreferenced cache final must be deleted", cacheB.exists())
        assertFalse("unreferenced working copy must be deleted", working.exists())
        assertEquals(3, outcome.entries.count { it.disposition == StartupReconcileDisposition.DELETED_UNREFERENCED })
        assertEquals(0, outcome.failedCount)
    }

    // Both the legacy pref reference and the live in-process source path preserve
    // their files; lookalikes are still reclaimed.
    @Test
    fun prefAndInProcessReferencedSourcesPreserved() {
        val cacheRef = File(context.cacheDir, "source_keep.img").apply { writeText("k") }
        val cacheOrphan = File(context.cacheDir, "source_orphan.img").apply { writeText("o") }
        val workingRef = File(context.filesDir.resolve("editor_sources"), "restored_live.img")
        workingRef.parentFile!!.mkdirs()
        workingRef.writeText("l")
        prefs().edit().putString(KEY_DRAFT_SOURCE, cacheRef.absolutePath).commit()

        val outcome = reconcileStartupArtifacts(context, workingRef.absolutePath)

        assertTrue("pref-referenced cache source must exist", cacheRef.exists())
        assertTrue("in-process working copy must exist", workingRef.exists())
        assertFalse("unreferenced lookalike must be deleted", cacheOrphan.exists())
        assertEquals(2, outcome.entries.count { it.disposition == StartupReconcileDisposition.PRESERVED_REFERENCED })
        assertEquals(1, outcome.entries.count { it.disposition == StartupReconcileDisposition.DELETED_UNREFERENCED })
        assertEquals(0, outcome.failedCount)
    }

    // Legacy current-dir temps are reclaimed; all source files there are preserved
    // because at startup they may be the active document's source (bitmap-dirty
    // drafts) or a legacy draft's source and ownership cannot be proven.
    @Test
    fun legacyCurrentTempsDeletedSourcesPreserved() {
        val legacy = context.filesDir.resolve("drafts/current").apply { mkdirs() }
        val tmp = File(legacy, "source.img.tmp").apply { writeText("t") }
        val orphanSource = File(legacy, "source_orphan.img").apply { writeText("o") }
        val liveSource = File(legacy, "source.img").apply { writeText("s") }
        val liveThumb = File(legacy, "thumbnail.jpg").apply { writeText("j") }

        val outcome = reconcileStartupArtifacts(context, null)

        assertFalse("temp must be deleted", tmp.exists())
        assertTrue("orphan lookalike source must be preserved", orphanSource.exists())
        assertTrue("legacy live source must be untouched", liveSource.exists())
        assertTrue("legacy thumbnail must be untouched", liveThumb.exists())
        assertEquals(1, outcome.entries.count { it.disposition == StartupReconcileDisposition.DELETED_TEMP })
        assertEquals(3, outcome.entries.count { it.disposition == StartupReconcileDisposition.IGNORED_UNKNOWN })
    }

    // Stale uuid-suffixed temps inside the pointer generation are reclaimed while
    // manifest-declared payloads stay intact.
    @Test
    fun staleTempsInsidePointerGenerationDeletedWhilePayloadsIntact() {
        val generations = draftGenerationsRoot(context)
        val pointerDir = File(generations, "gen_current").apply { mkdirs() }
        val temp1 = File(pointerDir, "source.uuid1.tmp").apply { writeText("t1") }
        val temp2 = File(pointerDir, "manifest.uuid2.tmp").apply { writeText("t2") }
        val manifest = File(pointerDir, "manifest.json").apply { writeText("{}") }
        val complete = File(pointerDir, "complete").apply { writeText("ok") }
        prefs().edit().putString(KEY_DRAFT_GENERATION_ID, "gen_current").commit()

        val outcome = reconcileStartupArtifacts(context, null)

        assertFalse("stale source temp must be deleted", temp1.exists())
        assertFalse("stale manifest temp must be deleted", temp2.exists())
        assertTrue("manifest must be intact", manifest.exists())
        assertTrue("complete marker must be intact", complete.exists())
        assertEquals(2, outcome.entries.count { it.disposition == StartupReconcileDisposition.DELETED_TEMP })
        assertEquals(0, outcome.failedCount)
    }

    // Reconciliation is idempotent: a second pass deletes nothing new.
    @Test
    fun secondPassDeletesNothing() {
        val generations = draftGenerationsRoot(context)
        val pointerDir = File(generations, "gen_current").apply { mkdirs() }
        File(pointerDir, "complete").writeText("ok")
        File(generations, ".staging_aaa").mkdirs()
        val cacheOrphan = File(context.cacheDir, "source_orphan.img").apply { writeText("o") }
        prefs().edit().putString(KEY_DRAFT_GENERATION_ID, "gen_current").commit()

        val first = reconcileStartupArtifacts(context, null)
        val second = reconcileStartupArtifacts(context, null)

        assertTrue("first pass must delete orphans", first.deletedCount > 0)
        assertEquals("second pass must be a no-op", 0, second.deletedCount)
        assertEquals(0, second.failedCount)
        assertFalse(cacheOrphan.exists())
        assertTrue(pointerDir.exists())
    }

    // Foreign files and directories matching no owned pattern are ignored.
    @Test
    fun foreignFilesIgnoredAndUntouched() {
        val generations = draftGenerationsRoot(context)
        val foreignDir = File(generations, "other_dir").apply { mkdirs() }
        File(foreignDir, "data").writeText("d")
        val foreignCache = File(context.cacheDir, "random_cache.bin").apply { writeText("r") }

        val outcome = reconcileStartupArtifacts(context, null)

        assertTrue("foreign dir must be untouched", foreignDir.exists())
        assertTrue("foreign cache file must be untouched", foreignCache.exists())
        assertEquals(2, outcome.entries.count { it.disposition == StartupReconcileDisposition.IGNORED_UNKNOWN })
        assertEquals(0, outcome.deletedCount)
    }

    @Test
    fun liveIncomingStagingAndFinalArePreservedUntilOwnershipEnds() {
        val staging = File(context.cacheDir, IncomingSourceArtifactNames.stagingName("live")).apply { writeText("partial") }
        val final = File(context.cacheDir, IncomingSourceArtifactNames.finalName("live")).apply { writeText("final") }
        IncomingSourceLiveOwnership.register(staging, final)
        try {
            val live = reconcileStartupArtifacts(context, null)
            assertTrue("live staging must survive", staging.exists())
            assertTrue("live final before adoption must survive", final.exists())
            assertEquals(2, live.entries.count { it.disposition == StartupReconcileDisposition.PRESERVED_LIVE_TRANSACTION })
        } finally {
            IncomingSourceLiveOwnership.release(staging, final)
        }
        val dead = reconcileStartupArtifacts(context, null)
        assertFalse("released staging is a startup orphan", staging.exists())
        assertFalse("released final is a startup orphan", final.exists())
        assertEquals(2, dead.deletedCount)
    }

    @Test
    fun liveAndDeadRestoredWorkingSourcesUseCurrentProcessOwnership() {
        val live = File(context.filesDir.resolve("editor_sources"), "restored_live.img").apply {
            parentFile!!.mkdirs()
            writeText("live")
        }
        val dead = File(context.filesDir.resolve("editor_sources"), "restored_dead.img").apply {
            writeText("dead")
        }
        RestoredWorkingSourceOwnership.acquire(live)
        try {
            val outcome = reconcileStartupArtifacts(context, null)
            assertTrue("live restore source must survive", live.exists())
            assertFalse("dead restore source must be reclaimed", dead.exists())
            assertTrue(
                outcome.entries.any {
                    it.path == live.absolutePath &&
                        it.disposition == StartupReconcileDisposition.PRESERVED_LIVE_RESTORE
                },
            )
        } finally {
            RestoredWorkingSourceOwnership.releaseRestore(live)
            RestoredWorkingSourceOwnership.deleteIfUnowned(live)
        }
    }

    @Test
    fun staleWorkingSnapshotCannotDeleteSourceAcquiredAfterSnapshot() = runBlocking {
        val candidate = File(context.filesDir.resolve("editor_sources"), "restored_snapshot.img").apply {
            parentFile!!.mkdirs()
            writeText("candidate")
        }
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val seam = StartupReconcileTestSeam().also {
            it.workingSnapshotReached = reached
            it.workingSnapshotRelease = release
        }
        val installed = StartupReconcileTestSeam.install(seam)
        try {
            val reconciliation = async(Dispatchers.Default) { reconcileStartupArtifacts(context, null) }
            awaitEditorCompletionForTest("working-source reconcile must capture its snapshot", reached)
            RestoredWorkingSourceOwnership.acquire(candidate)
            release.complete(Unit)
            awaitEditorCompletionForTest("working-source reconcile must finish", reconciliation)
            assertTrue("new owner must win deletion linearization", candidate.exists())
            assertTrue(RestoredWorkingSourceOwnership.isRestoreOwnedForTest(candidate))
        } finally {
            release.complete(Unit)
            installed.close()
            RestoredWorkingSourceOwnership.releaseRestore(candidate)
            RestoredWorkingSourceOwnership.deleteIfUnowned(candidate)
        }
    }

    @Test
    fun unknownImgStagingIsNotClaimedByIncomingSourceCleanup() {
        val unknown = File(context.cacheDir, "unrelated.img.staging").apply { writeText("foreign") }

        val outcome = reconcileStartupArtifacts(context, null)

        assertTrue("unknown staging owner must be preserved", unknown.exists())
        assertTrue(outcome.entries.any { it.path == unknown.absolutePath && it.disposition == StartupReconcileDisposition.IGNORED_UNKNOWN })
    }

    @Test
    fun olderCacheSnapshotCannotDeleteNewerAdoptedIncomingFinal() {
        runBlocking {
        val staleFinal = File(context.cacheDir, IncomingSourceArtifactNames.finalName("snapshot-race")).apply { writeText("dead") }
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val seam = StartupReconcileTestSeam().also {
            it.cacheSnapshotReached = reached
            it.cacheSnapshotRelease = release
        }
        val installed = StartupReconcileTestSeam.install(seam)
        try {
            val reconciliation = async(Dispatchers.Default) { reconcileStartupArtifacts(context, null) }
            awaitEditorCompletionForTest("reconciliation must capture cache snapshot", reached)
            assertTrue(staleFinal.delete())

            val acquisition = async(Dispatchers.Default) {
                IncomingSourceTransaction(
                    context,
                    inputStreamProvider = { ByteArrayInputStream(byteArrayOf(7)) },
                    idProvider = { "snapshot-race" },
                ).acquire(android.net.Uri.EMPTY)
            }
            awaitEditorCompletionForTest("new transaction must promote its final", acquisition)
            val owned = acquisition.await()
            assertTrue(owned.file.isFile)
            assertTrue(IncomingSourceLiveOwnership.isLiveForTest(owned.file))

            // The old reconcile view is still paused. Adoption must hand the
            // exact path from transaction ownership to the current document
            // root before the transaction owner is released.
            owned.transferToDocument()
            assertFalse(IncomingSourceLiveOwnership.isLiveForTest(owned.file))
            assertTrue(IncomingSourceLiveOwnership.isDocumentOwnedForTest(owned.file))

            release.complete(Unit)
            awaitEditorCompletionForTest("reconciliation must finish", reconciliation)
            assertTrue("old snapshot must not delete newly adopted final", owned.file.exists())
            IncomingSourceLiveOwnership.releaseDocumentForTest(owned.file)
            owned.file.delete()
        } finally {
            release.complete(Unit)
            installed.close()
        }
        }
    }

    // The real startup path runs reconciliation: orphans present at VM init are
    // reclaimed before startup init completes, observable via the test seam.
    @Test
    fun startupInitRunsReconcileThroughSeam() = runBlocking {
        val generations = draftGenerationsRoot(context)
        val staging = File(generations, ".staging_aaa").apply { mkdirs() }
        File(staging, "partial").writeText("p")
        val orphanGen = File(generations, "gen_orphan").apply { mkdirs() }
        File(orphanGen, "complete").writeText("ok")
        val seam = StartupReconcileTestSeam()
        val installed = StartupReconcileTestSeam.install(seam)
        var sessionFactory: AutoCloseable? = null
        try {
            sessionFactory = installNativeSessionFactoryForTest { 1L }
            val vm = harness.createEditor()
            awaitInit(vm)
            val outcome = seam.outcome ?: error("reconcile must run during startup init")
            assertFalse("orphan staging must be gone after startup", staging.exists())
            assertFalse("orphan generation must be gone after startup", orphanGen.exists())
            assertTrue("reconcile must have observed deletions", outcome.deletedCount >= 2)
        } finally {
            sessionFactory?.close()
            installed.close()
        }
    }

    @Test
    fun realStartupReconcileAndOpenImagePreserveNewCurrentSource() = runBlocking {
        val orphan = File(context.cacheDir, IncomingSourceArtifactNames.finalName("startup-orphan")).apply { writeText("dead") }
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val startupSeam = StartupReconcileTestSeam().also {
            it.cacheSnapshotReached = reached
            it.cacheSnapshotRelease = release
        }
        val startupHandle = StartupReconcileTestSeam.install(startupSeam)
        var openHandle: AutoCloseable? = null
        try {
            val vm = harness.createEditor()
            awaitEditorCompletionForTest("startup reconciliation must capture its view", reached)

            val preview = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            openHandle = harness.ownSeam(
                OpenImageTestSeam.install(
                    OpenImageTestSeam(
                        sourceTransactionFactory = { app, _ ->
                            IncomingSourceTransaction(
                                app,
                                inputStreamProvider = { ByteArrayInputStream(byteArrayOf(4, 5, 6)) },
                                idProvider = { "startup-open" },
                            )
                        },
                        decode = { preview },
                        nativeSessionFactory = { 8080L },
                    ),
                ),
            )
            val openCompletion = async(Dispatchers.Default) {
                vm.uiState.first {
                    !it.isBusy && it.sourcePath?.endsWith(IncomingSourceArtifactNames.finalName("startup-open")) == true
                }
            }
            vm.openImage(Uri.parse("content://startup/open-image"))
            awaitEditorCompletionForTest(
                description = "openImage must complete while startup reconcile is paused",
                completion = openCompletion,
                pumpMain = {
                    shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                },
            )
            val sourcePath = checkNotNull(vm.uiState.value.sourcePath)
            assertTrue("new source must be document-owned before reconcile resumes", IncomingSourceLiveOwnership.isDocumentOwnedForTest(File(sourcePath)))

            release.complete(Unit)
            awaitInit(vm)
            assertFalse("startup orphan must be reclaimed", orphan.exists())
            assertTrue("open image source must survive startup cleanup", File(sourcePath).exists())
            IncomingSourceLiveOwnership.releaseDocumentForTest(File(sourcePath))
            File(sourcePath).delete()
            Unit
        } finally {
            release.complete(Unit)
            openHandle?.close()
            startupHandle.close()
        }
    }

    private fun awaitInit(vm: EditorViewModel) {
        awaitEditorCompletionForTest(
            description = "startup init must complete",
            completion = vm.startupInitCompletion,
            pumpMain = {
                shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
                shadowOf(android.os.Looper.getMainLooper()).idle()
            },
        )
    }
}
