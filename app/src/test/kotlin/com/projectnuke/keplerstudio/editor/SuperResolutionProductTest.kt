package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperResolutionProductTest {
    @Test
    fun foregroundServiceUsesDataSyncOnApi34() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, superResolutionForegroundServiceType(34))
        assertTrue(superResolutionForegroundServiceType(34) != 0)
    }

    @Test
    fun foregroundServiceUsesMediaProcessingOnApi35And36() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, superResolutionForegroundServiceType(35))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, superResolutionForegroundServiceType(36))
    }

    @Test
    fun preflightUsesAuthoritativeFullSourceBoundsNotPreviewBounds() {
        val state =
            EditorUiState(
                sourcePath = "authoritative-source",
                previewBitmap = Bitmap.createBitmap(2040, 1530, Bitmap.Config.ARGB_8888),
            )
        try {
            val geometry =
                checkNotNull(
                    resolveFullExportSourceGeometry(state) {
                        FullExportSourceGeometry(4080, 3060)
                    },
                )
            val preflight =
                computeSuperResolutionPreflight(
                    geometry.width,
                    geometry.height,
                    Long.MAX_VALUE,
                    Long.MAX_VALUE,
                ) as SuperResolutionPreflightResult.Ready
            assertEquals(4080, geometry.width)
            assertEquals(3060, geometry.height)
            assertEquals(4080, preflight.preflight.inputWidth)
            assertEquals(3060, preflight.preflight.inputHeight)
            assertEquals(16320, preflight.preflight.outputWidth)
            assertEquals(12240, preflight.preflight.outputHeight)
            assertEquals(599_270_400L, preflight.preflight.rgb8ScratchBytes)
        } finally {
            state.previewBitmap?.recycle()
        }
    }

    @Test
    fun cropPreflightGeometryMatchesFullExportTransformContract() {
        val crop = CropState(cropLeft = 0.1f, cropTop = 0.2f, cropRight = 0.8f, cropBottom = 0.9f, rotationDegrees = 90)
        val state = EditorUiState(sourcePath = "source", cropState = crop)
        val geometry =
            checkNotNull(resolveFullExportSourceGeometry(state) { FullExportSourceGeometry(4080, 3060) })
        assertEquals(cropTransformedDimensions(4080, 3060, crop).first, geometry.width)
        assertEquals(cropTransformedDimensions(4080, 3060, crop).second, geometry.height)
    }

    @Test
    fun dirtyBakedPreflightUsesTheSameBaseGeometryAsFullExportPreparation() {
        val base = Bitmap.createBitmap(137, 91, Bitmap.Config.ARGB_8888)
        val state = EditorUiState(baseBitmapDirty = true, originalPreviewBitmap = base)
        val geometry = checkNotNull(resolveFullExportSourceGeometry(state))
        assertEquals(137, geometry.width)
        assertEquals(91, geometry.height)
        base.recycle()
    }

    @Test
    fun sameVolumeRejectsWhenIndividualRequirementsFitButCombinedPeakDoesNot() {
        val unconstrained =
            computeSuperResolutionPreflight(
                100,
                80,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                internalVolumeId = "volume",
                destinationVolumeId = "volume",
                runtimeReserveBytes = 0L,
            ) as SuperResolutionPreflightResult.Ready
        val p = unconstrained.preflight
        val free = maxOf(p.rgb8ScratchBytes, p.pngRequiredBytes) + 1L
        assertTrue(free >= p.rgb8ScratchBytes)
        assertTrue(free >= p.pngRequiredBytes)
        val rejected =
            computeSuperResolutionPreflight(
                100,
                80,
                free,
                free,
                internalVolumeId = "volume",
                destinationVolumeId = "volume",
                runtimeReserveBytes = 0L,
            )
        assertTrue(rejected is SuperResolutionPreflightResult.Rejected)
    }

    @Test
    fun sameVolumeAcceptsWhenCombinedPeakFits() {
        val result =
            computeSuperResolutionPreflight(
                100,
                80,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                internalVolumeId = "volume",
                destinationVolumeId = "volume",
                runtimeReserveBytes = 0L,
            ) as SuperResolutionPreflightResult.Ready
        val p = result.preflight
        val combined = p.combinedRequiredBytes + 1L
        val accepted =
            computeSuperResolutionPreflight(
                100,
                80,
                combined,
                combined,
                internalVolumeId = "volume",
                destinationVolumeId = "volume",
                runtimeReserveBytes = 0L,
            )
        assertTrue(accepted is SuperResolutionPreflightResult.Ready)
    }

    @Test
    fun distinctVolumesCheckEachCapacityIndependently() {
        val ready =
            computeSuperResolutionPreflight(
                100,
                80,
                1_000_000L,
                1_000_000L,
                internalVolumeId = "internal",
                destinationVolumeId = "external",
                runtimeReserveBytes = 0L,
            )
        assertTrue(ready is SuperResolutionPreflightResult.Ready)
        val rejected =
            computeSuperResolutionPreflight(
                100,
                80,
                1_000_000L,
                1L,
                internalVolumeId = "internal",
                destinationVolumeId = "external",
                runtimeReserveBytes = 0L,
            )
        assertTrue(rejected is SuperResolutionPreflightResult.Rejected)
        assertEquals(
            SuperResolutionFailureKind.DestinationStorageInsufficient,
            (rejected as SuperResolutionPreflightResult.Rejected).failure,
        )
    }

    @Test
    fun unknownVolumeIdentityUsesConservativeCombinedPolicy() {
        val result =
            computeSuperResolutionPreflight(
                100,
                80,
                100_000L,
                100_000L,
                runtimeReserveBytes = 0L,
            )
        assertTrue(result is SuperResolutionPreflightResult.Rejected)
    }

    @Test
    fun frozenFullExportHandoffDoesNotNeedTheOriginalViewModelBitmap() =
        kotlinx.coroutines.runBlocking {
            val original = Bitmap.createBitmap(19, 13, Bitmap.Config.ARGB_8888)
            original.eraseColor(0xff234567.toInt())
            val state =
                EditorUiState(
                    baseBitmapDirty = true,
                    originalPreviewBitmap = original,
                    previewBitmap = original,
                    baseContentToken = "frozen",
                )
            val request = FullExportSourceRequest.capture(state, "generation")
            original.recycle()
            val prepared = prepareFullExportSourceBitmapFromRequest(request)
            assertEquals(19, prepared.width)
            assertEquals(13, prepared.height)
            prepared.recycle()
            request.close()
        }

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
