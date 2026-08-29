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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Phase N5 — bounded-memory file-backed production pipeline: reusable input/output tile
 * buffers, storage admission before tile 0, bounded progress state, and the full
 * cancellation/staleness/failure matrix with atomic artifact lifetime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TileFileBackedUpscalerTest {

    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    private lateinit var workDir: File
    private var pressureOverride: AutoCloseable? = null

    @Before
    fun setUp() {
        workDir = File(context.filesDir, "n5_pipeline_${System.nanoTime()}").apply { mkdirs() }
        ModelAvailabilityRegistry.resetForTest()
        installPressure { Long.MAX_VALUE }
    }

    @After
    fun tearDown() {
        ModelAvailabilityRegistry.resetForTest()
        workDir.deleteRecursively()
        pressureOverride?.close()
        pressureOverride = null
    }

    private fun installPressure(capacity: (File) -> Long?) {
        pressureOverride?.close()
        pressureOverride =
            StoragePressure.installForTest(
                StoragePressureController(
                    capacity = { f -> capacity(f) },
                    reserveBytes = 0L,
                    pressureSweep = { TransientMaintenanceReport.EMPTY },
                ),
            )
    }

    // --- fake ENN + session -------------------------------------------------

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

        var memcpyInStatuses = listOf(EnnStatus.SUCCESS)
        var executeStatuses = listOf(EnnStatus.SUCCESS)
        var memcpyOutStatuses = listOf(EnnStatus.SUCCESS)

        val memcpyInCalls = AtomicInteger()
        val executeCalls = AtomicInteger()
        val memcpyOutCalls = AtomicInteger()
        val releaseBufferCalls = AtomicInteger()
        val closeModelCalls = AtomicInteger()
        val deinitializeCalls = AtomicInteger()

        var executeThrowAtCall: Int = -1

        /** Records the identity of each D2H output buffer (reuse proof). */
        val outputBufferIdentity = mutableListOf<Int>()
        val outputBufferSizes = mutableListOf<Int>()

        var outputFiller: ((Int, ByteArray) -> Unit)? = null

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
            outputBufferIdentity += System.identityHashCode(out)
            outputBufferSizes += out.size
            outputFiller?.invoke(call, out)
            return memcpyOutStatuses[minOf(call, memcpyOutStatuses.size - 1)]
        }

        override fun execute(modelId: Long): Int {
            val call = executeCalls.getAndIncrement()
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
            preparedModelFileProvider =
                { _: ModelAssetContract -> ModelLoadResult.Ready(File(context.filesDir, "fake.nnc")) },
        )

    private suspend fun loadedSession(enn: FakeEnn): ExynosUpscaleSession {
        val s = session(enn)
        assertTrue(s.load(fakeToken()) is ModelLoadResult.Ready)
        return s
    }

    private fun sourceBytes(w: Int, h: Int): ByteArray {
        val floats = FloatArray(3 * w * h)
        for (c in 0 until 3) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    floats[c * w * h + y * w + x] = ((c * w * h + y * w + x) % 256) / 255f
                }
            }
        }
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(floats)
        return buf.array()
    }

    private fun fillConstant(output: ByteArray) {
        val floats = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val n = floats.remaining()
        for (i in 0 until n) floats.put(i, 0.5f)
    }

    private fun target(name: String) = File(workDir, name)

    // --- success / reuse ----------------------------------------------------

    @Test
    fun successReusesSingleInputAndOutputBufferAcrossAllTiles() = runBlocking {
        val enn = FakeEnn().apply { outputFiller = { _, out -> fillConstant(out) } }
        val s = loadedSession(enn)
        try {
            val w = 257
            val h = 128
            val plan = (TilePlanner.plan(w, h) as TilePlanResult.Planned).plan
            assertTrue("need >1 tiles", plan.tiles.size > 1)

            val pipeline = TileFileBackedUpscaler(s, context)
            val source = RecordingSource(ByteArrayTileInputSource(sourceBytes(w, h), w, h))
            val observed = mutableListOf<TileRunRecord>()

            val result = pipeline.upscaleToFile(source, target("out.rgb8"), ModelOperationContext(1L, "g1"), observer = { observed += it })

            assertTrue("expected Success, got $result", result is FileBackedUpscaleResult.Success)
            result as FileBackedUpscaleResult.Success
            assertEquals(plan.tiles.size, result.completedTiles)
            assertEquals(plan.tiles.size, result.tileCount)
            assertEquals(result.tileCount, observed.size)
            assertEquals(enn.executeCalls.get(), plan.tiles.size)

            // Artifact is file-backed, correct geometry, no in-memory bytes exposed.
            assertEquals(w * 4, result.artifact.width)
            assertEquals(h * 4, result.artifact.height)
            assertEquals(3L * w * 4 * h * 4, result.artifact.byteCount)
            assertEquals(result.artifact.byteCount, result.artifact.file.length())
            assertTrue(result.artifact.file.exists())

            // Reuse: one input buffer identity observed across all tile fills.
            assertEquals(1, source.intoRefs.distinct().size)
            assertEquals(ExynosUpscaleSession.INPUT_BYTES, source.intoSizes.first())
            // Reuse: one output buffer identity across all D2H copies.
            assertEquals(1, enn.outputBufferIdentity.distinct().size)
            assertEquals(ExynosUpscaleSession.OUTPUT_BYTES, enn.outputBufferSizes.first())
        } finally {
            s.close()
        }
        assertEquals(1, enn.releaseBufferCalls.get())
        assertEquals(1, enn.closeModelCalls.get())
        assertEquals(1, enn.deinitializeCalls.get())
    }

    @Test
    fun sessionReusesCallerOutputBufferAndRejectsWrongSize() = runBlocking {
        val enn = FakeEnn().apply { outputFiller = { _, out -> fillConstant(out) } }
        val s = loadedSession(enn)
        try {
            val input = ByteArray(ExynosUpscaleSession.INPUT_BYTES)
            val output = ByteArray(ExynosUpscaleSession.OUTPUT_BYTES)
            val ctx = ModelOperationContext(1L, "g1")
            repeat(3) { i ->
                val r = s.runRawFp32ChwInto(input, output, ctx, "reuse-$i")
                assertTrue("run $i", r.succeeded)
            }
            assertEquals(1, enn.outputBufferIdentity.distinct().size)

            val tooSmall = s.runRawFp32ChwInto(input, ByteArray(10), ctx, "bad")
            assertFalse(tooSmall.succeeded)
            assertEquals(3, enn.executeCalls.get())
        } finally {
            s.close()
        }
    }

    // --- storage admission --------------------------------------------------

    @Test
    fun insufficientStorageBeforeTileZeroExecutesNoTilesAndLeavesNoArtifact() = runBlocking {
        installPressure { 0L }
        val enn = FakeEnn()
        val s = loadedSession(enn)
        try {
            val pipeline = TileFileBackedUpscaler(s, context)
            val result =
                pipeline.upscaleToFile(
                    ByteArrayTileInputSource(sourceBytes(129, 128), 129, 128),
                    target("denied.rgb8"),
                    ModelOperationContext(1L, "g1"),
                )
            assertTrue("expected StorageInsufficient, got $result", result is FileBackedUpscaleResult.StorageInsufficient)
            assertEquals(0, enn.executeCalls.get())
            assertFalse(target("denied.rgb8").exists())
            // No staging remainder in the work dir.
            assertEquals(0, workDir.list()?.size ?: 0)
        } finally {
            s.close()
        }
    }

    // --- cancellation / staleness ------------------------------------------

    @Test
    fun cancellationOnInteriorTileSettlesStagingAndPublishesNothing() = runBlocking {
        val enn = FakeEnn().apply { outputFiller = { _, out -> fillConstant(out) } }
        val s = loadedSession(enn)
        try {
            val pipeline = TileFileBackedUpscaler(s, context)
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var seen = 0
            s.preExecuteCheck = {
                if (seen++ == 0) {
                    reached.complete(Unit)
                    release.await()
                }
            }
            val cancelled = AtomicBoolean(false)
            val ctx = ModelOperationContext(1L, "g1", isCancelled = { cancelled.get() })
            val scope = CoroutineScope(Dispatchers.Default)
            val deferred = scope.async {
                pipeline.upscaleToFile(
                    ByteArrayTileInputSource(sourceBytes(257, 128), 257, 128),
                    target("cancel.rgb8"),
                    ctx,
                )
            }
            reached.await()
            cancelled.set(true)
            release.complete(Unit)
            val result = deferred.await()
            assertTrue("expected Cancelled, got $result", result is FileBackedUpscaleResult.Cancelled)
            result as FileBackedUpscaleResult.Cancelled
            assertEquals(1, result.completedTiles)
            assertEquals(1, enn.executeCalls.get())
            assertFalse(target("cancel.rgb8").exists())
            assertEquals(0, workDir.list()?.size ?: 0)
            scope.cancel()
        } finally {
            s.close()
        }
    }

    @Test
    fun stalenessOnInteriorTileSettlesStagingAndPublishesNothing() = runBlocking {
        val enn = FakeEnn().apply { outputFiller = { _, out -> fillConstant(out) } }
        val s = loadedSession(enn)
        try {
            val pipeline = TileFileBackedUpscaler(s, context)
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var seen = 0
            s.preExecuteCheck = {
                if (seen++ == 0) {
                    reached.complete(Unit)
                    release.await()
                }
            }
            val stale = AtomicBoolean(false)
            val ctx = ModelOperationContext(1L, "g1", isCurrent = { _, _ -> !stale.get() })
            val scope = CoroutineScope(Dispatchers.Default)
            val deferred = scope.async {
                pipeline.upscaleToFile(
                    ByteArrayTileInputSource(sourceBytes(257, 128), 257, 128),
                    target("stale.rgb8"),
                    ctx,
                )
            }
            reached.await()
            stale.set(true)
            release.complete(Unit)
            val result = deferred.await()
            assertTrue("expected Stale, got $result", result is FileBackedUpscaleResult.Stale)
            assertEquals(1, enn.executeCalls.get())
            assertFalse(target("stale.rgb8").exists())
            assertEquals(0, workDir.list()?.size ?: 0)
            scope.cancel()
        } finally {
            s.close()
        }
    }

    @Test
    fun cancellationAfterLastTileBeforePublicationPublishesNothing() = runBlocking {
        val enn = FakeEnn().apply { outputFiller = { _, out -> fillConstant(out) } }
        val s = loadedSession(enn)
        try {
            val pipeline = TileFileBackedUpscaler(s, context)
            val cancelled = AtomicBoolean(false)
            val total = (TilePlanner.plan(129, 128) as TilePlanResult.Planned).plan.tiles.size
            val ctx = ModelOperationContext(1L, "g1", isCancelled = { cancelled.get() })
            val observer = TileRunObserver { record -> if (record.index == total - 1) cancelled.set(true) }
            val result =
                pipeline.upscaleToFile(
                    ByteArrayTileInputSource(sourceBytes(129, 128), 129, 128),
                    target("lastcancel.rgb8"),
                    ctx,
                    observer = observer,
                )
            assertTrue("expected Cancelled, got $result", result is FileBackedUpscaleResult.Cancelled)
            result as FileBackedUpscaleResult.Cancelled
            assertEquals(total, result.completedTiles)
            assertFalse(target("lastcancel.rgb8").exists())
            assertEquals(0, workDir.list()?.size ?: 0)
        } finally {
            s.close()
        }
    }

    // --- failure matrix -----------------------------------------------------

    @Test
    fun sourceReadFailureReturnsStructuredFailureAndSettlesStaging() = runBlocking {
        val enn = FakeEnn()
        val s = loadedSession(enn)
        try {
            val pipeline = TileFileBackedUpscaler(s, context)
            val result =
                pipeline.upscaleToFile(
                    ThrowingSource(257, 128),
                    target("srcerr.rgb8"),
                    ModelOperationContext(1L, "g1"),
                )
            assertTrue("expected Failure, got $result", result is FileBackedUpscaleResult.Failure)
            result as FileBackedUpscaleResult.Failure
            assertEquals(TileFailureReason.SourceReadFailed, result.reason)
            assertEquals(0, enn.executeCalls.get())
            assertFalse(target("srcerr.rgb8").exists())
            assertEquals(0, workDir.list()?.size ?: 0)
            assertEquals(ModelRunnerLifecycle.Loaded, s.lifecycle)
        } finally {
            s.close()
        }
    }

    @Test
    fun executeFailureOnInteriorTileIsTotalAndSessionRemainsLoaded() = runBlocking {
        val enn = FakeEnn().apply {
            executeStatuses = listOf(EnnStatus.SUCCESS, EnnStatus.FAILED_TIMEOUT_HW_RECOVERED)
            outputFiller = { _, out -> fillConstant(out) }
        }
        val s = loadedSession(enn)
        try {
            val pipeline = TileFileBackedUpscaler(s, context)
            val result =
                pipeline.upscaleToFile(
                    ByteArrayTileInputSource(sourceBytes(257, 128), 257, 128),
                    target("execfail.rgb8"),
                    ModelOperationContext(1L, "g1"),
                )
            assertTrue("expected Failure, got $result", result is FileBackedUpscaleResult.Failure)
            result as FileBackedUpscaleResult.Failure
            assertEquals(TileFailureReason.ExecuteFailed, result.reason)
            assertEquals(1, result.completedTiles)
            assertEquals(1, result.failedTileIndex)
            assertEquals(ModelRunnerLifecycle.Loaded, s.lifecycle)
            assertFalse(target("execfail.rgb8").exists())
            assertEquals(0, workDir.list()?.size ?: 0)
        } finally {
            s.close()
        }
        assertTrue(enn.closeModelCalls.get() >= 1)
        assertTrue(enn.deinitializeCalls.get() >= 1)
    }

    @Test
    fun h2dD2hAndNativeThrowFailureMatrix() = runBlocking {
        val h2d = FakeEnn().apply { memcpyInStatuses = listOf(EnnStatus.SUCCESS, EnnStatus.IO) }
        val d2h = FakeEnn().apply { memcpyOutStatuses = listOf(EnnStatus.SUCCESS, EnnStatus.IO) }
        val thrown = FakeEnn().apply { executeThrowAtCall = 1 }
        run {
            val s = loadedSession(h2d)
            try {
                val r = TileFileBackedUpscaler(s, context).upscaleToFile(
                    ByteArrayTileInputSource(sourceBytes(257, 128), 257, 128),
                    target("h2d.rgb8"),
                    ModelOperationContext(1L, "g1"),
                )
                assertTrue(r is FileBackedUpscaleResult.Failure)
                assertEquals(TileFailureReason.H2dFailed, (r as FileBackedUpscaleResult.Failure).reason)
            } finally { s.close() }
        }
        run {
            val s = loadedSession(d2h)
            try {
                val r = TileFileBackedUpscaler(s, context).upscaleToFile(
                    ByteArrayTileInputSource(sourceBytes(257, 128), 257, 128),
                    target("d2h.rgb8"),
                    ModelOperationContext(1L, "g1"),
                )
                assertTrue(r is FileBackedUpscaleResult.Failure)
                assertEquals(TileFailureReason.D2hFailed, (r as FileBackedUpscaleResult.Failure).reason)
            } finally { s.close() }
        }
        run {
            val s = loadedSession(thrown)
            try {
                val r = TileFileBackedUpscaler(s, context).upscaleToFile(
                    ByteArrayTileInputSource(sourceBytes(257, 128), 257, 128),
                    target("thrown.rgb8"),
                    ModelOperationContext(1L, "g1"),
                )
                assertTrue(r is FileBackedUpscaleResult.Failure)
                assertEquals(TileFailureReason.NativeThrew, (r as FileBackedUpscaleResult.Failure).reason)
                assertEquals(1, r.failedTileIndex)
            } finally { s.close() }
        }
    }

    @Test
    fun sinkWriteFailureSettlesStagingAndPublishesNothing() = runBlocking {
        val enn = FakeEnn().apply { outputFiller = { _, out -> fillConstant(out) } }
        val s = loadedSession(enn)
        try {
            val failingIo =
                object : FileBackedSinkIo {
                    val deleted = mutableListOf<File>()
                    override fun createTruncate(file: File, length: Long) = Unit
                    override fun write(position: Long, bytes: ByteArray, offset: Int, length: Int): Int =
                        throw java.io.IOException("disk full")
                    override fun force() = Unit
                    override fun close() = Unit
                    override fun length(): Long = 0L
                    override fun rename(from: File, to: File): Boolean = false
                    override fun delete(file: File): Boolean {
                        deleted += file
                        return true
                    }
                }
            val pipeline = TileFileBackedUpscaler(s, context, sinkIoFactory = { failingIo })
            val result =
                pipeline.upscaleToFile(
                    ByteArrayTileInputSource(sourceBytes(129, 128), 129, 128),
                    target("writefail.rgb8"),
                    ModelOperationContext(1L, "g1"),
                )
            assertTrue("expected Failure, got $result", result is FileBackedUpscaleResult.Failure)
            result as FileBackedUpscaleResult.Failure
            assertEquals(TileFailureReason.AssemblyFailed, result.reason)
            assertEquals(ModelRunnerLifecycle.Loaded, s.lifecycle)
            assertFalse(target("writefail.rgb8").exists())
            assertEquals(1, failingIo.deleted.size)
        } finally {
            s.close()
        }
    }

    @Test
    fun renameFailureReturnsPublishFailureAndPublishesNothing() = runBlocking {
        val enn = FakeEnn().apply { outputFiller = { _, out -> fillConstant(out) } }
        val s = loadedSession(enn)
        try {
            val failingIo =
                object : FileBackedSinkIo {
                    var length = 0L
                    override fun createTruncate(file: File, length: Long) { this.length = length }
                    override fun write(position: Long, bytes: ByteArray, offset: Int, length: Int) = length
                    override fun force() = Unit
                    override fun close() = Unit
                    override fun length(): Long = length
                    override fun rename(from: File, to: File): Boolean = false
                    override fun delete(file: File): Boolean = true
                }
            val pipeline =
                TileFileBackedUpscaler(
                    s,
                    context,
                    operationTokenFactory = { "t" },
                    sinkIoFactory = { failingIo },
                )
            val result =
                pipeline.upscaleToFile(
                    ByteArrayTileInputSource(sourceBytes(129, 128), 129, 128),
                    target("renamefail.rgb8"),
                    ModelOperationContext(1L, "g1"),
                )
            assertTrue("expected Failure, got $result", result is FileBackedUpscaleResult.Failure)
            result as FileBackedUpscaleResult.Failure
            assertEquals(TileFailureReason.ArtifactPublishFailed, result.reason)
            assertEquals(2, result.completedTiles)
            assertFalse(target("renamefail.rgb8").exists())
        } finally {
            s.close()
        }
    }

    @Test
    fun closeRemainsTotalAfterFailure() = runBlocking {
        val enn = FakeEnn().apply {
            executeStatuses = listOf(EnnStatus.SUCCESS, EnnStatus.FAILED)
            outputFiller = { _, out -> fillConstant(out) }
        }
        val s = loadedSession(enn)
        val r = TileFileBackedUpscaler(s, context).upscaleToFile(
            ByteArrayTileInputSource(sourceBytes(257, 128), 257, 128),
            target("close.rgb8"),
            ModelOperationContext(1L, "g1"),
        )
        assertTrue(r is FileBackedUpscaleResult.Failure)
        s.close()
        assertEquals(1, enn.releaseBufferCalls.get())
        assertEquals(1, enn.closeModelCalls.get())
        assertEquals(1, enn.deinitializeCalls.get())
        assertEquals(ModelRunnerLifecycle.Unloaded, s.lifecycle)
    }

    // --- helpers ------------------------------------------------------------

    private class RecordingSource(private val delegate: TileInputSource) : TileInputSource {
        override val sourceWidth = delegate.sourceWidth
        override val sourceHeight = delegate.sourceHeight
        val intoRefs = mutableListOf<ByteArray>()
        val intoSizes = mutableListOf<Int>()

        override suspend fun fillChwTile(sx: Int, sy: Int, into: ByteArray) {
            intoRefs += into
            intoSizes += into.size
            delegate.fillChwTile(sx, sy, into)
        }
    }

    private class ThrowingSource(
        override val sourceWidth: Int,
        override val sourceHeight: Int,
    ) : TileInputSource {
        override suspend fun fillChwTile(sx: Int, sy: Int, into: ByteArray) {
            throw java.io.IOException("source read failed")
        }
    }
}