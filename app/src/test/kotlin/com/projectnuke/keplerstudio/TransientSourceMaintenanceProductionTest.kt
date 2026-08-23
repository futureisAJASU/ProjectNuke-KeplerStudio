package com.projectnuke.keplerstudio

import android.app.Application
import android.net.Uri
import com.projectnuke.keplerstudio.editor.IncomingSourceArtifactNames
import com.projectnuke.keplerstudio.editor.IncomingSourceLiveOwnership
import com.projectnuke.keplerstudio.editor.IncomingSourceTransaction
import com.projectnuke.keplerstudio.editor.TransientEntryDisposition
import com.projectnuke.keplerstudio.editor.TransientMaintenanceMode
import com.projectnuke.keplerstudio.editor.TransientSourceFamily
import com.projectnuke.keplerstudio.editor.TransientSourceMaintenance
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unified transient-source maintenance backend races. Every physical delete
 * goes through the owning registry's linearized boundary; these tests pin the
 * caller-visible semantics of the three production callers (startup, editor
 * age-based hygiene, MainActivity manual card) onto that single backend.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TransientSourceMaintenanceProductionTest {
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun setUp() {
        IncomingSourceLiveOwnership.clearForTest()
        clearCacheCandidates()
    }

    @After
    fun tearDown() {
        IncomingSourceLiveOwnership.clearForTest()
        clearCacheCandidates()
    }

    // ------------------------------------------------------------------
    // Required race 4: cleanup vs multi-VM document replacement. A stale
    // captured string must not decide protection; the registry does.
    // ------------------------------------------------------------------

    @Test
    fun staleManualStringsProtectOldPathOnlyRegistryProtectsNewDocument() = runBlocking {
        val firstId = "multi-vm-first"
        val secondId = "multi-vm-second"
        val first = acquireFinal(firstId)
        val second = acquireFinal(secondId)
        // Document replacement exactly as commitUiState performs it: the
        // live document root moves first -> second in one atomic transition.
        first.transferToDocument()
        second.transferToDocument(first.file.absolutePath)

        val report =
            TransientSourceMaintenance.cleanupIncoming(
                context,
                mode = TransientMaintenanceMode.MANUAL,
            )

        assertTrue("newly adopted document must survive without any captured string", second.file.exists())
        assertFalse(
            "released old artifact is reclaimable once its ownership ended",
            first.file.exists(),
        )
        assertEquals(
            TransientEntryDisposition.DELETED,
            report.entries.single { it.path == first.file.canonicalPath }.disposition,
        )
        assertEquals(
            TransientEntryDisposition.PRESERVED_LIVE_DOCUMENT,
            report.entries.single { it.path == second.file.canonicalPath }.disposition,
        )
        IncomingSourceLiveOwnership.releaseDocumentForTest(File(second.file.absolutePath))
    }

    // ------------------------------------------------------------------
    // Required race 5: cache-mode maintenance never touches Draft save
    // sources or legacy temps outside its family contract.
    // ------------------------------------------------------------------

    @Test
    fun cacheMaintenanceNeverTouchesDraftSaveSourcesOrLegacyTemps() = runBlocking {
        val legacySource = legacyFile("source_cache-scope.img")
        val legacyTemp = legacyFile("stale.tmp")
        val orphanFinal = File(context.cacheDir, "source_scope-orphan.img").apply { writeText("o") }

        val manualReport =
            TransientSourceMaintenance.cleanup(context, TransientMaintenanceMode.MANUAL)

        assertTrue("draft source family untouched by MANUAL", legacySource.exists())
        assertTrue("legacy temps are STARTUP-only", legacyTemp.exists())
        assertFalse("unowned incoming final reclaimed", orphanFinal.exists())
        assertTrue(
            "report must not claim any drafts/current candidate",
            manualReport.entries.none { it.family == TransientSourceFamily.LEGACY_TEMP },
        )
        legacySource.delete()
        legacyTemp.delete()
        Unit
    }

    // ------------------------------------------------------------------
    // Required race 6: two concurrent maintenance runs observe the same
    // candidate; exactly one deletes it and byte accounting stays truthful.
    // ------------------------------------------------------------------

    @Test
    fun concurrentRunsDeleteEachCandidateExactlyOnce() = runBlocking {
        val orphanCount = 24
        var totalBytes = 0L
        repeat(orphanCount) { index ->
            val file = File(context.cacheDir, "source_concurrent-$index.img")
            val payload = ByteArray(128 + index) { it.toByte() }
            file.writeBytes(payload)
            totalBytes += payload.size
        }

        val first = java.util.concurrent.CompletableFuture.supplyAsync {
            TransientSourceMaintenance.cleanupIncoming(context, TransientMaintenanceMode.MANUAL)
        }
        val second = java.util.concurrent.CompletableFuture.supplyAsync {
            TransientSourceMaintenance.cleanupIncoming(context, TransientMaintenanceMode.MANUAL)
        }
        val reportA = first.get()
        val reportB = second.get()

        // Linearized deletion: every candidate is deleted by EXACTLY one
        // pass; the other observes nothing, an unowned survivor, or an
        // already-absent entry - but never deletes twice.
        val deletedPaths =
            (reportA.entries + reportB.entries)
                .filter { it.disposition == TransientEntryDisposition.DELETED }
                .map { it.path }
        assertEquals("each candidate deleted exactly once", orphanCount, deletedPaths.toSet().size)
        assertEquals("no duplicate delete entries", deletedPaths.size, deletedPaths.toSet().size)
        repeat(orphanCount) { index ->
            assertFalse(File(context.cacheDir, "source_concurrent-$index.img").exists())
        }
        assertEquals("bytes counted once, truthfully", totalBytes, reportA.reclaimedBytes + reportB.reclaimedBytes)
        // A FAILED disposition may only be a transient delete race where the
        // other pass won; a genuinely undeletable path would survive and be
        // caught by the no-remains assertions above.
        val failedPaths =
            (reportA.entries + reportB.entries)
                .filter { it.disposition == TransientEntryDisposition.FAILED }
                .map { it.path }
        assertTrue("failures must be transient races, not silent survivors", failedPaths.all { !File(it).exists() })
    }

    // ------------------------------------------------------------------
    // Required race 7: a physical delete failure is reported truthfully and
    // never converted into reclaimed bytes.
    // ------------------------------------------------------------------

    @Test
    fun failedDeletionIsTruthfulAndNotCountedAsReclaimed() = runBlocking {
        val stubbornPath = "source_stubborn-failure.img"
        val stubborn = File(context.cacheDir, stubbornPath).apply { writeText("x") }
        // Keep an open handle so deletion fails where the platform enforces it.
        RandomAccessFile(stubborn, "rw").use { handle ->
            handle.write(0)
            val report =
                TransientSourceMaintenance.cleanupIncoming(context, TransientMaintenanceMode.MANUAL)
            val entry = report.entries.single { it.path.endsWith(stubbornPath) }
            if (!stubborn.exists()) {
                // Platform allowed deleting an open file (POSIX): truthful DELETED.
                assertEquals(TransientEntryDisposition.DELETED, entry.disposition)
            } else {
                assertEquals(TransientEntryDisposition.FAILED, entry.disposition)
                assertEquals("failed deletes must not count as reclaimed bytes", 0L, entry.bytes)
            }
        }
        stubborn.delete()
        Unit
    }

    // ------------------------------------------------------------------
    // Required race 9 + foreign files: unknown filenames remain untouched.
    // ------------------------------------------------------------------

    @Test
    fun unknownFilenamesRemainUntouched() = runBlocking {
        val foreign = File(context.cacheDir, "random.bin").apply { writeText("f") }
        val lookalikeStaging = File(context.cacheDir, "other_source_x.img.staging").apply { writeText("s") }

        val report = TransientSourceMaintenance.cleanupIncoming(context, TransientMaintenanceMode.STARTUP)

        assertTrue(foreign.exists())
        assertTrue(lookalikeStaging.exists())
        assertTrue(report.entries.none { it.path.endsWith("random.bin") })
        assertTrue(report.entries.none { it.path.endsWith("other_source_x.img.staging") })
        foreign.delete()
        lookalikeStaging.delete()
        Unit
    }

    // ------------------------------------------------------------------
    // Age-based hygiene keeps young finals alive and honors active paths.
    // ------------------------------------------------------------------

    @Test
    fun ageBasedCleanupSkipsYoungAndProtectedFinals() = runBlocking {
        val young = File(context.cacheDir, "source_age-young.img").apply { writeText("y") }
        val oldProtected = File(context.cacheDir, "source_age-old-protected.img").apply { writeText("p") }
        val oldFree = File(context.cacheDir, "source_age-old-free.img").apply { writeText("f") }
        val oldThresholdMs = 10L
        Thread.sleep(30)
        // Keep the "young" file genuinely young relative to the threshold.
        young.setLastModified(System.currentTimeMillis())
        oldProtected.setLastModified(System.currentTimeMillis() - 60_000)
        oldFree.setLastModified(System.currentTimeMillis() - 60_000)

        val report =
            TransientSourceMaintenance.cleanupIncoming(
                context,
                mode = TransientMaintenanceMode.AGE_BASED,
                protectedPaths = setOf(oldProtected.absolutePath),
                olderThanMillis = oldThresholdMs,
            )

        assertTrue(young.exists())
        assertTrue(oldProtected.exists())
        assertFalse(oldFree.exists())
        assertEquals(
            TransientEntryDisposition.SKIPPED_BY_MODE,
            report.entries.single { it.path == young.canonicalPath }.disposition,
        )
        assertEquals(
            TransientEntryDisposition.PRESERVED_PROTECTED,
            report.entries.single { it.path == oldProtected.canonicalPath }.disposition,
        )
        assertEquals(
            TransientEntryDisposition.DELETED,
            report.entries.single { it.path == oldFree.canonicalPath }.disposition,
        )
        young.delete()
        oldProtected.delete()
        Unit
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private suspend fun acquireFinal(id: String): com.projectnuke.keplerstudio.editor.OwnedIncomingSource =
        IncomingSourceTransaction(
            context,
            inputStreamProvider = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
            idProvider = { id },
        ).acquire(Uri.EMPTY)

    private fun legacyFile(name: String): File {
        val directory = context.filesDir.resolve("drafts/current").apply { mkdirs() }
        return directory.resolve(name).apply { writeText(name) }
    }

    private fun clearCacheCandidates() {
        context.cacheDir.listFiles()?.forEach { file ->
            if (
                IncomingSourceArtifactNames.isFinalName(file.name) ||
                IncomingSourceArtifactNames.isStagingName(file.name)
            ) {
                file.delete()
            }
        }
    }
}
