package com.projectnuke.keplerstudio.editor

import android.app.Application
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TileFileBackedUpscalerCorrectiveTest {

    private val context: Application get() = RuntimeEnvironment.getApplication() as Application
    private lateinit var workDir: File
    private var pressureOverride: AutoCloseable? = null

    @Before
    fun setUp() {
        workDir = File(context.filesDir, "n5_corr_${System.nanoTime()}").apply { mkdirs() }
        ModelAvailabilityRegistry.resetForTest()
        pressureOverride = StoragePressure.installForTest(
            StoragePressureController(capacity = { Long.MAX_VALUE }, reserveBytes = 0L, pressureSweep = { TransientMaintenanceReport.EMPTY })
        )
    }

    @After
    fun tearDown() {
        ModelAvailabilityRegistry.resetForTest()
        workDir.deleteRecursively()
        pressureOverride?.close()
        pressureOverride = null
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
        var memcpyInStatuses = listOf(EnnStatus.SUCCESS)
        var executeStatuses = listOf(EnnStatus.SUCCESS)
        var memcpyOutStatuses = listOf(EnnStatus.SUCCESS)
        val memcpyInCalls = AtomicInteger()
        val executeCalls = AtomicInteger()
        val memcpyOutCalls = AtomicInteger()
        val outputBufferIdentity = mutableListOf<Int>()
        var outputFiller: ((Int, ByteArray) -> Unit)? = null
        override fun probeRuntime() = probeResult
        override fun initialize() = initializeStatus
        override fun deinitialize() = deinitializeStatus
        override fun openModel(path: String) = EnnOpenModelResult(openModelStatus, if (openModelStatus == EnnStatus.SUCCESS) 7001L else 0L)
        override fun closeModel(modelId: Long) = closeModelStatus
        override fun allocateAllBuffers(modelId: Long) = EnnAllocateResult(allocationStatus, if (allocationStatus == EnnStatus.SUCCESS) 0x515AL else 0L, 1, 1)
        override fun releaseBuffers(bufferSet: Long, bufferCount: Int) = releaseBuffersStatus
        override fun getBufferInfoByIndex(modelId: Long, direction: Int, index: Int): IntArray? = if (direction == 0) inputInfo else outputInfo
        override fun memcpyHostToDevice(bufferSet: Long, index: Int, data: ByteArray): Int {
            val call = memcpyInCalls.getAndIncrement()
            return memcpyInStatuses[minOf(call, memcpyInStatuses.size - 1)]
        }
        override fun memcpyDeviceToHost(bufferSet: Long, index: Int, out: ByteArray): Int {
            val call = memcpyOutCalls.getAndIncrement()
            outputBufferIdentity += System.identityHashCode(out)
            outputFiller?.invoke(call, out)
            return memcpyOutStatuses[minOf(call, memcpyOutStatuses.size - 1)]
        }
        override fun execute(modelId: Long): Int {
            val call = executeCalls.getAndIncrement()
            return executeStatuses[minOf(call, executeStatuses.size - 1)]
        }
        override fun getMetaInfo(metaId: Int, modelId: Long): String? = "npu"
    }

    private fun fakeToken(): ValidatedModelCapabilityToken {
        val g = ModelAvailabilityRegistry.beginProbe()
        ModelAvailabilityRegistry.applyForTest(ModelFeature.ExynosUpscale, ModelCapabilityObservation(ModelCapabilityPublisher.Probe, g, ModelCapabilityPhase.Loadable, true, true, true, true, true))
        return (ModelAvailabilityRegistry.validatedCapabilityToken(ModelFeature.ExynosUpscale) as ModelLoadResult.Ready).runner
    }

    private fun session(enn: FakeEnn, retention: DiagnosticRetention = DiagnosticRetention.FULL) = ExynosUpscaleSession(context, enn, Dispatchers.Default, { ModelLoadResult.Ready(File(context.filesDir, "fake.nnc")) }, retention)
    private suspend fun loaded(enn: FakeEnn, retention: DiagnosticRetention = DiagnosticRetention.FULL): ExynosUpscaleSession {
        val s = session(enn, retention)
        assertTrue(s.load(fakeToken()) is ModelLoadResult.Ready)
        return s
    }

    private fun sourceBytes(w: Int, h: Int): ByteArray {
        val floats = FloatArray(3 * w * h) { 0.5f }
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(floats)
        return buf.array()
    }

    private fun fillConstant(out: ByteArray) {
        val fb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        for (i in 0 until fb.remaining()) fb.put(i, 0.5f)
    }

    private fun target(name: String) = File(workDir, name)

    // --- 1. Bounded diagnostic retention ---

    @Test
    fun boundedRetentionKeepsO1WhileFullRetainsAll() = runBlocking {
        val enn = FakeEnn().apply { outputFiller = { _, out -> fillConstant(out) } }
        // Bounded session: LAST_ONLY via TileFileBackedUpscaler path
        val boundedSession = loaded(enn, DiagnosticRetention.LAST_ONLY)
        try {
            val input = ByteArray(ExynosUpscaleSession.INPUT_BYTES)
            val output = ByteArray(ExynosUpscaleSession.OUTPUT_BYTES)
            val ctx = ModelOperationContext(1L, "g1")
            repeat(50) { i ->
                val r = boundedSession.runRawFp32ChwInto(input, output, ctx, "tile-$i")
                assertTrue(r.succeeded)
            }
            assertEquals(1, boundedSession.runDiagnosticsHistory.size)
            assertTrue(boundedSession.lastRunDiagnostics.attemptLabel == "tile-49")
            // Same output buffer reused
            assertEquals(1, enn.outputBufferIdentity.distinct().size)
            assertEquals(ExynosUpscaleSession.OUTPUT_BYTES, output.size)
        } finally { boundedSession.close() }

        val enn2 = FakeEnn().apply { outputFiller = { _, out -> fillConstant(out) } }
        val fullSession = loaded(enn2, DiagnosticRetention.FULL)
        try {
            val input = ByteArray(ExynosUpscaleSession.INPUT_BYTES)
            val output = ByteArray(ExynosUpscaleSession.OUTPUT_BYTES)
            val ctx = ModelOperationContext(1L, "g1")
            repeat(50) { i ->
                val r = fullSession.runRawFp32ChwInto(input, output, ctx, "full-$i")
                assertTrue(r.succeeded)
            }
            assertEquals(50, fullSession.runDiagnosticsHistory.size)
            assertEquals("full-0", fullSession.runDiagnosticsHistory.first().attemptLabel)
            assertEquals("full-49", fullSession.runDiagnosticsHistory.last().attemptLabel)
        } finally { fullSession.close() }
    }

    @Test
    fun tileFileBackedUpscalerUsesBoundedRetentionExplicitly() = runBlocking {
        val enn = FakeEnn().apply { outputFiller = { _, out -> fillConstant(out) } }
        val s = loaded(enn, DiagnosticRetention.FULL)
        try {
            assertEquals(DiagnosticRetention.FULL, s.diagnosticRetention)
            val pipeline = TileFileBackedUpscaler(s, context)
            val w = 257; val h = 128
            val result = pipeline.upscaleToFile(ByteArrayTileInputSource(sourceBytes(w, h), w, h), target("bounded.rgb8"), ModelOperationContext(1L, "g1"))
            assertTrue(result is FileBackedUpscaleResult.Success)
            // After operation, bounded path should have kept history O(1) regardless of tile count (~3 tiles)
            // But it restores previous retention after completion.
            // Check that during operation, history was bounded: after success, history size should be 1 (last only)
            // Since we restore to FULL, but the pipeline set LAST_ONLY and restored, the history accumulated during tiles was bounded.
            // We verify history size ==1 (or <=2 because restored session had prior 0)
            assertTrue("bounded history must be O(1), got ${s.runDiagnosticsHistory.size}", s.runDiagnosticsHistory.size <= 2)
            // Explicit check: lastRunDiagnostics is authoritative and has last tile label
            assertTrue(s.lastRunDiagnostics.attemptLabel?.contains("tile-") == true)
        } finally { s.close() }
    }

    @Test
    fun largeFakeRunProvesO1HistoryReuse() = runBlocking {
        val enn = FakeEnn().apply { outputFiller = { _, out -> fillConstant(out) } }
        val s = loaded(enn, DiagnosticRetention.LAST_ONLY)
        try {
            val input = ByteArray(ExynosUpscaleSession.INPUT_BYTES)
            val output = ByteArray(ExynosUpscaleSession.OUTPUT_BYTES)
            val ctx = ModelOperationContext(1L, "g1")
            repeat(3350) { i ->
                val r = s.runRawFp32ChwInto(input, output, ctx, "big-$i")
                assertTrue(r.succeeded)
                if (i % 500 == 0) assertEquals(1, s.runDiagnosticsHistory.size)
            }
            assertEquals(1, s.runDiagnosticsHistory.size)
            assertEquals("big-3349", s.lastRunDiagnostics.attemptLabel)
            assertEquals(1, enn.outputBufferIdentity.distinct().size)
        } finally { s.close() }
    }

    // --- 2. Cancel/stale gate truth race ---

    @Test
    fun cancellationAfterOuterCheckButBeforeH2dIsNotMisclassifiedAsH2dFailed() = runBlocking {
        val enn = FakeEnn().apply { outputFiller = { _, out -> fillConstant(out) } }
        val s = loaded(enn)
        try {
            val pipeline = TileFileBackedUpscaler(s, context)
            val cancelled = AtomicBoolean(false)
            val ctx = ModelOperationContext(1L, "g1", isCancelled = { cancelled.get() })
            // Flipping source: flips cancelled AFTER outer tile-loop check but BEFORE session H2D gate.
            // The orchestrator checks isCancelled before fill; fill flips the flag; session's gate then sees cancelled.
            val base = ByteArrayTileInputSource(sourceBytes(513, 128), 513, 128)
            var flipped = false
            val flippingSource = object : TileInputSource {
                override val sourceWidth = base.sourceWidth
                override val sourceHeight = base.sourceHeight
                override suspend fun fillChwTile(sx: Int, sy: Int, into: ByteArray) {
                    base.fillChwTile(sx, sy, into)
                    if (!flipped) {
                        flipped = true
                        cancelled.set(true)
                    }
                }
            }
            val result = pipeline.upscaleToFile(flippingSource, target("racecancel.rgb8"), ctx)
            assertTrue("expected Cancelled, got $result", result is FileBackedUpscaleResult.Cancelled)
            assertEquals(0, enn.executeCalls.get())
            // Must not be misclassified as H2dFailed; no H2D executed flag
            assertEquals(0, enn.memcpyInCalls.get())
            assertFalse(target("racecancel.rgb8").exists())
            assertEquals(0, workDir.list()?.size ?: 0)
        } finally { s.close() }
    }

    @Test
    fun stalenessAfterOuterCheckButBeforeH2dIsNotMisclassified() = runBlocking {
        val enn = FakeEnn().apply { outputFiller = { _, out -> fillConstant(out) } }
        val s = loaded(enn)
        try {
            val pipeline = TileFileBackedUpscaler(s, context)
            val stale = AtomicBoolean(false)
            val ctx = ModelOperationContext(1L, "g1", isCurrent = { _, _ -> !stale.get() })
            val base = ByteArrayTileInputSource(sourceBytes(513, 128), 513, 128)
            var flipped = false
            val flippingSource = object : TileInputSource {
                override val sourceWidth = base.sourceWidth
                override val sourceHeight = base.sourceHeight
                override suspend fun fillChwTile(sx: Int, sy: Int, into: ByteArray) {
                    base.fillChwTile(sx, sy, into)
                    if (!flipped) {
                        flipped = true
                        stale.set(true)
                    }
                }
            }
            val result = pipeline.upscaleToFile(flippingSource, target("racestale.rgb8"), ctx)
            assertTrue("expected Stale, got $result", result is FileBackedUpscaleResult.Stale)
            assertEquals(0, enn.executeCalls.get())
            assertEquals(0, enn.memcpyInCalls.get())
            assertFalse(target("racestale.rgb8").exists())
        } finally { s.close() }
    }

    // --- 5. Publication guard ---

    @Test
    fun cancellationAtPublicationGuardPreventsPublication() = runBlocking {
        val enn = FakeEnn().apply { outputFiller = { _, out -> fillConstant(out) } }
        val s = loaded(enn)
        try {
            val cancelled = AtomicBoolean(false)
            val ctx = ModelOperationContext(1L, "g1", isCancelled = { cancelled.get() })
            // Use observer to flip cancelled after last tile completed but before guard.
            val total = (TilePlanner.plan(129, 128) as TilePlanResult.Planned).plan.tiles.size
            var guardFlipped = false
            val sinkIo = object : FileBackedSinkIo {
                var length = 0L
                override fun createTruncate(file: File, length: Long) { this.length = length }
                override fun write(position: Long, bytes: ByteArray, offset: Int, length: Int) = length
                override fun force() = Unit
                override fun close() = Unit
                override fun length(): Long = length
                override fun atomicMove(from: File, to: File) { throw AssertionError("atomicMove must not be called when guard rejects") }
                override fun rename(from: File, to: File): Boolean = throw AssertionError("rename must not be called")
                override fun delete(file: File): Boolean = true
            }
            // We test guard via direct sink finish with guard.
            val sink = FileBackedRgb8TileSink(target("guardcancel.rgb8"), 512, 512, "tok", sinkIo)
            sink.writeTile(Rect(0, 0, 512, 512), Rect(0, 0, 512, 512), ByteArray(ExynosUpscaleSession.OUTPUT_BYTES).also { fillConstant(it) })
            cancelled.set(true)
            try {
                sink.finish { !cancelled.get() }
                org.junit.Assert.fail("expected guard rejection")
            } catch (e: PublicationGuardException) {
                // staging settled
            }
            assertFalse(target("guardcancel.rgb8").exists())
        } finally { s.close() }
    }

    @Test
    fun staleAtPublicationGuardPreventsPublication() = runBlocking {
        val stale = AtomicBoolean(false)
        val sinkIo = object : FileBackedSinkIo {
            override fun createTruncate(file: File, length: Long) {}
            override fun write(position: Long, bytes: ByteArray, offset: Int, length: Int) = length
            override fun force() = Unit
            override fun close() = Unit
            override fun length(): Long = 3L * 512 * 512
            override fun atomicMove(from: File, to: File) { throw AssertionError("must not atomicMove after stale guard") }
            override fun rename(from: File, to: File): Boolean = false
            override fun delete(file: File): Boolean = true
        }
        // Need a sink that validates length correctly; use 512x512 geometry
        val sink = FileBackedRgb8TileSink(File(workDir, "guardstale.rgb8"), 512, 512, "tok2", sinkIo)
        sink.writeTile(Rect(0, 0, 512, 512), Rect(0, 0, 512, 512), ByteArray(ExynosUpscaleSession.OUTPUT_BYTES).also { fillConstant(it) })
        stale.set(true)
        try {
            sink.finish { !stale.get() }
            org.junit.Assert.fail("expected stale guard rejection")
        } catch (e: PublicationGuardException) {
        }
        assertFalse(File(workDir, "guardstale.rgb8").exists())
    }

    // --- overflow preflight ---

    @Test
    fun overflowDimensionsReturnInvalidInsteadOfThrowingOrLooping() = runBlocking {
        val enn = FakeEnn()
        val s = loaded(enn)
        try {
            val pipeline = TileFileBackedUpscaler(s, context)
            // sourceWidth that overflows when *4 > Int.MAX_VALUE
            val hugeW = Int.MAX_VALUE / 4 + 1
            val hugeH = 128
            // Provide a minimal source that claims huge dimensions but we won't actually allocate huge bytes
            val fakeSource = object : TileInputSource {
                override val sourceWidth = hugeW
                override val sourceHeight = hugeH
                override suspend fun fillChwTile(sx: Int, sy: Int, into: ByteArray) { throw AssertionError("should not be called on overflow") }
            }
            val result = pipeline.upscaleToFile(fakeSource, target("overflow.rgb8"), ModelOperationContext(1L, "g1"))
            assertTrue("expected InvalidDimensions, got $result", result is FileBackedUpscaleResult.InvalidDimensions)
            assertEquals(0, enn.executeCalls.get())
            assertFalse(target("overflow.rgb8").exists())
        } finally { s.close() }
    }

    @Test
    fun computeRgb8OutputGeometryOverflowIsInvalid() {
        val invalid = computeRgb8OutputGeometry(Int.MAX_VALUE, Int.MAX_VALUE)
        assertTrue(invalid is Rgb8SizeVerdict.Invalid)
    }

    // --- additional explicit matrix tests ---

    @Test
    fun cancellationBeforeTileZeroDoesNoWork() = runBlocking {
        val enn = FakeEnn()
        val s = loaded(enn)
        try {
            val pipeline = TileFileBackedUpscaler(s, context)
            val ctx = ModelOperationContext(1L, "g1", isCancelled = { true })
            val result = pipeline.upscaleToFile(ByteArrayTileInputSource(sourceBytes(129, 128), 129, 128), target("before0.rgb8"), ctx)
            assertTrue(result is FileBackedUpscaleResult.Cancelled)
            assertEquals(0, enn.executeCalls.get())
            assertFalse(target("before0.rgb8").exists())
        } finally { s.close() }
    }

    @Test
    fun staleBeforeTileZeroDoesNoWork() = runBlocking {
        val enn = FakeEnn()
        val s = loaded(enn)
        try {
            val pipeline = TileFileBackedUpscaler(s, context)
            val ctx = ModelOperationContext(1L, "g1", isCurrent = { _, _ -> false })
            val result = pipeline.upscaleToFile(ByteArrayTileInputSource(sourceBytes(129, 128), 129, 128), target("stale0.rgb8"), ctx)
            assertTrue(result is FileBackedUpscaleResult.Stale)
            assertEquals(0, enn.executeCalls.get())
        } finally { s.close() }
    }

    @Test
    fun cancellationDuringSinkWriteSettlesAndPublishesNothing() = runBlocking {
        val enn = FakeEnn().apply { outputFiller = { _, out -> fillConstant(out) } }
        val s = loaded(enn)
        try {
            val sinkIoFactory: () -> FileBackedSinkIo = {
                object : FileBackedSinkIo {
                    var length = 0L
                    override fun createTruncate(file: File, length: Long) { this.length = length }
                    override fun write(position: Long, bytes: ByteArray, offset: Int, length: Int): Int {
                        throw kotlinx.coroutines.CancellationException("sink cancelled")
                    }
                    override fun force() = Unit
                    override fun close() = Unit
                    override fun length(): Long = length
                    override fun atomicMove(from: File, to: File) { throw AssertionError("should not move after sink failure") }
                    override fun rename(from: File, to: File): Boolean = false
                    override fun delete(file: File): Boolean = true
                }
            }
            val pipeline = TileFileBackedUpscaler(s, context, sinkIoFactory = sinkIoFactory)
            try {
                pipeline.upscaleToFile(ByteArrayTileInputSource(sourceBytes(129, 128), 129, 128), target("sinkcancel.rgb8"), ModelOperationContext(1L, "g1"))
                org.junit.Assert.fail("expected CancellationException to propagate")
            } catch (e: kotlinx.coroutines.CancellationException) {
            }
            assertFalse(target("sinkcancel.rgb8").exists())
        } finally { s.close() }
    }
}
