package com.projectnuke.keplerstudio.editor

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SuperResolutionOperationRegistryTest {
    @After
    fun tearDown() {
        SuperResolutionOperationRegistry.resetForTest()
        SuperResolutionServiceStartSeam.resetForTest()
        RuntimeEnvironment.getApplication()
            .getSharedPreferences(SuperResolutionMediaProcessingService.JOURNAL_PREFS, 0)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun oneServiceOwnerRejectsDuplicateAndAllCancelRoutesReachSameOwner() {
        val first = request(1L)
        assertEquals(SuperResolutionStartResult.Started(1L), SuperResolutionOperationRegistry.admitForTest(first))
        assertEquals(first, SuperResolutionOperationRegistry.claim(1L))
        var cancelCalls = 0
        assertTrue(SuperResolutionOperationRegistry.bindOwner(1L) { cancelCalls += 1 })

        assertEquals(
            SuperResolutionStartResult.AlreadyRunning(1L),
            SuperResolutionOperationRegistry.admitForTest(request(2L)),
        )
        assertFalse(SuperResolutionOperationRegistry.cancel(2L))
        assertEquals(0, cancelCalls)
        assertTrue(SuperResolutionOperationRegistry.cancelCurrent())
        assertEquals(1, cancelCalls)

        SuperResolutionOperationRegistry.finish(
            1L,
            SuperResolutionExportStatus(
                phase = SuperResolutionExportPhase.Cancelled,
                failureKind = SuperResolutionFailureKind.Cancelled,
            ),
        )
        assertEquals(
            SuperResolutionStartResult.AlreadyRunning(1L),
            SuperResolutionOperationRegistry.admitForTest(request(2L)),
        )
        SuperResolutionOperationRegistry.releaseOwner(1L)
        assertEquals(
            SuperResolutionStartResult.Started(2L),
            SuperResolutionOperationRegistry.admitForTest(request(2L)),
        )
    }

    @Test
    fun processDeathRecoveryAndNewAdmissionAreMutuallyExclusive() {
        assertTrue(SuperResolutionOperationRegistry.beginDebtRecovery())
        assertEquals(
            SuperResolutionStartResult.Failed("process-death cleanup is still settling"),
            SuperResolutionOperationRegistry.admitForTest(request(3L)),
        )
        SuperResolutionOperationRegistry.endDebtRecovery()
        assertEquals(
            SuperResolutionStartResult.Started(3L),
            SuperResolutionOperationRegistry.admitForTest(request(3L)),
        )
        assertFalse(SuperResolutionOperationRegistry.beginDebtRecovery())
    }

    @Test
    fun pendingCancellationIsTerminalAndStaleStartCannotClaimIt() {
        val pending = request(11L)
        assertEquals(SuperResolutionStartResult.Started(11L), SuperResolutionOperationRegistry.admitForTest(pending))
        assertTrue(SuperResolutionOperationRegistry.cancelCurrent())
        assertEquals(SuperResolutionExportPhase.Cancelled, SuperResolutionOperationRegistry.state.value.status.phase)
        assertFalse(SuperResolutionOperationRegistry.state.value.status.isBusy)
        assertFalse(SuperResolutionOperationRegistry.hasActiveOperation())
        assertNull(SuperResolutionOperationRegistry.claim(11L))

        assertEquals(
            SuperResolutionStartResult.Started(12L),
            SuperResolutionOperationRegistry.admitForTest(request(12L)),
        )
    }

    @Test
    fun realSubmitPendingCancellationSettlesAdmissionJournalAndAllowsNextSubmit() {
        val context = RuntimeEnvironment.getApplication()
        val journal = SuperResolutionOperationJournal(context)
        journal.clear(journal.read()?.operationId ?: -1L)
        SuperResolutionServiceStartSeam.start = { _, _ -> }

        assertEquals(
            SuperResolutionStartResult.Started(21L),
            SuperResolutionOperationRegistry.submit(context, request(21L)),
        )
        assertTrue(journal.hasData())
        assertTrue(SuperResolutionOperationRegistry.cancelCurrent())
        assertEquals(SuperResolutionExportPhase.Cancelled, SuperResolutionOperationRegistry.state.value.status.phase)
        assertFalse(journal.hasData())
        assertNull(SuperResolutionOperationRegistry.claim(21L))

        // The stale service delivery cannot resurrect the cancelled admission or its journal.
        assertNull(SuperResolutionOperationRegistry.claim(21L))
        assertEquals(
            SuperResolutionStartResult.Started(22L),
            SuperResolutionOperationRegistry.submit(context, request(22L)),
        )
    }

    @Test
    fun submitConsumesRequestOwnershipOnEveryNonStartedResult() {
        val context = RuntimeEnvironment.getApplication()
        val journal = SuperResolutionOperationJournal(context)

        journal.write(
            SuperResolutionDebtRecord(
                operationId = 40L,
                phase = SuperResolutionJournalPhase.ADMITTED,
                startedAtMillis = 1L,
                updatedAtMillis = 1L,
            ),
        )
        val (debtRequest, debtBitmap) = ownedRequest(41L)
        assertTrue(SuperResolutionOperationRegistry.submit(context, debtRequest) is SuperResolutionStartResult.Failed)
        assertTrue(debtBitmap?.isRecycled == true)
        journal.clear(40L)

        val first = request(42L)
        assertEquals(SuperResolutionStartResult.Started(42L), SuperResolutionOperationRegistry.admitForTest(first))
        val (duplicateRequest, duplicateBitmap) = ownedRequest(43L)
        assertTrue(SuperResolutionOperationRegistry.submit(context, duplicateRequest) is SuperResolutionStartResult.AlreadyRunning)
        assertTrue(duplicateBitmap?.isRecycled == true)
        SuperResolutionOperationRegistry.resetForTest()

        SuperResolutionServiceStartSeam.start = { _, _ -> throw IllegalStateException("start failed") }
        val (failedRequest, failedBitmap) = ownedRequest(44L)
        assertTrue(SuperResolutionOperationRegistry.submit(context, failedRequest) is SuperResolutionStartResult.Failed)
        assertTrue(failedBitmap?.isRecycled == true)

        SuperResolutionServiceStartSeam.start = { _, _ -> }
        val (startedRequest, startedBitmap) = ownedRequest(45L)
        assertEquals(SuperResolutionStartResult.Started(45L), SuperResolutionOperationRegistry.submit(context, startedRequest))
        assertTrue(startedBitmap?.isRecycled == false)
        assertTrue(SuperResolutionOperationRegistry.cancelCurrent())
        assertTrue(startedBitmap?.isRecycled == true)
    }

    @Test
    fun cancellationBetweenClaimAndOwnerBindingSettlesAndBlocksStaleBinding() {
        val request = request(13L)
        assertEquals(SuperResolutionStartResult.Started(13L), SuperResolutionOperationRegistry.admitForTest(request))
        assertNotNull(SuperResolutionOperationRegistry.claim(13L))

        assertTrue(SuperResolutionOperationRegistry.cancelCurrent())
        assertEquals(SuperResolutionExportPhase.Cancelled, SuperResolutionOperationRegistry.state.value.status.phase)
        assertFalse(SuperResolutionOperationRegistry.hasActiveOperation())
        assertFalse(SuperResolutionOperationRegistry.bindOwner(13L) {})
    }

    @Test
    fun timeoutKeepsPhysicallySettlingOwnerUntilExactRelease() {
        assertEquals(SuperResolutionStartResult.Started(31L), SuperResolutionOperationRegistry.admitForTest(request(31L)))
        assertNotNull(SuperResolutionOperationRegistry.claim(31L))
        assertTrue(SuperResolutionOperationRegistry.bindOwner(31L) {})
        assertTrue(SuperResolutionOperationRegistry.beginPhysicalSettlement(31L))
        SuperResolutionOperationRegistry.finish(31L, cancelledStatusForTest())

        assertTrue(
            SuperResolutionOperationRegistry.admitForTest(request(32L)) is SuperResolutionStartResult.Failed,
        )
        assertNull(SuperResolutionOperationRegistry.claim(32L))
        SuperResolutionOperationRegistry.releaseOwner(31L)
        assertEquals(SuperResolutionStartResult.Started(32L), SuperResolutionOperationRegistry.admitForTest(request(32L)))
        assertNotNull(SuperResolutionOperationRegistry.claim(32L))

        // A stale A completion is harmless once B owns the registry.
        SuperResolutionOperationRegistry.releaseOwner(31L)
        assertTrue(SuperResolutionOperationRegistry.isOwner(32L))
    }

    private fun request(id: Long): SuperResolutionServiceRequest =
        SuperResolutionServiceRequest(
            operationId = id,
            documentGeneration = "generation",
            documentIdentity = "document",
            preflight =
                SuperResolutionPreflight(
                    inputWidth = 1,
                    inputHeight = 1,
                    outputWidth = 4,
                    outputHeight = 4,
                    rgb8ScratchBytes = 48,
                    pngRequiredBytes = 256,
                    internalUsableBytes = 1024,
                    destinationUsableBytes = 1024,
                    requiresConfirmation = false,
                ),
            sourceRequest = FullExportSourceRequest.capture(EditorUiState(), "generation"),
        )

    private fun ownedRequest(id: Long): Pair<SuperResolutionServiceRequest, android.graphics.Bitmap?> {
        val source = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        val state = EditorUiState(baseBitmapDirty = true, previewBitmap = source, originalPreviewBitmap = source)
        val snapshot = FullExportSourceRequest.capture(state, "generation")
        val owned = snapshot.ownedBaseBitmapForTest()
        source.recycle()
        return SuperResolutionServiceRequest(
            operationId = id,
            documentGeneration = "generation",
            documentIdentity = "document",
            preflight = SuperResolutionPreflight(1, 1, 4, 4, 48, 256, 1024, 1024, false),
            sourceRequest = snapshot,
        ) to owned
    }

    private fun cancelledStatusForTest() =
        SuperResolutionExportStatus(
            phase = SuperResolutionExportPhase.Cancelled,
            failureKind = SuperResolutionFailureKind.Cancelled,
        )
}
