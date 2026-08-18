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
    fun exportRetryBecomesStaleWhenSettingsChangeWithoutSecondExport() = runBlocking {
        val rows = RecordingRows()
        val history = RecordingHistoryStore(context)
        val editor = editorWithDirtyBase()
        installSeam(rows, history)
        val recoverySeam = MemoryRecoveryTestSeam(rejectExportPreparation = true)
        val recoveryHandle = harness.ownSeam(MemoryRecoveryTestSeam.install(recoverySeam))

        try {
            editor.exportPreview()
            awaitCompletion(editor) { recoverySeam.automaticReached.isCompleted }
            recoverySeam.automaticRelease.complete(Unit)
            awaitCompletion(editor) { editor.uiState.value.memoryRecoveryRequest != null }
            val token = checkNotNull(editor.uiState.value.memoryRecoveryRequest?.token)
            editor.setExportFormat(ExportFormat.Jpeg)
            editor.setExportResolution(ExportResolution.Percent50)
            editor.retryPendingMemoryRecovery(token)
            awaitCompletion(editor) { editor.uiState.value.memoryRecoveryRequest == null }
        } finally {
            recoveryHandle.close()
        }

        assertEquals(0, rows.inserted.get())
        assertEquals(0, rows.published.get())
        assertEquals(0, rows.deleted.get())
        assertEquals(0, history.commits.get())
        assertFalse(editor.uiState.value.isBusy)
    }

    @Test
    fun asyncExportRecoveryOwnsInvocationSettingsNotLaterUiSettings() = runBlocking {
        val rows = RecordingRows().also {
            it.enableEncodingGate()
            it.failEncodeWithMemory = 4096L
        }
        val history = RecordingHistoryStore(context)
        val editor = editorWithDirtyBase()
        installSeam(rows, history)
        val recoverySeam = MemoryRecoveryTestSeam()
        val recoveryHandle = harness.ownSeam(MemoryRecoveryTestSeam.install(recoverySeam))

        try {
            editor.exportPreview()
            awaitMainUntil { rows.encodeStarted.isCompleted }

            editor.setExportFormat(ExportFormat.Jpeg)
            editor.setExportResolution(ExportResolution.Percent50)
            rows.releaseEncoding()

            awaitMainUntil { recoverySeam.recoveryRequested.isCompleted }
            val descriptor = recoverySeam.recoveryRequested.getCompleted()
            assertEquals(
                MemoryRetryInput.Export(ExportFormat.Png, ExportResolution.Full),
                descriptor.input,
            )
            awaitCompletion(editor) { editor.memoryRecoveryOwnerPhaseForTest() == null }
            assertFalse(recoverySeam.automaticReached.isCompleted)
        } finally {
            recoveryHandle.close()
        }

        assertEquals(1, rows.inserted.get())
        assertEquals(1, rows.deleted.get())
        assertEquals(0, rows.published.get())
        assertEquals(0, history.commits.get())
        assertEquals(ExportFormat.Png, rows.lastRequestFormat)
        assertFalse(editor.uiState.value.isBusy)
        assertTrue(editor.uiState.value.message != "이미지가 Gallery > Pictures/KeplerStudio에 저장되었고, 앱 내 내보낸 사진 기록에도 추가되었습니다.")
    }

    @Test
    fun exportSettlesBusyAfterSuccessfulLeaveWithoutOverwritingLeaveState() = runBlocking {
        val rows = RecordingRows().also { it.enableEncodingGate() }
        val history = RecordingHistoryStore(context)
        val editor = editorWithDirtyBase(baseBitmapDirty = false)
        installSeam(rows, history)
        val draftSeam = DraftSaveTestSeam()
        harness.ownSeam(DraftSaveTestSeam.install(editor, draftSeam))
        editor.exportPreview()
        awaitMainUntil { rows.encodeStarted.isCompleted && editor.uiState.value.isBusy }

        val leaveToken = editor.requestSaveAndLeave()
        awaitMainUntil { draftSeam.reached.isCompleted }
        draftSeam.releaseGate.complete(Unit)
        awaitCompletion(editor) {
            editor.editorLeaveState.value.phase == EditorLeavePhase.Completed ||
                editor.editorLeaveState.value.phase == EditorLeavePhase.Failed
        }
        assertEquals(EditorLeavePhase.Completed, editor.editorLeaveState.value.phase)
        assertTrue(editor.uiState.value.isBusy)

        rows.releaseEncoding()
        awaitCompletion(editor) { !editor.exportJobActiveForTest() }

        assertFalse(editor.uiState.value.isBusy)
        assertEquals(EditorLeavePhase.Completed, editor.editorLeaveState.value.phase)
        assertEquals(1, rows.published.get())
        assertEquals(1, editor.uiState.value.savedExports.size)
        editor.acknowledgeEditorLeave(leaveToken)
        assertEquals(EditorLeavePhase.Idle, editor.editorLeaveState.value.phase)
        assertTrue(editor.canEnterEditorActionPure())
    }

    @Test
    fun exportSettlesBusyAfterFailedLeaveAndDoesNotReplaceFailure() = runBlocking {
        val rows = RecordingRows().also { it.enableEncodingGate() }
        val history = RecordingHistoryStore(context)
        val editor = editorWithDirtyBase(baseBitmapDirty = false)
        installSeam(rows, history)
        val draftSeam = DraftSaveTestSeam(failure = IllegalStateException("final draft failure"))
        val draftHandle = harness.ownSeam(DraftSaveTestSeam.install(editor, draftSeam))

        editor.exportPreview()
        awaitMainUntil { rows.encodeStarted.isCompleted && editor.uiState.value.isBusy }

        val leaveToken = editor.requestSaveAndLeave()
        awaitMainUntil { draftSeam.reached.isCompleted }
        draftSeam.releaseGate.complete(Unit)
        awaitMainUntil {
            editor.editorLeaveState.value.phase == EditorLeavePhase.Failed
        }
        val leaveMessage = editor.editorLeaveState.value.message
        assertTrue(editor.uiState.value.isBusy)

        rows.releaseEncoding()
        awaitCompletion(editor) { !editor.exportJobActiveForTest() }

        assertFalse(editor.uiState.value.isBusy)
        assertEquals(EditorLeavePhase.Failed, editor.editorLeaveState.value.phase)
        assertEquals(leaveMessage, editor.editorLeaveState.value.message)
        assertFalse(editor.uiState.value.message?.contains("Gallery") == true)

        editor.acknowledgeEditorLeave(leaveToken)
        assertEquals(EditorLeavePhase.Idle, editor.editorLeaveState.value.phase)
        assertTrue(editor.canEnterEditorActionPure())
        draftHandle.close()
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

    @Test
    fun retentionPersistenceFailureSettlesAsFormalUserMessage() = runBlocking {
        val editor = editorWithDirtyBase()
        val persistence = RecordingHistoryPersistence().also { it.failWrites = true }
        val history = SavedExportHistoryStore(context, persistence = persistence)
        installHistoryStore(history)

        editor.setExportHistoryRetention(ExportHistoryRetention.Days7)

        awaitCompletion(editor) {
            editor.uiState.value.message == "내보낸 사진 기록 보관 정책을 저장하지 못했습니다."
        }

        assertEquals(0L, history.revision)
        assertEquals(ExportHistoryRetention.Never, persistence.state.retention)
        assertNotNull(editor.lastSavedExportHistoryFailureForTest)
    }

    @Test
    fun startupHistoryFailureDoesNotPreventEditorInitialization() = runBlocking {
        val persistence = RecordingHistoryPersistence().also { it.failWrites = true }
        val history = SavedExportHistoryStore(context, persistence = persistence)
        installHistoryStore(history)
        val editor = harness.createEditor()

        awaitMainUntil { editor.startupInitCompletion.isCompleted }

        assertEquals("내보낸 사진 기록을 불러오지 못했습니다.", editor.uiState.value.message)
        assertNotNull(editor.lastSavedExportHistoryFailureForTest)
    }

    /**
     * Installs (or replaces) the production export seam with [rows] and
     * [history]; single-installation is enforced by the seam registry.
     */
    private fun installSeam(rows: ExportRowStore, history: RecordingHistoryStore) {
        val seam = ExportTestSeam(rowStore = rows, historyStore = history.store)
        seamHandles += harness.ownSeam(ExportTestSeam.install(seam))
    }

    private fun installHistoryStore(history: SavedExportHistoryStore) {
        seamHandles += harness.ownSeam(ExportTestSeam.install(ExportTestSeam(historyStore = history)))
    }

    /** Closes the most recent seam then installs a fresh one. */
    private fun replaceSeam(rows: ExportRowStore, history: RecordingHistoryStore) {
        seamHandles.removeLast()?.close()
        installSeam(rows, history)
    }

    private fun editorWithDirtyBase(baseBitmapDirty: Boolean = true): EditorViewModel {
        val vm = harness.createEditor()
        awaitInit(vm)
        val base = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        base.eraseColor(0xff112233.toInt())
        val source = context.filesDir.resolve("export-source.png")
        source.outputStream().use { check(base.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        vm.updateUiState {
            it.copy(
                sourcePath = source.absolutePath,
                baseContentToken = "export-base-token",
                previewBitmap = base,
                originalPreviewBitmap = base,
                baseBitmapDirty = baseBitmapDirty,
                exportFormat = ExportFormat.Png,
                exportResolution = ExportResolution.Full,
            )
        }
        awaitReady(vm)
        return vm
    }

    private fun awaitInit(vm: EditorViewModel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (vm.startupInitCompletion.isCompleted) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
            Thread.sleep(5L)
        }
        assertTrue("startup init must complete", vm.startupInitCompletion.isCompleted)
    }

    private fun awaitReady(vm: EditorViewModel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (vm.canEnterEditorAction()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
            Thread.sleep(5L)
        }
        assertTrue(vm.canEnterEditorAction())
    }

    private fun awaitCompletion(vm: EditorViewModel, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
            if (predicate()) return
            Thread.sleep(5L)
        }
        assertTrue(
            "state=${vm.editorLeaveState.value} ui=${vm.uiState.value} ownership=${vm.debugResidentOwnership()}",
            predicate(),
        )
    }

    /** Pumps the Main looper (and gives background a turn) until [predicate]
     *  holds, used for parked deterministic gates that are completed once the
     *  production coroutine has reached a known phase. */
    private fun awaitMainUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
            if (predicate()) return
            Thread.sleep(5L)
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
    @Volatile var failEncodeWithMemory: Long? = null
    @Volatile var failPublish = false
    @Volatile var failDelete = false
    var encodedBitmap: Bitmap? = null
    @Volatile var lastRequestFormat: ExportFormat? = null
    var lastPublishedUri: Uri? = null

    fun enableEncodingGate() {
        gate = CompletableDeferred()
    }

    fun releaseEncoding() {
        gate?.complete(Unit)
    }

    override suspend fun insertPending(request: ExportRowRequest): Uri {
        inserted.incrementAndGet()
        lastRequestFormat = request.format
        insertStarted.complete(Unit)
        return Uri.parse("content://exports/${request.fileName}")
    }

    override suspend fun encode(uri: Uri, bitmap: Bitmap, format: ExportFormat) {
        encodeStarted.complete(Unit)
        gate?.await()
        encodedBitmap = bitmap
        failEncodeWithMemory?.let { throw BitmapAllocationRejectedException(it) }
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
    var failCommit: Boolean
        get() = persistence.failWrites
        set(value) {
            persistence.failWrites = value
        }
}

private class RecordingHistoryPersistence : SavedExportHistoryPersistence {
    var state =
        SavedExportPersistedState(
            rawHistory = "",
            initialized = true,
            retention = ExportHistoryRetention.Never,
        )
    val historyWrites = AtomicInteger()
    @Volatile var failWrites = false

    override suspend fun readState(): SavedExportPersistedState = state

    override suspend fun updateState(
        transform: suspend (SavedExportPersistedState) -> SavedExportPersistedState,
    ): SavedExportPersistedState {
        historyWrites.incrementAndGet()
        val next = transform(state)
        if (failWrites) error("history write")
        state = next
        return next
    }
}
