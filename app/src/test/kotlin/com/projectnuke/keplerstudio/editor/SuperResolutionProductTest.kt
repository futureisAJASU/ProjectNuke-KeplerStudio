package com.projectnuke.keplerstudio.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperResolutionProductTest {
    @Test
    fun acceptedFixturePreflightUsesOverflowSafeActualGeometry() {
        val result = computeSuperResolutionPreflight(
            inputWidth = 4080,
            inputHeight = 3060,
            internalUsableBytes = 4L * 1024L * 1024L * 1024L,
            destinationUsableBytes = 4L * 1024L * 1024L * 1024L,
        ) as SuperResolutionPreflightResult.Ready

        assertEquals(16320, result.preflight.outputWidth)
        assertEquals(12240, result.preflight.outputHeight)
        assertEquals(599_270_400L, result.preflight.rgb8ScratchBytes)
        assertTrue(result.preflight.pngRequiredBytes >= result.preflight.rgb8ScratchBytes)
        assertTrue(result.preflight.requiresConfirmation)
    }

    @Test
    fun insufficientScratchRejectsBeforeRuntimeWork() {
        val result = computeSuperResolutionPreflight(4080, 3060, 128L, Long.MAX_VALUE)
        assertTrue(result is SuperResolutionPreflightResult.Rejected)
        assertEquals(
            SuperResolutionFailureKind.InternalStorageInsufficient,
            (result as SuperResolutionPreflightResult.Rejected).failure,
        )
    }

    @Test
    fun dimensionOverflowFailsClosed() {
        val result = computeSuperResolutionPreflight(Int.MAX_VALUE, 1, Long.MAX_VALUE, Long.MAX_VALUE)
        assertTrue(result is SuperResolutionPreflightResult.Rejected)
        assertEquals(
            SuperResolutionFailureKind.InvalidDimensions,
            (result as SuperResolutionPreflightResult.Rejected).failure,
        )
    }

    @Test
    fun availabilityUsesRegistryTruthWithoutTechnicalDetail() {
        val ready = ModelCapabilityState(
            phase = ModelCapabilityPhase.Loadable,
            assetPresent = true,
            assetValid = true,
            runtimeAvailable = true,
            contractSupported = true,
            runnerImplemented = true,
        )
        assertTrue(superResolutionAvailability(ready).canStart)
        assertEquals(
            SuperResolutionAvailability.MODEL_UNAVAILABLE,
            superResolutionAvailability(ModelCapabilityState(phase = ModelCapabilityPhase.AssetInvalid)).availability,
        )
        val runtime = superResolutionAvailability(ModelCapabilityState(phase = ModelCapabilityPhase.RuntimeUnavailable))
        assertEquals(SuperResolutionAvailability.RUNTIME_UNAVAILABLE, runtime.availability)
        assertFalse(runtime.reason.contains("ENN"))
        assertFalse(runtime.reason.contains("NNC"))
    }

    @Test
    fun progressPresentationUsesFourStagesAndNeverRegresses() {
        val previous =
            SuperResolutionExportStatus(
                phase = SuperResolutionExportPhase.Upscaling,
                isBusy = true,
                progress = SuperResolutionExportProgress(
                    phase = SuperResolutionExportPhase.Upscaling,
                    overallFraction = 0.42f,
                    completedTiles = 12,
                    totalTiles = 100,
                    canCancel = true,
                ),
            )
        val regressing =
            previous.copy(
                phase = SuperResolutionExportPhase.Encoding,
                progress = previous.progress.copy(
                    phase = SuperResolutionExportPhase.Encoding,
                    overallFraction = 0.30f,
                    encodingRowsCompleted = 32,
                    encodingRowsTotal = 1000,
                ),
            )
        val settled = monotonicSuperResolutionStatus(previous, regressing)
        assertEquals(0.42f, settled.progress.overallFraction)
        assertEquals(SuperResolutionUserStage.CREATING_IMAGE, superResolutionProgressUi(settled).stage)
    }

    @Test
    fun publishedCleanupDebtNeverTellsUserPhotoWasLostOrOffersRetry() {
        val uri = android.net.Uri.EMPTY
        val status =
            SuperResolutionExportStatus(
                phase = SuperResolutionExportPhase.Succeeded,
                publishedUri = uri,
                cleanupDebt = true,
                failureKind = SuperResolutionFailureKind.InternalCleanupFailure,
            )
        val failure = checkNotNull(superResolutionFailureUi(status))
        assertEquals(SuperResolutionUserFailure.CLEANUP_DEBT, failure.failure)
        assertEquals(uri, failure.publishedUri)
        assertFalse(failure.retrySafe)
        assertTrue(failure.message.contains("저장"))
    }
}
