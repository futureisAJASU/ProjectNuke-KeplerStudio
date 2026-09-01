package com.projectnuke.keplerstudio.exynos

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Debug
import android.os.PowerManager
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.projectnuke.keplerstudio.editor.ActiveQuickEffect
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.ModelAvailabilityRegistry
import com.projectnuke.keplerstudio.editor.ModelFeature
import com.projectnuke.keplerstudio.editor.QuickEffectKind
import com.projectnuke.keplerstudio.editor.SuperResolutionExportPhase
import com.projectnuke.keplerstudio.editor.SuperResolutionExportProgress
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bounded physical smoke for the foreground-service owner. This deliberately uses a smaller
 * source than N6's fixed 4080x3060 fixture: N6 remains the authoritative full-resolution test.
 */
@RunWith(AndroidJUnit4::class)
class ProductSuperResolutionServicePhysicalTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as Application

    private fun enabled() =
        InstrumentationRegistry.getArguments().getString("kepler.exynosNpuProbe") == "true"

    private fun isS24Exynos(): Boolean {
        val properties = listOf("ro.board.platform", "ro.soc.model", "ro.product.device", "ro.product.model")
        return properties.mapNotNull { key ->
            runCatching {
                Class.forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java)
                    .invoke(null, key) as String
            }.getOrNull()
        }.joinToString(" ").lowercase().let {
            it.contains("2400") || it.contains("s5e9945") || it.contains("e1s")
        }
    }

    private fun insertFixture(width: Int = 1024, height: Int = 768): android.net.Uri {
        val uri = checkNotNull(
            context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "KeplerStudio_product_service_${System.nanoTime()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KeplerStudio")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        )
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                row[x] =
                    0xff000000.toInt() or
                        (((x * 255 / width) and 0xff) shl 16) or
                        (((y * 255 / height) and 0xff) shl 8) or
                        ((x * 17 + y * 31) and 0xff)
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        context.contentResolver.openOutputStream(uri).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, checkNotNull(output)))
        }
        bitmap.recycle()
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
        return uri
    }

    private fun await(timeoutSeconds: Long, label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline && !condition()) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { }
            Thread.sleep(25)
        }
        assertTrue("timed out waiting for $label", condition())
    }

    private data class DocumentIdentity(
        val sourcePath: String?,
        val baseContentToken: String?,
        val revision: Int,
        val generation: String,
        val previewIdentity: Int?,
        val originalPreviewIdentity: Int?,
        val params: Any,
        val crop: Any,
        val quickEffects: List<ActiveQuickEffect>,
        val canUndo: Boolean,
        val canRedo: Boolean,
        val draftGeneration: String?,
        val draftPointer: String?,
        val draftSourcePath: String?,
        val draftBaseContentToken: String?,
        val draftGenerationSourcePath: String?,
        val draftGenerationThumbnailPath: String?,
    )

    private fun captureDocument(vm: EditorViewModel): DocumentIdentity {
        val state = vm.uiState.value
        return DocumentIdentity(
            sourcePath = state.sourcePath,
            baseContentToken = state.baseContentToken,
            revision = state.revision,
            generation = vm.currentDocumentGeneration(),
            previewIdentity = state.previewBitmap?.let(System::identityHashCode),
            originalPreviewIdentity = state.originalPreviewBitmap?.let(System::identityHashCode),
            params = state.params,
            crop = state.cropState,
            quickEffects = state.activeQuickEffects,
            canUndo = state.canUndo,
            canRedo = state.canRedo,
            draftGeneration = state.draftGenerationId,
            draftPointer = vm.draftPointerBaseline,
            draftSourcePath = state.draftSourcePath,
            draftBaseContentToken = state.draftBaseContentToken,
            draftGenerationSourcePath = state.draftGenerationSourcePath,
            draftGenerationThumbnailPath = state.draftGenerationThumbnailPath,
        )
    }

    private fun documentJson(value: DocumentIdentity) = JSONObject().apply {
        put("source_path", value.sourcePath ?: JSONObject.NULL)
        put("base_content_token", value.baseContentToken ?: JSONObject.NULL)
        put("revision", value.revision)
        put("document_generation", value.generation)
        put("preview_identity", value.previewIdentity ?: JSONObject.NULL)
        put("original_preview_identity", value.originalPreviewIdentity ?: JSONObject.NULL)
        put("params", value.params.toString())
        put("crop", value.crop.toString())
        put("quick_effects", JSONArray(value.quickEffects.map { it.toString() }))
        put("can_undo", value.canUndo)
        put("can_redo", value.canRedo)
        put("draft_generation", value.draftGeneration ?: JSONObject.NULL)
        put("draft_pointer", value.draftPointer ?: JSONObject.NULL)
        put("draft_source_path", value.draftSourcePath ?: JSONObject.NULL)
        put("draft_base_content_token", value.draftBaseContentToken ?: JSONObject.NULL)
        put("draft_generation_source_path", value.draftGenerationSourcePath ?: JSONObject.NULL)
        put("draft_generation_thumbnail_path", value.draftGenerationThumbnailPath ?: JSONObject.NULL)
    }

    private fun sample(label: String, elapsedMs: Long, progress: SuperResolutionExportProgress) =
        JSONObject().apply {
            val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            put("label", label)
            put("elapsed_ms", elapsedMs)
            put("phase", progress.phase.name)
            put("overall_fraction", progress.overallFraction)
            put("tiles", "${progress.completedTiles}/${progress.totalTiles}")
            put("rows", "${progress.encodingRowsCompleted}/${progress.encodingRowsTotal}")
            put("java_heap", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
            put("native_heap", Debug.getNativeHeapAllocatedSize())
            put("pss", memory.totalPss * 1024L)
            put("thermal_status", power.currentThermalStatus)
            put("battery_level", battery?.getIntExtra("level", -1) ?: -1)
        }

    @Test
    fun productServiceOwnsAndReattachesActualNpuExport() = runBlocking {
        assumeTrue("physical product-service probe is opt-in", enabled())
        assumeTrue("target must be Exynos 2400 S24", isS24Exynos())
        ModelAvailabilityRegistry.resetForTest()
        val uri = insertFixture()
        val vm = EditorViewModel(app)
        var reattached: EditorViewModel? = null
        val samples = JSONArray()
        val start = System.nanoTime()
        try {
            val generation = ModelAvailabilityRegistry.beginProbe()
            ModelAvailabilityRegistry.probePackagedCapabilities(context, generation)
            assertTrue(ModelAvailabilityRegistry.state.value[ModelFeature.ExynosUpscale]?.canAttemptModelUse == true)
            vm.openImage(uri)
            await(120, "opened and persisted document") {
                val state = vm.uiState.value
                !state.isBusy && !state.historyBusy && !vm.historyActivityBusyForTest() &&
                    !vm.hasActiveDraftSaveJobForTest() && state.draftGenerationId != null &&
                    state.draftGenerationId == vm.draftPointerBaseline && state.sourcePath != null
            }
            vm.updateParams { it.copy(exposure = 0.25f, contrast = 0.15f) }
            await(90, "parameter render") {
                !vm.uiState.value.isBusy &&
                    vm.uiState.value.params.exposure == 0.25f &&
                    vm.uiState.value.params.contrast == 0.15f
            }
            vm.finishSelectionParamGesture()
            vm.awaitSelectionParamGestureFinishedForTest()
            await(90, "parameter history") {
                !vm.uiState.value.isBusy && !vm.uiState.value.historyBusy &&
                    !vm.historyActivityBusyForTest() &&
                    vm.uiState.value.params.exposure == 0.25f &&
                    vm.uiState.value.params.contrast == 0.15f
            }
            val quickEffectDraftEpoch = vm.draftEpochForTest()
            vm.applyVignetteCorrection()
            await(120, "representative edits") {
                val state = vm.uiState.value
                !state.isBusy && !state.historyBusy && !vm.historyActivityBusyForTest() &&
                    !vm.hasActiveDraftSaveJobForTest() && state.params.exposure == 0.25f &&
                    state.params.contrast == 0.15f &&
                    state.activeQuickEffects.any { it.kind == QuickEffectKind.VignetteCorrection } &&
                    vm.draftEpochForTest() > quickEffectDraftEpoch &&
                    state.draftGenerationId == vm.draftPointerBaseline
            }
            val before = captureDocument(vm)
            vm.exportSuperResolutionProduct()
            await(90, "foreground service ownership") {
                vm.productSuperResolutionOperation.value.status.isBusy
            }
            val operationId = checkNotNull(vm.productSuperResolutionOperation.value.operationId)
            samples.put(sample("started", elapsed(start), vm.productSuperResolutionOperation.value.status.progress))
            await(90, "actual NPU progress") {
                val status = vm.productSuperResolutionOperation.value.status
                status.phase == SuperResolutionExportPhase.Upscaling && status.progress.completedTiles > 0
            }
            samples.put(sample("npu", elapsed(start), vm.productSuperResolutionOperation.value.status.progress))
            reattached = EditorViewModel(app)
            await(30, "ViewModel reattachment") {
                reattached!!.productSuperResolutionOperation.value.operationId == operationId &&
                    reattached!!.productSuperResolutionOperation.value.status.isBusy
            }
            assertTrue("new ViewModel must attach to the same operation", reattached!!.productSuperResolutionOperation.value.status.isBusy)
            await(240, "service terminal settlement") {
                !vm.productSuperResolutionOperation.value.status.isBusy &&
                    vm.productSuperResolutionOperation.value.operationId == operationId
            }
            val terminal = vm.productSuperResolutionOperation.value.status
            samples.put(sample("terminal", elapsed(start), terminal.progress))
            assertEquals(SuperResolutionExportPhase.Succeeded, terminal.phase)
            val published = checkNotNull(terminal.publishedUri)
            await(60, "SavedExport history refresh") {
                vm.uiState.value.savedExports.any { it.uriString == published.toString() }
            }
            val after = captureDocument(vm)
            assertEquals(before, after)
            assertTrue(ModelAvailabilityRegistry.state.value[ModelFeature.ExynosUpscale]?.sessionActive != true)
            val reportDir = File(context.getExternalFilesDir(null), "artifacts/exynos-sr-productization-20260901").apply { mkdirs() }
            File(reportDir, "product_service_e2e.json").writeText(
                JSONObject().apply {
                    put("status", "PASS")
                    put("operation_owner", "SuperResolutionMediaProcessingService")
                    put("operation_id", operationId)
                    put("input", "1024x768")
                    put("output", "4096x3072")
                    put("elapsed_ms", elapsed(start))
                    put("reattached_same_operation", true)
                    put("published_uri", published.toString())
                    put("document_identity_before", documentJson(before))
                    put("document_identity_after", documentJson(after))
                    put("document_unchanged", before == after)
                    put("actual_npu_run", true)
                    put("npu_diagnostics", "covered by the accepted N6 physical evidence lane")
                    put("samples", samples)
                }.toString(2),
            )
        } finally {
            runCatching { reattached?.shutdownForTest() }
            runCatching { vm.shutdownForTest() }
            runCatching { context.contentResolver.delete(uri, null, null) }
            ModelAvailabilityRegistry.resetForTest()
        }
    }

    private fun elapsed(start: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
}
