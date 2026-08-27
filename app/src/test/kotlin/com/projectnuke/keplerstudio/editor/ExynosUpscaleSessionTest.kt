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
            outputFiller?.invoke(out)
            return memcpyOutStatus
        }

        override fun execute(modelId: Long): Int {
            stepLog += "execute:start"
            executeCalls.incrementAndGet()
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
}
