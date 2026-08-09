package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
class OpenImageProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private var activeEditor: EditorViewModel? = null
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun setUp() {
        harness = OwnedEditorViewModelHarness(context)
        clearSourceFiles()
    }

    @After
    fun tearDown() {
        activeEditor?.let { editor ->
            editor.clearDraft()
            awaitMainUntil {
                !editor.uiState.value.maintenanceBusy &&
                    !editor.hasActiveDraftSaveJobForTest()
            }
        }
        harness.close()
        clearSourceFiles()
    }

    @Test
    fun copyFailurePreservesPreviousDocumentAndSanitizesMessage() = runBlocking {
        val oldPreview = bitmap(0xffff0000.toInt())
        val oldRelease = AtomicInteger()
        val editor = editorWithDocument(oldPreview, oldRelease)
        val oldPath = checkNotNull(editor.uiState.value.sourcePath)
        val before = sourceFiles()
        replaceSeam(
            OpenImageTestSeam(
                sourceTransactionFactory = { app, _ ->
                    IncomingSourceTransaction(
                        app,
                        inputStreamProvider = {
                            throw IOException("provider secret path")
                        },
                    )
                },
            )
        )

        editor.openImage(Uri.parse("content://incoming/copy-failure"))
        awaitOpenStarted(editor)
        awaitCompletion(editor) { !editor.uiState.value.isBusy && editor.lastOpenImageFailureForTest != null }

        assertEquals(oldPath, editor.uiState.value.sourcePath)
        assertFalse(oldPreview.isRecycled)
        assertEquals(0, oldRelease.get())
        assertEquals(before, sourceFiles())
        assertEquals("선택한 이미지 파일을 읽지 못했습니다.", editor.uiState.value.message)
        assertFalse(editor.uiState.value.message.orEmpty().contains("provider secret path"))
        assertEquals("provider secret path", editor.lastOpenImageFailureForTest?.message)
    }

    @Test
    fun decodeFailureDeletesNewSourceAndPreservesPreviousDocument() = runBlocking {
        val oldPreview = bitmap(0xffff0000.toInt())
        val oldRelease = AtomicInteger()
        val editor = editorWithDocument(oldPreview, oldRelease)
        val oldPath = checkNotNull(editor.uiState.value.sourcePath)
        val before = sourceFiles()
        replaceSeam(
            OpenImageTestSeam(
                sourceTransactionFactory = ::successfulTransaction,
                decode = { throw IllegalStateException("codec secret") },
            )
        )

        editor.openImage(Uri.parse("content://incoming/decode-failure"))
        awaitOpenStarted(editor)
        awaitCompletion(editor) { !editor.uiState.value.isBusy && editor.lastOpenImageFailureForTest?.message == "codec secret" }

        assertEquals(oldPath, editor.uiState.value.sourcePath)
        assertFalse(oldPreview.isRecycled)
        assertEquals(0, oldRelease.get())
        assertEquals(before, sourceFiles())
        assertEquals("이미지를 디코딩하지 못했습니다.", editor.uiState.value.message)
        assertFalse(editor.uiState.value.message.orEmpty().contains("codec secret"))
    }

    @Test
    fun nativeSessionFailureRecyclesPreviewAndDeletesNewSource() = runBlocking {
        val oldPreview = bitmap(0xffff0000.toInt())
        val oldRelease = AtomicInteger()
        val editor = editorWithDocument(oldPreview, oldRelease)
        val oldPath = checkNotNull(editor.uiState.value.sourcePath)
        val newPreview = bitmap(0xff0000ff.toInt())
        val before = sourceFiles()
        replaceSeam(
            OpenImageTestSeam(
                sourceTransactionFactory = ::successfulTransaction,
                decode = { newPreview },
                nativeSessionFactory = { throw IllegalStateException("native secret") },
            )
        )

        editor.openImage(Uri.parse("content://incoming/native-failure"))
        awaitOpenStarted(editor)
        awaitCompletion(editor) { !editor.uiState.value.isBusy && editor.lastOpenImageFailureForTest?.message == "native secret" }

        assertEquals(oldPath, editor.uiState.value.sourcePath)
        assertFalse(oldPreview.isRecycled)
        assertTrue(newPreview.isRecycled)
        assertEquals(0, oldRelease.get())
        assertEquals(before, sourceFiles())
        assertEquals("이미지 처리 세션을 시작하지 못했습니다.", editor.uiState.value.message)
        assertFalse(editor.uiState.value.message.orEmpty().contains("native secret"))
    }

    @Test
    fun successfulOpenTransfersOwnershipAndReleasesPreviousDocumentOnce() = runBlocking {
        val oldPreview = bitmap(0xffff0000.toInt())
        val oldRelease = AtomicInteger()
        val editor = editorWithDocument(oldPreview, oldRelease)
        val oldPath = checkNotNull(editor.uiState.value.sourcePath)
        val newPreview = bitmap(0xff0000ff.toInt())
        val newRelease = AtomicInteger()
        replaceSeam(
            OpenImageTestSeam(
                sourceTransactionFactory = ::successfulTransaction,
                decode = { newPreview },
                nativeSessionFactory = { 2002L },
                nativeSessionReleaser = { session ->
                    assertEquals(2002L, session)
                    newRelease.incrementAndGet()
                },
            )
        )

        editor.openImage(Uri.parse("content://incoming/success"))
        awaitOpenStarted(editor)
        awaitCompletion(editor) {
            !editor.uiState.value.isBusy && editor.uiState.value.sourcePath != oldPath
        }

        val newPath = checkNotNull(editor.uiState.value.sourcePath)
        assertNotEquals(oldPath, newPath)
        assertTrue(File(newPath).isFile)
        assertFalse(File(oldPath).exists())
        assertTrue(oldPreview.isRecycled)
        assertFalse(newPreview.isRecycled)
        assertEquals(1, oldRelease.get())
        assertEquals(0, newRelease.get())
        assertEquals(newPreview, editor.uiState.value.previewBitmap)
    }

    @Test
    fun staleAfterNativeCreationCannotAdoptOrClearNewerBusyOwner() = runBlocking {
        val oldPreview = bitmap(0xffff0000.toInt())
        val oldRelease = AtomicInteger()
        val editor = editorWithDocument(oldPreview, oldRelease)
        val oldPath = checkNotNull(editor.uiState.value.sourcePath)
        val newPreview = bitmap(0xff0000ff.toInt())
        val newRelease = AtomicInteger()
        val before = sourceFiles()
        replaceSeam(
            OpenImageTestSeam(
                sourceTransactionFactory = ::successfulTransaction,
                decode = { newPreview },
                nativeSessionFactory = {
                    editor.updateUiState {
                        it.copy(
                            revision = it.revision + 1,
                            isBusy = true,
                            message = "newer operation owns busy",
                        )
                    }
                    3003L
                },
                nativeSessionReleaser = { newRelease.incrementAndGet() },
            )
        )

        editor.openImage(Uri.parse("content://incoming/stale"))
        awaitOpenStarted(editor)
        awaitCompletion(editor) { !editor.openImageJobActiveForTest() }

        assertEquals(oldPath, editor.uiState.value.sourcePath)
        assertTrue(editor.uiState.value.isBusy)
        assertEquals("newer operation owns busy", editor.uiState.value.message)
        assertFalse(oldPreview.isRecycled)
        assertTrue(newPreview.isRecycled)
        assertEquals(1, newRelease.get())
        assertEquals(0, oldRelease.get())
        assertEquals(before, sourceFiles())
    }

    @Test
    fun cancellationAfterNativeCreationReleasesNewResourcesAndClearsOwnBusyState() = runBlocking {
        val oldPreview = bitmap(0xffff0000.toInt())
        val oldRelease = AtomicInteger()
        val editor = editorWithDocument(oldPreview, oldRelease)
        val oldPath = checkNotNull(editor.uiState.value.sourcePath)
        val newPreview = bitmap(0xff0000ff.toInt())
        val newRelease = AtomicInteger()
        val before = sourceFiles()
        replaceSeam(
            OpenImageTestSeam(
                sourceTransactionFactory = ::successfulTransaction,
                decode = { newPreview },
                nativeSessionFactory = {
                    editor.cancelOpenImageForTest()
                    4004L
                },
                nativeSessionReleaser = { newRelease.incrementAndGet() },
            )
        )

        editor.openImage(Uri.parse("content://incoming/cancel"))
        awaitOpenStarted(editor)
        awaitCompletion(editor) { !editor.openImageJobActiveForTest() }

        assertEquals(oldPath, editor.uiState.value.sourcePath)
        assertFalse(editor.uiState.value.isBusy)
        assertFalse(oldPreview.isRecycled)
        assertTrue(newPreview.isRecycled)
        assertEquals(1, newRelease.get())
        assertEquals(0, oldRelease.get())
        assertEquals(before, sourceFiles())
    }

    @Test
    fun openImageCapturesSeamAtOperationCreation() = runBlocking {
        val editor = harness.createEditor().also { activeEditor = it }
        awaitInit(editor)
        val firstBitmap = bitmap(0xff00ff00.toInt())
        val secondCalls = AtomicInteger()
        val firstStarted = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        val firstHandle =
            installSeam(
                OpenImageTestSeam(
                    sourceTransactionFactory = ::successfulTransaction,
                    decode = {
                        firstStarted.complete(Unit)
                        firstRelease.await()
                        firstBitmap
                    },
                    nativeSessionFactory = { 5005L },
                )
            )

        editor.openImage(Uri.parse("content://incoming/first"))
        awaitMainUntil { firstStarted.isCompleted }
        firstHandle.close()
        installSeam(
            OpenImageTestSeam(
                sourceTransactionFactory = ::successfulTransaction,
                decode = {
                    secondCalls.incrementAndGet()
                    throw IllegalStateException("later seam consumed")
                },
            )
        )
        firstRelease.complete(Unit)

        awaitCompletion(editor) { !editor.uiState.value.isBusy }
        assertEquals(0, secondCalls.get())
        assertEquals(firstBitmap, editor.uiState.value.previewBitmap)
        assertFalse(firstBitmap.isRecycled)
    }

    private fun editorWithDocument(oldPreview: Bitmap, oldRelease: AtomicInteger): EditorViewModel {
        val editor = harness.createEditor().also { activeEditor = it }
        awaitInit(editor)
        installSeam(
            OpenImageTestSeam(
                sourceTransactionFactory = ::successfulTransaction,
                decode = { oldPreview },
                nativeSessionFactory = { 1001L },
                nativeSessionReleaser = { session ->
                    assertEquals(1001L, session)
                    oldRelease.incrementAndGet()
                },
            )
        )
        editor.openImage(Uri.parse("content://incoming/old"))
        awaitCompletion(editor) {
            !editor.uiState.value.isBusy &&
                editor.uiState.value.sourcePath != null &&
                editor.uiState.value.previewBitmap === oldPreview
        }
        awaitMainUntil { editor.canEnterEditorAction() }
        return editor
    }

    private fun installSeam(seam: OpenImageTestSeam): AutoCloseable =
        harness.ownSeam(OpenImageTestSeam.install(seam)).also { installedHandle = it }

    private fun replaceSeam(seam: OpenImageTestSeam) {
        // The harness owns closed handles too, so this only needs to close the
        // currently installed handle before installing the replacement.
        installedHandle?.close()
        installSeam(seam)
    }

    private var installedHandle: AutoCloseable? = null

    private fun successfulTransaction(
        app: android.content.Context,
        @Suppress("UNUSED_PARAMETER") uri: Uri,
    ): IncomingSourceTransaction =
        IncomingSourceTransaction(
            app,
            inputStreamProvider = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
        )

    private fun bitmap(color: Int): Bitmap =
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }

    private fun sourceFiles(): Set<String> =
        context.cacheDir
            .listFiles { file ->
                file.name.startsWith("source_") &&
                    (file.name.endsWith(".img") || file.name.endsWith(".img.staging"))
            }
            .orEmpty()
            .map { it.name }
            .toSet()

    private fun clearSourceFiles() {
        context.cacheDir.listFiles { file ->
            file.name.startsWith("source_") &&
                (file.name.endsWith(".img") || file.name.endsWith(".img.staging"))
        }.orEmpty().forEach { it.delete() }
    }

    private fun awaitInit(vm: EditorViewModel) {
        awaitMainUntil { vm.startupInitCompletion.isCompleted }
    }

    private fun awaitCompletion(vm: EditorViewModel, predicate: () -> Boolean) {
        awaitMainUntil(predicate)
    }

    private fun awaitOpenStarted(vm: EditorViewModel) {
        awaitMainUntil { vm.uiState.value.isBusy }
    }

    private fun awaitMainUntil(predicate: () -> Boolean) {
        repeat(2000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
        }
        assertTrue(predicate())
    }
}
