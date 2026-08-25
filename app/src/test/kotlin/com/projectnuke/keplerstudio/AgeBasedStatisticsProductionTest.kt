package com.projectnuke.keplerstudio

import android.app.Application
import com.projectnuke.keplerstudio.editor.IncomingSourceArtifactNames
import com.projectnuke.keplerstudio.editor.IncomingSourceLiveOwnership
import java.io.File
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
 * Phase-6 corrective: the "7일 지난 캐시" line must be ownership-truthful.
 *
 * The displayed age-reclaimable count/bytes must mirror the REAL AGE_BASED
 * action — incoming finals only, older than the threshold, unprotected,
 * currently ownership-unowned — instead of raw physically-old filenames.
 * Every scenario drives the real production statistics path and the real
 * AGE_BASED action. File aging uses deterministic lastModified timestamps;
 * no sleeps anywhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AgeBasedStatisticsProductionTest {
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun setUp() {
        IncomingSourceLiveOwnership.clearForTest()
        clearIncomingFinals()
    }

    @After
    fun tearDown() {
        IncomingSourceLiveOwnership.clearForTest()
        clearIncomingFinals()
    }

    // ------------------------------------------------------------------
    // A. old unowned incoming final -> old stats include it; action deletes it
    // ------------------------------------------------------------------

    @Test
    fun oldUnownedFinalIsCountedAndThenDeletedByAgeAction() {
        val old = oldFinal("age-stats-orphan", 777L)

        val snapshot = ageStats(active = null, draft = null)

        assertEquals("old unowned file is reclaimable", 1, snapshot.ageReclaimableCount)
        assertEquals(777L, snapshot.ageReclaimableBytes)

        val result = ageAction(active = null, draft = null)

        assertEquals(snapshot.ageReclaimableCount, result.removedCount)
        assertEquals(snapshot.ageReclaimableBytes, result.removedBytes)
        assertFalse(old.exists())
    }

    // ------------------------------------------------------------------
    // B. old live IncomingSource transaction -> stats EXCLUDE; action preserves
    // ------------------------------------------------------------------

    @Test
    fun oldLiveTransactionIsExcludedFromAgeStatisticsAndPreserved() {
        val live = oldFinal("age-stats-live-tx", 100L)
        IncomingSourceLiveOwnership.register(live, live)
        try {
            val snapshot = ageStats(active = null, draft = null)

            assertEquals("live transaction root is not reclaimable", 0, snapshot.ageReclaimableCount)
            assertEquals(0L, snapshot.ageReclaimableBytes)

            val result = ageAction(active = null, draft = null)

            assertEquals(0, result.removedCount)
            assertTrue(live.exists())
        } finally {
            IncomingSourceLiveOwnership.release(live, live)
        }
        Unit
    }

    // ------------------------------------------------------------------
    // C. old adopted incoming document -> stats exclude; action preserves
    // ------------------------------------------------------------------

    @Test
    fun oldAdoptedDocumentIsExcludedFromAgeStatisticsAndPreserved() {
        val document = oldFinal("age-stats-doc", 200L)
        IncomingSourceLiveOwnership.registerDocument(document)
        try {
            val snapshot = ageStats(active = null, draft = null)

            assertEquals(0, snapshot.ageReclaimableCount)
            assertEquals(0L, snapshot.ageReclaimableBytes)

            val result = ageAction(active = null, draft = null)

            assertEquals(0, result.removedCount)
            assertTrue(document.exists())
        } finally {
            IncomingSourceLiveOwnership.releaseDocument(document)
        }
        Unit
    }

    // ------------------------------------------------------------------
    // D/E. old file protected by activeSourcePath / draftSourcePath ->
    //      stats exclude; action preserves
    // ------------------------------------------------------------------

    @Test
    fun oldFileProtectedByActiveRootIsExcludedAndPreserved() {
        val protectedFile = oldFinal("age-stats-active", 300L)

        val snapshot = ageStats(active = protectedFile.absolutePath, draft = null)
        assertEquals(0, snapshot.ageReclaimableCount)

        val result = ageAction(active = protectedFile.absolutePath, draft = null)
        assertEquals(0, result.removedCount)
        assertTrue(protectedFile.exists())
        protectedFile.delete()
        Unit
    }

    @Test
    fun oldFileProtectedByDraftRootIsExcludedAndPreserved() {
        val protectedFile = oldFinal("age-stats-draft", 350L)

        val snapshot = ageStats(active = null, draft = protectedFile.absolutePath)
        assertEquals(0, snapshot.ageReclaimableCount)

        val result = ageAction(active = null, draft = protectedFile.absolutePath)
        assertEquals(0, result.removedCount)
        assertTrue(protectedFile.exists())
        protectedFile.delete()
        Unit
    }

    // ------------------------------------------------------------------
    // F. young unowned incoming file -> stats exclude; action keeps it
    // ------------------------------------------------------------------

    @Test
    fun youngUnownedFinalIsExcludedFromAgeStatisticsAndKept() {
        val young = incomingFinal("age-stats-young", 64L)

        val snapshot = ageStats(active = null, draft = null)
        assertEquals("young files are not age-eligible", 0, snapshot.ageReclaimableCount)
        // Backend statistics still see it as a physical AGE_BASED candidate.
        val backend =
            com.projectnuke.keplerstudio.editor.TransientSourceMaintenance.inspectTransientSources(
                context,
                com.projectnuke.keplerstudio.editor.TransientMaintenanceMode.AGE_BASED,
                olderThanMillis = 7L * 24L * 60L * 60L * 1000L,
            )
        assertEquals(1, backend.candidateCount)
        assertEquals(0, backend.reclaimableCount)

        val result = ageAction(active = null, draft = null)
        assertEquals(0, result.removedCount)
        assertTrue(young.exists())
        young.delete()
        Unit
    }

    // ------------------------------------------------------------------
    // G. no ownership/root change between stats and action -> exact parity
    // ------------------------------------------------------------------

    @Test
    fun ageStatisticsMatchActualAgeActionWithoutChanges() {
        val first = oldFinal("age-stats-parity-a", 111L)
        val second = oldFinal("age-stats-parity-b", 222L)
        val young = incomingFinal("age-stats-parity-young", 9L)

        val snapshot = ageStats(active = null, draft = null)
        assertEquals(2, snapshot.ageReclaimableCount)
        assertEquals(333L, snapshot.ageReclaimableBytes)

        val result = ageAction(active = null, draft = null)
        assertEquals(snapshot.ageReclaimableCount, result.removedCount)
        assertEquals(snapshot.ageReclaimableBytes, result.removedBytes)
        assertFalse(first.exists())
        assertFalse(second.exists())
        assertTrue(young.exists())
        young.delete()
        Unit
    }

    // ------------------------------------------------------------------
    // H. ownership changes AFTER the stats snapshot -> action safely deletes
    //    less than the stale estimate; newly-owned file survives
    // ------------------------------------------------------------------

    @Test
    fun ownershipChangeAfterSnapshotMakesAgeActionDeleteLessThanDisplayed() = runBlocking {
        val free = oldFinal("age-stats-stale-free", 400L)
        val becomingDocument = oldFinal("age-stats-stale-doc", 500L)

        val snapshot = ageStats(active = null, draft = null)
        assertEquals(2, snapshot.ageReclaimableCount)
        assertEquals(900L, snapshot.ageReclaimableBytes)

        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val seamHandle = ManualCacheCleanupTestSeam.install(
            ManualCacheCleanupTestSeam().also {
                it.snapshotReached = reached
                it.snapshotRelease = release
            },
        )
        try {
            val action = async(Dispatchers.Default) { ageAction(active = null, draft = null) }
            withTimeout(15_000L) { reached.await() }
            // The file becomes a live document after the user saw the
            // estimate; delete-time authority must win over stale bytes.
            IncomingSourceLiveOwnership.registerDocument(becomingDocument)
            release.complete(Unit)
            withTimeout(15_000L) { action.await() }

            assertEquals("only the still-unowned file was reclaimed", 1, lastResult.removedCount)
            assertEquals(400L, lastResult.removedBytes)
            assertTrue("newly-owned document survives", becomingDocument.exists())
            assertFalse(free.exists())
        } finally {
            seamHandle.close()
            release.complete(Unit)
            IncomingSourceLiveOwnership.releaseDocument(becomingDocument)
        }
        Unit
    }

    // ------------------------------------------------------------------
    // Root-change classification: the protection roots themselves flip the
    // statistics (Compose producer keys on exactly these values).
    // ------------------------------------------------------------------

    @Test
    fun changingProtectionRootsFlipsBothStatFamilies() {
        val file = oldFinal("age-stats-root-flip", 810L)

        val unprotected = ageStats(active = null, draft = null)
        assertEquals("unowned: reclaimable for both actions", 1, unprotected.reclaimableCount)
        assertEquals(1, unprotected.ageReclaimableCount)

        val activeProtected = ageStats(active = file.absolutePath, draft = null)
        assertEquals(0, activeProtected.reclaimableCount)
        assertEquals(0, activeProtected.ageReclaimableCount)

        val draftProtected = ageStats(active = null, draft = file.absolutePath)
        assertEquals(0, draftProtected.reclaimableCount)
        assertEquals(0, draftProtected.ageReclaimableCount)

        file.delete()
        Unit
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private var lastResult: TemporaryCacheCleanupResult = TemporaryCacheCleanupResult(-1, -1L)

    private fun ageStats(active: String?, draft: String?): TemporaryCacheStats =
        calculateTemporaryCacheStatsForTest(context, active, draft)

    private fun ageAction(active: String?, draft: String?): TemporaryCacheCleanupResult =
        cleanupTemporarySourceFilesForTest(
            context = context,
            activeSourcePath = active,
            draftSourcePath = draft,
            olderThan7DaysOnly = true,
        ).also { lastResult = it }

    private fun incomingFinal(id: String, bytes: Long): File =
        File(context.cacheDir, IncomingSourceArtifactNames.finalName(id))
            .apply { writeByteArrays(bytes) }

    /** Deterministic aging: no sleeps, explicit timestamps only. */
    private fun oldFinal(id: String, bytes: Long): File =
        incomingFinal(id, bytes).apply {
            setLastModified(System.currentTimeMillis() - (8L * 24L * 60L * 60L * 1000L))
        }

    private fun File.writeByteArrays(size: Long): File {
        val payload = ByteArray(size.toInt()) { (it % 251).toByte() }
        writeBytes(payload)
        return this
    }

    private fun clearIncomingFinals() {
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
