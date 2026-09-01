package com.projectnuke.keplerstudio.editor

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            prepareSource = { error("not executed by registry test") },
        )
}
