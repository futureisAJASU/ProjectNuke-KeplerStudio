package com.projectnuke.keplerstudio.editor

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperResolutionOperationRegistryTest {
    @After
    fun tearDown() {
        SuperResolutionOperationRegistry.resetForTest()
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
    fun cancellationBetweenClaimAndOwnerBindingSettlesAndBlocksStaleBinding() {
        val request = request(13L)
        assertEquals(SuperResolutionStartResult.Started(13L), SuperResolutionOperationRegistry.admitForTest(request))
        assertNotNull(SuperResolutionOperationRegistry.claim(13L))

        assertTrue(SuperResolutionOperationRegistry.cancelCurrent())
        assertEquals(SuperResolutionExportPhase.Cancelled, SuperResolutionOperationRegistry.state.value.status.phase)
        assertFalse(SuperResolutionOperationRegistry.hasActiveOperation())
        assertFalse(SuperResolutionOperationRegistry.bindOwner(13L) {})
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
}
