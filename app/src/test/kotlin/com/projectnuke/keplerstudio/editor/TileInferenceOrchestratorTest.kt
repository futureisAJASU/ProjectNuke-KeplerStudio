package com.projectnuke.keplerstudio.editor

import android.app.Application
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Phase N4.8–N4.11 — sequential tiled orchestration over an already-loaded session with a
 * fake ENN backend: success coverage, cancellation/staleness between tiles, and injected
 * H2D/execute/D2H/assembly failures proving no later tile runs and no partial success.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TileInferenceOrchestratorTest {

    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun setUp() {
        ModelAvailabilityRegistry.resetForTest()
        File(context.filesDir, "exynos_models").deleteRecursively()
    }

    @After
    fun tearDown() {
        ModelAvailabilityRegistry.resetForTest()
        File(context.filesDir, "exynos_models").deleteRecursively()
    }

    private class FakeEnn : ExynosEnnNativeInterface {
        var probeResult = true
        var initializeStatus = EnnStatus.SUCCESS
        var deinitializeStatus = EnnStatus.SUCCESS
        var openModelStatus = EnnStatus.SUCCESS
        var allocationStatus = EnnStatus.SUCCESS
        var releaseBuffersStatus = EnnStatus.SUCCESS
        var closeModelStatus = EnnStatus.SUCCESS

        var inputInfo = intArrayOf(1, 128, 128, 3, ExynosUpscaleSession.INPUT_BYTES)
        var outputInfo = intArrayOf(1, 512, 512, 3, ExynosUpscaleSession.OUTPUT_BYTES)

        /** Per-call status sequences; last value repeats when the list is exhausted. */
        var memcpyInStatuses = listOf(EnnStatus.SUCCESS)
        var executeStatuses = listOf(EnnStatus.SUCCESS)
        var memcpyOutStatuses = listOf(EnnStatus.SUCCESS)

        val memcpyInCalls = AtomicInteger()
        val executeCalls = AtomicInteger()
        val memcpyOutCalls = AtomicInteger()
        val releaseBufferCalls = AtomicInteger()
        val closeModelCalls = AtomicInteger()
        val deinitializeCalls = AtomicInteger()

        /** When set to a call index >= 0, native execute throws before returning at that call. */
        var executeThrowAtCall: Int = -1

        var outputFiller: ((callIndex: Int, ByteArray) -> Unit)? = null
        var executeGate: CompletableDeferred<Unit>? = null
        var executeStarted: CompletableDeferred<Unit>? = null

        override fun probeRuntime() = probeResult
        override fun initialize() = initializeStatus
        override fun deinitialize(): Int {
            deinitializeCalls.incrementAndGet()
            return deinitializeStatus
        }

        override fun openModel(path: String) =
            EnnOpenModelResult(openModelStatus, if (openModelStatus == EnnStatus.SUCCESS) 7001L else 0L)

        override fun closeModel(modelId: Long): Int {
            closeModelCalls.incrementAndGet()
            return closeModelStatus
        }

        override fun allocateAllBuffers(modelId: Long) =
            EnnAllocateResult(allocationStatus, if (allocationStatus == EnnStatus.SUCCESS) 0x515AL else 0L, 1, 1)

        override fun releaseBuffers(bufferSet: Long, bufferCount: Int): Int {
            releaseBufferCalls.incrementAndGet()
            return releaseBuffersStatus
        }

        override fun getBufferInfoByIndex(modelId: Long, direction: Int, index: Int): IntArray? =
            if (direction == 0) inputInfo else outputInfo

        override fun memcpyHostToDevice(bufferSet: Long, index: Int, data: ByteArray): Int {
            val call = memcpyInCalls.getAndIncrement()
            return memcpyInStatuses[minOf(call, memcpyInStatuses.size - 1)]
        }

        override fun memcpyDeviceToHost(bufferSet: Long, index: Int, out: ByteArray): Int {
            val call = memcpyOutCalls.getAndIncrement()
            outputFiller?.invoke(call, out)
            return memcpyOutStatuses[minOf(call, memcpyOutStatuses.size - 1)]
        }

        override fun execute(modelId: Long): Int {
            val call = executeCalls.getAndIncrement()
            executeStarted?.complete(Unit)
            executeGate?.let { gate -> runBlocking { gate.await() } }
            if (call == executeThrowAtCall) throw IllegalStateException("native execute boom")
            return executeStatuses[minOf(call, executeStatuses.size - 1)]
        }

        override fun getMetaInfo(metaId: Int, modelId: Long): String? = "nnc-test"
    }

    private fun fakeToken(): ValidatedModelCapabilityToken {
        val generation = ModelAvailabilityRegistry.beginProbe()
        ModelAvailabilityRegistry.applyForTest(
            ModelFeature.ExynosUpscale,
            ModelCapabilityObservation(
                ModelCapabilityPublisher.Probe,
                generation,
                ModelCapabilityPhase.Loadable,
                assetPresent = true,
                assetValid = true,
                runtimeAvailable = true,
                contractSupported = true,
                runnerImplemented = true,
            ),
        )
        val result = ModelAvailabilityRegistry.validatedCapabilityToken(ModelFeature.ExynosUpscale)
        assertTrue(result is ModelLoadResult.Ready)
        return (result as ModelLoadResult.Ready).runner
    }

    private fun session(enn: FakeEnn): ExynosUpscaleSession =
        ExynosUpscaleSession(
            context = context,
            native = enn,
            ioDispatcher = Dispatchers.Default,
            preparedModelFileProvider = { _: ModelAssetContract -> ModelLoadResult.Ready(File(context.filesDir, "fake.nnc")) },
        )

    /** Deterministic CHW FP32 source: value at (c,y,x) = c*HW + y*W + x (as float). */
    private fun sourceBytes(w: Int, h: Int): ByteArray {
        val floats = FloatArray(3 * w * h)
        for (c in 0 until 3) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    floats[c * w * h + y * w + x] = (c * w * h + y * w + x).toFloat()
                }
            }
        }
        return floatsToBytes(floats)
    }

    private fun floatsToBytes(floats: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(floats)
        return buf.array()
    }

    private fun bytesToFloats(bytes: ByteArray): FloatArray {
        val f = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(f.remaining()).also { f.get(it) }
    }

    private fun floatAt(bytes: ByteArray, index: Int): Float =
        bytesToFloats(bytes)[index]

    @Test
    fun twoTilePlanRunsEachTileOnceAndAssemblesCoverage() = runBlocking {
        val enn = FakeEnn()
        val session = session(enn)
        try {
            assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
            val orchestrator = TileInferenceOrchestrator(session)
            val result = orchestrator.upscaleRaw(sourceBytes(129, 128), 129, 128, ModelOperationContext(1L, "g1"))
            assertTrue("expected success, got $result", result is TiledUpscaleResult.Success)
            result as TiledUpscaleResult.Success
            assertEquals(2, result.tileCount)
            assertEquals(2, result.completedTiles)
            assertEquals(2, enn.executeCalls.get())
            assertEquals(2, enn.memcpyInCalls.get())
            assertEquals(129 * 4 * 128 * 4 * 3 * 4, result.outputBytes.size)
        } finally {
            session.close()
        }
        assertEquals(1, enn.releaseBufferCalls.get())
        assertEquals(1, enn.closeModelCalls.get())
        assertEquals(1, enn.deinitializeCalls.get())
    }

    @Test
    fun cancellationBetweenTilesPreventsSubsequentTilesAndPublishesNothing() = runBlocking {
        val enn = FakeEnn()
        val session = session(enn)
        try {
            assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
            val orchestrator = TileInferenceOrchestrator(session)

            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var executeSeen = 0
            session.preExecuteCheck = {
                if (executeSeen++ == 0) {
                    reached.complete(Unit)
                    release.await()
                }
            }

            val cancelled = AtomicBoolean(false)
            val ctx = ModelOperationContext(1L, "g1", isCancelled = { cancelled.get() })
            val scope = CoroutineScope(Dispatchers.Default)
            val deferred = scope.async { orchestrator.upscaleRaw(sourceBytes(129, 128), 129, 128, ctx) }
            reached.await()
            cancelled.set(true)
            release.complete(Unit)

            val result = deferred.await()
            assertTrue("expected Cancelled, got $result", result is TiledUpscaleResult.Cancelled)
            assertEquals("only tile 0 must run", 1, enn.executeCalls.get())
            scope.cancel()
        } finally {
            session.close()
        }
    }

    @Test
    fun stalenessBetweenTilesPreventsSubsequentTilesAndPublishesNothing() = runBlocking {
        val enn = FakeEnn()
        val session = session(enn)
        try {
            assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
            val orchestrator = TileInferenceOrchestrator(session)

            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var executeSeen = 0
            session.preExecuteCheck = {
                if (executeSeen++ == 0) {
                    reached.complete(Unit)
                    release.await()
                }
            }

            val stale = AtomicBoolean(false)
            val ctx = ModelOperationContext(1L, "g1", isCurrent = { _, _ -> !stale.get() })
            val scope = CoroutineScope(Dispatchers.Default)
            val deferred = scope.async { orchestrator.upscaleRaw(sourceBytes(129, 128), 129, 128, ctx) }
            reached.await()
            stale.set(true)
            release.complete(Unit)

            val result = deferred.await()
            assertTrue("expected Stale, got $result", result is TiledUpscaleResult.Stale)
            assertEquals("only tile 0 must run", 1, enn.executeCalls.get())
            scope.cancel()
        } finally {
            session.close()
        }
    }

    @Test
    fun executeFailureOnInteriorTileIsTotal() = runBlocking {
        val enn = FakeEnn().apply {
            executeStatuses = listOf(EnnStatus.SUCCESS, EnnStatus.FAILED_TIMEOUT_HW_RECOVERED)
        }
        val session = session(enn)
        try {
            assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
            val orchestrator = TileInferenceOrchestrator(session)
            val result = orchestrator.upscaleRaw(sourceBytes(129, 128), 129, 128, ModelOperationContext(1L, "g1"))
            assertTrue("expected Failure, got $result", result is TiledUpscaleResult.Failure)
            result as TiledUpscaleResult.Failure
            assertEquals(TileFailureReason.ExecuteFailed, result.reason)
            assertEquals(1, result.completedTiles)
            assertEquals(1, result.failedTileIndex)
            assertEquals("second tile must not run", 2, enn.executeCalls.get())
            assertEquals(ModelRunnerLifecycle.Loaded, session.lifecycle)
        } finally {
            session.close()
        }
        assertTrue(enn.closeModelCalls.get() >= 1)
        assertTrue(enn.deinitializeCalls.get() >= 1)
    }

    @Test
    fun h2dFailureOnInteriorTileIsTotal() = runBlocking {
        val enn = FakeEnn().apply { memcpyInStatuses = listOf(EnnStatus.SUCCESS, EnnStatus.IO) }
        val session = session(enn)
        try {
            assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
            val orchestrator = TileInferenceOrchestrator(session)
            val result = orchestrator.upscaleRaw(sourceBytes(257, 128), 257, 128, ModelOperationContext(1L, "g1"))
            assertTrue("expected Failure, got $result", result is TiledUpscaleResult.Failure)
            result as TiledUpscaleResult.Failure
            assertEquals(TileFailureReason.H2dFailed, result.reason)
            assertEquals(1, result.completedTiles)
            // No execute may run for the failed tile.
            assertEquals(1, enn.executeCalls.get())
        } finally {
            session.close()
        }
    }

    @Test
    fun d2hFailureOnInteriorTileIsTotal() = runBlocking {
        val enn = FakeEnn().apply { memcpyOutStatuses = listOf(EnnStatus.SUCCESS, EnnStatus.IO) }
        val session = session(enn)
        try {
            assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
            val orchestrator = TileInferenceOrchestrator(session)
            val result = orchestrator.upscaleRaw(sourceBytes(257, 128), 257, 128, ModelOperationContext(1L, "g1"))
            assertTrue("expected Failure, got $result", result is TiledUpscaleResult.Failure)
            result as TiledUpscaleResult.Failure
            assertEquals(TileFailureReason.D2hFailed, result.reason)
            assertEquals(1, result.completedTiles)
            assertEquals(ModelRunnerLifecycle.Loaded, session.lifecycle)
        } finally {
            session.close()
        }
    }

    @Test
    fun nativeExecuteThrowOnInteriorTileIsTotalAndCapturesStage() = runBlocking {
        val enn = FakeEnn().apply { executeThrowAtCall = 1 }
        val session = session(enn)
        try {
            assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
            val orchestrator = TileInferenceOrchestrator(session)
            val result = orchestrator.upscaleRaw(sourceBytes(257, 128), 257, 128, ModelOperationContext(1L, "g1"))
            assertTrue("expected Failure, got $result", result is TiledUpscaleResult.Failure)
            result as TiledUpscaleResult.Failure
            assertEquals(TileFailureReason.NativeThrew, result.reason)
            assertEquals(1, result.failedTileIndex)
            assertEquals(1, result.completedTiles)
            // Tile 0 succeeds; tile 1 enters native execute then throws; tiles 2+ never run.
            assertEquals(2, enn.executeCalls.get())
            assertEquals("throwing stage must be captured as execute", "execute", session.lastRunDiagnostics.throwableStage)
            assertEquals(ModelRunnerLifecycle.Loaded, session.lifecycle)
        } finally {
            session.close()
        }
        assertTrue(enn.closeModelCalls.get() >= 1)
        assertTrue(enn.deinitializeCalls.get() >= 1)
    }

    @Test
    fun assemblyFailureInvalidatesSinkAndPublishesNothing() = runBlocking {
        val enn = FakeEnn()
        val session = session(enn)
        try {
            assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
            val orchestrator =
                TileInferenceOrchestrator(
                    session,
                    sinkFactory = { w, h -> ThrowingSink(w, h) },
                )
            val result = orchestrator.upscaleRaw(sourceBytes(129, 128), 129, 128, ModelOperationContext(1L, "g1"))
            assertTrue("expected Failure, got $result", result is TiledUpscaleResult.Failure)
            result as TiledUpscaleResult.Failure
            assertEquals(TileFailureReason.AssemblyFailed, result.reason)
        } finally {
            session.close()
        }
    }

    private class ThrowingSink(outputWidth: Int, outputHeight: Int) : TileOutputSink {
        override val outputWidth = outputWidth
        override val outputHeight = outputHeight

        override fun writeTile(outputCrop: Rect, dest: Rect, tileOutput: ByteArray) {
            throw IllegalStateException("sink exploded")
        }

        override fun invalidate() = Unit
        override fun finish(): ByteArray = throw IllegalStateException("not finished")
    }

    @Test
    fun finalCloseRemainsTotalAfterFailure() = runBlocking {
        val enn = FakeEnn().apply { executeStatuses = listOf(EnnStatus.SUCCESS, EnnStatus.FAILED) }
        val session = session(enn)
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        val orchestrator = TileInferenceOrchestrator(session)
        val result = orchestrator.upscaleRaw(sourceBytes(129, 128), 129, 128, ModelOperationContext(1L, "g1"))
        assertTrue(result is TiledUpscaleResult.Failure)
        session.close()
        assertEquals(1, enn.releaseBufferCalls.get())
        assertEquals(1, enn.closeModelCalls.get())
        assertEquals(1, enn.deinitializeCalls.get())
        assertEquals(ModelRunnerLifecycle.Unloaded, session.lifecycle)
    }

    @Test
    fun extractChwSubTileIsByteExact() {
        val w = 200
        val h = 130
        val srcFloats = FloatArray(3 * w * h)
        for (c in 0 until 3) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    srcFloats[c * w * h + y * w + x] = (c * 1_000_000 + y * 1000 + x).toFloat()
                }
            }
        }
        val src = floatsToBytes(srcFloats)
        val sx = 17
        val sy = 2
        val tile = TileInferenceOrchestrator.extractChwSubTile(src, w, h, sx, sy)
        assertEquals(3 * 128 * 128 * 4, tile.size)
        val tileFloats = bytesToFloats(tile)
        for (c in 0 until 3) {
            for (y in 0 until 128) {
                for (x in 0 until 128) {
                    val expected = (c * 1_000_000 + (sy + y) * 1000 + (sx + x)).toFloat()
                    assertEquals(expected, tileFloats[c * 128 * 128 + y * 128 + x])
                }
            }
        }
    }

    @Test
    fun boundedSinkPlacesCropAtDestExactly() {
        val sink = BoundedMemoryTileSink(1024, 512)
        val tileBytes = ByteArray(3 * 512 * 512 * 4)
        val tileFloats = FloatArray(3 * 512 * 512)
        for (i in tileFloats.indices) tileFloats[i] = i.toFloat()
        val tile = floatsToBytes(tileFloats)
        val crop = Rect(10, 20, 100, 50)
        val dest = Rect(522, 20, 100, 50)
        sink.writeTile(crop, dest, tile)
        val out = sink.finish()
        assertEquals(3 * 1024 * 512 * 4, out.size)
        val outFloats = bytesToFloats(out)
        for (c in 0 until 3) {
            for (ry in 0 until crop.height) {
                for (rx in 0 until crop.width) {
                    val srcIdx = c * 512 * 512 + (crop.top + ry) * 512 + (crop.left + rx)
                    val dstIdx = c * 512 * 1024 + (dest.top + ry) * 1024 + (dest.left + rx)
                    assertEquals("channel $c row $ry col $rx", tileFloats[srcIdx], outFloats[dstIdx])
                }
            }
        }
    }

    @Test
    fun unsupportedAndSizeMismatchAreStructured() = runBlocking {
        val enn = FakeEnn()
        val session = session(enn)
        try {
            assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
            val orchestrator = TileInferenceOrchestrator(session)
            val unsupported = orchestrator.upscaleRaw(sourceBytes(64, 64), 64, 64, ModelOperationContext(1L, "g1"))
            assertTrue(unsupported is TiledUpscaleResult.UnsupportedSourceSize)
            val mismatch = orchestrator.upscaleRaw(ByteArray(100), 129, 128, ModelOperationContext(1L, "g1"))
            assertTrue(mismatch is TiledUpscaleResult.SourceSizeMismatch)
            assertEquals(0, enn.executeCalls.get())
        } finally {
            session.close()
        }
    }
}