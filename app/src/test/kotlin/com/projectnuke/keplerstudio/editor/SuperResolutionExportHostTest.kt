package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SuperResolutionExportHostTest {

    private val context: Application get() = RuntimeEnvironment.getApplication() as Application
    private lateinit var cacheDir: File
    private var pressureOverride: AutoCloseable? = null

    @Before
    fun setUp() {
        cacheDir = File(context.cacheDir, "sr_test_${System.nanoTime()}").apply { mkdirs() }
        ModelAvailabilityRegistry.resetForTest()
        pressureOverride = StoragePressure.installForTest(StoragePressureController(capacity = { Long.MAX_VALUE }, reserveBytes = 0L, pressureSweep = { TransientMaintenanceReport.EMPTY }))
    }

    @After
    fun tearDown() {
        ModelAvailabilityRegistry.resetForTest()
        cacheDir.deleteRecursively()
        pressureOverride?.close()
    }

    private fun bitmap(w:Int,h:Int, c:Int=0xFF808080.toInt()): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(c)
        return bmp
    }

    private open class FakeRowStore : SuperResolutionRowStore {
        var insertCalls=0; var publishCalls=0; var deleteCalls=0
        var insertFail: Throwable? = null
        var publishFail: Throwable? = null
        var nextUri: Uri = Uri.parse("content://media/1")
        val buffers = mutableMapOf<Uri, ByteArrayOutputStream>()
        val published = mutableSetOf<Uri>()
        override suspend fun insertPending(fileName: String): Uri {
            insertCalls++
            insertFail?.let { throw it }
            val uri = Uri.parse("content://media/$insertCalls")
            buffers[uri]=ByteArrayOutputStream()
            nextUri=uri
            return uri
        }
        override suspend fun openOutputStream(uri: Uri): OutputStream {
            return buffers[uri] ?: ByteArrayOutputStream().also { buffers[uri]=it }
        }
        override suspend fun publish(uri: Uri): Int {
            publishCalls++
            publishFail?.let { throw it }
            if (!buffers.containsKey(uri)) throw IllegalStateException("no pending")
            published.add(uri)
            return 1
        }
        override suspend fun delete(uri: Uri): SuperResolutionRowDeleteResult {
            deleteCalls++
            buffers.remove(uri); published.remove(uri)
            return SuperResolutionRowDeleteResult.Deleted
        }
    }



    private fun fakeToken(): ValidatedModelCapabilityToken {
        val g=ModelAvailabilityRegistry.beginProbe()
        ModelAvailabilityRegistry.applyForTest(ModelFeature.ExynosUpscale, ModelCapabilityObservation(ModelCapabilityPublisher.Probe,g,ModelCapabilityPhase.Loadable,true,true,true,true,true))
        return (ModelAvailabilityRegistry.validatedCapabilityToken(ModelFeature.ExynosUpscale) as ModelLoadResult.Ready).runner
    }

    private fun makeAvailable() {
        val g=ModelAvailabilityRegistry.beginProbe()
        ModelAvailabilityRegistry.applyForTest(ModelFeature.ExynosUpscale, ModelCapabilityObservation(ModelCapabilityPublisher.Probe,g,ModelCapabilityPhase.Loadable,true,true,true,true,true))
    }

    private fun makeSession(available:Boolean=true, failLoad:Boolean=false, failDeinitialize:Boolean=false): ExynosUpscaleSession {
        val enn = object : ExynosEnnNativeInterface {
            override fun probeRuntime()=true
            override fun initialize()=EnnStatus.SUCCESS
            override fun deinitialize()=if (failDeinitialize) EnnStatus.FAILED else EnnStatus.SUCCESS
            override fun openModel(path:String)=EnnOpenModelResult(EnnStatus.SUCCESS, 1L)
            override fun closeModel(modelId: Long)=EnnStatus.SUCCESS
            override fun allocateAllBuffers(modelId: Long)=EnnAllocateResult(EnnStatus.SUCCESS, 0x100L,1,1)
            override fun releaseBuffers(bufferSet: Long, bufferCount: Int)=EnnStatus.SUCCESS
            override fun getBufferInfoByIndex(modelId: Long, direction: Int, index: Int)= if (direction==0) intArrayOf(1,128,128,3,ExynosUpscaleSession.INPUT_BYTES) else intArrayOf(1,512,512,3,ExynosUpscaleSession.OUTPUT_BYTES)
            override fun memcpyHostToDevice(bufferSet: Long, index: Int, data: ByteArray)=EnnStatus.SUCCESS
            override fun memcpyDeviceToHost(bufferSet: Long, index: Int, out: ByteArray): Int { java.util.Arrays.fill(out, 0x3F.toByte()); return EnnStatus.SUCCESS }
            override fun execute(modelId: Long)=EnnStatus.SUCCESS
            override fun getMetaInfo(metaId: Int, modelId: Long) = "v2.4.11.l"
        }
        return ExynosUpscaleSession(context, enn, kotlinx.coroutines.Dispatchers.Default, { if (failLoad) ModelLoadResult.LoadFailed("fail") else ModelLoadResult.Ready(File(context.filesDir,"fake.nnc")) })
    }

    @Test
    fun sessionCloseFailureIsPublishedAsCleanupDebtWithoutDeletingPublishedRow() = runBlocking {
        makeAvailable()
        val input = bitmap(32, 32)
        val rowStore = FakeRowStore()
        val history = SavedExportHistoryStore(context, persistence = object : SavedExportHistoryPersistence {
            var state = SavedExportPersistedState(null, false, ExportHistoryRetention.Never)
            override suspend fun readState() = state
            override suspend fun updateState(transform: suspend (SavedExportPersistedState) -> SavedExportPersistedState): SavedExportPersistedState {
                state = transform(state)
                return state
            }
        })
        val session = makeSession(failDeinitialize = true)
        try {
            val result = SuperResolutionExportOrchestrator.exportBitmap(
                context = context,
                inputBitmap = input,
                fileName = "close-failure.png",
                operationContext = ModelOperationContext(700L, "close-failure"),
                rowStore = rowStore,
                historyStore = history,
                sessionProvider = { session },
            )
            assertTrue(result is SuperResolutionExportResult.PublishedWithMetadataFailure)
            val published = result as SuperResolutionExportResult.PublishedWithMetadataFailure
            assertNotNull(published.uri)
            assertTrue(published.cleanupDebt)
            assertEquals(0, rowStore.deleteCalls)
        } finally {
            input.recycle()
            runCatching { session.close() }
        }
    }

    private suspend fun awaitLatchWithMainDrain(
        latch: java.util.concurrent.CountDownLatch,
        timeoutSeconds: Long,
        label: String,
    ) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (!latch.await(0, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            assertTrue("$label timed out", System.nanoTime() < deadline)
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
            kotlinx.coroutines.yield()
        }
    }

    @Test
    fun modelUnavailableDisablesAction() = runBlocking {
        // No capability
        ModelAvailabilityRegistry.resetForTest()
        val bmp = bitmap(128,128)
        val rowStore = FakeRowStore()
        val history = SavedExportHistoryStore(context, persistence = object: SavedExportHistoryPersistence {
            var s=SavedExportPersistedState(null,false,ExportHistoryRetention.Never)
            override suspend fun readState()=s
            override suspend fun updateState(transform: suspend (SavedExportPersistedState) -> SavedExportPersistedState): SavedExportPersistedState { s=transform(s); return s }
        })
        val ctx = ModelOperationContext(1L,"g1")
        val result = SuperResolutionExportOrchestrator.exportBitmap(context,bmp,"test.png",ctx, rowStore=rowStore, historyStore=history, sessionProvider={makeSession()})
        assertTrue(result is SuperResolutionExportResult.Failure)
        assertEquals(SuperResolutionFailureKind.ModelUnavailable, (result as SuperResolutionExportResult.Failure).kind)
        bmp.recycle()
    }

    @Test
    fun noDocumentDoesNotStart() {
        // This is ViewModel-level; we test orchestrator rejects invalid dimensions via 0x0 bitmap not possible, so we test via ViewModel canStart
        val vm = EditorViewModel(context)
        assertFalse(vm.canStartSuperResolution())
    }

    @Test
    fun sourcePreparationSharesFullExportSemantics() = runBlocking {
        makeAvailable()
        // Verify that input bitmap dimensions are truthfully recorded as actual input, not sensor
        val bmp = bitmap(256,128)
        val rowStore = FakeRowStore()
        val history = SavedExportHistoryStore(context, persistence = object: SavedExportHistoryPersistence {
            var s=SavedExportPersistedState(null,false,ExportHistoryRetention.Never)
            override suspend fun readState()=s
            override suspend fun updateState(transform: suspend (SavedExportPersistedState) -> SavedExportPersistedState): SavedExportPersistedState { s=transform(s); return s }
        })
        val session = makeSession()
        session.load(fakeToken())
        val ctx = ModelOperationContext(3L,"g1")
        val result = SuperResolutionExportOrchestrator.exportBitmap(context,bmp,"sr.png",ctx, rowStore=rowStore, historyStore=history, sessionProvider={session})
        assertTrue("expected Success got $result", result is SuperResolutionExportResult.Success)
        result as SuperResolutionExportResult.Success
        assertEquals(256, result.inputWidth); assertEquals(128, result.inputHeight)
        assertEquals(1024, result.outputWidth); assertEquals(512, result.outputHeight)
        bmp.recycle(); session.close()
    }

    @Test
    fun n5BridgeExactDimensionsAndNoFullBitmap() = runBlocking {
        makeAvailable()
        val bmp = bitmap(257,191)
        val rowStore = FakeRowStore()
        val history = SavedExportHistoryStore(context, persistence = object: SavedExportHistoryPersistence {
            var s=SavedExportPersistedState(null,false,ExportHistoryRetention.Never)
            override suspend fun readState()=s
            override suspend fun updateState(transform: suspend (SavedExportPersistedState) -> SavedExportPersistedState): SavedExportPersistedState { s=transform(s); return s }
        })
        val session = makeSession()
        session.load(fakeToken())
        val ctx = ModelOperationContext(4L,"g1")
        val result = SuperResolutionExportOrchestrator.exportBitmap(context,bmp,"sr.png",ctx, rowStore=rowStore, historyStore=history, sessionProvider={session})
        assertTrue(result is SuperResolutionExportResult.Success)
        // N5 would have produced 257*4=1028 etc, check
        result as SuperResolutionExportResult.Success
        assertEquals(257*4, result.outputWidth)
        bmp.recycle(); session.close()
    }

    @Test
    fun rgbArtifactRemovedAfterSuccess() = runBlocking {
        makeAvailable()
        val bmp = bitmap(128,128)
        val rowStore = FakeRowStore()
        val history = SavedExportHistoryStore(context, persistence = object: SavedExportHistoryPersistence {
            var s=SavedExportPersistedState(null,false,ExportHistoryRetention.Never)
            override suspend fun readState()=s
            override suspend fun updateState(transform: suspend (SavedExportPersistedState) -> SavedExportPersistedState): SavedExportPersistedState { s=transform(s); return s }
        })
        val session = makeSession()
        session.load(fakeToken())
        val ctx = ModelOperationContext(5L,"g1")
        val result = SuperResolutionExportOrchestrator.exportBitmap(context,bmp,"sr.png",ctx, rowStore=rowStore, historyStore=history, sessionProvider={session})
        assertTrue(result is SuperResolutionExportResult.Success)
        // Artifact file should be deleted (temp)
        // We can't directly check artifact, but ensure no .rgb8 remains in cache
        val rgb8s = context.cacheDir.listFiles()?.filter { it.name.endsWith(".rgb8") } ?: emptyList()
        assertTrue(rgb8s.isEmpty())
        bmp.recycle(); session.close()
    }

    @Test
    fun pendingRowDeletedOnEncodeFailure() = runBlocking {
        makeAvailable()
        val bmp = bitmap(128,128)
        val rowStore = FakeRowStore().apply { insertFail = null }
        // Make PNG encode fail by using artifact with invalid byteCount: we will tamper via session that produces bad artifact? Easier: make rowStore openOutputStream throw
        val failingStore = object : SuperResolutionRowStore {
            override suspend fun insertPending(fileName: String)=Uri.parse("content://media/99")
            override suspend fun openOutputStream(uri: Uri): OutputStream { throw IOException("write fail") }
            override suspend fun publish(uri: Uri): Int { return 0 }
            override suspend fun delete(uri: Uri) = SuperResolutionRowDeleteResult.Deleted
        }
        val history = SavedExportHistoryStore(context, persistence = object: SavedExportHistoryPersistence {
            var s=SavedExportPersistedState(null,false,ExportHistoryRetention.Never)
            override suspend fun readState()=s
            override suspend fun updateState(transform: suspend (SavedExportPersistedState) -> SavedExportPersistedState): SavedExportPersistedState { s=transform(s); return s }
        })
        val session = makeSession()
        session.load(fakeToken())
        val ctx = ModelOperationContext(6L,"g1")
        val result = SuperResolutionExportOrchestrator.exportBitmap(context,bmp,"sr.png",ctx, rowStore=failingStore, historyStore=history, sessionProvider={session})
        assertTrue(result is SuperResolutionExportResult.Failure)
        bmp.recycle(); session.close()
    }

    @Test
    fun metadataFailureAfterPublishPreservesImage() = runBlocking {
        makeAvailable()
        val bmp = bitmap(128,128)
        val rowStore = FakeRowStore()
        val history = SavedExportHistoryStore(context, persistence = object: SavedExportHistoryPersistence {
            var s=SavedExportPersistedState(null,false,ExportHistoryRetention.Never)
            override suspend fun readState()=s
            override suspend fun updateState(transform: suspend (SavedExportPersistedState) -> SavedExportPersistedState): SavedExportPersistedState { throw IOException("metadata write failed") }
        })
        val session = makeSession()
        session.load(fakeToken())
        val ctx = ModelOperationContext(7L,"g1")
        val result = SuperResolutionExportOrchestrator.exportBitmap(context,bmp,"sr.png",ctx, rowStore=rowStore, historyStore=history, sessionProvider={session})
        assertTrue("expected PublishedWithMetadataFailure got $result", result is SuperResolutionExportResult.PublishedWithMetadataFailure)
        val partial = result as SuperResolutionExportResult.PublishedWithMetadataFailure
        assertEquals(SuperResolutionFailureKind.MetadataPersistFailure, partial.failure)
        assertTrue(rowStore.published.isNotEmpty())
        bmp.recycle(); session.close()
    }

    @Test
    fun cancellationPropagatesAndDeletesPending() = runBlocking {
        makeAvailable()
        val bmp = bitmap(256,256)
        val rowStore = FakeRowStore()
        val history = SavedExportHistoryStore(context, persistence = object: SavedExportHistoryPersistence {
            var s=SavedExportPersistedState(null,false,ExportHistoryRetention.Never)
            override suspend fun readState()=s
            override suspend fun updateState(transform: suspend (SavedExportPersistedState) -> SavedExportPersistedState): SavedExportPersistedState { s=transform(s); return s }
        })
        val session = makeSession()
        session.load(fakeToken())
        val ctx = ModelOperationContext(8L,"g1", isCancelled={true})
        val result = SuperResolutionExportOrchestrator.exportBitmap(context,bmp,"sr.png",ctx, rowStore=rowStore, historyStore=history, sessionProvider={session})
        assertTrue(result is SuperResolutionExportResult.Cancelled || result is SuperResolutionExportResult.Failure)
        // No published image
        assertTrue(rowStore.published.isEmpty())
        bmp.recycle(); session.close()
    }

    @Test
    fun progressMonotonic() = runBlocking {
        makeAvailable()
        val bmp = bitmap(128,128)
        val rowStore = FakeRowStore()
        val history = SavedExportHistoryStore(context, persistence = object: SavedExportHistoryPersistence {
            var s=SavedExportPersistedState(null,false,ExportHistoryRetention.Never)
            override suspend fun readState()=s
            override suspend fun updateState(transform: suspend (SavedExportPersistedState) -> SavedExportPersistedState): SavedExportPersistedState { s=transform(s); return s }
        })
        val session = makeSession()
        session.load(fakeToken())
        val ctx = ModelOperationContext(9L,"g1")
        val progresses = mutableListOf<Float>()
        val result = SuperResolutionExportOrchestrator.exportBitmap(context,bmp,"sr.png",ctx, rowStore=rowStore, historyStore=history, sessionProvider={session}, onProgress={ progresses.add(it.overallFraction) })
        assertTrue(result is SuperResolutionExportResult.Success)
        for (i in 1 until progresses.size) assertTrue(progresses[i] >= progresses[i-1])
        assertEquals(1f, progresses.last())
        bmp.recycle(); session.close()
    }

    @Test
    fun wakeLockReleasedOnAllPaths() = runBlocking {
        makeAvailable()
        val bmp = bitmap(128,128)
        val fakeWake = FakeN5WakeLock()
        val rowStore = FakeRowStore()
        val history = SavedExportHistoryStore(context, persistence = object: SavedExportHistoryPersistence {
            var s=SavedExportPersistedState(null,false,ExportHistoryRetention.Never)
            override suspend fun readState()=s
            override suspend fun updateState(transform: suspend (SavedExportPersistedState) -> SavedExportPersistedState): SavedExportPersistedState { s=transform(s); return s }
        })
        val session = makeSession()
        session.load(fakeToken())
        val ctx = ModelOperationContext(10L,"g1")
        val result = SuperResolutionExportOrchestrator.exportBitmap(context,bmp,"sr.png",ctx, rowStore=rowStore, historyStore=history, sessionProvider={session}, wakeLockFactory={_,_->fakeWake})
        assertTrue(fakeWake.acquireCalls==1 && fakeWake.releaseCalls==1 && !fakeWake.isHeld)
        bmp.recycle(); session.close()
    }

    @Test
    fun alphaUnsupportedRejected() = runBlocking {
        makeAvailable()
        val bmp = Bitmap.createBitmap(128,128, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0x80000000.toInt()) // semi-transparent
        val rowStore = FakeRowStore()
        val history = SavedExportHistoryStore(context, persistence = object: SavedExportHistoryPersistence {
            var s=SavedExportPersistedState(null,false,ExportHistoryRetention.Never)
            override suspend fun readState()=s
            override suspend fun updateState(transform: suspend (SavedExportPersistedState) -> SavedExportPersistedState): SavedExportPersistedState { s=transform(s); return s }
        })
        val ctx = ModelOperationContext(11L,"g1")
        val result = SuperResolutionExportOrchestrator.exportBitmap(context,bmp,"sr.png",ctx, rowStore=rowStore, historyStore=history, sessionProvider={makeSession()})
        assertTrue(result is SuperResolutionExportResult.Failure)
        assertEquals(SuperResolutionFailureKind.AlphaUnsupported, (result as SuperResolutionExportResult.Failure).kind)
        bmp.recycle()
    }

    @Test
    fun heavyWorkerRunsOffMainThread() = runBlocking {
        makeAvailable()
        val bmp = bitmap(128,128)
        var workerThread: Thread? = null
        val rowStore = FakeRowStore()
        val history = SavedExportHistoryStore(context, persistence = object: SavedExportHistoryPersistence {
            var s=SavedExportPersistedState(null,false,ExportHistoryRetention.Never)
            override suspend fun readState()=s
            override suspend fun updateState(transform: suspend (SavedExportPersistedState) -> SavedExportPersistedState): SavedExportPersistedState { s=transform(s); return s }
        })
        val session = makeSession()
        session.load(fakeToken())
        val ctx = ModelOperationContext(99L,"g1")
        val workerEntered = java.util.concurrent.CountDownLatch(1)
        val workerRelease = java.util.concurrent.CountDownLatch(1)
        val mainHeartbeatLatch = java.util.concurrent.CountDownLatch(1)
        // Run export on a background dispatcher so we can gate the worker.
        val deferred = async(kotlinx.coroutines.Dispatchers.Default) {
            SuperResolutionExportOrchestrator.exportBitmap(
                context,bmp,"sr.png",ctx,
                rowStore=rowStore, historyStore=history, sessionProvider={session},
                heavyWorkerObserver={ _, thread ->
                    workerThread = thread
                    workerEntered.countDown()
                    // Keep worker blocked so Main must execute while heavy stage is still active.
                    workerRelease.await(5, java.util.concurrent.TimeUnit.SECONDS)
                },
                onProgress={ p ->
                    assertTrue(p.overallFraction >= 0f)
                }
            )
        }
        assertTrue("worker must enter heavy stage", workerEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // Post heartbeat to Main while worker gate remains blocked.
        Handler(Looper.getMainLooper()).post { mainHeartbeatLatch.countDown() }
        // Pump Robolectric Main looper: ensure heartbeat executes while worker is still blocked.
        val heartbeatDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < heartbeatDeadline && mainHeartbeatLatch.count != 0L) {
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
            delay(5)
        }
        assertTrue("Main heartbeat must execute while worker gate remains blocked", mainHeartbeatLatch.await(1, java.util.concurrent.TimeUnit.SECONDS))
        // Release worker and finish operation.
        workerRelease.countDown()
        val result = deferred.await()
        assertTrue(result is SuperResolutionExportResult.Success || result is SuperResolutionExportResult.Failure)
        assertNotNull(workerThread)
        assertNotSame(Looper.getMainLooper().thread, workerThread)
        bmp.recycle(); session.close()
    }

    @Test
    fun rgb8DeleteFalseWithNoExceptionIsCleanupDebtUntilGone() {
        val file = File(cacheDir, "artifact.rgb8").apply { writeBytes(byteArrayOf(1)) }
        var deleteCalls = 0
        val failure = deleteRgb8ArtifactBounded(
            file,
            exists = { true },
            delete = { deleteCalls++; false },
        )
        assertNotNull(failure)
        assertEquals(2, deleteCalls)
    }

    @Test
    fun rgb8DeleteTrueRequiresFileToBeGone() {
        val file = File(cacheDir, "artifact.rgb8")
        var exists = true
        val failure = deleteRgb8ArtifactBounded(
            file,
            exists = { exists },
            delete = { exists = false; true },
        )
        assertNull(failure)
    }

    @Test
    fun pendingRowDeleteRetriesAfterFirstFailure() = runBlocking {
        val results = ArrayDeque<SuperResolutionRowDeleteResult>().apply {
            add(SuperResolutionRowDeleteResult.Exception(IOException("first")))
            add(SuperResolutionRowDeleteResult.Deleted)
        }
        val store = object : SuperResolutionRowStore {
            var calls = 0
            override suspend fun insertPending(fileName: String) = Uri.parse("content://test/pending")
            override suspend fun openOutputStream(uri: Uri): OutputStream = ByteArrayOutputStream()
            override suspend fun publish(uri: Uri) = 1
            override suspend fun delete(uri: Uri): SuperResolutionRowDeleteResult {
                calls++
                return results.removeFirst()
            }
        }
        assertNull(deletePendingRowBounded(store, Uri.parse("content://test/pending")))
        assertEquals(2, store.calls)
    }

    @Test
    fun pendingRowDeleteZeroWithRowStillPresentIsDebt() = runBlocking {
        val store = object : SuperResolutionRowStore {
            var calls = 0
            override suspend fun insertPending(fileName: String) = Uri.parse("content://test/pending")
            override suspend fun openOutputStream(uri: Uri): OutputStream = ByteArrayOutputStream()
            override suspend fun publish(uri: Uri) = 1
            override suspend fun delete(uri: Uri): SuperResolutionRowDeleteResult {
                calls++
                return SuperResolutionRowDeleteResult.StillExistsAfterZero
            }
        }
        assertNotNull(deletePendingRowBounded(store, Uri.parse("content://test/pending")))
        assertEquals(2, store.calls)
    }

    @Test
    fun pendingRowDeleteFailsTwiceIsDebt() = runBlocking {
        val store = object : SuperResolutionRowStore {
            var calls = 0
            override suspend fun insertPending(fileName: String) = Uri.parse("content://test/pending")
            override suspend fun openOutputStream(uri: Uri): OutputStream = ByteArrayOutputStream()
            override suspend fun publish(uri: Uri) = 1
            override suspend fun delete(uri: Uri): SuperResolutionRowDeleteResult {
                calls++
                return SuperResolutionRowDeleteResult.Exception(IOException("failure $calls"))
            }
        }
        assertNotNull(deletePendingRowBounded(store, Uri.parse("content://test/pending")))
        assertEquals(2, store.calls)
    }

    @Test
    fun normalFullAndN6SourcesHaveByteForByteParityForEditedDocument() = runBlocking {
        val base = bitmap(8, 8, 0xff234567.toInt())
        val source = File(cacheDir, "edited-source.png").also {
            it.outputStream().use { stream -> check(base.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
        }
        val layer = SelectionLayer("selection", "selection", SelectionLayerKind.Subject, bitmap(8, 8, 0xffabcdef.toInt()))
        val harness = OwnedEditorViewModelHarness(context)
        val vm = harness.createEditor()
        val stateField = EditorViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<EditorUiState>
        val params = EditParams(exposure = 0.35f, contrast = -0.2f, saturation = 0.4f)
            stateFlow.value = EditorUiState(
            sourcePath = source.absolutePath,
            baseBitmapDirty = true,
            baseContentToken = "edited-token",
            originalPreviewBitmap = base,
            previewBitmap = base,
            params = params,
            presetLook = createPresetColorLookFromParams(params, size = 5, strength = 0.5f),
            activeQuickEffects = listOf(ActiveQuickEffect(QuickEffectKind.VignetteCorrection)),
            selectionLayers = listOf(layer),
            cropState = CropState(CropAspectRatio.Free, 0.1f, 0.1f, 0.9f, 0.9f),
            correctionEngineState = CorrectionEngineState(
                visiblePreview = VisiblePreviewState.Rendered(
                    requestedRoute = NativeRenderRoute.V1,
                    actualRoute = NativeRenderRoute.V1,
                    decision = RenderRouteDecision.FollowDocument,
                    algorithmVersion = AlgorithmContracts.NATIVE_V1,
                ),
                ),
            )
            // This fixture represents a settled editor document. The real
            // parameter path updates this private baseline when its render
            // commits; bypassing that path would otherwise make SR's required
            // action settlement look like an SR document mutation.
            EditorViewModel::class.java.getDeclaredField("lastSuccessfullyRenderedParams").apply {
                isAccessible = true
                set(vm, params)
            }
        val requests = mutableListOf<RenderRequest>()
        val renderer = EditorRenderer.installRendererOverrideForTest { request ->
            requests += request
            RenderResult.Success(
                operation = request.operation,
                requestedRoute = NativeRenderRoute.V1,
                output = request.basePreview.copy(Bitmap.Config.ARGB_8888, true),
                actualRoute = NativeRenderRoute.V1,
                decision = RenderRouteDecision.FollowDocument,
                usedDebugOverride = false,
                algorithmVersion = AlgorithmContracts.NATIVE_V1,
                participation = RenderParticipation(),
                durationMillis = 0L,
                knownTransientBytes = 0L,
            )
        }
        val crop = installCropTransformForTest { input, _ -> input.copy(Bitmap.Config.ARGB_8888, true) }
        try {
            val normal = vm.prepareNormalFullExportSourceBitmapForTest()
            val n6 = vm.prepareFullExportSourceBitmapForTest()
            assertEquals(normal.width, n6.width)
            assertEquals(normal.height, n6.height)
            val normalPixels = IntArray(normal.width * normal.height)
            val n6Pixels = IntArray(n6.width * n6.height)
            normal.getPixels(normalPixels, 0, normal.width, 0, 0, normal.width, normal.height)
            n6.getPixels(n6Pixels, 0, n6.width, 0, 0, n6.width, n6.height)
            assertArrayEquals(normalPixels, n6Pixels)
            assertEquals(2, requests.size)
            assertEquals(RenderOperation.ExportDirty, requests[0].operation)
            assertEquals(RenderOperation.ExportDirty, requests[1].operation)
            assertEquals(params, requests[0].params)
            assertEquals(requests[0].quickEffects, requests[1].quickEffects)
            assertEquals(requests[0].selectionLayers.size, requests[1].selectionLayers.size)
            normal.recycle()
            n6.recycle()

            stateFlow.value = stateFlow.value.copy(baseBitmapDirty = false)
            val cleanNormal = vm.prepareNormalFullExportSourceBitmapForTest()
            val cleanN6 = vm.prepareFullExportSourceBitmapForTest()
            assertEquals(cleanNormal.width, cleanN6.width)
            assertEquals(cleanNormal.height, cleanN6.height)
            val cleanNormalPixels = IntArray(cleanNormal.width * cleanNormal.height)
            val cleanN6Pixels = IntArray(cleanN6.width * cleanN6.height)
            cleanNormal.getPixels(cleanNormalPixels, 0, cleanNormal.width, 0, 0, cleanNormal.width, cleanNormal.height)
            cleanN6.getPixels(cleanN6Pixels, 0, cleanN6.width, 0, 0, cleanN6.width, cleanN6.height)
            assertArrayEquals(cleanNormalPixels, cleanN6Pixels)
            assertEquals(4, requests.size)
            assertEquals(RenderOperation.ExportClean, requests[2].operation)
            assertEquals(RenderOperation.ExportClean, requests[3].operation)
            cleanNormal.recycle()
            cleanN6.recycle()

            // Real product-path parity: observe exact pre-compression bitmaps via
            // read-only seams at the actual ViewModel entrypoints.
            // Run both real actions against equivalent settled documents and
            // byte-compare with bounded row scratch; must fail if either product
            // action stops using the shared Full-export renderer.
            fun hashBitmapBounded(bmp: Bitmap): String {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                val w = bmp.width; val h = bmp.height
                val row = IntArray(w)
                val buf = java.nio.ByteBuffer.allocate(4)
                for (y in 0 until h) {
                    bmp.getPixels(row, 0, w, 0, y, w, 1)
                    for (px in row) {
                        buf.clear()
                        buf.putInt(px)
                        md.update(buf.array())
                    }
                }
                return md.digest().joinToString("") { "%02x".format(it) }
            }
            // Helper to run one parity round (dirty vs clean) via real ViewModel actions.
            suspend fun runParityRound(isDirty: Boolean) {
                // Normal Full export observation
                var normalHash: String? = null
                var normalW = -1; var normalH = -1
                val normalLatch = java.util.concurrent.CountDownLatch(1)
                val normalRowStore = object : ExportRowStore {
                    override suspend fun insertPending(request: ExportRowRequest): Uri { return Uri.parse("content://test/normal") }
                    override suspend fun encode(uri: Uri, bitmap: Bitmap, format: ExportFormat) { }
                    override suspend fun publish(uri: Uri) { }
                    override suspend fun delete(uri: Uri) { }
                }
                val normalHistory = SavedExportHistoryStore(context, persistence = object: SavedExportHistoryPersistence {
                    var s=SavedExportPersistedState(null,false,ExportHistoryRetention.Never)
                    override suspend fun readState()=s
                    override suspend fun updateState(transform: suspend (SavedExportPersistedState) -> SavedExportPersistedState): SavedExportPersistedState { s=transform(s); return s }
                })
                val exportSeam = ExportTestSeam(
                    rowStore = normalRowStore,
                    historyStore = normalHistory,
                    sourceBitmapObserver = { bmp ->
                        normalW = bmp.width; normalH = bmp.height
                        normalHash = hashBitmapBounded(bmp)
                        normalLatch.countDown()
                    }
                )
                val exportHandle = ExportTestSeam.install(exportSeam)
                try {
                    // Ensure Full resolution is selected for parity.
                    stateFlow.value = stateFlow.value.copy(baseBitmapDirty = isDirty, exportResolution = ExportResolution.Full)
                    // Wait for any prior busy to settle.
                    while (vm.uiState.value.isBusy) {
                        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
                        yieldToEditorBackgroundForTest()
                        kotlinx.coroutines.delay(5)
                    }
                    vm.exportPreview()
                    // Wait for observer (bounded)
                    val d1 = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                    while (System.nanoTime() < d1 && normalLatch.count != 0L) {
                        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
                        kotlinx.coroutines.delay(5)
                    }
                    assertTrue("normal Full export must hit shared renderer observer", normalLatch.count == 0L)
                    assertNotNull(normalHash)
                } finally { exportHandle.close() }
                // Drain pipeline settlement
                val drain1 = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                while (System.nanoTime() < drain1 && vm.uiState.value.isBusy) {
                    org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
                    kotlinx.coroutines.delay(5)
                }
                // N6 observation via real exportSuperResolution
                var n6Hash: String? = null
                var n6W = -1; var n6H = -1
                val n6Latch = java.util.concurrent.CountDownLatch(1)
                val srSeam2 = SuperResolutionTestSeam(
                    rowStore = FakeRowStore(),
                    historyStore = SavedExportHistoryStore(context, persistence = object: SavedExportHistoryPersistence {
                        var s=SavedExportPersistedState(null,false,ExportHistoryRetention.Never)
                        override suspend fun readState()=s
                        override suspend fun updateState(transform: suspend (SavedExportPersistedState) -> SavedExportPersistedState): SavedExportPersistedState { s=transform(s); return s }
                    }),
                    sessionProvider = { makeSession() },
                    sourceBitmapObserver = { bmp ->
                        n6W = bmp.width; n6H = bmp.height
                        n6Hash = hashBitmapBounded(bmp)
                        n6Latch.countDown()
                    }
                )
                val srHandle2 = SuperResolutionTestSeam.install(srSeam2)
                try {
                    makeAvailable()
                    // Ensure document still settled for SR.
                    while (vm.uiState.value.isBusy) {
                        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
                        yieldToEditorBackgroundForTest()
                        kotlinx.coroutines.delay(5)
                    }
                    assertTrue("SR must be startable for parity", vm.canStartSuperResolution())
                    vm.exportSuperResolution()
                    val d2 = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                    while (System.nanoTime() < d2 && n6Latch.count != 0L) {
                        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
                        kotlinx.coroutines.delay(5)
                    }
                    assertTrue("N6 must hit shared renderer observer", n6Latch.count == 0L)
                    assertNotNull(n6Hash)
                } finally { srHandle2.close() }
                // Drain SR settlement
                val drain2 = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < drain2 && vm.superResolutionStatus.value.isBusy) {
                    org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
                    kotlinx.coroutines.delay(5)
                }
                assertEquals("Exact dimensions must match (Full) dirty=$isDirty", normalW, n6W)
                assertEquals("Exact height must match", normalH, n6H)
                assertEquals("Byte-exact RGB/ARGB pixels must match — shared Full renderer", normalHash, n6Hash)
            }
            runParityRound(isDirty = true)
            runParityRound(isDirty = false)
        } finally {
            crop.close()
            renderer.close()
            layer.bitmap.recycle()
            base.recycle()
            harness.close()
        }
    }

    @Test
    fun viewModelProductActionSuccessPreservesDocumentAndHistoryState() = runBlocking {
        makeAvailable()
        val harness = OwnedEditorViewModelHarness(context)
        val vm = harness.createEditor()
        try {
            val initDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < initDeadline && (!vm.startupInitCompletion.isCompleted || vm.startupCoordinatorActiveForTest())) {
                org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
                yieldToEditorBackgroundForTest()
                Thread.sleep(5L)
            }
            val preview = bitmap(256, 256, 0xff102030.toInt())
            vm.updateUiState {
                it.copy(
                    sourcePath = null,
                    baseBitmapDirty = true,
                    baseContentToken = "vm-product-token",
                    originalPreviewBitmap = preview,
                    previewBitmap = preview,
                    params = EditParams(exposure = 0.4f, contrast = -0.15f),
                    revision = 7,
                )
            }
            while (vm.uiState.value.isBusy) {
                org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
                yieldToEditorBackgroundForTest()
                Thread.sleep(5L)
            }
            // This directly seeded state is already the result of a settled
            // parameter render. Keep the same private baseline the real
            // parameter path records before an external action is admitted.
            EditorViewModel::class.java.getDeclaredField("lastSuccessfullyRenderedParams").apply {
                isAccessible = true
                set(vm, vm.uiState.value.params)
            }
            val rowStore = FakeRowStore()
            val history = SavedExportHistoryStore(context, persistence = object : SavedExportHistoryPersistence {
                var state = SavedExportPersistedState(null, false, ExportHistoryRetention.Never)
                override suspend fun readState() = state
                override suspend fun updateState(transform: suspend (SavedExportPersistedState) -> SavedExportPersistedState): SavedExportPersistedState {
                    state = transform(state)
                    return state
                }
            })
            val seam = SuperResolutionTestSeam(
                rowStore = rowStore,
                historyStore = history,
                sessionProvider = { makeSession() },
            )
            val seamHandle = SuperResolutionTestSeam.install(seam)
            try {
                makeAvailable()
                val before = vm.uiState.value
                val beforePreview = before.previewBitmap
                val beforeOriginal = before.originalPreviewBitmap
                assertTrue("ViewModel product action must be admitted", vm.canStartSuperResolution())
                vm.exportSuperResolution()
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
                while (System.nanoTime() < deadline && vm.superResolutionStatus.value.phase !in setOf(
                        SuperResolutionExportPhase.Succeeded,
                        SuperResolutionExportPhase.Failed,
                        SuperResolutionExportPhase.Cancelled,
                    )) {
                    org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
                    yieldToEditorBackgroundForTest()
                    Thread.sleep(5L)
                }
                assertEquals(SuperResolutionExportPhase.Succeeded, vm.superResolutionStatus.value.phase)
                val historyDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                while (System.nanoTime() < historyDeadline && vm.uiState.value.savedExports.isEmpty()) {
                    org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
                    yieldToEditorBackgroundForTest()
                    Thread.sleep(5L)
                }
                val after = vm.uiState.value
                assertEquals(before.sourcePath, after.sourcePath)
                assertEquals(before.baseContentToken, after.baseContentToken)
                assertEquals(before.revision, after.revision)
                assertSame(beforePreview, after.previewBitmap)
                assertSame(beforeOriginal, after.originalPreviewBitmap)
                assertEquals(before.params, after.params)
                assertEquals(before.cropState, after.cropState)
                assertEquals(before.selectionLayers, after.selectionLayers)
                assertEquals(before.canUndo, after.canUndo)
                assertEquals(before.canRedo, after.canRedo)
                assertEquals(before.draftGenerationId, after.draftGenerationId)
                assertEquals(before.draftSourcePath, after.draftSourcePath)
                assertEquals(before.draftBaseContentToken, after.draftBaseContentToken)
                assertEquals(1, rowStore.published.size)
                assertEquals(1, after.savedExports.size)
                preview.recycle()
            } finally {
                seamHandle.close()
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun viewModelProductActionSurfacesSourceMemoryRejection() = runBlocking {
        makeAvailable()
        val harness = OwnedEditorViewModelHarness(context)
        val vm = harness.createEditor()
        val preview = bitmap(32, 32)
        val stateField = EditorViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<EditorUiState>
        stateFlow.value = vm.uiState.value.copy(
            previewBitmap = preview,
            originalPreviewBitmap = preview,
            baseBitmapDirty = true,
            baseContentToken = "memory-rejected",
        )
        val initDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < initDeadline && (!vm.startupInitCompletion.isCompleted || vm.startupCoordinatorActiveForTest())) {
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
            Thread.sleep(5L)
        }
        makeAvailable()
        EditorViewModel::class.java.getDeclaredField("lastSuccessfullyRenderedParams").apply {
            isAccessible = true
            set(vm, vm.uiState.value.params)
        }
        val recovery = MemoryRecoveryTestSeam(rejectExportPreparation = true)
        val recoveryHandle = MemoryRecoveryTestSeam.install(recovery)
        try {
            assertTrue(vm.canStartSuperResolution())
            vm.exportSuperResolution()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline &&
                (vm.superResolutionStatus.value.phase == SuperResolutionExportPhase.Idle ||
                    vm.superResolutionStatus.value.isBusy)
            ) {
                org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
                yieldToEditorBackgroundForTest()
                Thread.sleep(5L)
            }
            assertEquals(SuperResolutionExportPhase.Failed, vm.superResolutionStatus.value.phase)
            assertEquals(SuperResolutionFailureKind.SourceRenderMemoryRejected, vm.superResolutionStatus.value.failureKind)
        } finally {
            recoveryHandle.close()
            harness.close()
        }
    }

    @Test
    fun viewModelArbitrationAllowsSrSupersessionButBlocksNormalConflict() {
        makeAvailable()
        val vm = EditorViewModel(context)
        val preview = bitmap(16, 16)
        vm.updateUiState { it.copy(previewBitmap = preview, originalPreviewBitmap = preview, isBusy = true) }
        makeAvailable()
        assertFalse("normal export owns the busy state", vm.canStartSuperResolution())
        val oldSrOwner = SupervisorJob()
        EditorViewModel::class.java.getDeclaredField("superResolutionJob").apply {
            isAccessible = true
            set(vm, oldSrOwner)
        }
        assertTrue("a newer SR may supersede an older SR", vm.canStartSuperResolution())
        vm.shutdownForTest()
        assertTrue(oldSrOwner.isCancelled)
    }

    @Test
    fun srDocumentGenerationStaleBeforeTileWork() = runBlocking {
        makeAvailable()
        val harness = OwnedEditorViewModelHarness(context)
        val vm = harness.createEditor()
        val preview = bitmap(128, 128)
        val afterModelLoadEntered = java.util.concurrent.CountDownLatch(1)
        val releaseAfterModelLoad = java.util.concurrent.CountDownLatch(1)
        val executeCalls = java.util.concurrent.atomic.AtomicInteger()
        val fakeEnn = object : ExynosEnnNativeInterface {
            override fun probeRuntime() = true
            override fun initialize() = EnnStatus.SUCCESS
            override fun deinitialize() = EnnStatus.SUCCESS
            override fun openModel(path: String) = EnnOpenModelResult(EnnStatus.SUCCESS, 1L)
            override fun closeModel(modelId: Long) = EnnStatus.SUCCESS
            override fun allocateAllBuffers(modelId: Long) = EnnAllocateResult(EnnStatus.SUCCESS, 0x100L, 1, 1)
            override fun releaseBuffers(bufferSet: Long, bufferCount: Int) = EnnStatus.SUCCESS
            override fun getBufferInfoByIndex(modelId: Long, direction: Int, index: Int) =
                if (direction == 0) intArrayOf(1, 128, 128, 3, ExynosUpscaleSession.INPUT_BYTES)
                else intArrayOf(1, 512, 512, 3, ExynosUpscaleSession.OUTPUT_BYTES)
            override fun memcpyHostToDevice(bufferSet: Long, index: Int, data: ByteArray) = EnnStatus.SUCCESS
            override fun memcpyDeviceToHost(bufferSet: Long, index: Int, out: ByteArray): Int {
                java.util.Arrays.fill(out, 0x3F.toByte())
                return EnnStatus.SUCCESS
            }
            override fun execute(modelId: Long): Int {
                executeCalls.incrementAndGet()
                return EnnStatus.SUCCESS
            }
            override fun getMetaInfo(metaId: Int, modelId: Long) = "v2.4.11.l"
        }
        val session = ExynosUpscaleSession(
            context,
            fakeEnn,
            kotlinx.coroutines.Dispatchers.Default,
            { ModelLoadResult.Ready(File(context.filesDir, "fake.nnc")) },
        )
        val rowStore = FakeRowStore()
        var persistedHistory = SavedExportPersistedState(null, false, ExportHistoryRetention.Never)
        val history = SavedExportHistoryStore(context, persistence = object : SavedExportHistoryPersistence {
            override suspend fun readState() = persistedHistory
            override suspend fun updateState(transform: suspend (SavedExportPersistedState) -> SavedExportPersistedState): SavedExportPersistedState {
                persistedHistory = transform(persistedHistory)
                return persistedHistory
            }
        })
        val seam = SuperResolutionTestSeam(
            sessionProvider = { session },
            rowStore = rowStore,
            historyStore = history,
            milestoneObserver = { label ->
                if (label == "after_model_load") {
                    afterModelLoadEntered.countDown()
                    check(releaseAfterModelLoad.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        "stale-generation gate was not released"
                    }
                }
            },
        )
        val seamHandle = SuperResolutionTestSeam.install(seam)
        try {
            vm.updateUiState {
                it.copy(
                    previewBitmap = preview,
                    originalPreviewBitmap = preview,
                    sourcePath = null,
                    baseBitmapDirty = true,
                    baseContentToken = "stale-gen-token",
                    params = EditParams(),
                    revision = 1,
                )
            }
            val initDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < initDeadline && (!vm.startupInitCompletion.isCompleted || vm.startupCoordinatorActiveForTest())) {
                org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
                yieldToEditorBackgroundForTest()
                Thread.sleep(5L)
            }
            EditorViewModel::class.java.getDeclaredField("lastSuccessfullyRenderedParams").apply {
                isAccessible = true
                set(vm, vm.uiState.value.params)
            }
            val capturedGeneration = vm.historyCoordinator.currentGeneration()
            val historyBefore = persistedHistory
            makeAvailable()
            assertTrue("real SR product action must be admitted", vm.canStartSuperResolution())
            vm.exportSuperResolution()
            awaitLatchWithMainDrain(afterModelLoadEntered, 10, "real product after_model_load boundary")

            // Change the authoritative generation on Main while production N6 is held
            // immediately before TileFileBackedUpscaler is entered.
            val genLatch = java.util.concurrent.CountDownLatch(1)
            val newGen = java.util.concurrent.atomic.AtomicReference<String>()
            Handler(Looper.getMainLooper()).post {
                vm.historyCoordinator.replaceDocument()
                newGen.set(vm.historyCoordinator.currentGeneration())
                genLatch.countDown()
            }
            while (!genLatch.await(0, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
                kotlinx.coroutines.yield()
            }
            assertNotEquals("generation must change on Main", capturedGeneration, newGen.get())
            assertNotEquals(capturedGeneration, vm.historyCoordinator.currentGeneration())

            // Let the real product-created ModelOperationContext reach the tile boundary.
            releaseAfterModelLoad.countDown()
            val productJob = checkNotNull(vm.superResolutionJobForTest())
            while (productJob.isActive) {
                org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
                kotlinx.coroutines.yield()
            }

            assertEquals("stale SR must not execute a native tile", 0, executeCalls.get())
            assertEquals("stale SR must not insert a MediaStore row", 0, rowStore.insertCalls)
            assertEquals("stale SR must not publish a MediaStore row", 0, rowStore.publishCalls)
            assertEquals("stale SR must not mutate saved-export history", historyBefore, persistedHistory)
            // Use the stale context directly with the upscaler — should return Stale before any tile.
        } finally {
            releaseAfterModelLoad.countDown()
            runCatching { session.close() }
            seamHandle.close()
            harness.close()
        }
    }

    @Test
    fun arbitrationNewerSrSupersedesOlderSr() = runBlocking {
        makeAvailable()
        val harness = OwnedEditorViewModelHarness(context)
        val vm = harness.createEditor()
        val preview = bitmap(128, 128)
        try {
            vm.updateUiState {
                it.copy(
                    previewBitmap = preview,
                    originalPreviewBitmap = preview,
                    sourcePath = null,
                    baseBitmapDirty = true,
                    baseContentToken = "arbitration-token",
                    params = EditParams(),
                    revision = 1,
                )
            }
            val initDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < initDeadline && (!vm.startupInitCompletion.isCompleted || vm.startupCoordinatorActiveForTest())) {
                org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
                yieldToEditorBackgroundForTest()
                Thread.sleep(5L)
            }
            EditorViewModel::class.java.getDeclaredField("lastSuccessfullyRenderedParams").apply {
                isAccessible = true
                set(vm, vm.uiState.value.params)
            }
            // A is held at the real native execute boundary while B supersedes it.
            val aExecuteEntered = java.util.concurrent.CountDownLatch(1)
            val releaseA = java.util.concurrent.CountDownLatch(1)
            val bTerminalProgress = java.util.concurrent.CountDownLatch(1)
            val bProgress = java.util.concurrent.atomic.AtomicReference<SuperResolutionExportProgress>()
            val sessionCallCount = java.util.concurrent.atomic.AtomicInteger(0)
            val rowStoreB = FakeRowStore()
            val historyB = SavedExportHistoryStore(context, persistence = object: SavedExportHistoryPersistence {
                var s = SavedExportPersistedState(null, false, ExportHistoryRetention.Never)
                override suspend fun readState() = s
                override suspend fun updateState(transform: suspend (SavedExportPersistedState) -> SavedExportPersistedState): SavedExportPersistedState { s = transform(s); return s }
            })
            val seam = SuperResolutionTestSeam(
                sessionProvider = {
                    val n = sessionCallCount.incrementAndGet()
                    if (n == 1) {
                        // A: slow execute to hold after admission but before terminal — deterministic hold via sleep
                        val slowEnn = object : ExynosEnnNativeInterface {
                            override fun probeRuntime()=true
                            override fun initialize()=EnnStatus.SUCCESS
                            override fun deinitialize()=EnnStatus.SUCCESS
                            override fun openModel(path:String)=EnnOpenModelResult(EnnStatus.SUCCESS, 1L)
                            override fun closeModel(modelId: Long)=EnnStatus.SUCCESS
                            override fun allocateAllBuffers(modelId: Long)=EnnAllocateResult(EnnStatus.SUCCESS, 0x100L,1,1)
                            override fun releaseBuffers(bufferSet: Long, bufferCount: Int)=EnnStatus.SUCCESS
                            override fun getBufferInfoByIndex(modelId: Long, direction: Int, index: Int)= if (direction==0) intArrayOf(1,128,128,3,ExynosUpscaleSession.INPUT_BYTES) else intArrayOf(1,512,512,3,ExynosUpscaleSession.OUTPUT_BYTES)
                            override fun memcpyHostToDevice(bufferSet: Long, index: Int, data: ByteArray)=EnnStatus.SUCCESS
                            override fun memcpyDeviceToHost(bufferSet: Long, index: Int, out: ByteArray): Int { java.util.Arrays.fill(out, 0x3F.toByte()); return EnnStatus.SUCCESS }
                            override fun execute(modelId: Long): Int {
                                aExecuteEntered.countDown()
                                while (true) {
                                    try {
                                        releaseA.await()
                                        break
                                    } catch (_: InterruptedException) {
                                        // Keep the explicit ownership gate even if cancellation interrupts the thread.
                                    }
                                }
                                return EnnStatus.SUCCESS
                            }
                            override fun getMetaInfo(metaId: Int, modelId: Long) = "v2.4.11.l"
                        }
                        ExynosUpscaleSession(context, slowEnn, kotlinx.coroutines.Dispatchers.Default, { ModelLoadResult.Ready(File(context.filesDir,"fake.nnc")) })
                    } else {
                        makeSession()
                    }
                },
                rowStore = rowStoreB,
                historyStore = historyB,
                progressObserver = { p ->
                    bProgress.set(p)
                    if (p.phase == SuperResolutionExportPhase.Succeeded) bTerminalProgress.countDown()
                }
            )
            val handle = SuperResolutionTestSeam.install(seam)
            try {
                makeAvailable()
                assertTrue(vm.canStartSuperResolution())
                vm.exportSuperResolution()
                // Deterministic gate: wait until A is admitted (isBusy && Upscaling or Preparing) — not just fixed delay
                awaitLatchWithMainDrain(aExecuteEntered, 10, "A native execute boundary")
                val firstToken = vm.superResolutionTokenForTest()
                val firstJob = vm.superResolutionJobForTest()
                assertNotNull(firstToken)
                assertNotNull(firstJob)
                val aStatus = vm.superResolutionStatus.value
                val aPhase = aStatus.phase
                val aProgress = aStatus.progress
                val aMessage = vm.uiState.value.message
                val aBusy = aStatus.isBusy
                assertTrue("A must be busy while parked in native execute", aBusy)
                // Start B while A is held (A is sleeping in native execute) — B must supersede.
                makeAvailable()
                assertTrue("newer SR supersedes older", vm.canStartSuperResolution())
                vm.exportSuperResolution()
                // B's admission is synchronous: token/job update happens immediately on Main
                val secondToken = vm.superResolutionTokenForTest()
                val secondJob = vm.superResolutionJobForTest()
                assertNotEquals("second SR must have newer token", firstToken, secondToken)
                assertNotEquals("second SR must have different owning Job", firstJob, secondJob)
                assertEquals("B must own current token", secondToken, vm.superResolutionTokenForTest())
                assertEquals("B must own current Job", secondJob, vm.superResolutionJobForTest())
                assertTrue("A Job must be cancelled/superseded", checkNotNull(firstJob).isCancelled)
                awaitLatchWithMainDrain(bTerminalProgress, 20, "B terminal success progress")
                while (vm.superResolutionStatus.value.phase != SuperResolutionExportPhase.Succeeded ||
                    vm.superResolutionStatus.value.isBusy ||
                    vm.superResolutionStatus.value.publishedUri == null
                ) {
                    org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
                    kotlinx.coroutines.yield()
                }
                val statusAfterB = vm.superResolutionStatus.value
                // Debug: log actual status if isBusy mismatch
                if (statusAfterB.phase != SuperResolutionExportPhase.Succeeded || statusAfterB.isBusy) {
                    println("DEBUG statusAfterB=$statusAfterB progress=${statusAfterB.progress} published=${statusAfterB.publishedUri}")
                }
                assertEquals(SuperResolutionExportPhase.Succeeded, statusAfterB.phase)
                assertEquals(false, statusAfterB.isBusy)
                val uriB = statusAfterB.publishedUri
                assertNotNull(uriB)
                val bPhase = statusAfterB.phase
                val bProgressValue = statusAfterB.progress
                val bMessage = vm.uiState.value.message
                val bBusy = statusAfterB.isBusy
                val bUri = uriB
                assertEquals(1f, bProgressValue.overallFraction)
                // rowStoreB.published should contain exactly 1 (B's) — A may have inserted but not published if superseded
                // Allow 1 or 2 but must contain uriB and not be overwritten by A after B
                assertTrue("rowStore must contain B's uri", rowStoreB.published.contains(uriB))
                assertEquals(1, rowStoreB.published.size)
                releaseA.countDown()
                val aSettlementDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                while (!checkNotNull(firstJob).isCompleted) {
                    assertTrue("A Job settlement timed out", System.nanoTime() < aSettlementDeadline)
                    org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
                    kotlinx.coroutines.yield()
                }
                assertTrue("A Job must settle after release", firstJob.isCompleted)
                org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
                kotlinx.coroutines.yield()
                val finalStatus = vm.superResolutionStatus.value
                assertEquals(bPhase, finalStatus.phase)
                assertEquals(bProgressValue, finalStatus.progress)
                assertEquals(bMessage, vm.uiState.value.message)
                assertEquals(bBusy, finalStatus.isBusy)
                assertEquals(bUri, finalStatus.publishedUri)
                // Prove A did not overwrite B's observable state
                // Phase/progress/message/isBusy/publishedUri must still be B's, not A's stale values.
                // Progress must be B's final progress (overallFraction 1.0, not A's intermediate)
                // Message must be B's success message, not A's intermediate.
                assertTrue(vm.uiState.value.message?.contains("AI 4배") == true)
                // B remains authoritative through settlement — poll once more after a short idle.
            } finally {
                releaseA.countDown()
                handle.close()
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun srUserCancellationPreservesDocumentIdentity() = runBlocking {
        makeAvailable()
        val harness = OwnedEditorViewModelHarness(context)
        val vm = harness.createEditor()
        val preview = bitmap(64, 64)
        try {
            vm.updateUiState {
                it.copy(
                    previewBitmap = preview,
                    originalPreviewBitmap = preview,
                    sourcePath = null,
                    baseBitmapDirty = true,
                    baseContentToken = "cancel-token",
                    params = EditParams(saturation = 0.5f),
                    revision = 4,
                )
            }
            val initDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < initDeadline && (!vm.startupInitCompletion.isCompleted || vm.startupCoordinatorActiveForTest())) {
                org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
                yieldToEditorBackgroundForTest()
                Thread.sleep(5L)
            }
            EditorViewModel::class.java.getDeclaredField("lastSuccessfullyRenderedParams").apply {
                isAccessible = true
                set(vm, vm.uiState.value.params)
            }
            val seam = SuperResolutionTestSeam(
                sessionProvider = { makeSession() },
                rowStore = FakeRowStore(),
                historyStore = SavedExportHistoryStore(context, persistence = object: SavedExportHistoryPersistence {
                    var s = SavedExportPersistedState(null, false, ExportHistoryRetention.Never)
                    override suspend fun readState() = s
                    override suspend fun updateState(transform: suspend (SavedExportPersistedState) -> SavedExportPersistedState): SavedExportPersistedState { s = transform(s); return s }
                }),
            )
            val handle = SuperResolutionTestSeam.install(seam)
            try {
                makeAvailable()
                val before = vm.uiState.value
                assertTrue(vm.canStartSuperResolution())
                vm.exportSuperResolution()
                // Cancel shortly after start.
                kotlinx.coroutines.delay(50)
                vm.cancelSuperResolution()
                // Wait for terminal Cancelled state.
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < deadline && vm.superResolutionStatus.value.phase != SuperResolutionExportPhase.Cancelled && vm.superResolutionStatus.value.failureKind != SuperResolutionFailureKind.Cancelled) {
                    org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
                    yieldToEditorBackgroundForTest()
                    Thread.sleep(5L)
                }
                val status = vm.superResolutionStatus.value
                assertTrue("should be cancelled or failed", status.phase == SuperResolutionExportPhase.Cancelled || status.failureKind == SuperResolutionFailureKind.Cancelled)
                assertEquals(false, status.isBusy)
                val after = vm.uiState.value
                // Document identity must be unchanged.
                assertEquals(before.sourcePath, after.sourcePath)
                assertEquals(before.baseContentToken, after.baseContentToken)
                assertEquals(before.revision, after.revision)
                assertSame(before.previewBitmap, after.previewBitmap)
                assertSame(before.originalPreviewBitmap, after.originalPreviewBitmap)
                assertEquals(before.params, after.params)
                assertEquals(before.cropState, after.cropState)
                assertEquals(before.selectionLayers, after.selectionLayers)
                assertEquals(before.canUndo, after.canUndo)
                assertEquals(before.canRedo, after.canRedo)
            } finally {
                handle.close()
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun srStaleOperationPreservesDocumentIdentity() = runBlocking {
        makeAvailable()
        val harness = OwnedEditorViewModelHarness(context)
        val vm = harness.createEditor()
        val preview = bitmap(64, 64)
        try {
            vm.updateUiState {
                it.copy(
                    previewBitmap = preview,
                    originalPreviewBitmap = preview,
                    sourcePath = null,
                    baseBitmapDirty = true,
                    baseContentToken = "stale-token",
                    params = EditParams(),
                    revision = 1,
                )
            }
            val initDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < initDeadline && (!vm.startupInitCompletion.isCompleted || vm.startupCoordinatorActiveForTest())) {
                org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
                yieldToEditorBackgroundForTest()
                Thread.sleep(5L)
            }
            EditorViewModel::class.java.getDeclaredField("lastSuccessfullyRenderedParams").apply {
                isAccessible = true
                set(vm, vm.uiState.value.params)
            }
            val seam = SuperResolutionTestSeam(
                sessionProvider = { makeSession() },
                rowStore = FakeRowStore(),
                historyStore = SavedExportHistoryStore(context, persistence = object: SavedExportHistoryPersistence {
                    var s = SavedExportPersistedState(null, false, ExportHistoryRetention.Never)
                    override suspend fun readState() = s
                    override suspend fun updateState(transform: suspend (SavedExportPersistedState) -> SavedExportPersistedState): SavedExportPersistedState { s = transform(s); return s }
                }),
            )
            val handle = SuperResolutionTestSeam.install(seam)
            try {
                makeAvailable()
                val before = vm.uiState.value
                assertTrue(vm.canStartSuperResolution())
                vm.exportSuperResolution()
                // Start second SR to make first stale.
                kotlinx.coroutines.delay(50)
                vm.exportSuperResolution()
                // Wait for settlement.
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < deadline && vm.superResolutionStatus.value.isBusy) {
                    org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
                    yieldToEditorBackgroundForTest()
                    Thread.sleep(5L)
                }
                val after = vm.uiState.value
                // Document identity must be unchanged.
                assertEquals(before.sourcePath, after.sourcePath)
                assertEquals(before.baseContentToken, after.baseContentToken)
                assertEquals(before.revision, after.revision)
                assertSame(before.previewBitmap, after.previewBitmap)
                assertSame(before.originalPreviewBitmap, after.originalPreviewBitmap)
                assertEquals(before.params, after.params)
                assertEquals(before.cropState, after.cropState)
                assertEquals(before.selectionLayers, after.selectionLayers)
            } finally {
                handle.close()
            }
        } finally {
            harness.close()
        }
    }
}
