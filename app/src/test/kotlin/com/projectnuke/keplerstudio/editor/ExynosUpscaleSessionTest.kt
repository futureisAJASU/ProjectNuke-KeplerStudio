package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
import org.robolectric.annotation.Config

/**
 * Phase N1 — ExynosUpscaleSession lifecycle, ownership, cancellation and capability
 * publication, exercised through an injected FAKE ENN backend only (no production
 * native code runs under unit tests).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ExynosUpscaleSessionTest {
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

    /** Fake ENN backend over in-memory buffers; records every physical teardown step. */
    private class FakeEnn : ExynosEnnNativeInterface {
        var probeResult: Boolean = true
        var initializeStatus: Int = EnnStatus.SUCCESS
        var openModelResult: Long = 7001L
        var allocationResult: LongArray? = longArrayOf(0x515A, 1L, 1L)
        var inputInfo: IntArray =
            intArrayOf(
                1,
                ExynosUpscaleSession.INPUT_WIDTH,
                ExynosUpscaleSession.INPUT_HEIGHT,
                3,
                ExynosUpscaleSession.INPUT_BYTES,
            )
        var outputInfo: IntArray =
            intArrayOf(
                1,
                ExynosUpscaleSession.OUTPUT_WIDTH,
                ExynosUpscaleSession.OUTPUT_HEIGHT,
                3,
                ExynosUpscaleSession.OUTPUT_BYTES,
            )
        var executeResult: Boolean = true
        var outputFiller: ((ByteArray) -> Unit)? = null
        var metaInfo: String? = "nnc-test-1.0"
        var openModelFailure: Throwable? = null
        var memcpyInFailure: Throwable? = null

        val initializeCalls = AtomicInteger()
        val deinitializeCalls = AtomicInteger()
        val openCalls = AtomicInteger()
        val closeModelCalls = AtomicInteger()
        val allocateCalls = AtomicInteger()
        val releaseBufferCalls = AtomicInteger()
        /** Ordered log of physical lifecycle steps for close-vs-inference ordering proof. */
        val stepLog = mutableListOf<String>()
        var executeGate: CompletableDeferred<Unit>? = null
        var executeStarted: CompletableDeferred<Unit>? = null

        override fun probeRuntime(): Boolean = probeResult

        override fun initialize(): Int {
            stepLog += "initialize"
            return initializeStatus.also { initializeCalls.incrementAndGet() }
        }

        override fun deinitialize(): Int {
            stepLog += "deinitialize"
            return EnnStatus.SUCCESS.also { deinitializeCalls.incrementAndGet() }
        }

        override fun openModel(path: String): Long {
            openModelFailure?.let { throw it }
            stepLog += "openModel:$path"
            return if (openModelResult < 0) -1L else openModelResult.also { openCalls.incrementAndGet() }
        }

        override fun closeModel(modelId: Long): Boolean {
            stepLog += "closeModel"
            closeModelCalls.incrementAndGet()
            return true
        }

        override fun allocateAllBuffers(modelId: Long): LongArray? {
            allocateCalls.incrementAndGet()
            return allocationResult
        }

        override fun releaseBuffers(bufferSet: Long, bufferCount: Int): Boolean {
            stepLog += "releaseBuffers"
            releaseBufferCalls.incrementAndGet()
            return true
        }

        override fun getBufferInfoByIndex(modelId: Long, direction: Int, index: Int): IntArray? =
            when (direction) {
                ENN_DIR_IN_TEST -> inputInfo
                else -> outputInfo
            }

        override fun memcpyHostToDevice(bufferSet: Long, index: Int, data: ByteArray): Boolean {
            memcpyInFailure?.let { throw it }
            require(data.size == ExynosUpscaleSession.INPUT_BYTES)
            return true
        }

        override fun memcpyDeviceToHost(bufferSet: Long, index: Int, out: ByteArray): Boolean {
            outputFiller?.invoke(out)
            return true
        }

        override fun execute(modelId: Long): Boolean {
            stepLog += "execute:start"
            executeStarted?.complete(Unit)
            executeGate?.let { gate -> runBlocking { gate.await() } }
            stepLog += "execute:end"
            return executeResult
        }

        override fun getMetaInfo(metaId: Int, modelId: Long): String? = metaInfo
    }

    private companion object {
        const val ENN_DIR_IN_TEST = 0
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

    private fun preparedFileProviderReturning(result: ModelLoadResult<File>) =
        { _: ModelAssetContract -> result }

    private fun session(native: ExynosEnnNativeInterface): ExynosUpscaleSession =
        ExynosUpscaleSession(
            context = context,
            native = native,
            ioDispatcher = Dispatchers.Default,
            preparedModelFileProvider =
                preparedFileProviderReturning(ModelLoadResult.Ready(File(context.filesDir, "fake.nnc"))),
        )

    private fun testPixels(): IntArray =
        IntArray(ExynosUpscaleSession.INPUT_WIDTH * ExynosUpscaleSession.INPUT_HEIGHT) { i ->
            val r = i and 0xFF
            val g = (i * 7) and 0xFF
            val b = (i * 13) and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

    // ------------------------------------------------------------------
    // Valid load
    // ------------------------------------------------------------------

    @Test
    fun validLoadPublishesReadySessionAndCapability() = runBlocking {
        val enn = FakeEnn()
        val session = session(enn)
        try {
            val result = session.load(fakeToken())
            assertTrue("load must succeed: $result", result is ModelLoadResult.Ready)
            assertEquals(ModelRunnerLifecycle.Loaded, session.lifecycle)
            assertNotNull(session.descriptor)
            assertEquals(1, enn.initializeCalls.get())
            assertEquals(1, enn.openCalls.get())
            assertEquals(1, enn.allocateCalls.get())
            val capability = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.ExynosUpscale)
            assertEquals(ModelCapabilityPhase.Ready, capability.phase)
            assertTrue(capability.sessionActive)
        } finally {
            session.close()
        }
    }

    @Test
    fun assetInvalidIsReportedAndNoNativeHandleLeaks() = runBlocking {
        val enn = FakeEnn()
        val session =
            ExynosUpscaleSession(
                context = context,
                native = enn,
                ioDispatcher = Dispatchers.Default,
                preparedModelFileProvider =
                    preparedFileProviderReturning(ModelLoadResult.AssetInvalid("hash mismatch")),
            )
        val result = session.load(fakeToken())
        assertTrue(result is ModelLoadResult.AssetInvalid)
        assertEquals(0, enn.initializeCalls.get())
        assertNull(session.descriptor)
        val capability = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.ExynosUpscale)
        assertEquals(ModelCapabilityPhase.AssetInvalid, capability.phase)
    }

    @Test
    fun runtimeUnavailableRejectsBeforeAnyNativeWork() = runBlocking {
        val enn = FakeEnn().apply { probeResult = false }
        val session = session(enn)
        val result = session.load(fakeToken())
        assertTrue(result is ModelLoadResult.RuntimeUnavailable)
        assertEquals(0, enn.initializeCalls.get())
        assertEquals(0, enn.openCalls.get())
        val capability = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.ExynosUpscale)
        assertEquals(ModelCapabilityPhase.RuntimeUnavailable, capability.phase)
    }

    @Test
    fun contractMismatchTeardownsEverythingAndReportsUnsupportedContract() = runBlocking {
        val enn = FakeEnn().apply { outputInfo = intArrayOf(1, 256, 256, 3, 256 * 256 * 3 * 4) }
        val session = session(enn)
        val result = session.load(fakeToken())
        assertTrue(result is ModelLoadResult.UnsupportedContract)
        assertEquals(1, enn.releaseBufferCalls.get())
        assertEquals(1, enn.closeModelCalls.get())
        assertEquals(1, enn.deinitializeCalls.get())
        assertNull(session.descriptor)
        assertEquals(ModelRunnerLifecycle.Unloaded, session.lifecycle)
        val capability = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.ExynosUpscale)
        assertEquals(ModelCapabilityPhase.ContractUnsupported, capability.phase)
        assertFalse(capability.sessionActive)
    }

    // ------------------------------------------------------------------
    // Cancellation
    // ------------------------------------------------------------------

    @Test
    fun cancelBeforeLoadPropagatesCancellationAndLeavesNothingLoaded() = runBlocking {
        val enn = FakeEnn()
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val session =
            ExynosUpscaleSession(
                context = context,
                native = enn,
                ioDispatcher = Dispatchers.Default,
                preparedModelFileProvider = { _: ModelAssetContract ->
                    reached.complete(Unit)
                    runBlocking { release.await() }
                    ModelLoadResult.Ready(File(context.filesDir, "fake.nnc"))
                },
            )
        val scope = CoroutineScope(Dispatchers.Default)
        val deferred = scope.async { session.load(fakeToken()) }
        reached.await()
        deferred.cancel(CancellationException("cancelled before load"))
        release.complete(Unit)
        var thrown: Throwable? = null
        try {
            deferred.await()
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("expected cancellation, got $thrown", thrown is CancellationException)
        assertEquals(0, enn.initializeCalls.get())
        assertNull(session.descriptor)
        scope.cancel()
    }

    @Test
    fun cancelBeforeInferencePreventsNewWork() = runBlocking {
        val enn = FakeEnn()
        val session = session(enn)
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        val cancelledContext =
            ModelOperationContext(operationToken = 1L, documentGeneration = "g1", isCancelled = { true })
        val result = session.run(testPixels(), cancelledContext)
        assertTrue(result is ModelRunResult.Failure)
        result as ModelRunResult.Failure
        assertEquals(ModelFailureReason.Cancelled, result.failure.reason)
        assertTrue(enn.stepLog.none { it.startsWith("execute") })
        session.close()
    }

    // ------------------------------------------------------------------
    // Close semantics
    // ------------------------------------------------------------------

    @Test
    fun closeAfterLoadUnpublishesCapabilityExactlyOnce() = runBlocking {
        val enn = FakeEnn()
        val session = session(enn)
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        session.close()
        session.close()
        assertEquals(1, enn.releaseBufferCalls.get())
        assertEquals(1, enn.closeModelCalls.get())
        assertEquals(1, enn.deinitializeCalls.get())
        assertEquals(ModelRunnerLifecycle.Unloaded, session.lifecycle)
        assertNull(session.descriptor)
        val capability = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.ExynosUpscale)
        assertFalse("capability must be unpublished", capability.sessionActive)
        assertTrue(capability.phase in setOf(ModelCapabilityPhase.Unloaded, ModelCapabilityPhase.Loadable))
    }

    @Test
    fun closeRacingParkedInferenceSettlesDeterministically() = runBlocking {
        val enn = FakeEnn()
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        enn.executeGate = gate
        enn.executeStarted = started
        val session = session(enn)
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        val scope = CoroutineScope(Dispatchers.Default)
        val inference = scope.async { session.run(testPixels(), ModelOperationContext(1L, "g1")) }
        started.await()
        val closer = scope.async { session.close() }
        gate.complete(Unit)
        val result = inference.await()
        closer.await()
        assertTrue("parked inference completes before teardown", result is ModelRunResult.Success)
        (result as ModelRunResult.Success).value.recycle()
        val executeEnd = enn.stepLog.indexOf("execute:end")
        val releaseIndex = enn.stepLog.indexOf("releaseBuffers")
        assertTrue(executeEnd in 0 until releaseIndex)
        scope.cancel()
    }

    @Test
    fun staleCloseCannotDestroyNewerSessionInstance() = runBlocking {
        val oldEnn = FakeEnn()
        val oldSession = session(oldEnn)
        assertTrue(oldSession.load(fakeToken()) is ModelLoadResult.Ready)

        val newEnn = FakeEnn()
        val newSession = session(newEnn)
        assertTrue(newSession.load(fakeToken()) is ModelLoadResult.Ready)

        oldSession.close()
        newSession.close()

        assertEquals(1, oldEnn.closeModelCalls.get())
        assertEquals(1, newEnn.closeModelCalls.get())
        assertTrue(newSession.lifecycle == ModelRunnerLifecycle.Unloaded)
    }

    // ------------------------------------------------------------------
    // Run-time staleness / failure mapping
    // ------------------------------------------------------------------

    @Test
    fun staleModelOperationContextNeverReachesNativeCode() = runBlocking {
        val enn = FakeEnn()
        val session = session(enn)
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        val staleContext =
            ModelOperationContext(
                operationToken = 1L,
                documentGeneration = "g-old",
                isCurrent = { _, _ -> false },
            )
        val result = session.run(testPixels(), staleContext)
        assertTrue(result is ModelRunResult.Failure)
        assertEquals(ModelFailureReason.StaleGeneration, (result as ModelRunResult.Failure).failure.reason)
        assertTrue(enn.stepLog.none { it.startsWith("execute") })
        session.close()
    }

    @Test
    fun failedInferenceMapsToStructuredInferenceFailed() = runBlocking {
        val enn = FakeEnn().apply { executeResult = false }
        val session = session(enn)
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        val result = session.run(testPixels(), ModelOperationContext(1L, "g1"))
        assertTrue(result is ModelRunResult.Failure)
        assertEquals(
            ModelFailureReason.InferenceFailed,
            (result as ModelRunResult.Failure).failure.reason,
        )
        session.close()
    }

    @Test
    fun backendExceptionDuringLoadMapsToLoadFailedWithTotalTeardown() = runBlocking {
        val enn = FakeEnn().apply { openModelFailure = IllegalStateException("service exploded") }
        val session = session(enn)
        val result = session.load(fakeToken())
        assertTrue(result is ModelLoadResult.LoadFailed)
        assertEquals(1, enn.deinitializeCalls.get())
        assertNull(session.descriptor)
    }

    @Test
    fun backendExceptionDuringInferenceMapsToInferenceFailed() = runBlocking {
        val enn = FakeEnn().apply { memcpyInFailure = IllegalStateException("buffer gone") }
        val session = session(enn)
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        val result = session.run(testPixels(), ModelOperationContext(1L, "g1"))
        assertTrue(result is ModelRunResult.Failure)
        assertEquals(
            ModelFailureReason.InferenceFailed,
            (result as ModelRunResult.Failure).failure.reason,
        )
        session.close()
    }

    @Test
    fun openModelNegativeIdMapsToRuntimeUnavailableForWrongChipsetNnc() = runBlocking {
        val enn = FakeEnn().apply { openModelResult = -1L }
        val session = session(enn)
        val result = session.load(fakeToken())
        assertTrue(result is ModelLoadResult.RuntimeUnavailable)
        assertEquals(1, enn.deinitializeCalls.get())
    }

    // ------------------------------------------------------------------
    // Output contract: CHW channel interpretation + x4 spatial size
    // ------------------------------------------------------------------

    @Test
    fun successfulRunProducesX4RgbBitmapFromChwFloatOutput() = runBlocking {
        val planeSize = ExynosUpscaleSession.OUTPUT_WIDTH * ExynosUpscaleSession.OUTPUT_HEIGHT
        val enn = FakeEnn().apply {
            outputFiller = { bytes ->
                val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.nativeOrder())
                val floats = buffer.asFloatBuffer()
                floats.put(0, 1.0f)
                floats.put(planeSize, 0.5f)
                floats.put(2 * planeSize, 0.25f)
            }
        }
        val session = session(enn)
        try {
            assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
            val result = session.run(testPixels(), ModelOperationContext(1L, "g1"))
            assertTrue(result is ModelRunResult.Success)
            val bitmap = (result as ModelRunResult.Success).value
            assertEquals(ExynosUpscaleSession.OUTPUT_WIDTH, bitmap.width)
            assertEquals(ExynosUpscaleSession.OUTPUT_HEIGHT, bitmap.height)
            val pixel = IntArray(1)
            bitmap.getPixels(pixel, 0, bitmap.width, 0, 0, 1, 1)
            assertEquals(255, (pixel[0] shr 16) and 0xFF)
            assertEquals(127, (pixel[0] shr 8) and 0xFF)
            assertEquals(63, pixel[0] and 0xFF)
            bitmap.recycle()
        } finally {
            session.close()
        }
    }

    // ------------------------------------------------------------------
    // No leak after failure + reload after close on the same instance
    // ------------------------------------------------------------------

    @Test
    fun failedAllocationLeavesNoLiveHandles() = runBlocking {
        val enn = FakeEnn().apply { allocationResult = null }
        val session = session(enn)
        val result = session.load(fakeToken())
        assertTrue(result is ModelLoadResult.RuntimeUnavailable)
        assertEquals(1, enn.closeModelCalls.get())
        assertEquals(1, enn.deinitializeCalls.get())
        assertEquals(0, enn.releaseBufferCalls.get())
        assertNull(session.descriptor)
    }
}

