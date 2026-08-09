package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
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
import java.util.concurrent.TimeUnit

/**
 * Production integration tests for the real `EditorViewModel.exportPreview()`
 * export transaction, driven through the production test seam so the
 * MediaStore row lifecycle is observable and deterministic. The seam injects
 * ONLY an alternate [ExportRowStore] and [SavedExportHistoryStore]; export
 * token checks, publication rules, rollback rules, and UI settlement remain
 * production behavior.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ExportPreviewProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application
    private val seamHandles = ArrayDeque<AutoCloseable>()

    @Before
    fun setUp() {
        harness = OwnedEditorViewModelHarness(context)
    }

    @After
    fun tearDown() {
        seamHandles.forEach { runCatching { it.close() } }
        seamHandles.clear()
        harness.close()
    }

    @Test
    fun successfulExportPublishesOnceAndCommitsHistory() = runBlocking {
        val rows = RecordingRows()
        val history = RecordingHistoryStore(context)
        val editor = editorWithDirtyBase()
        installSeam(rows, history)
        editor.exportPreview()

        awaitCompletion(editor) { editor.uiState.value.savedExports.size == 1 }

        assertEquals(1, rows.inserted.get())
        assertEquals(1, rows.encoded.get())
        assertEquals(1, rows.published.get())
        assertTrue(checkNotNull(rows.encodedBitmap).isRecycled)
        assertEquals(0, rows.deleted.get())
        assertEquals(1, history.commits.get())
        assertEquals(0, history.clears.get())
        assertFalse(editor.uiState.value.isBusy)
        assertEquals(1, editor.uiState.value.savedExports.size)
        val saved = editor.uiState.value.savedExports.single()
        assertEquals("PNG", saved.formatLabel)
        assertEquals("8 x 8", saved.resolutionLabel)
        assertTrue(
            editor.uiState.value.message?.startsWith(
                "이미지가 Gallery > Pictures/KeplerStudio에 저장되었고",
            ) == true,
        )
    }

    @Test
    fun encodeFailureLeavesNoPublishAndNoHistory() = runBlocking {
        val rows = RecordingRows().also { it.failEncode = true }
        val history = RecordingHistoryStore(context)
        val editor = editorWithDirtyBase()
        installSeam(rows, history)
        editor.exportPreview()

        awaitFailureCompletion(editor)

        assertEquals(1, rows.inserted.get())
        assertEquals(1, rows.deleteAttempts.get())
        assertEquals(1, rows.deleted.get())
        assertEquals(0, rows.published.get())
        assertTrue(checkNotNull(rows.encodedBitmap).isRecycled)
        assertEquals(0, history.commits.get())
        assertEquals(0, editor.uiState.value.savedExports.size)
        assertFalse(editor.uiState.value.isBusy)
        val msg = editor.uiState.value.message
        assertNotNull(msg)
        assertEquals("이미지를 내보내지 못했습니다.", msg)
    }

    @Test
    fun staleBeforePublishCleansUpAndCannotBeatNewerState() = runBlocking {
        val rows = RecordingRows().also { it.enableEncodingGate() }
        val history = RecordingHistoryStore(context)
        val editor = editorWithDirtyBase()
        installSeam(rows, history)
        editor.exportPreview()
        awaitMainUntil { rows.encodeStarted.isCompleted }

        // While the export is parked inside encode (a pending row already
        // exists), mutate editor state so the captured export identity becomes
        // stale; the export owner is NOT cancelled, so this is the genuine
        // stale-not-cancelled path.
        editor.updateUiState { it.copy(revision = it.revision + 1) }
        rows.releaseEncoding()

        awaitCompletion(editor) { !editor.uiState.value.isBusy }

        assertEquals(1, rows.inserted.get())
        assertEquals(0, rows.published.get())
        assertEquals(1, rows.deleted.get())
        assertEquals(0, history.commits.get())
        assertEquals(0, editor.uiState.value.savedExports.size)
        assertTrue(checkNotNull(rows.encodedBitmap).isRecycled)
        assertFalse(editor.uiState.value.isBusy)
    }

    @Test
    fun cancellationWhilePendingRemovesRowAndSettles() = runBlocking {
        val rows = RecordingRows().also { it.enableEncodingGate() }
        val history = RecordingHistoryStore(context)
        val editor = editorWithDirtyBase()
        installSeam(rows, history)
        editor.exportPreview()
        awaitMainUntil { rows.encodeStarted.isCompleted }

        // Cancel via the public invalidation used by document replacement; the
        // live export job is registered as the export owner so cancelling it
        // invalidates the captured identity.
        editor.invalidateExportForTest()
        rows.releaseEncoding()
        awaitCompletion(editor) { !editor.uiState.value.isBusy }

        assertEquals(1, rows.inserted.get())
        assertEquals(1, rows.deleted.get())
        assertEquals(0, rows.published.get())
        assertEquals(0, history.commits.get())
        assertFalse(editor.uiState.value.isBusy)
    }

    @Test
    fun metadataFailureAfterPublishKeepsVisibleRowAndReportsPartialSuccess() = runBlocking {
        val rows = RecordingRows()
        val history =
            RecordingHistoryStore(context).also { it.failCommit = true }
        val editor = editorWithDirtyBase()
        installSeam(rows, history)
        editor.exportPreview()

        awaitCompletion(editor) { !editor.uiState.value.isBusy }

        assertEquals(1, rows.published.get())
        assertEquals(0, rows.deleted.get())
        assertEquals(1, rows.inserted.get())
        assertEquals(1, history.commits.get())
        assertEquals(0, editor.uiState.value.savedExports.size)
        val msg = editor.uiState.value.message
        assertNotNull(msg)
        assertEquals("이미지는 갤러리에 저장되었지만 앱 내 내보낸 사진 기록을 저장하지 못했습니다.", msg)
        assertFalse(editor.uiState.value.isBusy)
    }

    @Test
    fun secondExportWhileBusyIsIgnored() = runBlocking {
        val rows = RecordingRows().also { it.enableEncodingGate() }
        val history = RecordingHistoryStore(context)
        val editor = editorWithDirtyBase()
        installSeam(rows, history)
        editor.exportPreview()
        awaitMainUntil { rows.encodeStarted.isCompleted }

        editor.exportPreview()

        assertEquals(1, rows.inserted.get())
        assertEquals(0, rows.encoded.get())
        assertEquals(0, rows.published.get())
        assertTrue(editor.uiState.value.isBusy)

        rows.releaseEncoding()
        awaitCompletion(editor) { !editor.uiState.value.isBusy }

        assertEquals(1, rows.encoded.get())
        assertEquals(1, rows.published.get())
        assertEquals(1, history.commits.get())
        assertFalse(editor.uiState.value.isBusy)
    }

    @Test
    fun exportCapturesSeamBeforeChildStarts() = runBlocking {
        val firstRows = RecordingRows().also { it.enableEncodingGate() }
        val firstHistory = RecordingHistoryStore(context)
        val secondRows = RecordingRows()
        val secondHistory = RecordingHistoryStore(context)
        val editor = editorWithDirtyBase()
        installSeam(firstRows, firstHistory)

        editor.exportPreview()
        replaceSeam(secondRows, secondHistory)
        awaitMainUntil {
            firstRows.encodeStarted.isCompleted || secondRows.encodeStarted.isCompleted
        }

        assertTrue(firstRows.encodeStarted.isCompleted)
        assertFalse(secondRows.encodeStarted.isCompleted)
        firstRows.releaseEncoding()
        awaitCompletion(editor) { !editor.uiState.value.isBusy }
        assertEquals(1, firstRows.published.get())
        assertEquals(1, firstHistory.commits.get())
        assertEquals(0, secondRows.inserted.get())
        assertEquals(0, secondHistory.commits.get())
    }

    /**
     * Installs (or replaces) the production export seam with [rows] and
     * [history]; single-installation is enforced by the seam registry.
     */
    private fun installSeam(rows: ExportRowStore, history: RecordingHistoryStore) {
        val seam = ExportTestSeam(rowStore = rows, historyStore = history.store)
        seamHandles += harness.ownSeam(ExportTestSeam.install(seam))
    }

    /** Closes the most recent seam then installs a fresh one. */
    private fun replaceSeam(rows: ExportRowStore, history: RecordingHistoryStore) {
        seamHandles.removeLast()?.close()
        installSeam(rows, history)
    }

    private fun editorWithDirtyBase(): EditorViewModel {
        val vm = harness.createEditor()
        val base = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        base.eraseColor(0xff112233.toInt())
        vm.updateUiState {
            it.copy(
                sourcePath = "/tmp/source_for_export.png",
                baseContentToken = "export-base-token",
                previewBitmap = base,
                originalPreviewBitmap = base,
                baseBitmapDirty = true,
                exportFormat = ExportFormat.Png,
                exportResolution = ExportResolution.Full,
            )
        }
        awaitInit(vm)
        awaitReady(vm)
        return vm
    }

    private fun awaitInit(vm: EditorViewModel) {
        repeat(2000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (vm.startupInitCompletion.isCompleted) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
        }
        assertTrue("startup init must complete", vm.startupInitCompletion.isCompleted)
    }

    private fun awaitReady(vm: EditorViewModel) {
        repeat(2000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (vm.canEnterEditorAction()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
        }
        assertTrue(vm.canEnterEditorAction())
    }

    private fun awaitCompletion(vm: EditorViewModel, predicate: () -> Boolean) {
        repeat(2000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
        }
        assertTrue(predicate())
    }

    /** Pumps the Main looper (and gives background a turn) until [predicate]
     *  holds, used for parked deterministic gates that are completed once the
     *  production coroutine has reached a known phase. */
    private fun awaitMainUntil(predicate: () -> Boolean) {
        repeat(2000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
        }
        assertTrue(predicate())
    }

    private fun awaitFailureCompletion(vm: EditorViewModel) =
        awaitCompletion(vm) { !vm.uiState.value.isBusy }
}

/**
 * Counted MediaStore-row fake with deterministic encode gating. The first
 * encode can be parked via [enableEncodingGate] and released via
 * [releaseEncoding] so a test can drive an export to a deterministic phase
 * without sleeps.
 */
private class RecordingRows : ExportRowStore {
    val inserted = AtomicInteger()
    val encoded = AtomicInteger()
    val published = AtomicInteger()
    val deleted = AtomicInteger()
    val deleteAttempts = AtomicInteger()
    val encodeStarted = CompletableDeferred<Unit>()
    val insertStarted = CompletableDeferred<Unit>()
    private var gate: CompletableDeferred<Unit>? = null
    @Volatile var failEncode = false
    @Volatile var failPublish = false
    @Volatile var failDelete = false
    var encodedBitmap: Bitmap? = null
    var lastPublishedUri: Uri? = null

    fun enableEncodingGate() {
        gate = CompletableDeferred()
    }

    fun releaseEncoding() {
        gate?.complete(Unit)
    }

    override suspend fun insertPending(request: ExportRowRequest): Uri {
        inserted.incrementAndGet()
        insertStarted.complete(Unit)
        return Uri.parse("content://exports/${request.fileName}")
    }

    override suspend fun encode(uri: Uri, bitmap: Bitmap, format: ExportFormat) {
        encodeStarted.complete(Unit)
        gate?.await()
        encodedBitmap = bitmap
        if (failEncode) error("encode")
        encoded.incrementAndGet()
    }

    override suspend fun publish(uri: Uri) {
        if (failPublish) error("publish")
        lastPublishedUri = uri
        published.incrementAndGet()
    }

    override suspend fun delete(uri: Uri) {
        deleteAttempts.incrementAndGet()
        if (failDelete) error("delete")
        deleted.incrementAndGet()
    }
}

/** Wraps the real store and exposes only persistence observations to tests. */
private class RecordingHistoryStore(
    context: android.content.Context,
) {
    val persistence = RecordingHistoryPersistence()
    val store = SavedExportHistoryStore(context, persistence = persistence)
    val commits: AtomicInteger
        get() = persistence.historyWrites
    val clears: AtomicInteger
        get() = persistence.historyClears
    var failCommit: Boolean
        get() = persistence.failWrites
        set(value) {
            persistence.failWrites = value
        }
}

private class RecordingHistoryPersistence : SavedExportHistoryPersistence {
    private var raw: String? = ""
    private var initialized = true
    val historyWrites = AtomicInteger()
    val historyClears = AtomicInteger()
    @Volatile var failWrites = false

    override fun readSavedHistoryRaw(): String? = raw

    override fun readSavedHistoryInitialized(): Boolean = initialized

    override fun writeSavedHistory(raw: String) {
        historyWrites.incrementAndGet()
        if (failWrites) error("history write")
        this.raw = raw
        initialized = true
    }

    override fun clearSavedHistory() {
        historyClears.incrementAndGet()
        this.raw = ""
        initialized = true
    }

    override fun readRetentionName(): String? = ExportHistoryRetention.Never.name

    override fun writeRetentionName(name: String) = Unit
}
