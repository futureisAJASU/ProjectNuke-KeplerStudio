package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import android.content.pm.ServiceInfo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SuperResolutionProductTest {
    private val context get() = RuntimeEnvironment.getApplication()
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
    fun postRgb8AdmissionCountsOnlyRemainingPngAndReserve() {
        val initial =
            computeSuperResolutionPreflight(
                100,
                80,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                internalVolumeId = "volume",
                destinationVolumeId = "volume",
                runtimeReserveBytes = 0L,
            ) as SuperResolutionPreflightResult.Ready
        val p = initial.preflight
        val initialFree = p.combinedRequiredBytes + 1L
        val currentFreeAfterRgb8 = initialFree - p.rgb8ScratchBytes

        assertTrue(
            computeSuperResolutionPostRgb8Preflight(
                pngRequiredBytes = p.pngRequiredBytes,
                internalUsableBytes = currentFreeAfterRgb8,
                destinationUsableBytes = currentFreeAfterRgb8,
                internalVolumeId = "volume",
                destinationVolumeId = "volume",
                runtimeReserveBytes = 0L,
            ) is SuperResolutionPostRgb8StorageResult.Ready,
        )
        // The old full-peak check would count the already-consumed RGB8 again and reject here.
        assertTrue(
            computeSuperResolutionPreflight(
                100,
                80,
                currentFreeAfterRgb8,
                currentFreeAfterRgb8,
                internalVolumeId = "volume",
                destinationVolumeId = "volume",
                runtimeReserveBytes = 0L,
            ) is SuperResolutionPreflightResult.Rejected,
        )
    }

    @Test
    fun postRgb8AdmissionRejectsWhenRemainingPngDoesNotFit() {
        val rejected =
            computeSuperResolutionPostRgb8Preflight(
                pngRequiredBytes = 10_000L,
                internalUsableBytes = 1_000L,
                destinationUsableBytes = 1_000L,
                internalVolumeId = "volume",
                destinationVolumeId = "volume",
                runtimeReserveBytes = 0L,
            )
        assertTrue(rejected is SuperResolutionPostRgb8StorageResult.Rejected)
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
    fun sourcePathNullCleanRenderedFullExportCopiesPreviewBeforeCrop() = kotlinx.coroutines.runBlocking {
        val preview = Bitmap.createBitmap(7, 5, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(preview.width * preview.height) { index -> 0xff000000.toInt() or index }
        preview.setPixels(pixels, 0, preview.width, 0, 0, preview.width, preview.height)
        val state =
            EditorUiState(
                sourcePath = null,
                baseBitmapDirty = false,
                previewBitmap = preview,
                correctionEngineState =
                    CorrectionEngineState(
                        visiblePreview =
                            VisiblePreviewState.Rendered(
                                requestedRoute = NativeRenderRoute.V1,
                                actualRoute = NativeRenderRoute.V1,
                                decision = RenderRouteDecision.StoredVisibleTruth,
                                algorithmVersion = "frozen-test",
                            ),
                    ),
            )
        val request = FullExportSourceRequest.capture(state, "frozen-generation")
        val prepared = prepareFullExportSourceBitmapFromRequest(request)
        assertEquals(7, prepared.width)
        assertEquals(5, prepared.height)
        assertArrayEquals(pixels, IntArray(pixels.size).also { prepared.getPixels(it, 0, prepared.width, 0, 0, prepared.width, prepared.height) })
        prepared.recycle()
        preview.recycle()
    }

    @Test
    fun sourcePathNullCleanRenderedFullExportAppliesOnlyTheExistingCropTransform() = kotlinx.coroutines.runBlocking {
        val preview = Bitmap.createBitmap(10, 8, Bitmap.Config.ARGB_8888)
        preview.eraseColor(0xff234567.toInt())
        val crop = CropState(cropLeft = 0.1f, cropTop = 0.1f, cropRight = 0.9f, cropBottom = 0.9f)
        val state = EditorUiState(sourcePath = null, baseBitmapDirty = false, previewBitmap = preview, cropState = crop)
        val request = FullExportSourceRequest.capture(state, "frozen-generation")
        val cropSeam = installCropTransformForTest { input, cropState ->
            val dimensions = cropTransformedDimensions(input.width, input.height, cropState)
            Bitmap.createBitmap(
                input,
                0,
                0,
                dimensions.first.coerceAtMost(input.width),
                dimensions.second.coerceAtMost(input.height),
            )
        }
        try {
            val prepared = prepareFullExportSourceBitmapFromRequest(request)
            val expected = cropTransformedDimensions(10, 8, crop)
            assertEquals(expected.first, prepared.width)
            assertEquals(expected.second, prepared.height)
            assertEquals(0xff234567.toInt(), prepared.getPixel(prepared.width / 2, prepared.height / 2))
            prepared.recycle()
        } finally {
            cropSeam.close()
            preview.recycle()
        }
    }

    @Test
    fun appOwnedCleanSourceLeaseSurvivesDocumentOwnerTeardown() {
        val source = File(context.filesDir, "editor_sources/restored_sr_lease_${System.nanoTime()}.img")
        source.parentFile?.mkdirs()
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { bitmap ->
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            RestoredWorkingSourceOwnership.registerDocument(source)
            val request = FullExportSourceRequest.capture(EditorUiState(sourcePath = source.absolutePath), "generation")
            RestoredWorkingSourceOwnership.releaseDocument(source)
            assertEquals(
                RestoredWorkingSourceOwnership.DeleteResult.PRESERVED_LIVE_RESTORE,
                RestoredWorkingSourceOwnership.deleteIfUnowned(source),
            )
            request.close()
            assertEquals(RestoredWorkingSourceOwnership.DeleteResult.DELETED, RestoredWorkingSourceOwnership.deleteIfUnowned(source))
        } finally {
            source.delete()
            RestoredWorkingSourceOwnership.clearForTest()
        }
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
