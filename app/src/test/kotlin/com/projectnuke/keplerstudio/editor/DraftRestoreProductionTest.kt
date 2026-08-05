package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import com.projectnuke.keplerstudio.bridge.nativeSessionFactoryForTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class DraftRestoreProductionTest {
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun cleanDraft() {
        context.filesDir.resolve("editor_history_v3").deleteRecursively()
        clearCurrentDraftGenerationPointer(context)
        draftGenerationsRoot(context).deleteRecursively()
    }

    @After
    fun cleanDraftAfter() {
        context.filesDir.resolve("editor_history_v3").deleteRecursively()
        clearCurrentDraftGenerationPointer(context)
        draftGenerationsRoot(context).deleteRecursively()
        nativeSessionFactoryForTest = null
    }

    // Test 1: a saved draft generation is actually restored into a fresh
    // EditorViewModel: adopted params, rendered pixels, working source and the
    // draft pointer all reappear.
    @Test
    fun restoredDraftReappliesAdoptedParamsPixelsAndSource() = runBlocking {
        val sourceFile = draftSourceFile("restore-source.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = false)
        val adopted = AtomicInteger(0)
        val restoreRenders = AtomicInteger(0)
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (request.operation == RenderOperation.DraftRestore) {
                    restoreRenders.incrementAndGet()
                    successOutput(0xff0000ff.toInt())
                } else {
                    successOutput(0xffff0000.toInt())
                }
            }
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(onRenderOutputAdopted = { adopted.incrementAndGet() })
            )
        try {
            awaitReady(vm1)
            vm1.updateParams { it.copy(exposure = 0.3f) }
            awaitEvent { adopted.get() >= 1 && vm1.hasOpenParameterGesture() }

            val saved = vm1.persistDraftSnapshotNow()
            assertTrue("draft save must succeed", saved)
            val validated =
                validateCurrentDraftGeneration(context)
                    ?: error("draft must validate after save")
            assertEquals("draft records adopted exposure", 0.3f, validated.manifest.params.exposure)
            val pointer =
                currentDraftGenerationId(context)
                    ?: error("draft pointer must exist after save")

            nativeSessionFactoryForTest = { 1L }
            val vm2 = EditorViewModel(context)
            awaitInit(vm2)
            assertEquals("restore render must have run", 1, restoreRenders.get())
            assertEquals("restored params", 0.3f, vm2.uiState.value.params.exposure)
            assertEquals("restored pixels", 0xff0000ff.toInt(), uiPixelColor(vm2))
            assertEquals("restored message", "임시저장된 편집을 불러왔습니다", vm2.uiState.value.message)
            val restoredSource = vm2.uiState.value.sourcePath
            assertNotNull("restored working source path", restoredSource)
            assertTrue("working source exists", File(restoredSource).isFile)
            assertFalse("working source is a fresh copy", restoredSource == sourceFile.absolutePath)
            assertEquals("draft generation id restored", pointer, vm2.uiState.value.draftGenerationId)
            assertEquals("restored base token", "restore-base", vm2.uiState.value.baseContentToken)
            assertEquals("restored original preview side", 16, vm2.uiState.value.originalPreviewBitmap?.width)
            assertFalse("restored document is not busy", vm2.uiState.value.isBusy)
            awaitEvent { vm2.canEnterEditorAction() }
        } finally {
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    // Test 2: a draft saved with a selection mask restores the full layer
    // (id, name, kind, geometry, enabled, inverted, opacity).
    @Test
    fun restoredDraftReappliesSelectionMaskLayer() = runBlocking {
        val sourceFile = draftSourceFile("restore-mask-source.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = true)
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (request.operation == RenderOperation.DraftRestore) {
                    successOutput(0xff0000ff.toInt())
                } else {
                    successOutput(0xffff0000.toInt())
                }
            }
        try {
            awaitReady(vm1)
            vm1.updateParams { it.copy(exposure = 0.25f) }
            awaitEvent { vm1.adoptedParamsForTest()?.exposure == 0.25f && !vm1.uiState.value.isBusy }
            val saved = vm1.persistDraftSnapshotNow()
            assertTrue("draft save with mask must succeed", saved)
            val validated =
                validateCurrentDraftGeneration(context)
                    ?: error("draft with mask must validate")
            assertEquals("manifest records one mask", 1, validated.manifest.selectionLayers.size)
            assertEquals("mask file persisted", 1, validated.maskFiles.size)

            nativeSessionFactoryForTest = { 1L }
            val vm2 = EditorViewModel(context)
            awaitInit(vm2)
            assertEquals("restored params", 0.25f, vm2.uiState.value.params.exposure)
            assertEquals("restored pixels", 0xff0000ff.toInt(), uiPixelColor(vm2))
            val layers = vm2.uiState.value.selectionLayers
            assertEquals("one restored selection layer", 1, layers.size)
            val layer = layers.single()
            assertEquals("restored layer id", "restore-mask", layer.id)
            assertEquals("restored layer name", "Restore Mask", layer.name)
            assertEquals("restored layer kind", SelectionLayerKind.Brush, layer.kind)
            assertEquals("restored layer geometry", 16, layer.bitmap.width)
            assertEquals("restored layer geometry", 16, layer.bitmap.height)
            assertTrue("restored layer enabled", layer.enabled)
            assertTrue("restored layer inverted", layer.inverted)
            assertEquals("restored layer opacity", 0.5f, layer.opacity)
            assertEquals("restored active layer id", "restore-mask", vm2.uiState.value.activeSelectionLayerId)
            awaitEvent { vm2.canEnterEditorAction() }
        } finally {
            renderer.close()
            sourceFile.delete()
        }
    }

    private fun draftSourceFile(name: String): File {
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

    private fun editor(sourcePath: String, withMask: Boolean): EditorViewModel {
        val vm = EditorViewModel(context)
        val base = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        base.eraseColor(0xff00ff00.toInt())
        val mask = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        vm.updateUiState {
            it.copy(
                sourcePath = sourcePath,
                baseContentToken = "restore-base",
                previewBitmap = base,
                originalPreviewBitmap = base,
                selectionLayers =
                    if (withMask) {
                        listOf(
                            SelectionLayer(
                                id = "restore-mask",
                                name = "Restore Mask",
                                kind = SelectionLayerKind.Brush,
                                bitmap = mask,
                                enabled = true,
                                inverted = true,
                                opacity = 0.5f,
                            )
                        )
                    } else {
                        emptyList()
                    },
                activeSelectionLayerId = if (withMask) "restore-mask" else null,
            )
        }
        awaitInit(vm)
        return vm
    }

    private fun successOutput(color: Int): RenderResult.Success =
        RenderResult.Success(
            operation = RenderOperation.NativePreview,
            requestedRoute = NativeRenderRoute.V1,
            output = outputBitmap(color),
            actualRoute = NativeRenderRoute.V1,
            decision = RenderRouteDecision.FollowDocument,
            usedDebugOverride = false,
            algorithmVersion = AlgorithmContracts.NATIVE_V1,
            participation = RenderParticipation(),
            durationMillis = 0L,
            knownTransientBytes = 0L,
        )

    private fun outputBitmap(color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp
    }

    private fun uiPixelColor(vm: EditorViewModel): Int {
        val preview = vm.uiState.value.previewBitmap ?: error("no preview")
        return preview.getPixel(8, 8)
    }

    private fun awaitReady(vm: EditorViewModel) {
        repeat(200) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (vm.canEnterEditorAction()) return
            Thread.sleep(5)
        }
        assertTrue(vm.canEnterEditorAction())
    }

    private fun awaitInit(vm: EditorViewModel) {
        repeat(2000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (vm.startupInitCompletion.isCompleted) return
            Thread.sleep(5)
        }
        assertTrue("startup init must complete", vm.startupInitCompletion.isCompleted)
    }

    private fun awaitEvent(predicate: () -> Boolean) {
        repeat(300) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            Thread.sleep(5)
        }
        assertTrue(predicate())
    }
}
