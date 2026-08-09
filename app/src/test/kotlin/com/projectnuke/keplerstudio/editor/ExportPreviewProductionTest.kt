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
import org.junit.Assert.assertNull
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
    fun exportDiag() = runBlocking {
        // Reserved hook for ad-hoc inspection during further pipeline work;
        // performs the real exportPreview() once and asserts success.
        val rows = RecordingRows()
        val history = RecordingHistoryStore(context)
        val editor = editorWithDirtyBase()
        installSeam(rows, history)
        editor.exportPreview()
        awaitCompletion(editor) { editor.uiState.value.savedExports.size == 1 }
        assertEquals(1, rows.published.get())
        assertEquals(1, editor.uiState.value.savedExports.size)
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
        assertEquals(0, history.commits.get())
        assertEquals(0, editor.uiState.value.savedExports.size)
        assertFalse(editor.uiState.value.isBusy)
        val msg = editor.uiState.value.message
        assertNotNull(msg)
        assertTrue("$msg", msg!!.startsWith("내보내기에 실패했습니다"))
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
    fun newerExportPreventsStaleOldCompletionFromSettling() = runBlocking {
        val oldRows = RecordingRows().also { it.enableEncodingGate() }
        val oldHistory = RecordingHistoryStore(context)
        val editor = editorWithDirtyBase()
        installSeam(oldRows, oldHistory)
        editor.exportPreview()
        awaitMainUntil { oldRows.encodeStarted.isCompleted }

        val newerRows = RecordingRows()
        val newerHistory = RecordingHistoryStore(context)
        replaceSeam(newerRows, newerHistory)

        // Launching the newer export bumps the export token (and cancels the
        // old export job) via invalidateExport(), so the old identity is no
        // longer current and its pending row must be cleaned up.
        editor.exportPreview()
        awaitCompletion(editor) { editor.uiState.value.savedExports.size == 1 }

        assertEquals(1, newerRows.published.get())
        assertEquals(1, newerHistory.commits.get())
        assertEquals(1, editor.uiState.value.savedExports.size)

        // Release the old completion; it became stale before its commit.
        oldRows.releaseEncoding()
        awaitCompletion(editor) { true }

        assertEquals(1, oldRows.inserted.get())
        assertEquals(0, oldRows.published.get())
        assertEquals(1, oldRows.deleted.get())
        assertEquals(0, oldHistory.commits.get())
        assertEquals(1, editor.uiState.value.savedExports.size)
        assertFalse(editor.uiState.value.isBusy)
        assertNull(oldRows.lastPublishedUri)
    }

    /**
     * Installs (or replaces) the production export seam with [rows] and
     * [history]; single-installation is enforced by the seam registry.
     */
    private fun installSeam(rows: ExportRowStore, history: SavedExportHistoryStore) {
        val seam = ExportTestSeam(rowStore = rows, historyStore = history)
        seamHandles += harness.ownSeam(ExportTestSeam.install(seam))
    }

    /** Closes the most recent seam then installs a fresh one. */
    private fun replaceSeam(rows: ExportRowStore, history: SavedExportHistoryStore) {
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

/**
 * Counts commits and clears and can be made to fail the next commit, while
 * delegating persistence to a real [SavedExportHistoryStore] backed by the
 * test application prefs. This lets production tests assert history
 * persistence without reimplementing the merge algorithm themselves.
 */
private class RecordingHistoryStore(
    context: android.content.Context,
) : SavedExportHistoryStore(context) {
    private val deps = SavedExportHistoryStore(context.applicationContext)
    val commits = AtomicInteger()
    val clears = AtomicInteger()
    @Volatile var failCommit = false

    override fun commit(
        item: SavedExport,
        retention: ExportHistoryRetention,
    ): SavedExportHistoryMutation {
        commits.incrementAndGet()
        if (failCommit) throw RuntimeException("history write")
        return deps.commit(item, retention)
    }

    override fun clear(): SavedExportHistoryMutation {
        clears.incrementAndGet()
        return deps.clear()
    }

    override fun remove(uriString: String): SavedExportHistoryMutation = deps.remove(uriString)

    override fun load(): List<SavedExport> = deps.load()

    override fun loadOrRebuild(retention: ExportHistoryRetention): List<SavedExport> =
        deps.loadOrRebuild(retention)

    override fun loadOrRebuildWithMutation(
        retention: ExportHistoryRetention,
    ): SavedExportHistoryMutation = deps.loadOrRebuildWithMutation(retention)

    override fun saveRetention(retention: ExportHistoryRetention) {
        deps.saveRetention(retention)
    }

    override fun loadRetention(): ExportHistoryRetention = deps.loadRetention()

    override fun rebuildFromMediaStore(): List<SavedExport> = deps.rebuildFromMediaStore()

    override fun prune(retention: ExportHistoryRetention): SavedExportHistoryMutation =
        deps.prune(retention)

    override val revision: Long
        get() = deps.revision
}
