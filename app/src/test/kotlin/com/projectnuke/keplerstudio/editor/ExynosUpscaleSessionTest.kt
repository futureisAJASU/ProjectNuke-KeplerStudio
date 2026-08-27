package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
 * Phase N1/N2 — ExynosUpscaleSession lifecycle, ownership, cancellation, teardown truthfulness,
 * and capability publication, exercised through an injected FAKE ENN backend only (no production
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
        var deinitializeStatus: Int = EnnStatus.SUCCESS
        var openModelStatus: Int = EnnStatus.SUCCESS
        var openModelId: Long = 7001L
        var allocationStatus: Int = EnnStatus.SUCCESS
        var allocationBufferSet: Long = 0x515AL
        var allocationNIn: Int = 1
        var allocationNOut: Int = 1
        var releaseBuffersStatus: Int = EnnStatus.SUCCESS
        var closeModelStatus: Int = EnnStatus.SUCCESS
        var memcpyInStatus: Int = EnnStatus.SUCCESS
        var memcpyOutStatus: Int = EnnStatus.SUCCESS
        var executeStatus: Int = EnnStatus.SUCCESS

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

        var outputFiller: ((ByteArray) -> Unit)? = null
        var metaInfo: String? = "nnc-test-1.0"

        var openModelThrows: Throwable? = null
        var releaseBuffersThrows: Throwable? = null
        var closeModelThrows: Throwable? = null
        var deinitializeThrows: Throwable? = null
        var memcpyInThrows: Throwable? = null
        var executeThrows: Throwable? = null
        var memcpyOutThrows: Throwable? = null

        val initializeCalls = AtomicInteger()
        val deinitializeCalls = AtomicInteger()
        val openCalls = AtomicInteger()
        val closeModelCalls = AtomicInteger()
        val allocateCalls = AtomicInteger()
        val releaseBufferCalls = AtomicInteger()
        val executeCalls = AtomicInteger()
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
            deinitializeCalls.incrementAndGet()
            deinitializeThrows?.let { throw it }
            return deinitializeStatus
        }

        override fun openModel(path: String): EnnOpenModelResult {
            openModelThrows?.let { throw it }
            stepLog += "openModel:$path"
            if (openModelStatus == EnnStatus.SUCCESS) {
                openCalls.incrementAndGet()
            }
            return EnnOpenModelResult(
                status = openModelStatus,
                modelId = if (openModelStatus == EnnStatus.SUCCESS) openModelId else 0L,
            )
        }

        override fun closeModel(modelId: Long): Int {
            stepLog += "closeModel"
            closeModelCalls.incrementAndGet()
            closeModelThrows?.let { throw it }
            return closeModelStatus
        }

        override fun allocateAllBuffers(modelId: Long): EnnAllocateResult {
            allocateCalls.incrementAndGet()
            return EnnAllocateResult(
                status = allocationStatus,
                bufferSet = if (allocationStatus == EnnStatus.SUCCESS) allocationBufferSet else 0L,
                nInBuffers = if (allocationStatus == EnnStatus.SUCCESS) allocationNIn else 0,
                nOutBuffers = if (allocationStatus == EnnStatus.SUCCESS) allocationNOut else 0,
            )
        }

        override fun releaseBuffers(bufferSet: Long, bufferCount: Int): Int {
            stepLog += "releaseBuffers"
            releaseBufferCalls.incrementAndGet()
            releaseBuffersThrows?.let { throw it }
            return releaseBuffersStatus
        }

        override fun getBufferInfoByIndex(modelId: Long, direction: Int, index: Int): IntArray? =
            when (direction) {
                ENN_DIR_IN_TEST -> inputInfo
                else -> outputInfo
            }

        override fun memcpyHostToDevice(bufferSet: Long, index: Int, data: ByteArray): Int {
            memcpyInThrows?.let { throw it }
            require(data.size == ExynosUpscaleSession.INPUT_BYTES)
            return memcpyInStatus
        }

        override fun memcpyDeviceToHost(bufferSet: Long, index: Int, out: ByteArray): Int {
            memcpyOutThrows?.let { throw it }
            outputFiller?.invoke(out)
            return memcpyOutStatus
        }

        override fun execute(modelId: Long): Int {
            stepLog += "execute:start"
            executeCalls.incrementAndGet()
            executeThrows?.let { throw it }
            executeStarted?.complete(Unit)
            executeGate?.let { gate -> runBlocking { gate.await() } }
            stepLog += "execute:end"
            return executeStatus
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
    // Pre-execute cancellation regression (Fix 1) — uses the EXACT FakeEnn
    // injected into the session with deterministic completable deferreds.
    // ------------------------------------------------------------------

    @Test
    fun cancelBeforeExecutePreventsNativeExecuteCall() = runBlocking {
        val enn = FakeEnn()
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val session = session(enn)
        session.preExecuteCheck = {
            reached.complete(Unit)
            release.await()
        }
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        val scope = CoroutineScope(Dispatchers.Default)
        val deferred = scope.async { session.run(testPixels(), ModelOperationContext(1L, "g1")) }
        reached.await()
        deferred.cancel(CancellationException("cancel before execute"))
        release.complete(Unit)
        var thrown: Throwable? = null
        try {
            deferred.await()
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("expected cancellation, got $thrown", thrown is CancellationException)
        assertEquals(0, enn.executeCalls.get())
        assertFalse("execute:start must not be logged", enn.stepLog.any { it.startsWith("execute:start") })
        assertEquals(ModelRunnerLifecycle.Loaded, session.lifecycle)
        session.close()
        assertEquals(1, enn.releaseBufferCalls.get())
        assertEquals(1, enn.closeModelCalls.get())
        assertEquals(1, enn.deinitializeCalls.get())
        assertEquals(ModelRunnerLifecycle.Unloaded, session.lifecycle)
        scope.cancel()
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
    // Run-time staleness / raw failure mapping
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
    fun failedInferenceMapsToStructuredInferenceFailedWithRawStatus() = runBlocking {
        val enn = FakeEnn().apply { executeStatus = EnnStatus.FAILED_TIMEOUT_HW_RECOVERED }
        val session = session(enn)
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        val result = session.run(testPixels(), ModelOperationContext(1L, "g1"))
        assertTrue(result is ModelRunResult.Failure)
        val failure = (result as ModelRunResult.Failure).failure
        assertEquals(ModelFailureReason.InferenceFailed, failure.reason)
        assertTrue("detail must include raw EnnReturn status", failure.detail?.contains("14") == true)
        session.close()
    }

    @Test
    fun backendExceptionDuringLoadMapsToLoadFailedWithTotalTeardown() = runBlocking {
        val enn = FakeEnn().apply { openModelThrows = IllegalStateException("service exploded") }
        val session = session(enn)
        val result = session.load(fakeToken())
        assertTrue(result is ModelLoadResult.LoadFailed)
        assertEquals(1, enn.deinitializeCalls.get())
        assertNull(session.descriptor)
        // Unexpected backend exception settles to Failed — total teardown does not overwrite it.
        assertEquals(ModelRunnerLifecycle.Failed, session.lifecycle)
        val capability = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.ExynosUpscale)
        assertEquals(ModelCapabilityPhase.Failed, capability.phase)
    }

    @Test
    fun backendExceptionDuringInferenceMapsToInferenceFailed() = runBlocking {
        val enn = FakeEnn().apply { memcpyInThrows = IllegalStateException("buffer gone") }
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
    fun openModelFailureReturnsRawStatusWithoutChipsetLabel() = runBlocking {
        val enn = FakeEnn().apply { openModelStatus = EnnStatus.IO }
        val session = session(enn)
        val result = session.load(fakeToken())
        assertTrue(result is ModelLoadResult.RuntimeUnavailable)
        val detail = (result as ModelLoadResult.RuntimeUnavailable).detail
        assertTrue("detail must include raw status", detail.contains("2(ENN_RET_IO)"))
        assertFalse("detail must not auto-label chipset incompatible", detail.contains("incompatible"))
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
    // No leak after failure
    // ------------------------------------------------------------------

    @Test
    fun failedAllocationLeavesNoLiveHandles() = runBlocking {
        val enn = FakeEnn().apply { allocationStatus = EnnStatus.MEM_ERR }
        val session = session(enn)
        val result = session.load(fakeToken())
        assertTrue(result is ModelLoadResult.RuntimeUnavailable)
        val detail = (result as ModelLoadResult.RuntimeUnavailable).detail
        assertTrue("detail must contain raw MEM_ERR status", detail.contains("5(ENN_RET_MEM_ERR)"))
        assertEquals(1, enn.closeModelCalls.get())
        assertEquals(1, enn.deinitializeCalls.get())
        assertEquals(0, enn.releaseBufferCalls.get())
        assertNull(session.descriptor)
    }

    // ------------------------------------------------------------------
    // Prepared file ownership: every failure path must delete the prepared file
    // ------------------------------------------------------------------

    @Test
    fun initializeFailureDeletesPreparedFile() = runBlocking {
        val tempFile = File(context.filesDir, "initialize_failure_test.nnc").apply { writeText("fake_nnc") }
        val enn = FakeEnn().apply { initializeStatus = EnnStatus.FAILED }
        val session =
            ExynosUpscaleSession(
                context = context,
                native = enn,
                ioDispatcher = Dispatchers.Default,
                preparedModelFileProvider =
                    preparedFileProviderReturning(ModelLoadResult.Ready(tempFile)),
            )
        val result = session.load(fakeToken())
        assertTrue(result is ModelLoadResult.RuntimeUnavailable)
        assertFalse("prepared file must be deleted on initialize failure", tempFile.exists())
        assertEquals(0, enn.openCalls.get())
        assertEquals(0, enn.closeModelCalls.get())
        assertNull(session.descriptor)
    }

    @Test
    fun openModelFailureDeletesPreparedFile() = runBlocking {
        val tempFile = File(context.filesDir, "open_model_failure_test.nnc").apply { writeText("fake_nnc") }
        val enn = FakeEnn().apply { openModelStatus = EnnStatus.FAILED }
        val session =
            ExynosUpscaleSession(
                context = context,
                native = enn,
                ioDispatcher = Dispatchers.Default,
                preparedModelFileProvider =
                    preparedFileProviderReturning(ModelLoadResult.Ready(tempFile)),
            )
        val result = session.load(fakeToken())
        assertTrue(result is ModelLoadResult.RuntimeUnavailable)
        assertFalse("prepared file must be deleted on openModel failure", tempFile.exists())
        assertEquals(0, enn.closeModelCalls.get())
        assertEquals(1, enn.deinitializeCalls.get())
        assertNull(session.descriptor)
    }

    @Test
    fun contractMismatchDeletesPreparedFile() = runBlocking {
        val tempFile = File(context.filesDir, "contract_mismatch_test.nnc").apply { writeText("fake_nnc") }
        val enn = FakeEnn().apply { outputInfo = intArrayOf(1, 256, 256, 3, 256 * 256 * 3 * 4) }
        val session =
            ExynosUpscaleSession(
                context = context,
                native = enn,
                ioDispatcher = Dispatchers.Default,
                preparedModelFileProvider =
                    preparedFileProviderReturning(ModelLoadResult.Ready(tempFile)),
            )
        val result = session.load(fakeToken())
        assertTrue(result is ModelLoadResult.UnsupportedContract)
        assertFalse("prepared file must be deleted on contract mismatch", tempFile.exists())
        assertEquals(1, enn.releaseBufferCalls.get())
        assertEquals(1, enn.closeModelCalls.get())
        assertEquals(1, enn.deinitializeCalls.get())
        assertNull(session.descriptor)
    }

    @Test
    fun successfulCloseDeletesPreparedFile() = runBlocking {
        val tempFile = File(context.filesDir, "successful_close_test.nnc").apply { writeText("fake_nnc") }
        val enn = FakeEnn()
        val session =
            ExynosUpscaleSession(
                context = context,
                native = enn,
                ioDispatcher = Dispatchers.Default,
                preparedModelFileProvider =
                    preparedFileProviderReturning(ModelLoadResult.Ready(tempFile)),
            )
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        assertTrue("prepared file must exist after successful load", tempFile.exists())
        session.close()
        assertFalse("prepared file must be deleted after close", tempFile.exists())
        assertEquals(1, enn.releaseBufferCalls.get())
        assertEquals(1, enn.closeModelCalls.get())
        assertEquals(1, enn.deinitializeCalls.get())
    }

    // ------------------------------------------------------------------
    // Truthful physical teardown status regressions A–F (Fix 3)
    // ------------------------------------------------------------------

    @Test
    fun releaseBuffersReturnsFailureIsRecorded() = runBlocking {
        val enn = FakeEnn().apply { releaseBuffersStatus = EnnStatus.FAILED }
        val session = session(enn)
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        session.close()
        val teardown = session.lastTeardownResult
        assertNotNull(teardown)
        teardown!!
        assertEquals(NativeStepOutcome.ReturnedFailure, teardown.releaseBuffersOutcome)
        assertEquals(EnnStatus.FAILED, teardown.releaseBuffersStatus)
        assertEquals(NativeStepOutcome.ReturnedSuccess, teardown.closeModelOutcome)
        assertEquals(NativeStepOutcome.ReturnedSuccess, teardown.deinitializeOutcome)
        assertFalse(teardown.allAttemptedSucceeded)
    }

    @Test
    fun closeModelReturnsFailureIsRecorded() = runBlocking {
        val enn = FakeEnn().apply { closeModelStatus = EnnStatus.FAILED_SERVICE_NULL }
        val session = session(enn)
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        session.close()
        val teardown = session.lastTeardownResult
        assertNotNull(teardown)
        teardown!!
        assertEquals(NativeStepOutcome.ReturnedSuccess, teardown.releaseBuffersOutcome)
        assertEquals(NativeStepOutcome.ReturnedFailure, teardown.closeModelOutcome)
        assertEquals(EnnStatus.FAILED_SERVICE_NULL, teardown.closeModelStatus)
        assertEquals(NativeStepOutcome.ReturnedSuccess, teardown.deinitializeOutcome)
        assertFalse(teardown.allAttemptedSucceeded)
    }

    @Test
    fun deinitializeReturnsNonSuccessIsRecorded() = runBlocking {
        val enn = FakeEnn().apply { deinitializeStatus = EnnStatus.FAILED_RESOURCE_BUSY }
        val session = session(enn)
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        session.close()
        val teardown = session.lastTeardownResult
        assertNotNull(teardown)
        teardown!!
        assertEquals(NativeStepOutcome.ReturnedSuccess, teardown.releaseBuffersOutcome)
        assertEquals(NativeStepOutcome.ReturnedSuccess, teardown.closeModelOutcome)
        assertEquals(NativeStepOutcome.ReturnedFailure, teardown.deinitializeOutcome)
        assertEquals(EnnStatus.FAILED_RESOURCE_BUSY, teardown.deinitializeStatus)
        assertFalse(teardown.allAttemptedSucceeded)
    }

    @Test
    fun allThreeReturnFailureEveryEligiblePhysicalTeardownStepAttemptedOnce() = runBlocking {
        val enn = FakeEnn().apply {
            releaseBuffersStatus = EnnStatus.FAILED
            closeModelStatus = EnnStatus.IO
            deinitializeStatus = EnnStatus.NOT_SUPPORTED
        }
        val session = session(enn)
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        session.close()
        assertEquals(1, enn.releaseBufferCalls.get())
        assertEquals(1, enn.closeModelCalls.get())
        assertEquals(1, enn.deinitializeCalls.get())
        val teardown = session.lastTeardownResult
        assertNotNull(teardown)
        teardown!!
        assertEquals(NativeStepOutcome.ReturnedFailure, teardown.releaseBuffersOutcome)
        assertEquals(NativeStepOutcome.ReturnedFailure, teardown.closeModelOutcome)
        assertEquals(NativeStepOutcome.ReturnedFailure, teardown.deinitializeOutcome)
        assertFalse(teardown.allAttemptedSucceeded)
        assertEquals(ModelRunnerLifecycle.Unloaded, session.lifecycle)
    }

    @Test
    fun oneOrMoreThrowSubsequentStepsStillAttemptedAndDistinguishedFromReturnedFailure() = runBlocking {
        val enn = FakeEnn().apply {
            releaseBuffersThrows = IllegalStateException("buffer explosion")
            closeModelStatus = EnnStatus.FAILED
        }
        val session = session(enn)
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        session.close()
        assertEquals(1, enn.releaseBufferCalls.get())
        assertEquals(1, enn.closeModelCalls.get())
        assertEquals(1, enn.deinitializeCalls.get())
        val teardown = session.lastTeardownResult
        assertNotNull(teardown)
        teardown!!
        assertEquals(NativeStepOutcome.Threw, teardown.releaseBuffersOutcome)
        assertEquals("buffer explosion", teardown.releaseBuffersDetail)
        assertEquals(NativeStepOutcome.ReturnedFailure, teardown.closeModelOutcome)
        assertEquals(NativeStepOutcome.ReturnedSuccess, teardown.deinitializeOutcome)
        assertFalse(teardown.allAttemptedSucceeded)
        assertEquals(ModelRunnerLifecycle.Unloaded, session.lifecycle)
    }

    @Test
    fun repeatedCloseDoesNotRepeatPhysicalTeardown() = runBlocking {
        val enn = FakeEnn()
        val session = session(enn)
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        session.close()
        val firstResult = session.lastTeardownResult
        session.close()
        session.close()
        assertEquals(1, enn.releaseBufferCalls.get())
        assertEquals(1, enn.closeModelCalls.get())
        assertEquals(1, enn.deinitializeCalls.get())
        assertEquals(firstResult, session.lastTeardownResult)
    }

    // ------------------------------------------------------------------
    // Staged model preparation failure regressions (Fix 4)
    // ------------------------------------------------------------------

    @Test
    fun copyVerifyingSizeMismatchDeletesTarget() {
        val targetDir = File(context.filesDir, "exynos_models_test").apply { mkdirs() }
        val target = File(targetDir, "model.nnc.size.tmp")
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
        var thrown: Throwable? = null
        try {
            copyVerifying(input, target, "irrelevant", 100L)
        } catch (t: Throwable) {
            thrown = t
        }
        assertNotNull(thrown)
        assertTrue(thrown is IllegalStateException)
        assertFalse("partial staging file must be deleted on size failure", target.exists())
    }

    @Test
    fun copyVerifyingShaMismatchDeletesTarget() {
        val targetDir = File(context.filesDir, "exynos_models_test").apply { mkdirs() }
        val target = File(targetDir, "model.nnc.sha.tmp")
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
        var thrown: Throwable? = null
        try {
            copyVerifying(
                input,
                target,
                "0000000000000000000000000000000000000000000000000000000000000000",
                4L,
            )
        } catch (t: Throwable) {
            thrown = t
        }
        assertNotNull(thrown)
        assertTrue(thrown is IllegalStateException)
        assertFalse("partial staging file must be deleted on SHA failure", target.exists())
    }

    @Test
    fun prepareModelFileLeavesNoStagingOnMissingAsset() {
        val manifest = requireNotNull(ModelAssetManifest.byId(ExynosUpscaleSession.EXYNOS_MODEL_ID))
        val targetDir = File(context.filesDir, "exynos_models").apply { mkdirs() }
        val token = "test-missing"
        val baseName = File(manifest.asset.assetPath).name
        val staging = File(targetDir, "$baseName.$token.tmp")
        val finalFile = File(targetDir, "$baseName.$token")
        val result = prepareModelFile(context, manifest.asset, token)
        assertTrue(
            "missing asset must be AssetMissing (or Ready if asset present in env)",
            result is ModelLoadResult.AssetMissing || result is ModelLoadResult.Ready,
        )
        assertFalse("no staging file may remain after terminal return", staging.exists())
        if (result is ModelLoadResult.Ready) {
            assertTrue("final file must exist on success", finalFile.exists())
            finalFile.delete()
        } else {
            assertFalse("no final file may exist on missing-asset path", finalFile.exists())
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle contract: expected load rejection -> Unloaded,
    // unexpected/cancel terminal failure -> Failed
    // ------------------------------------------------------------------

    @Test
    fun expectedRuntimeUnavailableSettlesToUnloaded() = runBlocking {
        val enn = FakeEnn().apply { probeResult = false }
        val session = session(enn)
        val result = session.load(fakeToken())
        assertTrue(result is ModelLoadResult.RuntimeUnavailable)
        assertEquals(ModelRunnerLifecycle.Unloaded, session.lifecycle)
    }

    @Test
    fun expectedContractMismatchSettlesToUnloaded() = runBlocking {
        val enn = FakeEnn().apply { outputInfo = intArrayOf(1, 256, 256, 3, 256 * 256 * 3 * 4) }
        val session = session(enn)
        val result = session.load(fakeToken())
        assertTrue(result is ModelLoadResult.UnsupportedContract)
        assertEquals(ModelRunnerLifecycle.Unloaded, session.lifecycle)
    }

    @Test
    fun cancellationDuringLoadSettlesToFailed() = runBlocking {
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
        deferred.cancel(CancellationException("cancelled during load"))
        release.complete(Unit)
        var thrown: Throwable? = null
        try {
            deferred.await()
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("expected cancellation, got $thrown", thrown is CancellationException)
        assertEquals(ModelRunnerLifecycle.Failed, session.lifecycle)
        scope.cancel()
    }

    @Test
    fun successfulLoadThenCloseTransitionsLoadedThenUnloaded() = runBlocking {
        val enn = FakeEnn()
        val session = session(enn)
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        assertEquals(ModelRunnerLifecycle.Loaded, session.lifecycle)
        session.close()
        assertEquals(ModelRunnerLifecycle.Unloaded, session.lifecycle)
    }

    // ------------------------------------------------------------------
    // N2 pre-NNC static corrective: lifecycle, stage-truth diagnostics,
    // prepared-file truthfulness, and staged-cleanup truthfulness.
    // ------------------------------------------------------------------

    // Regression: unexpected backend exception during load → physical ownership fully
    // settled, LoadFailed published, lifecycle == Failed, subsequent load on the SAME
    // session rejected, a NEW session remains usable. No sleeps.
    @Test
    fun unexpectedBackendExceptionDuringLoadSettlesToFailedAndRejectsReload() = runBlocking {
        val enn = FakeEnn().apply { openModelThrows = IllegalStateException("service exploded") }
        val session = session(enn)
        val result = session.load(fakeToken())
        assertTrue(result is ModelLoadResult.LoadFailed)
        // all physical ownership settled
        assertEquals(1, enn.deinitializeCalls.get())
        assertNull(session.descriptor)
        // registry load failure published as Failed
        val capability = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.ExynosUpscale)
        assertEquals(ModelCapabilityPhase.Failed, capability.phase)
        // lifecycle terminal Failed — total teardown did not overwrite it
        assertEquals(ModelRunnerLifecycle.Failed, session.lifecycle)
        // subsequent load on the same session rejected (terminal Failed)
        val reload = session.load(fakeToken())
        assertTrue("reload on Failed session must be rejected", reload is ModelLoadResult.LoadFailed)
        // diagnostic: the throwing stage was openModel
        val diag = session.lastLoadDiagnostics
        assertEquals(EnnStatus.SUCCESS, diag.initializeStatus)
        assertNull(diag.openModelStatus)
        assertEquals("openModel", diag.throwableStage)
        assertTrue("throwable detail must preserve the exception message", diag.throwableDetail?.contains("service exploded") == true)
        // a new session instance remains fully usable
        val newEnn = FakeEnn()
        val newSession = session(newEnn)
        assertTrue("new session must load after a sibling's unexpected failure", newSession.load(fakeToken()) is ModelLoadResult.Ready)
        newSession.close()
    }

    // Regression A: Open SUCCESS + Allocate SUCCESS then output tensor contract
    // mismatch → diagnostics preserve open=true, allocation=true, contract=false.
    @Test
    fun openAllocateSuccessThenContractMismatchPreservesNativeLoadDiagnostics() = runBlocking {
        val enn = FakeEnn().apply { outputInfo = intArrayOf(1, 256, 256, 3, 256 * 256 * 3 * 4) }
        val session = session(enn)
        val result = session.load(fakeToken())
        assertTrue(result is ModelLoadResult.UnsupportedContract)
        val diag = session.lastLoadDiagnostics
        assertEquals(EnnStatus.SUCCESS, diag.initializeStatus)
        assertEquals(EnnStatus.SUCCESS, diag.openModelStatus)
        assertEquals(EnnStatus.SUCCESS, diag.allocationStatus)
        assertEquals(1, diag.allocationNIn)
        assertEquals(1, diag.allocationNOut)
        assertTrue("contract gate must be reached after allocation success", diag.contractValidationReached)
        assertFalse("contract mismatch must be recorded as not passed", diag.contractValidationPassed)
        assertNull("no throwable stage on a structured rejection", diag.throwableStage)
    }

    // Regression B: H2D SUCCESS + Execute SUCCESS then D2H returns failure →
    // executeAttempted=true, executeSucceeded=true, outputRead=false.
    @Test
    fun executeSuccessThenD2hFailurePreservesExecuteDiagnostics() = runBlocking {
        val enn = FakeEnn().apply { memcpyOutStatus = EnnStatus.IO }
        val session = session(enn)
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        val result = session.run(testPixels(), ModelOperationContext(1L, "g1"))
        assertTrue(result is ModelRunResult.Failure)
        val diag = session.lastRunDiagnostics
        assertEquals(EnnStatus.SUCCESS, diag.h2dStatus)
        assertTrue("execute must be recorded as reached", diag.executeReached)
        assertEquals(EnnStatus.SUCCESS, diag.executeStatus)
        assertEquals(EnnStatus.IO, diag.d2hStatus)
        assertFalse("output decode must not pass when D2H failed", diag.outputDecodePassed)
        assertNull(diag.throwableStage)
        assertEquals(ModelRunnerLifecycle.Loaded, session.lifecycle)
        session.close()
    }

    // Regression C: H2D failure → execute not attempted, executeStatus absent.
    @Test
    fun h2dFailureLeavesExecuteNotAttempted() = runBlocking {
        val enn = FakeEnn().apply { memcpyInStatus = EnnStatus.IO }
        val session = session(enn)
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        val result = session.run(testPixels(), ModelOperationContext(1L, "g1"))
        assertTrue(result is ModelRunResult.Failure)
        val diag = session.lastRunDiagnostics
        assertEquals(EnnStatus.IO, diag.h2dStatus)
        assertFalse("execute must not be marked reached before H2D success", diag.executeReached)
        assertNull("execute status must be absent when execute was not reached", diag.executeStatus)
        assertNull("d2h must not be reached after H2D failure", diag.d2hStatus)
        assertEquals(0, enn.executeCalls.get())
        assertEquals(ModelRunnerLifecycle.Loaded, session.lifecycle)
        session.close()
    }

    // Regression D: execute returns an ENN error → executeAttempted=true AND the exact
    // raw executeStatus is preserved verbatim.
    @Test
    fun executeFailurePreservesExactRawStatusInDiagnostics() = runBlocking {
        val enn = FakeEnn().apply { executeStatus = EnnStatus.FAILED_TIMEOUT_HW_RECOVERED }
        val session = session(enn)
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        val result = session.run(testPixels(), ModelOperationContext(1L, "g1"))
        assertTrue(result is ModelRunResult.Failure)
        val diag = session.lastRunDiagnostics
        assertTrue(diag.executeReached)
        assertEquals(
            "exact raw EnnReturn must be preserved, not collapsed",
            EnnStatus.FAILED_TIMEOUT_HW_RECOVERED,
            diag.executeStatus,
        )
        assertEquals(EnnStatus.SUCCESS, diag.h2dStatus)
        // D2H was never reached because execute failed first
        assertNull(diag.d2hStatus)
        assertFalse(diag.outputDecodePassed)
        assertEquals(ModelRunnerLifecycle.Loaded, session.lifecycle)
        session.close()
    }

    // Regression: a thrown execute call preserves reached=true + throwable stage/detail.
    @Test
    fun thrownExecuteCallRecordsReachedWithThrowableStage() = runBlocking {
        val enn = FakeEnn().apply { executeThrows = IllegalStateException("npu faulted") }
        val session = session(enn)
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        val result = session.run(testPixels(), ModelOperationContext(1L, "g1"))
        assertTrue(result is ModelRunResult.Failure)
        val diag = session.lastRunDiagnostics
        assertTrue(diag.executeReached)
        assertNull("executeStatus must be absent when the call threw", diag.executeStatus)
        assertEquals("execute", diag.throwableStage)
        assertTrue(diag.throwableDetail?.contains("npu faulted") == true)
        session.close()
    }

    @Test
    fun lifecycleRemainsInferencingAcrossH2dAndD2hBoundaries() = runBlocking {
        val enn = FakeEnn()
        val session = session(enn)
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        val h2dReached = CompletableDeferred<Unit>()
        val h2dRelease = CompletableDeferred<Unit>()
        session.preH2dCheck = { h2dReached.complete(Unit); h2dRelease.await() }
        val scope = CoroutineScope(Dispatchers.Default)
        val h2dRun = scope.async { session.run(testPixels(), ModelOperationContext(1L, "g1")) }
        h2dReached.await()
        assertEquals(ModelRunnerLifecycle.Inferencing, session.lifecycle)
        h2dRelease.complete(Unit)
        assertTrue(h2dRun.await() is ModelRunResult.Success)

        val d2hReached = CompletableDeferred<Unit>()
        val d2hRelease = CompletableDeferred<Unit>()
        session.preH2dCheck = null
        session.preD2hCheck = { d2hReached.complete(Unit); d2hRelease.await() }
        val d2hRun = scope.async { session.run(testPixels(), ModelOperationContext(2L, "g1")) }
        d2hReached.await()
        assertEquals(ModelRunnerLifecycle.Inferencing, session.lifecycle)
        d2hRelease.complete(Unit)
        assertTrue(d2hRun.await() is ModelRunResult.Success)
        assertEquals(ModelRunnerLifecycle.Loaded, session.lifecycle)
        session.close()
        scope.cancel()
    }

    @Test
    fun successfulDecodeRestoresLoadedAndRetainsLabeledRunHistory() = runBlocking {
        val enn = FakeEnn()
        val session = session(enn)
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        assertTrue(session.run(testPixels(), ModelOperationContext(1L, "g1"), "cold") is ModelRunResult.Success)
        assertEquals(ModelRunnerLifecycle.Loaded, session.lifecycle)
        assertEquals("cold", session.runDiagnosticsHistory.single().attemptLabel)
        session.close()
    }

    @Test
    fun reportWriteFailureBecomesPrimaryOnlyWhenProbeOtherwiseSucceeded() {
        val writeFailure = IOException("metadata unavailable")
        val success = finalizeProbeReport({ throw writeFailure }, null, null)
        assertFalse(success.persisted)
        assertEquals(writeFailure, success.primaryFailure)

        val original = AssertionError("inference failed")
        val failed = finalizeProbeReport({ throw writeFailure }, original, null)
        assertEquals(original, failed.primaryFailure)
        assertTrue(original.suppressed.contains(writeFailure))
    }

    // Prepared-file deletion outcome classification (deterministic seam).
    @Test
    fun preparedFileDeletionOutcomeClassificationIsTruthful() {
        // delete() returned true → Deleted
        assertEquals(
            PreparedFileOutcome.Deleted,
            classifyPreparedFileDeletion(Result.success(true), fileStillExists = false),
        )
        // delete() returned true, file coincidentally also absent → still Deleted
        assertEquals(
            PreparedFileOutcome.Deleted,
            classifyPreparedFileDeletion(Result.success(true), fileStillExists = true),
        )
        // delete() returned false AND file still exists → DeleteFailed
        assertEquals(
            PreparedFileOutcome.DeleteFailed,
            classifyPreparedFileDeletion(Result.success(false), fileStillExists = true),
        )
        // delete() returned false AND no file remains → AlreadyAbsent (NOT a failure)
        assertEquals(
            PreparedFileOutcome.AlreadyAbsent,
            classifyPreparedFileDeletion(Result.success(false), fileStillExists = false),
        )
        // delete() threw AND file still exists → Threw
        assertEquals(
            PreparedFileOutcome.Threw,
            classifyPreparedFileDeletion(
                Result.failure(IOException("permission denied")),
                fileStillExists = true,
            ),
        )
        // delete() threw AND file absent → still Threw
        assertEquals(
            PreparedFileOutcome.Threw,
            classifyPreparedFileDeletion(
                Result.failure(IOException("permission denied")),
                fileStillExists = false,
            ),
        )
    }

    // Prepared-file delete failure retains cleanup debt until an explicit later close().
    @Test
    fun preparedFileDeleteFailureRetainsCleanupDebtUntilExplicitRetry() = runBlocking {
        val tempFile = File(context.filesDir, "delete_debt_test.nnc").apply { writeText("fake_nnc") }
        val enn = FakeEnn()
        val session =
            ExynosUpscaleSession(
                context = context,
                native = enn,
                ioDispatcher = Dispatchers.Default,
                preparedModelFileProvider =
                    preparedFileProviderReturning(ModelLoadResult.Ready(tempFile)),
            )
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        // Force delete to lie-fail while the physical file still exists.
        session.preparedFileDeleter = { false }
        session.close()
        val firstTeardown = session.lastTeardownResult
        assertNotNull(firstTeardown)
        firstTeardown!!
        assertEquals(PreparedFileOutcome.DeleteFailed, firstTeardown.preparedFileOutcome)
        assertEquals(tempFile.absolutePath, firstTeardown.preparedFilePath)
        assertFalse("allAttemptedSucceeded must be false while debt remains", firstTeardown.allAttemptedSucceeded)
        assertTrue("file must still exist (debt)", tempFile.exists())
        // Explicit later cleanup attempt: restore a working deleter and close again.
        session.preparedFileDeleter = { it.delete() }
        session.close()
        val retryTeardown = session.lastTeardownResult
        assertNotNull(retryTeardown)
        retryTeardown!!
        assertEquals(PreparedFileOutcome.Deleted, retryTeardown.preparedFileOutcome)
        assertFalse("debt file must be gone after retry", tempFile.exists())
        assertTrue(retryTeardown.allAttemptedSucceeded)
    }

    // An already-absent prepared file must NOT be recorded as a deletion failure.
    @Test
    fun alreadyAbsentPreparedFileIsNotRecordedAsDeleteFailure() = runBlocking {
        val tempFile = File(context.filesDir, "already_absent_test.nnc").apply { writeText("fake_nnc") }
        val enn = FakeEnn()
        val session =
            ExynosUpscaleSession(
                context = context,
                native = enn,
                ioDispatcher = Dispatchers.Default,
                preparedModelFileProvider =
                    preparedFileProviderReturning(ModelLoadResult.Ready(tempFile)),
            )
        assertTrue(session.load(fakeToken()) is ModelLoadResult.Ready)
        // Simulate the file being gone before teardown (e.g. external cleanup).
        assertTrue(tempFile.delete())
        session.close()
        val teardown = session.lastTeardownResult
        assertNotNull(teardown)
        teardown!!
        assertEquals(PreparedFileOutcome.AlreadyAbsent, teardown.preparedFileOutcome)
        assertTrue("already-absent is not a failure", teardown.allAttemptedSucceeded)
    }

    // Staged-file cleanup failure (copyVerifying verification failure + delete false while
    // the file still exists) must surface as a suppressed exception, never silent.
    @Test
    fun stagingCleanupFailureIsSurfacedNotSilent() {
        val targetDir = File(context.filesDir, "exynos_models_test").apply { mkdirs() }
        val target = File(targetDir, "staging_cleanup_test.tmp")
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
        var thrown: Throwable? = null
        try {
            // stagingDeleter deterministically reports false; the partial file still exists.
            copyVerifying(
                input = input,
                target = target,
                expectedSha256 = "irrelevant",
                expectedBytes = 100L,
                stagingDeleter = { false },
            )
        } catch (t: Throwable) {
            thrown = t
        }
        assertNotNull(thrown)
        assertTrue("verification must still throw", thrown is IllegalStateException)
        val suppressed = thrown!!.suppressed
        assertTrue(
            "staging cleanup failure must be surfaced as suppressed, not silent",
            suppressed.any { it is IOException && (it.message ?: "").contains("staging cleanup incomplete") },
        )
    }
}
