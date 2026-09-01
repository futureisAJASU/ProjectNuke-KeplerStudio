package com.projectnuke.keplerstudio.editor

import java.io.File
import org.junit.After
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
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
}
