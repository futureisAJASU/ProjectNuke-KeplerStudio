package com.projectnuke.keplerstudio.editor

import java.io.File
import android.net.Uri
import org.junit.After
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SuperResolutionOperationJournalTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        context.getSharedPreferences(SuperResolutionMediaProcessingService.JOURNAL_PREFS, 0).edit().clear().commit()
    }

    @Test
    fun recoveryDeletesOnlyTheJournaledRgb8Path() = runBlocking {
        val exact = File(context.cacheDir, "sr6_journaled_${System.nanoTime()}.rgb8")
        val staging = File(context.cacheDir, "${exact.name}.token.tmp")
        val unrelated = File(context.cacheDir, "sr6_unrelated_${System.nanoTime()}.rgb8")
        exact.writeBytes(byteArrayOf(1))
        staging.writeBytes(byteArrayOf(3))
        unrelated.writeBytes(byteArrayOf(2))
        val journal = SuperResolutionOperationJournal(context)
        journal.write(
            SuperResolutionDebtRecord(
                operationId = 81L,
                phase = SuperResolutionJournalPhase.RGB8_CREATED,
                startedAtMillis = 1L,
                updatedAtMillis = 1L,
                rgb8Path = exact.absolutePath,
                rgb8StagingPath = staging.absolutePath,
            ),
        )

        assertTrue(recoverExactSuperResolutionDebt(context, journal))
        assertFalse(exact.exists())
        assertFalse(staging.exists())
        assertTrue(unrelated.exists())
        assertTrue(journal.read() == null)
        unrelated.delete()
        Unit
    }

    @Test
    fun postPublicationJournalNeverDeletesTheRecordedPublicUri() = runBlocking {
        val exact = File(context.cacheDir, "sr6_published_${System.nanoTime()}.rgb8")
        exact.writeBytes(byteArrayOf(1))
        val journal = SuperResolutionOperationJournal(context)
        journal.write(
            SuperResolutionDebtRecord(
                operationId = 82L,
                phase = SuperResolutionJournalPhase.PUBLISHED,
                startedAtMillis = 1L,
                updatedAtMillis = 1L,
                rgb8Path = exact.absolutePath,
                pendingUri = "content://media/exact-public-uri",
                publicPublicationCommitted = true,
            ),
        )

        var deleteCalls = 0
        assertTrue(
            recoverExactSuperResolutionDebt(
                context,
                journal,
                pendingState = { false },
                deletePending = { deleteCalls++; 1 },
                rowExists = { true },
            ),
        )
        assertFalse(exact.exists())
        assertTrue(deleteCalls == 0)
        assertTrue(journal.read() == null)
    }

    @Test
    fun pendingMediaStoreDebtDeletesTheExactRecordedRowAndVerifiesAbsence() = runBlocking {
        val journal = SuperResolutionOperationJournal(context)
        journal.write(
            SuperResolutionDebtRecord(
                operationId = 83L,
                phase = SuperResolutionJournalPhase.PENDING_INSERTED,
                startedAtMillis = 1L,
                updatedAtMillis = 1L,
                pendingUri = "content://media/exact-pending-uri",
            ),
        )
        var deleteCalls = 0
        assertTrue(
            recoverExactSuperResolutionDebt(
                context,
                journal,
                pendingState = { true },
                deletePending = { deleteCalls++; 1 },
                rowExists = { false },
            ),
        )
        assertTrue(deleteCalls == 1)
        assertTrue(journal.read() == null)
    }

    @Test
    fun crashImmediatelyBeforePublicationPreservesAWorkflowThatCrossedTheBoundary() = runBlocking {
        val journal = SuperResolutionOperationJournal(context)
        journal.write(
            SuperResolutionDebtRecord(
                operationId = 85L,
                phase = SuperResolutionJournalPhase.BEFORE_PUBLICATION,
                startedAtMillis = 1L,
                updatedAtMillis = 1L,
                pendingUri = "content://media/crossed-public-boundary",
            ),
        )
        var deleteCalls = 0
        assertTrue(
            recoverExactSuperResolutionDebt(
                context,
                journal,
                pendingState = { false },
                deletePending = { deleteCalls++; 1 },
                rowExists = { true },
            ),
        )
        assertTrue(deleteCalls == 0)
        assertTrue(journal.read() == null)
    }

    @Test
    fun crashBeforeRgb8HasNoDebtAndClearsTheJournal() = runBlocking {
        val journal = SuperResolutionOperationJournal(context)
        journal.write(
            SuperResolutionDebtRecord(
                operationId = 84L,
                phase = SuperResolutionJournalPhase.SOURCE_PREPARING,
                startedAtMillis = 1L,
                updatedAtMillis = 1L,
            ),
        )
        assertTrue(recoverExactSuperResolutionDebt(context, journal))
        assertTrue(journal.read() == null)
    }

    @Test
    fun intendedRgb8PathsConvergeEvenWhenProcessDiesBeforeOrAfterCreate() = runBlocking {
        val finalPath = File(context.cacheDir, "sr6_intended_${System.nanoTime()}.rgb8")
        val stagingPath = File(context.cacheDir, "${finalPath.name}.token.tmp")
        val journal = SuperResolutionOperationJournal(context)
        journal.write(
            SuperResolutionDebtRecord(
                operationId = 86L,
                phase = SuperResolutionJournalPhase.RGB8_INTENDED,
                startedAtMillis = 1L,
                updatedAtMillis = 1L,
                rgb8Path = finalPath.absolutePath,
                rgb8StagingPath = stagingPath.absolutePath,
            ),
        )
        assertTrue(recoverExactSuperResolutionDebt(context, journal))
        assertTrue(journal.read() == null)

        finalPath.writeBytes(byteArrayOf(1))
        stagingPath.writeBytes(byteArrayOf(2))
        journal.write(
            SuperResolutionDebtRecord(
                operationId = 87L,
                phase = SuperResolutionJournalPhase.RGB8_CREATED,
                startedAtMillis = 1L,
                updatedAtMillis = 1L,
                rgb8Path = finalPath.absolutePath,
                rgb8StagingPath = stagingPath.absolutePath,
            ),
        )
        assertTrue(recoverExactSuperResolutionDebt(context, journal))
        assertFalse(finalPath.exists())
        assertFalse(stagingPath.exists())
    }

    @Test
    fun intendedMediaStoreIdentityFindsOnlyItsPendingRowBeforeUriWasJournaled() = runBlocking {
        val journal = SuperResolutionOperationJournal(context)
        val expected = Uri.parse("content://media/exact-token-row")
        journal.write(
            SuperResolutionDebtRecord(
                operationId = 88L,
                phase = SuperResolutionJournalPhase.PENDING_INTENDED,
                startedAtMillis = 1L,
                updatedAtMillis = 1L,
                mediaOwnershipToken = "token-88",
                mediaDisplayName = "exact-token-88.png",
                mediaRelativePath = "Pictures/KeplerStudio/",
                mediaCollectionUri = "content://media/external/images/media",
            ),
        )
        var deleted: Uri? = null
        assertTrue(
            recoverExactSuperResolutionDebt(
                context,
                journal,
                pendingState = { true },
                deletePending = { uri -> deleted = uri; 1 },
                rowExists = { false },
                findPendingOwnedRow = { SuperResolutionPendingRowLookup.Found(expected) },
            ),
        )
        assertEquals(expected, deleted)
        assertTrue(journal.read() == null)
    }

    @Test
    fun intendedMediaStoreIdentityWithoutAInsertedRowClearsSafely() = runBlocking {
        val journal = SuperResolutionOperationJournal(context)
        journal.write(
            SuperResolutionDebtRecord(
                operationId = 89L,
                phase = SuperResolutionJournalPhase.PENDING_INTENDED,
                startedAtMillis = 1L,
                updatedAtMillis = 1L,
                mediaOwnershipToken = "token-89",
                mediaDisplayName = "exact-token-89.png",
                mediaRelativePath = "Pictures/KeplerStudio/",
                mediaCollectionUri = "content://media/external/images/media",
            ),
        )
        assertTrue(
            recoverExactSuperResolutionDebt(
                context,
                journal,
                findPendingOwnedRow = { SuperResolutionPendingRowLookup.Absent },
            ),
        )
        assertTrue(journal.read() == null)
    }
}
