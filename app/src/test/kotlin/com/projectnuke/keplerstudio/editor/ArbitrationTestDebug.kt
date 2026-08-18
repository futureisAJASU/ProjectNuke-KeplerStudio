package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ArbitrationTestDebug {

    private lateinit var harness: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun setUp() {
        harness = OwnedEditorViewModelHarness(context, installBitmapCopySeam = true)
    }

    @After
    fun tearDown() {
        harness.close()
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (predicate()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
            if (predicate()) return
            Thread.sleep(5L)
        }
        assertTrue("predicate did not settle", predicate())
    }

    private fun createTestBitmap(): Bitmap =
        Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff00aa44.toInt()) }

    @Test
    fun testHarnessWithSourceAndSave() = runBlocking {
        val vm = harness.createEditor()
        await { vm.startupInitCompletion.isCompleted }
        
        val base = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff00aa44.toInt()) }
        val sourceFile = java.io.File(context.filesDir, "drafts/current/test-source.img")
        sourceFile.parentFile?.mkdirs()
        val source = createTestBitmap()
        sourceFile.outputStream().use { check(source.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        
        vm.updateUiState {
            it.copy(
                sourcePath = sourceFile.absolutePath,
                baseContentToken = "test-token",
                previewBitmap = base,
                originalPreviewBitmap = base,
            )
        }
        
        await { vm.canEnterEditorActionPure() }
        assertTrue(vm.canEnterEditorActionPure())
        
        // Try to save - use awaitEditorCompletionForTest like passing tests
        val callerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val deferred = callerScope.async {
            vm.persistDraftSnapshotNow()
        }
        try {
            awaitEditorCompletionForTest(
                description = "draft save caller must complete",
                completion = deferred,
                timeoutMillis = 30_000L,
                pumpMain = { shadowOf(android.os.Looper.getMainLooper()).idle() },
                diagnostic = { "leave=${vm.editorLeaveState.value}" },
            )
            val result = runBlocking { deferred.await() }
            assertTrue("Save must succeed", result)
        } finally {
            deferred.cancel()
            callerScope.cancel()
        }
        vm.acknowledgeEditorLeave()
    }
}