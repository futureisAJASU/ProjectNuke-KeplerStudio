package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import com.projectnuke.keplerstudio.ui.applyCropTransform
import com.projectnuke.keplerstudio.ui.updateCropRect
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CropProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun cleanDraft() {
        harness = OwnedEditorViewModelHarness(context)
        deleteDirectoryIfPresent(context.filesDir.resolve("editor_history_v3"))
        resetDraftSandboxForTest(context)
    }

    @After
    fun cleanDraftAfter() {
        harness.close()
        deleteDirectoryIfPresent(context.filesDir.resolve("editor_history_v3"))
        resetDraftSandboxForTest(context)
    }

    private fun deleteDirectoryIfPresent(directory: File) {
        runCatching { if (directory.isDirectory) directory.deleteRecursively() }
    }

    // Test 1: crop with selection masks preflights output bytes before the undo
    // capture and transforms the full layer set into the cropped geometry.
    @Test
    fun cropWithSelectionMasksTransformsLayersAndCommitsOneUndo() = runBlocking {
        val sourceFile = cropSourceFile("crop-mask-source.png")
        val vm = editor(sourceFile.absolutePath, withPreview = true, withMask = true)
        val transformCalls = AtomicInteger(0)
        val transform = installCropTransformForTest { source, crop ->
            val dims = cropTransformedDimensions(source.width, source.height, crop)
            val color =
                when (transformCalls.incrementAndGet()) {
                    1 -> 0xff0000ff.toInt()
                    2 -> 0xffff0000.toInt()
                    else -> 0xff00ff00.toInt()
                }
            outputBitmap(dims.first, dims.second, color)
        }
        try {
            awaitReady(vm)
            val startToken = vm.uiState.value.baseContentToken
            vm.updateCropRect(0.25f, 0.25f, 0.75f, 0.75f)
            vm.applyCropTransform()
            awaitEvent { !vm.uiState.value.isBusy && vm.uiState.value.message == "변경사항을 적용했습니다." }

            assertEquals("crop transform ran for original, preview and mask", 3, transformCalls.get())
            assertEquals("preview adopted", 0xffff0000.toInt(), uiPixelColor(vm.uiState.value.previewBitmap))
            assertEquals("original adopted", 0xff0000ff.toInt(), uiPixelColor(vm.uiState.value.originalPreviewBitmap))
            val layers = vm.uiState.value.selectionLayers
            assertEquals("one transformed mask layer", 1, layers.size)
            val layer = layers.single()
            assertEquals("mask layer id preserved", "crop-mask", layer.id)
            assertEquals("mask layer cropped width", 8, layer.bitmap.width)
            assertEquals("mask layer cropped height", 8, layer.bitmap.height)
            assertEquals("mask pixels transformed", 0xff00ff00.toInt(), uiPixelColor(layer.bitmap))
            assertEquals("crop state reset after apply", CropState(), vm.uiState.value.cropState)
            assertFalse("base token must rotate", startToken == vm.uiState.value.baseContentToken)
            assertEquals("one undo entry for the crop", 1, vm.undoEntryCountForTest())
            assertEquals("params untouched", 0f, vm.uiState.value.params.exposure)
        } finally {
            transform.close()
            sourceFile.delete()
        }
    }

    // Test 2: a missing preview is handled without corruption: the crop is a
    // graceful no-op — the original document, crop rect and history stay
    // exactly as they were, and no undo entry is created.
    @Test
    fun cropWithoutPreviewUsesOriginalAsReferenceAndCommitsOneUndo() = runBlocking {
        val sourceFile = cropSourceFile("crop-previewless-source.png")
        val vm = editor(sourceFile.absolutePath, withPreview = false, withMask = false)
        var hookCalls = 0
        val transform = installCropTransformForTest { source, crop ->
            hookCalls++
            val dims = cropTransformedDimensions(source.width, source.height, crop)
            outputBitmap(dims.first, dims.second, 0xff00aaff.toInt())
        }
        try {
            awaitReady(vm)
            assertEquals("no preview before crop", null, vm.uiState.value.previewBitmap)
            val originalPixels = uiPixelColor(vm.uiState.value.originalPreviewBitmap)
            val originalBitmap = vm.uiState.value.originalPreviewBitmap

            vm.updateCropRect(0.5f, 0.5f, 1f, 1f)
            val cropRectBefore = vm.uiState.value.cropState
            vm.applyCropTransform()
            awaitEvent { !vm.uiState.value.isBusy }

            assertEquals("original-only crop transforms one reference bitmap", 1, hookCalls)
            assertNotNull("crop creates a preview", vm.uiState.value.previewBitmap)
            assertFalse("original is replaced", originalBitmap === vm.uiState.value.originalPreviewBitmap)
            assertFalse("original pixels changed", originalPixels == uiPixelColor(vm.uiState.value.originalPreviewBitmap))
            assertFalse("crop state resets after apply", cropRectBefore == vm.uiState.value.cropState)
            assertEquals("one exact crop undo entry", 1, vm.undoEntryCountForTest())
        } finally {
            transform.close()
            sourceFile.delete()
        }
    }

    // Test 3: a failed crop transform rolls back gracefully: the previous
    // document stays exactly as it was and no undo entry is committed.
    @Test
    fun cropFailureRollsBackGracefullyWithoutHistoryEntry() = runBlocking {
        val sourceFile = cropSourceFile("crop-fail-source.png")
        val vm = editor(sourceFile.absolutePath, withPreview = true, withMask = true)
        val startPixels = uiPixelColor(vm.uiState.value.previewBitmap)
        val startLayers = vm.uiState.value.selectionLayers.toList()
        val startToken = vm.uiState.value.baseContentToken
        val transform = installCropTransformForTest { _, _ -> throw IllegalStateException("forced crop failure") }
        try {
            awaitReady(vm)
            vm.updateCropRect(0.25f, 0.25f, 0.75f, 0.75f)
            val cropStateBefore = vm.uiState.value.cropState
            vm.applyCropTransform()
            awaitEvent { !vm.uiState.value.isBusy && vm.uiState.value.message?.startsWith("자르기에 실패했습니다") == true }

            assertEquals("pixels unchanged after failure", startPixels, uiPixelColor(vm.uiState.value.previewBitmap))
            assertEquals("layers unchanged after failure", startLayers, vm.uiState.value.selectionLayers)
            assertEquals("crop rect preserved after failure", cropStateBefore, vm.uiState.value.cropState)
            assertEquals("base token unchanged after failure", startToken, vm.uiState.value.baseContentToken)
            assertEquals("no undo entry on failed crop", 0, vm.undoEntryCountForTest())
        } finally {
            transform.close()
            sourceFile.delete()
        }
    }

    private fun cropSourceFile(name: String): File {
        val source = context.cacheDir.resolve(name)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use { out ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
        } finally {
            bitmap.recycle()
        }
        return source
    }

    private fun editor(sourcePath: String, withPreview: Boolean, withMask: Boolean): EditorViewModel {
        val vm = harness.createEditor()
        val previewBmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        previewBmp.eraseColor(0xff00ff00.toInt())
        val originalBmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        originalBmp.eraseColor(0xff006600.toInt())
        val mask = if (withMask) Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888) else null
        vm.updateUiState {
            it.copy(
                sourcePath = sourcePath,
                baseContentToken = "crop-base",
                previewBitmap = if (withPreview) previewBmp else null,
                originalPreviewBitmap = originalBmp,
                selectionLayers =
                    if (withMask) {
                        listOf(
                            SelectionLayer(
                                id = "crop-mask",
                                name = "Crop Mask",
                                kind = SelectionLayerKind.Brush,
                                bitmap = checkNotNull(mask),
                            )
                        )
                    } else {
                        emptyList()
                    },
                activeSelectionLayerId = if (withMask) "crop-mask" else null,
            )
        }
        awaitInit(vm)
        return vm
    }

    private fun outputBitmap(width: Int, height: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp
    }

    private fun uiPixelColor(bitmap: Bitmap?): Int {
        val bmp = bitmap ?: error("no bitmap")
        return bmp.getPixel(bmp.width / 2, bmp.height / 2)
    }

    private fun awaitReady(vm: EditorViewModel) {
        repeat(200) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (vm.canEnterEditorAction()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        assertTrue(vm.canEnterEditorAction())
    }

    private fun awaitInit(vm: EditorViewModel) {
        awaitEditorCompletionForTest(
            description = "startup init must complete",
            completion = vm.startupInitCompletion,
            timeoutMillis = 30_000L,
            pumpMain = { shadowOf(android.os.Looper.getMainLooper()).idle() },
            diagnostic = { startupDiagnosticForTest(vm = vm, context = context) },
        )
    }

    private fun awaitEvent(predicate: () -> Boolean) {
        repeat(2000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
        }
        assertTrue(predicate())
    }
}
