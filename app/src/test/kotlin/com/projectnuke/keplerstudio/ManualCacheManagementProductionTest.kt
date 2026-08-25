package com.projectnuke.keplerstudio

import android.app.Application
import android.net.Uri
import com.projectnuke.keplerstudio.editor.IncomingSourceArtifactNames
import com.projectnuke.keplerstudio.editor.IncomingSourceLiveOwnership
import com.projectnuke.keplerstudio.editor.IncomingSourceTransaction
import com.projectnuke.keplerstudio.editor.RestoredWorkingSourceOwnership
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * Phase 6: the user-facing "temporary original cache" card must be truthful.
 *
 * The manual action spans the whole transient family (unowned incoming finals
 * PLUS unowned restored working sources), runs through the unified ownership-
 * aware backend, and the statistics line shares the EXACT same classification
 * without deleting anything. Every scenario here drives the real production
 * entry points (`cleanupTemporarySourceFilesForTest`,
 * `calculateTemporaryCacheStatsForTest`) against real registry state: captured
 * UI strings are advisory only and delete-time registry authority always wins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ManualCacheManagementProductionTest {
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application
    private var seamHandle: AutoCloseable? = null

    @Before
    fun setUp() {
        IncomingSourceLiveOwnership.clearForTest()
        RestoredWorkingSourceOwnership.clearForTest()
        clearTransientSources()
    }

    @After
    fun tearDown() {
        seamHandle?.close()
        seamHandle = null
        IncomingSourceLiveOwnership.clearForTest()
        RestoredWorkingSourceOwnership.clearForTest()
        clearTransientSources()
    }

    // ------------------------------------------------------------------
    // A. incoming final unowned -> manual action deletes; bytes/count exact
    // ------------------------------------------------------------------

    @Test
    fun manualActionDeletesUnownedIncomingFinalWithExactAccounting() {
        val payload = ByteArray(1234) { it.toByte() }
        val orphan = incomingFinal("manual-orphan", payload)

        val result = manualCleanup()

        assertEquals(1, result.removedCount)
        assertEquals(payload.size.toLong(), result.removedBytes)
        assertFalse(orphan.exists())
    }

    // ------------------------------------------------------------------
    // B. incoming live transaction -> manual action preserves
    // ------------------------------------------------------------------

    @Test
    fun manualActionPreservesIncomingLiveTransaction() = runBlocking {
        val owned = acquireIncoming("manual-live-tx")

        val result = manualCleanup()

        assertEquals("live transaction candidate must not be reclaimed", 0, result.removedCount)
        assertTrue(owned.file.exists())
        owned.cleanup()
        Unit
    }

    // ------------------------------------------------------------------
    // C. incoming adopted live document -> manual action preserves
    // ------------------------------------------------------------------

    @Test
    fun manualActionPreservesIncomingAdoptedDocument() = runBlocking {
        val owned = acquireIncoming("manual-adopted-doc").also { it.transferToDocument() }
        try {
            val result = manualCleanup()

            assertEquals("adopted document root must survive manual cleanup", 0, result.removedCount)
            assertTrue(owned.file.exists())
        } finally {
            IncomingSourceLiveOwnership.releaseDocumentForTest(owned.file)
            owned.file.delete()
        }
        Unit
    }

    // ------------------------------------------------------------------
    // D. stale UI snapshot -> source becomes live AFTER snapshot ->
    //    delete-time authority preserves
    // ------------------------------------------------------------------

    @Test
    fun staleUiSnapshotCannotAuthorizeDeletionOfNewlyAdoptedDocument() = runBlocking {
        val path = incomingFinal("manual-stale-incoming", byteArrayOf(9, 9, 9))
        val (reached, release) = installManualCleanupSeam()
        val action = async(Dispatchers.Default) { manualCleanup() }
        withTimeout(15_000L) { reached.await() }
        // The UI snapshot said this path was unprotected; adoption happens
        // before any physical deletion can run.
        assertTrue(path.delete())
        acquireIncoming("manual-stale-incoming").transferToDocument()
        release.complete(Unit)
        withTimeout(15_000L) { action.await() }

        assertTrue(
            "delete-time registry authority must preserve the newly adopted document",
            path.exists(),
        )
        assertEquals(0, lastResult.removedCount)
        IncomingSourceLiveOwnership.releaseDocumentForTest(path)
        path.delete()
        Unit
    }

    // ------------------------------------------------------------------
    // E. restored working orphan -> manual action deletes
    // ------------------------------------------------------------------

    @Test
    fun manualActionDeletesUnownedRestoredWorkingOrphanWithExactAccounting() {
        val payload = ByteArray(2048) { (it * 3).toByte() }
        val orphan = restoredWorking("manual-restored-orphan", payload)

        val result = manualCleanup()

        assertEquals(1, result.removedCount)
        assertEquals(payload.size.toLong(), result.removedBytes)
        assertFalse(orphan.exists())
    }

    // ------------------------------------------------------------------
    // F. restored working live restore -> manual action preserves
    // ------------------------------------------------------------------

    @Test
    fun manualActionPreservesRestoredWorkingLiveRestore() {
        val live = restoredWorking("manual-live-restore", byteArrayOf(1))
        RestoredWorkingSourceOwnership.acquire(live)
        try {
            val result = manualCleanup()

            assertEquals("live restore candidate must not be reclaimed", 0, result.removedCount)
            assertTrue(live.exists())
        } finally {
            RestoredWorkingSourceOwnership.releaseRestore(live)
        }
        Unit
    }

    // ------------------------------------------------------------------
    // G. restored working adopted document -> manual action preserves
    // ------------------------------------------------------------------

    @Test
    fun manualActionPreservesRestoredWorkingDocument() {
        val document = restoredWorking("manual-restored-doc", byteArrayOf(2))
        RestoredWorkingSourceOwnership.acquire(document)
        RestoredWorkingSourceOwnership.transferToDocument(document, null)
        try {
            val result = manualCleanup()

            assertEquals("restored document root must survive manual cleanup", 0, result.removedCount)
            assertTrue(document.exists())
        } finally {
            RestoredWorkingSourceOwnership.releaseDocument(document)
        }
        Unit
    }

    // ------------------------------------------------------------------
    // H. stale UI snapshot for restored source -> becomes document before
    //    delete -> survives
    // ------------------------------------------------------------------

    @Test
    fun staleUiSnapshotCannotDeleteNewlyAdoptedRestoredDocument() = runBlocking {
        val path = restoredWorking("manual-stale-restored", byteArrayOf(7, 7))
        val (reached, release) = installManualCleanupSeam()
        val action = async(Dispatchers.Default) { manualCleanup() }
        withTimeout(15_000L) { reached.await() }
        RestoredWorkingSourceOwnership.acquire(path)
        RestoredWorkingSourceOwnership.transferToDocument(path, null)
        release.complete(Unit)
        withTimeout(15_000L) { action.await() }

        assertTrue(
            "delete-time authority must preserve the newly adopted restored document",
            path.exists(),
        )
        assertEquals(0, lastResult.removedCount)
        RestoredWorkingSourceOwnership.releaseDocument(path)
        path.delete()
        Unit
    }

    // ------------------------------------------------------------------
    // I. two live ViewModels/documents -> one manual cleanup cannot delete
    //    the other's source
    // ------------------------------------------------------------------

    @Test
    fun manualCleanupRespectEveryLiveDocumentAcrossViewModels() = runBlocking {
        val incomingDoc = acquireIncoming("manual-vm-one").also { it.transferToDocument() }
        val restoredDoc = restoredWorking("manual-vm-two", byteArrayOf(5, 5))
        RestoredWorkingSourceOwnership.acquire(restoredDoc)
        RestoredWorkingSourceOwnership.transferToDocument(restoredDoc, null)
        try {
            // No captured strings at all: protection comes purely from the
            // registries, as it must for another ViewModel's live document.
            val result = manualCleanup()

            assertEquals("both live documents survive", 0, result.removedCount)
            assertTrue(incomingDoc.file.exists())
            assertTrue(restoredDoc.exists())
        } finally {
            IncomingSourceLiveOwnership.releaseDocumentForTest(incomingDoc.file)
            incomingDoc.file.delete()
            RestoredWorkingSourceOwnership.releaseDocument(restoredDoc)
        }
        Unit
    }

    // ------------------------------------------------------------------
    // J. physical delete failure -> excluded from count/bytes; file remains
    // ------------------------------------------------------------------

    @Test
    fun failedPhysicalDeleteIsExcludedFromResultTruthfully() {
        val orphanPayload = ByteArray(500) { 1 }
        val orphan = incomingFinal("manual-fail-sibling", orphanPayload)
        val stubborn = File(context.cacheDir, "source_manual-stubborn.img").apply { writeText("x") }
        RandomAccessFile(stubborn, "rw").use { handle ->
            handle.write(0)
            val result = manualCleanup()

            if (stubborn.exists()) {
                // Windows host: the open handle makes deletion fail truthfully.
                assertEquals("failure must not be counted", 1, result.removedCount)
                assertEquals("failed bytes are never reclaimed", orphanPayload.size.toLong(), result.removedBytes)
                assertTrue(stubborn.exists())
            } else {
                // POSIX platform allowed deleting the open file: DELETED is truthful.
                assertEquals(2, result.removedCount)
            }
        }
        stubborn.delete()
        Unit
    }

    // ------------------------------------------------------------------
    // K. stats vs action consistency: same classification, no deletion
    //    while inspecting
    // ------------------------------------------------------------------

    @Test
    fun manualStatisticsMatchActualActionWithoutOwnershipChanges() {
        val first = incomingFinal("manual-stats-a", ByteArray(100))
        val second = incomingFinal("manual-stats-b", ByteArray(200))
        val restored = restoredWorking("manual-stats-restored", ByteArray(300))

        val snapshot = calculateTemporaryCacheStatsForTest(context, null, null)
        assertEquals("reclaimable candidates across both families", 3, snapshot.reclaimableCount)
        assertEquals(600L, snapshot.reclaimableBytes)
        // Backend statistics expose the physically-eligible candidate count too.
        val backendSnapshot =
            com.projectnuke.keplerstudio.editor.TransientSourceMaintenance.inspectManualTransientSources(context)
        assertEquals(3, backendSnapshot.candidateCount)
        assertEquals(snapshot.reclaimableCount, backendSnapshot.reclaimableCount)

        // Inspection must never delete anything.
        val reread = calculateTemporaryCacheStatsForTest(context, null, null)
        assertEquals(snapshot.reclaimableCount, reread.reclaimableCount)
        assertTrue(first.exists())
        assertTrue(second.exists())
        assertTrue(restored.exists())

        val result = manualCleanup()
        assertEquals("action reclaims exactly what truthful stats reported", snapshot.reclaimableCount, result.removedCount)
        assertEquals(snapshot.reclaimableBytes, result.removedBytes)
        assertFalse(first.exists())
        assertFalse(second.exists())
        assertFalse(restored.exists())
    }

    // ------------------------------------------------------------------
    // L. ownership changes after the stats snapshot -> action safely
    //    deletes LESS than the stale displayed estimate
    // ------------------------------------------------------------------

    @Test
    fun ownershipChangeAfterSnapshotMakesActionDeleteLessThanDisplayed() = runBlocking {
        val incoming = incomingFinal("manual-stale-stats-incoming", ByteArray(400))
        val restored = restoredWorking("manual-stale-stats-restored", ByteArray(600))

        val snapshot = calculateTemporaryCacheStatsForTest(context, null, null)
        assertEquals(2, snapshot.reclaimableCount)
        assertEquals(1000L, snapshot.reclaimableBytes)

        val (reached, release) = installManualCleanupSeam()
        val action = async(Dispatchers.Default) { manualCleanup() }
        withTimeout(15_000L) { reached.await() }
        // The restored copy becomes a live document AFTER the user saw the
        // estimate; the action must favor current ownership safety over
        // matching the stale displayed bytes.
        RestoredWorkingSourceOwnership.acquire(restored)
        RestoredWorkingSourceOwnership.transferToDocument(restored, null)
        release.complete(Unit)
        withTimeout(15_000L) { action.await() }

        assertEquals("only the still-unowned candidate is reclaimed", 1, lastResult.removedCount)
        assertEquals(400L, lastResult.removedBytes)
        assertTrue("newly-owned restored document survives", restored.exists())
        assertFalse(incoming.exists())
        RestoredWorkingSourceOwnership.releaseDocument(restored)
        restored.delete()
        Unit
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private var lastResult: TemporaryCacheCleanupResult = TemporaryCacheCleanupResult(-1, -1L)

    private fun manualCleanup(): TemporaryCacheCleanupResult =
        cleanupTemporarySourceFilesForTest(
            context = context,
            activeSourcePath = null,
            draftSourcePath = null,
            olderThan7DaysOnly = false,
        ).also { lastResult = it }

    /**
     * Installs the production seam gate: [reached] completes when the real
     * manual cleanup has captured its UI snapshot and is parked before any
     * physical deletion; [release] lets the delete-time registry checks run.
     */
    private fun installManualCleanupSeam(): Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>> {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        seamHandle = ManualCacheCleanupTestSeam.install(
            ManualCacheCleanupTestSeam().also {
                it.snapshotReached = reached
                it.snapshotRelease = release
            },
        )
        return reached to release
    }

    private suspend fun acquireIncoming(id: String) =
        IncomingSourceTransaction(
            context,
            inputStreamProvider = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
            idProvider = { id },
        ).acquire(Uri.EMPTY)

    private fun incomingFinal(id: String, payload: ByteArray): File =
        File(context.cacheDir, IncomingSourceArtifactNames.finalName(id))
            .apply { writeBytes(payload) }

    private fun restoredWorking(name: String, payload: ByteArray): File =
        context.filesDir
            .resolve("editor_sources")
            .apply { mkdirs() }
            .resolve("restored_$name.img")
            .apply { writeBytes(payload) }

    private fun clearTransientSources() {
        context.cacheDir.listFiles()?.forEach { file ->
            if (
                IncomingSourceArtifactNames.isFinalName(file.name) ||
                IncomingSourceArtifactNames.isStagingName(file.name)
            ) {
                file.delete()
            }
        }
        context.filesDir.resolve("editor_sources").listFiles()?.forEach { file ->
            if (RestoredWorkingSourceOwnership.isOwnedName(file.name)) file.delete()
        }
    }
}
