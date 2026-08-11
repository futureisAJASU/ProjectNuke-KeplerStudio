package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class HistoryAdmissionFeedbackProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun setUp() {
        harness = OwnedEditorViewModelHarness(context)
    }

    @After
    fun tearDown() {
        harness.close()
    }

    @Test
    fun memoryCapacityAdmissionUsesMemoryFeedback() = runBlocking {
        val backend = FeedbackStorageBackend()
        harness.ownSeam(HistoryStorageBackendTestSeam.install(backend))
        val editor = harness.createEditor()
        awaitReady(editor)

        val first = bitmap(1600, 1600, 0xff122334.toInt())
        val second = bitmap(1600, 1600, 0xff233445.toInt())
        assertTrue(editor.commitUndoSnapshot(snapshot(editor.currentDocumentGeneration(), first, HistorySnapshotStorage.MetadataOnly), true))
        awaitMainUntil { !editor.uiState.value.historyBusy && editor.undoEntryCountForTest() == 1 }
        assertTrue(editor.commitUndoSnapshot(snapshot(editor.currentDocumentGeneration(), second), true))

        awaitMainUntil { !editor.uiState.value.historyBusy && editor.uiState.value.message != null }
        assertTrue(editor.uiState.value.message.orEmpty().contains("메모리가 부족하여 되돌리기 기록을 저장하지 못했습니다"))
        assertFalse(editor.uiState.value.message.orEmpty().contains("저장소에 저장하지 못했습니다"))
    }

    @Test
    fun storagePublicationFailureUsesStorageFeedbackWithoutMemoryCopy() = runBlocking {
        val backend = FeedbackStorageBackend(mode = FeedbackStorageBackend.Mode.Failure)
        harness.ownSeam(HistoryStorageBackendTestSeam.install(backend))
        val editor = harness.createEditor()
        awaitReady(editor)
        val snapshot = snapshot(editor.currentDocumentGeneration(), bitmap(2060, 2060, 0xff334455.toInt()))

        assertTrue(editor.commitUndoSnapshot(snapshot, true))
        awaitMainUntil { backend.publishEntered.isCompleted }
        backend.release.complete(Unit)
        awaitMainUntil { !editor.uiState.value.historyBusy && editor.uiState.value.message != null }

        assertTrue(editor.uiState.value.message.orEmpty().contains("저장소에 저장하지 못했습니다"))
        assertFalse(editor.uiState.value.message.orEmpty().contains("메모리가 부족"))
        assertEquals(0, editor.undoEntryCountForTest())
    }

    @Test
    fun supersededAdmissionDoesNotOverwriteNewDocumentMessage() = runBlocking {
        val backend = FeedbackStorageBackend(mode = FeedbackStorageBackend.Mode.Failure)
        harness.ownSeam(HistoryStorageBackendTestSeam.install(backend))
        val editor = harness.createEditor()
        awaitReady(editor)
        val snapshot = snapshot(editor.currentDocumentGeneration(), bitmap(2060, 2060, 0xff445566.toInt()))
        assertTrue(editor.commitUndoSnapshot(snapshot, true))
        awaitMainUntil { backend.publishEntered.isCompleted }

        editor.historyCoordinator.replaceDocument()
        editor.updateUiState { it.copy(message = "새 문서 메시지") }
        backend.release.complete(Unit)
        awaitMainUntil { !editor.uiState.value.historyBusy }

        assertEquals("새 문서 메시지", editor.uiState.value.message)
        assertEquals(0, editor.historyCoordinator.undoEntryCountForTest())
    }

    @Test
    fun ordinaryStorageNonRetentionStillContinuesAcceptedExternalAction() = runBlocking {
        val backend = FeedbackStorageBackend(mode = FeedbackStorageBackend.Mode.Failure)
        harness.ownSeam(HistoryStorageBackendTestSeam.install(backend))
        harness.ownSeam(
            EditorRenderer.installRendererOverrideForTest {
                RenderResult.Success(
                    operation = it.operation,
                    requestedRoute = NativeRenderRoute.V1,
                    output = bitmap(8, 8, 0xff556677.toInt()),
                    actualRoute = NativeRenderRoute.V1,
                    decision = RenderRouteDecision.FollowDocument,
                    usedDebugOverride = false,
                    algorithmVersion = AlgorithmContracts.NATIVE_V1,
                    participation = RenderParticipation(),
                    durationMillis = 0L,
                    knownTransientBytes = 0L,
                )
            },
        )
        val editor = harness.createEditor()
        awaitReady(editor)
        val large = bitmap(2060, 2060, 0xff667788.toInt())
        editor.updateUiState {
            it.copy(
                previewBitmap = large,
                originalPreviewBitmap = large,
                baseContentToken = "feedback-large-base",
            )
        }
        editor.updateParams { it.copy(exposure = 0.4f) }
        awaitMainUntil { editor.adoptedParamsForTest()?.exposure == 0.4f }
        val settlement = editor.settleParameterTransactionBeforeExternalEdit()
        var continued = 0
        assertTrue(
            editor.continueAfterOwnParameterSettlement(settlement) { continued++ },
        )
        awaitMainUntil { backend.publishEntered.isCompleted }
        backend.release.complete(Unit)
        awaitMainUntil { !editor.uiState.value.historyBusy && !editor.hasOpenParameterGesture() }

        assertEquals(1, continued)
        assertTrue(editor.uiState.value.message.orEmpty().contains("저장소에 저장하지 못했습니다"))
        assertFalse(editor.uiState.value.message.orEmpty().contains("메모리가 부족"))
    }

    @Test
    fun feedbackMapperSuppressesSupersededAndClosedOutcomes() {
        val flags = HistoryFlags(false, false, false)
        assertEquals(
            HistoryAdmissionFeedback.None,
            historyAdmissionUserFeedback(HistoryAdmissionOutcome.NotRetained(HistoryAdmissionNotRetainedReason.Superseded, flags)),
        )
        assertEquals(
            HistoryAdmissionFeedback.None,
            historyAdmissionUserFeedback(HistoryAdmissionOutcome.NotRetained(HistoryAdmissionNotRetainedReason.Closed, flags)),
        )
    }

    private fun snapshot(
        generation: String,
        bitmap: Bitmap,
        storage: HistorySnapshotStorage = HistorySnapshotStorage.Exact,
    ): EditorHistorySnapshot =
        EditorHistorySnapshot(
            params = EditParams(),
            correctionEngine = CorrectionEngine.Engine1,
            noiseEngine = NoiseEngine.FastEdgeAware,
            detailEngine = DetailEngine.MaskedUnsharp,
            toneEngine = ToneEngine.HistogramAuto,
            hazeEngine = DehazeEngine.FastContrast,
            baseBitmapDirty = false,
            baseContentToken = "feedback-base",
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
            storage = storage,
            coordinatorGeneration = generation,
        )

    private fun bitmap(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }

    private fun awaitReady(editor: EditorViewModel) {
        awaitMainUntil { editor.canEnterEditorActionPure() }
    }

    private fun awaitMainUntil(condition: () -> Boolean) {
        repeat(20_000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
            if (condition()) return
            yieldToEditorBackgroundForTest()
        }
        assertTrue("condition was not reached", condition())
    }

    private class FeedbackStorageBackend(
        private val mode: Mode = Mode.Success,
    ) : HistoryStorageBackend {
        enum class Mode { Success, Failure, InsufficientStorage }

        val publishEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        private val root = File(System.getProperty("java.io.tmpdir"), "kepler-history-feedback-${System.nanoTime()}")

        override fun registerSession(sessionId: String) = Unit
        override fun unregisterSession(sessionId: String) = Unit
        override suspend fun initializeSession(sessionId: String) = Unit

        override suspend fun publish(
            entry: EditorHistoryEntry,
            snapshot: EditorHistorySnapshot,
        ): HistoryPublishResult {
            publishEntered.complete(Unit)
            release.await()
            return when (mode) {
                Mode.Success -> HistoryPublishResult.Success(
                    ColdHistoryPayload(File(root, entry.id).also(File::mkdirs), snapshot.bitmapBytes(), snapshot.bitmapBytes(), entry.documentGeneration),
                )
                Mode.Failure -> HistoryPublishResult.Failed(IllegalStateException("feedback publication failure"))
                Mode.InsufficientStorage -> HistoryPublishResult.InsufficientStorage
            }
        }

        override suspend fun load(
            entry: EditorHistoryEntry,
            expectedGeneration: String,
            register: (EditorHistorySnapshot) -> Unit,
        ): EditorHistorySnapshot? = null

        override suspend fun requiredBitmapBytes(entry: EditorHistoryEntry, expectedGeneration: String): Long? = null
        override suspend fun deleteEntries(entries: Collection<EditorHistoryEntry>) = DeletionResult(true, emptyList())
        override suspend fun delete(entry: EditorHistoryEntry) = true
        override suspend fun delete(payload: ColdHistoryPayload) = true
        override suspend fun deletePayloads(payloads: Collection<ColdHistoryPayload>) = DeletionResult(true, emptyList())
        override suspend fun deleteSession(sessionId: String) = true
    }
}
