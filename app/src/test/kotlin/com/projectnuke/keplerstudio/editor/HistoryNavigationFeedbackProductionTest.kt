package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HistoryNavigationFeedbackProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun setUp() {
        harness = OwnedEditorViewModelHarness( context)
    }

    @After
    fun tearDown() {
        harness.close()
    }

    @Test
    fun realUndoCurrentStateStorageFailureDoesNotRequestMemoryRecovery() = runBlocking {
        val backend = NavigationStorageBackend()
        harness.ownSeam(HistoryStorageBackendTestSeam.install(backend))
        val editor = harness.createEditor()
        awaitReady(editor)
        val current = bitmap(2060, 2060, 0xff122334.toInt())
        val target = bitmap(1, 1, 0xff233445.toInt())
        editor.updateUiState {
            it.copy(
                previewBitmap = current,
                originalPreviewBitmap = current,
                baseContentToken = "navigation-feedback-base",
            )
        }
        assertTrue(editor.commitUndoSnapshot(snapshot(editor, target), true))
        awaitMainUntil({ debugDump(editor) }) { !editor.uiState.value.historyBusy && editor.undoEntryCountForTest() == 1 }

        backend.mode = NavigationStorageBackend.Mode.Failure
        editor.undoEdit()
        awaitMainUntil({ debugDump(editor) }) {
            !editor.uiState.value.historyBusy &&
                editor.uiState.value.message.orEmpty().contains("현재 편집 상태를 기록에 저장하지 못해")
        }

        assertEquals(1, editor.undoEntryCountForTest())
        assertEquals(0, editor.redoEntryCountForTest())
        assertNull(editor.uiState.value.memoryRecoveryRequest)
        assertFalse(editor.uiState.value.message.orEmpty().contains("메모리가 부족"))
        assertEquals(2060, checkNotNull(editor.uiState.value.previewBitmap).width)
    }

    @Test
    fun realColdUndoThenRedoRestoresThePreviousCurrentDocument() = runBlocking {
        val backend = NavigationStorageBackend()
        harness.ownSeam(HistoryStorageBackendTestSeam.install(backend))
        val editor = harness.createEditor()
        awaitReady(editor)
        val current = bitmap(2060, 2060, 0xff243546.toInt())
        val target = bitmap(1, 1, 0xff354657.toInt())
        editor.updateUiState {
            it.copy(
                previewBitmap = current,
                originalPreviewBitmap = current,
                baseContentToken = "navigation-cold-base",
            )
        }
        assertTrue(editor.commitUndoSnapshot(snapshot(editor, target), true))
        awaitMainUntil { !editor.uiState.value.historyBusy && editor.undoEntryCountForTest() == 1 }

        editor.undoEdit()
        awaitMainUntil {
            !editor.uiState.value.historyBusy &&
                editor.redoEntryCountForTest() == 1 &&
                editor.uiState.value.previewBitmap?.width == 1
        }
        editor.redoEdit()
        awaitMainUntil {
            !editor.uiState.value.historyBusy &&
                editor.undoEntryCountForTest() == 1 &&
                editor.redoEntryCountForTest() == 0 &&
                editor.uiState.value.previewBitmap?.width == 2060
        }

        assertNull(editor.uiState.value.memoryRecoveryRequest)
    }

    @Test
    fun supersededUndoCannotOverwriteANewerDocumentMessage() = runBlocking {
        val backend = NavigationStorageBackend()
        harness.ownSeam(HistoryStorageBackendTestSeam.install(backend))
        val editor = harness.createEditor()
        awaitReady(editor)
        val current = bitmap(1, 1, 0xff465768.toInt())
        val target = bitmap(1, 1, 0xff576879.toInt())
        editor.updateUiState {
            it.copy(
                previewBitmap = current,
                originalPreviewBitmap = current,
                baseContentToken = "navigation-stale-base",
            )
        }
        assertTrue(editor.commitUndoSnapshot(snapshot(editor, target), true))
        awaitMainUntil { !editor.uiState.value.historyBusy && editor.undoEntryCountForTest() == 1 }

        val navigation = HistoryNavigationTestSeam()
        harness.ownSeam(HistoryNavigationTestSeam.install(navigation))
        editor.undoEdit()
        awaitMainUntil { navigation.reached.isCompleted && editor.uiState.value.historyBusy }
        editor.historyCoordinator.replaceDocument()
        editor.updateUiState { it.copy(message = "새 문서 메시지") }
        navigation.releaseGate.complete(Unit)
        awaitMainUntil { !editor.uiState.value.historyBusy }

        assertEquals("새 문서 메시지", editor.uiState.value.message)
    }

    @Test
    fun realColdRedoSpillFailureUsesStorageFeedbackAndKeepsDocument() = runBlocking {
        val backend = NavigationStorageBackend()
        harness.ownSeam(HistoryStorageBackendTestSeam.install(backend))
        val editor = harness.createEditor()
        awaitReady(editor)
        val current = bitmap(2060, 2060, 0xff344556.toInt())
        val target = bitmap(2040, 2040, 0xff455667.toInt())
        editor.updateUiState {
            it.copy(
                previewBitmap = current,
                originalPreviewBitmap = current,
                baseContentToken = "navigation-spill-base",
            )
        }
        assertTrue(editor.commitUndoSnapshot(snapshot(editor, target), true))
        awaitMainUntil({ debugDump(editor) }) { !editor.uiState.value.historyBusy && editor.undoEntryCountForTest() == 1 }

        editor.undoEdit()
        awaitMainUntil {
            !editor.uiState.value.historyBusy &&
                editor.redoEntryCountForTest() == 1 &&
                editor.uiState.value.previewBitmap?.width == 2040
        }

        val smallCurrent = bitmap(1, 1, 0xff566778.toInt())
        editor.updateUiState {
            it.copy(previewBitmap = smallCurrent, originalPreviewBitmap = smallCurrent)
        }
        val smallUndo = bitmap(1, 1, 0xff677889.toInt())
        assertTrue(editor.commitUndoSnapshot(snapshot(editor, smallUndo), false))
        awaitMainUntil({ debugDump(editor) }) { !editor.uiState.value.historyBusy && editor.undoEntryCountForTest() == 1 }

        val spillable = bitmap(2040, 2040, 0xff566778.toInt())
        assertTrue(editor.commitUndoSnapshot(snapshot(editor, spillable), false))
        awaitMainUntil({ debugDump(editor) }) { !editor.uiState.value.historyBusy && editor.undoEntryCountForTest() == 2 }

        val largeCurrent = bitmap(2040, 2040, 0xff78899a.toInt())
        editor.updateUiState {
            it.copy(previewBitmap = largeCurrent, originalPreviewBitmap = largeCurrent)
        }

        backend.mode = NavigationStorageBackend.Mode.Failure
        editor.redoEdit()
        awaitMainUntil({ debugDump(editor) }) {
            !editor.uiState.value.historyBusy &&
                editor.uiState.value.message.orEmpty().contains("되돌리기 기록을 저장하지 못해")
        }

        assertEquals(2, editor.undoEntryCountForTest())
        assertEquals(1, editor.redoEntryCountForTest())
        assertNull(editor.uiState.value.memoryRecoveryRequest)
        assertFalse(editor.uiState.value.message.orEmpty().contains("메모리가 부족"))
        assertEquals(2040, checkNotNull(editor.uiState.value.previewBitmap).width)
    }

    private fun snapshot(editor: EditorViewModel, bitmap: Bitmap): EditorHistorySnapshot =
        EditorHistorySnapshot(
            params = EditParams(),
            correctionEngine = CorrectionEngine.Engine1,
            noiseEngine = NoiseEngine.FastEdgeAware,
            detailEngine = DetailEngine.MaskedUnsharp,
            toneEngine = ToneEngine.HistogramAuto,
            hazeEngine = DehazeEngine.FastContrast,
            baseBitmapDirty = false,
            baseContentToken = editor.uiState.value.baseContentToken,
            previewBitmap = bitmap,
            originalPreviewBitmap = null,
            presetLook = null,
            cropState = CropState(),
            selectionLayers = emptyList(),
            activeSelectionLayerId = null,
            selectionPaintSettings = SelectionPaintSettings(),
            showSelectionOverlay = false,
            activeQuickEffects = emptyList(),
            flareGuardRuntimeStatus = null,
            storage = HistorySnapshotStorage.Exact,
            coordinatorGeneration = editor.currentDocumentGeneration(),
        )

    private fun bitmap(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }

    private fun awaitReady(editor: EditorViewModel) {
        awaitMainUntil { editor.canEnterEditorActionPure() }
    }

    private fun awaitMainUntil(condition: () -> Boolean) {
        awaitMainUntil({ "" }, condition)
    }

    private fun awaitMainUntil(dump: () -> String, condition: () -> Boolean) {
        repeat(20_000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
            if (condition()) return
            yieldToEditorBackgroundForTest()
        }
        val finalDump = dump()
        System.err.println("HISTORY-NAV-TIMEOUT $finalDump")
        assertTrue("condition was not reached: $finalDump", condition())
    }

    private fun debugDump(editor: EditorViewModel): String {
        val state = editor.uiState.value
        return "busy=${state.isBusy} historyBusy=${state.historyBusy} undo=${editor.undoEntryCountForTest()} " +
            "redo=${editor.redoEntryCountForTest()} budget=${BitmapMemoryBudget.historyBudgetBytes()} " +
            "message=${state.message} recovery=${state.memoryRecoveryRequest}"
    }

    private class NavigationStorageBackend : HistoryStorageBackend {
        enum class Mode { Success, Failure, InsufficientStorage }

        private data class Record(
            val generation: String,
            val width: Int,
            val height: Int,
            val color: Int,
            val bytes: Long,
        )

        private val root = File(System.getProperty("java.io.tmpdir"), "kepler-history-navigation-${System.nanoTime()}")
        private val records = LinkedHashMap<String, Record>()
        var mode = Mode.Success

        override fun registerSession(sessionId: String) = Unit
        override fun unregisterSession(sessionId: String) = Unit
        override suspend fun initializeSession(sessionId: String) {
            root.mkdirs()
        }

        override suspend fun publish(
            entry: EditorHistoryEntry,
            snapshot: EditorHistorySnapshot,
        ): HistoryPublishResult {
            when (mode) {
                Mode.Failure -> return HistoryPublishResult.Failed(IllegalStateException("navigation publication failure"))
                Mode.InsufficientStorage -> return HistoryPublishResult.InsufficientStorage
                Mode.Success -> Unit
            }
            val bitmap = checkNotNull(snapshot.previewBitmap)
            records[entry.id] =
                Record(entry.documentGeneration, bitmap.width, bitmap.height, bitmap.getPixel(0, 0), snapshot.bitmapBytes())
            return HistoryPublishResult.Success(
                ColdHistoryPayload(File(root, entry.id).also(File::mkdirs), snapshot.bitmapBytes(), snapshot.bitmapBytes(), entry.documentGeneration),
            )
        }

        override suspend fun load(
            entry: EditorHistoryEntry,
            expectedGeneration: String,
            register: (EditorHistorySnapshot) -> Unit,
        ): EditorHistorySnapshot? {
            val record = records[entry.id]?.takeIf { it.generation == expectedGeneration } ?: return null
            val decoded = Bitmap.createBitmap(record.width, record.height, Bitmap.Config.ARGB_8888)
                .also { it.eraseColor(record.color) }
            return EditorHistorySnapshot(
                params = EditParams(),
                correctionEngine = CorrectionEngine.Engine1,
                noiseEngine = NoiseEngine.FastEdgeAware,
                detailEngine = DetailEngine.MaskedUnsharp,
                toneEngine = ToneEngine.HistogramAuto,
                hazeEngine = DehazeEngine.FastContrast,
                baseBitmapDirty = false,
                baseContentToken = "navigation-spill-base",
                previewBitmap = decoded,
                originalPreviewBitmap = null,
                presetLook = null,
                cropState = CropState(),
                selectionLayers = emptyList(),
                activeSelectionLayerId = null,
                selectionPaintSettings = SelectionPaintSettings(),
                showSelectionOverlay = false,
                activeQuickEffects = emptyList(),
                flareGuardRuntimeStatus = null,
                storage = HistorySnapshotStorage.Exact,
                coordinatorGeneration = expectedGeneration,
            ).also(register)
        }

        override suspend fun requiredBitmapBytes(
            entry: EditorHistoryEntry,
            expectedGeneration: String,
        ): Long? = records[entry.id]?.takeIf { it.generation == expectedGeneration }?.bytes

        override suspend fun deleteEntries(entries: Collection<EditorHistoryEntry>): DeletionResult {
            entries.forEach { entry -> entry.coldPayload?.let { delete(it) } }
            return DeletionResult(true, emptyList())
        }

        override suspend fun delete(entry: EditorHistoryEntry): Boolean = entry.coldPayload?.let { delete(it) } ?: true

        override suspend fun delete(payload: ColdHistoryPayload): Boolean {
            records.entries.removeIf { it.value.generation == payload.generation && it.key == payload.directory.name }
            return true
        }

        override suspend fun deletePayloads(payloads: Collection<ColdHistoryPayload>): DeletionResult {
            payloads.forEach { delete(it) }
            return DeletionResult(true, emptyList())
        }

        override suspend fun deleteSession(sessionId: String): Boolean {
            root.deleteRecursively()
            return true
        }
    }
}
