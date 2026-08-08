package com.projectnuke.keplerstudio.editor

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.ReadableByteChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import android.content.Context
import org.robolectric.RobolectricTestRunner
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Tensor

/**
 * Gate 1 model-load lifecycle coverage via an injectable loader/factory seam.
 *
 * The LoaderFactory seam lets us exercise every non-Ready path that does NOT
 * require a real LiteRT tensor inspection: assetMissing, assetInvalid, runtime
 * unavailable, interpreter construction failure, I/O failure during mapping and
 * repeated-failure contributor accumulation. Every non-Ready result must settle
 * the global diagnostic contributor exactly once (loading -> unloaded), so the
 * "loading" state never leaks as a permanent contributor.
 *
 * Tensor contract validation (per-axis quant rejection, zero-point range, output
 * alpha tolerance) is covered in `FlareGuardTensorContractTest` against the
 * parser/quant helpers, and the runtime InvalidOutput classification is covered
 * in `ModelRunnerContractTest`. Those do not require a live LiteRT interpreter.
 */
@RunWith(RobolectricTestRunner::class)
class FlareGuardModelLoaderLifecycleTest {
    @Before
    fun resetRegistry() {
        ModelAvailabilityRegistry.resetForTest()
        GlobalModelDiagnostics.resetForTest(true)
    }

    @After
    fun cleanup() {
        ModelAvailabilityRegistry.resetForTest()
        GlobalModelDiagnostics.resetForTest(true)
    }

    @Test
    fun assetMissingSettlesDiagnosticAndReturnsAssetMissing() {
        val entry = checkNotNull(ModelAssetManifest.byId("flare_masker"))
        val result =
            FlareGuardModelRunner.create(
                factory = FakeFactory,
                assetOpen = { null },
                manifestProvider = { entry },
            )
        assertIs<ModelLoadResult.AssetMissing>(result)
        assertTrue(GlobalModelDiagnostics.snapshot().isEmpty())
    }

    @Test
    fun assetInvalidSettlesDiagnosticAndReturnsAssetInvalid() {
        val entry = pinnedEntry()
        val result =
            FlareGuardModelRunner.create(
                factory = FakeFactory,
                assetOpen = { ByteArrayInputStream(ByteArray(8)) },
                manifestProvider = { entry },
            )
        assertIs<ModelLoadResult.AssetInvalid>(result)
        assertTrue(GlobalModelDiagnostics.snapshot().isEmpty())
    }

    @Test
    fun runtimeUnavailableSettlesDiagnosticAndClosesInterpreter() {
        val entry = pinnedEntry()
        val factory =
            object : FlareGuardLoaderFactory {
                override fun loadAsset(): MappedByteBuffer = mappedBuffer()

                override fun newInterpreter(model: MappedByteBuffer): Interpreter =
                    throw UnsatisfiedLinkError("libnefte_runtimes.so not loaded")
            }
        val result =
            FlareGuardModelRunner.create(
                factory = factory,
                assetOpen = { validStream(entry) },
                manifestProvider = { entry },
            )
        assertIs<ModelLoadResult.RuntimeUnavailable>(result)
        assertTrue(GlobalModelDiagnostics.snapshot().isEmpty())
    }

    @Test
    fun interpreterConstructionFailureSettlesDiagnostic() {
        val entry = pinnedEntry()
        val factory =
            object : FlareGuardLoaderFactory {
                override fun loadAsset(): MappedByteBuffer = mappedBuffer()

                override fun newInterpreter(model: MappedByteBuffer): Interpreter =
                    throw IllegalStateException("model buffer does not look like a tflite flatbuffer")
            }
        val result =
            FlareGuardModelRunner.create(
                factory = factory,
                assetOpen = { validStream(entry) },
                manifestProvider = { entry },
            )
        assertIs<ModelLoadResult.UnsupportedContract>(result)
        assertTrue(GlobalModelDiagnostics.snapshot().isEmpty())
    }

    @Test
    fun ioFailureAfterValidationMapsToLoadFailed() {
        val entry = pinnedEntry()
        val factory =
            object : FlareGuardLoaderFactory {
                override fun loadAsset(): MappedByteBuffer = throw IOException("asset fd not found")

                override fun newInterpreter(model: MappedByteBuffer): Interpreter =
                    error("newInterpreter must not be called when loadAsset throws")
            }
        val result =
            FlareGuardModelRunner.create(
                factory = factory,
                assetOpen = { validStream(entry) },
                manifestProvider = { entry },
            )
        assertIs<ModelLoadResult.LoadFailed>(result)
        assertTrue(GlobalModelDiagnostics.snapshot().isEmpty())
    }

    @Test
    fun missingManifestEntrySettlesDiagnosticAsAssetMissing() {
        val result =
            FlareGuardModelRunner.create(
                factory = FakeFactory,
                assetOpen = { null },
                manifestProvider = { null },
            )
        assertIs<ModelLoadResult.AssetMissing>(result)
        assertTrue(GlobalModelDiagnostics.snapshot().isEmpty())
    }

    @Test
    fun noClassDefFoundSettlesDiagnosticAsRuntimeUnavailable() {
        val entry = pinnedEntry()
        val factory =
            object : FlareGuardLoaderFactory {
                override fun loadAsset(): MappedByteBuffer = mappedBuffer()

                override fun newInterpreter(model: MappedByteBuffer): Interpreter =
                    throw NoClassDefFoundError("org/tensorflow/lite/NativeInterpreterWrapper")
            }
        val result =
            FlareGuardModelRunner.create(
                factory = factory,
                assetOpen = { validStream(entry) },
                manifestProvider = { entry },
            )
        assertIs<ModelLoadResult.RuntimeUnavailable>(result)
        assertTrue(GlobalModelDiagnostics.snapshot().isEmpty())
    }

    @Test
    fun repeatedFailureDoesNotAccumulateGlobalContributors() {
        val entry = pinnedEntry()
        repeat(3) {
            val result =
                FlareGuardModelRunner.create(
                    factory = FakeFactory,
                    assetOpen = { null },
                    manifestProvider = { entry },
                )
            assertIs<ModelLoadResult.AssetMissing>(result)
        }
        assertEquals(0, GlobalModelDiagnostics.snapshot().size)
    }

    @Test
    fun postConstructionTensorInspectionFailureClosesExactlyOnce() {
        val interpreter = mockk<Interpreter>(relaxed = true)
        every { interpreter.inputTensorCount } returns 1
        every { interpreter.outputTensorCount } returns 1
        every { interpreter.getInputTensor(0) } throws IllegalStateException("inspection")
        val result = createWith(interpreter)
        assertIs<ModelLoadResult.UnsupportedContract>(result)
        verify(exactly = 1) { interpreter.close() }
        assertTrue(GlobalModelDiagnostics.snapshot().isEmpty())
    }

    @Test
    fun unsupportedTensorCountsCloseExactlyOnce() {
        for ((inputs, outputs) in listOf(0 to 1, 2 to 1, 1 to 0, 1 to 2)) {
            val interpreter = mockk<Interpreter>(relaxed = true)
            every { interpreter.inputTensorCount } returns inputs
            every { interpreter.outputTensorCount } returns outputs
            val result = createWith(interpreter)
            assertIs<ModelLoadResult.UnsupportedContract>(result)
            verify(exactly = 1) { interpreter.close() }
        }
    }

    @Test
    fun readyRunnerOwnsInterpreterUntilRepeatedClose() {
        val interpreter = validInterpreter()
        val result = createWith(interpreter)
        val ready = assertIs<ModelLoadResult.Ready<FlareGuardModelRunner>>(result)
        verify(exactly = 0) { interpreter.close() }
        assertTrue(GlobalModelDiagnostics.snapshot().isNotEmpty())
        ready.runner.close()
        ready.runner.close()
        verify(exactly = 1) { interpreter.close() }
        assertTrue(GlobalModelDiagnostics.snapshot().isEmpty())
    }

    @Test
    fun assertAlphaInContractRejectsOutOfToleranceValues() {
        assertContractFails(Float.NaN)
        assertContractFails(Float.POSITIVE_INFINITY)
        assertContractFails(Float.NEGATIVE_INFINITY)
        assertContractFails(-0.001f)
        assertContractFails(1.001f)
        // Values at tolerance edges are accepted.
        FlareGuardContract.assertAlphaInContract(0f)
        FlareGuardContract.assertAlphaInContract(1f)
        FlareGuardContract.assertAlphaInContract(FlareGuardContract.ALLOWED_ALPHA_TOLERANCE)
    }

    @Test
    fun structuredFailureReasonsRemainDistinct() {
        assertEquals(
            ModelFailureReason.StaleGeneration,
            FlareGuardModelRunner.classifyInferenceFailure(StaleModelGenerationException()),
        )
        assertEquals(
            ModelFailureReason.Cancelled,
            FlareGuardModelRunner.classifyInferenceFailure(
                kotlinx.coroutines.CancellationException("cancelled"),
            ),
        )
        assertIs<ModelLoadResult.LoadFailed>(ModelLoadResult.LoadFailed("x"))
    }

    /**
     * Real production-path retry test.
     *
     * Drives the actual loadValidated() transaction twice:
     * 1. First attempt: valid capability -> loadValidated -> transient LoadFailed -> registry Failed+factsLoadable
     * 2. Second attempt: same valid capability -> loadValidated -> Ready -> registry Loadable
     *
     * Uses the internal test seam to inject a factory that fails once then succeeds.
     */
    @Test
    fun retryableLoadValidatedSucceedsOnSecondAttempt() = runBlocking {
        // Seed valid capability through real Probe
        ModelAvailabilityRegistry.applyForTest(
            ModelFeature.FlareGuard,
            ModelCapabilityObservation(
                publisher = ModelCapabilityPublisher.Probe,
                generation = 1L,
                phase = ModelCapabilityPhase.Loadable,
                assetPresent = true,
                assetValid = true,
                runtimeAvailable = true,
                contractSupported = true,
                runnerImplemented = true,
            ),
        )

        val context = createTestContext()
        var attemptCount = 0
        var firstToken: ValidatedModelCapabilityToken? = null
        var secondToken: ValidatedModelCapabilityToken? = null

        // First attempt: transient failure
        val firstResult = FlareGuardModelRunner.loadValidated(context) { ctx, token ->
            attemptCount++
            if (attemptCount == 1) {
                firstToken = token
                ModelLoadResult.LoadFailed("transient stream error")
            } else {
                // Should not be called on first attempt
                error("factory should not be called again on first attempt")
            }
        }
        assertIs<ModelLoadResult.LoadFailed>(firstResult)

        // Verify registry state after first failure
        var state = ModelAvailabilityRegistry.state.value[ModelFeature.FlareGuard]!!
        assertEquals(ModelCapabilityPhase.Failed, state.phase)
        assertTrue(state.factsLoadable)
        assertTrue(state.canAttemptModelUse)
        assertFalse(state.sessionReady)

        // Second attempt: success
        val mockInterpreter = validInterpreter()
        val secondResult = FlareGuardModelRunner.loadValidated(context) { ctx, token ->
            attemptCount++
            secondToken = token
            createWithInternal(mockInterpreter)
        }
        val ready = assertIs<ModelLoadResult.Ready<FlareGuardModelRunner>>(secondResult)

        // Verify registry state after success
        state = ModelAvailabilityRegistry.state.value[ModelFeature.FlareGuard]!!
        assertEquals(ModelCapabilityPhase.Loadable, state.phase)
        assertTrue(state.factsLoadable)
        assertTrue(state.canAttemptModelUse)
        assertFalse(state.sessionReady) // loader success alone does NOT claim sessionReady

        // Verify exactly two factory invocations
        assertEquals(2, attemptCount)

        // Verify both tokens refer to ModelFeature.FlareGuard
        assertNotNull(firstToken)
        assertNotNull(secondToken)
        assertEquals(ModelFeature.FlareGuard, firstToken?.feature)
        assertEquals(ModelFeature.FlareGuard, secondToken?.feature)

        // Verify both tokens use the current authoritative validation generation
        // (no newer Probe occurred between the two attempts)
        val expectedValidationGen = state.validationEpoch
        assertEquals(expectedValidationGen, firstToken?.validationGeneration)
        assertEquals(expectedValidationGen, secondToken?.validationGeneration)

        // The two retries use the same validation generation
        assertEquals(firstToken?.validationGeneration, secondToken?.validationGeneration)

        ready.runner.close()
    }

    /**
     * Regression test: newer invalid authoritative probe blocks retry before Loading.
     *
     * Sequence:
     * 1. Valid probe -> Loadable
     * 2. loadValidated fails transiently -> Failed+factsLoadable
     * 3. Newer probe AssetInvalid
     * 4. loadValidated called again -> rejected with AssetInvalid, NO Loading published
     */
    @Test
    fun newerInvalidProbeBlocksRetryBeforeLoading() = runBlocking {
        // Step 1: Valid probe
        ModelAvailabilityRegistry.applyForTest(
            ModelFeature.FlareGuard,
            ModelCapabilityObservation(
                publisher = ModelCapabilityPublisher.Probe,
                generation = 1L,
                phase = ModelCapabilityPhase.Loadable,
                assetPresent = true,
                assetValid = true,
                runtimeAvailable = true,
                contractSupported = true,
                runnerImplemented = true,
            ),
        )

        val context = createTestContext()

        // Step 2: First load fails transiently
        val firstResult = FlareGuardModelRunner.loadValidated(context) { _, _ ->
            ModelLoadResult.LoadFailed("transient")
        }
        assertIs<ModelLoadResult.LoadFailed>(firstResult)

        var state = ModelAvailabilityRegistry.state.value[ModelFeature.FlareGuard]!!
        assertEquals(ModelCapabilityPhase.Failed, state.phase)
        assertTrue(state.factsLoadable)
        assertTrue(state.canAttemptModelUse)

        // Step 3: Newer authoritative probe publishes AssetInvalid
        ModelAvailabilityRegistry.applyForTest(
            ModelFeature.FlareGuard,
            ModelCapabilityObservation(
                publisher = ModelCapabilityPublisher.Probe,
                generation = 2L, // newer generation
                phase = ModelCapabilityPhase.AssetInvalid,
                assetPresent = true,
                assetValid = false,
            ),
        )

        // Step 4: Retry attempt should be rejected BEFORE Loading
        val loadGenBefore = ModelAvailabilityRegistry.state.value[ModelFeature.FlareGuard]!!.loadGeneration
        val retryResult = FlareGuardModelRunner.loadValidated(context) { _, _ ->
            error("factory must not be called - retry should be rejected at token validation")
        }
        val loadGenAfter = ModelAvailabilityRegistry.state.value[ModelFeature.FlareGuard]!!.loadGeneration

        // Result should be AssetInvalid (validation rejected)
        assertIs<ModelLoadResult.AssetInvalid>(retryResult)

        // No new Loading observation should have been published
        // (loadGeneration should not have incremented for a rejected retry)
        assertEquals(loadGenBefore, loadGenAfter)

        // Capability should now be structurally unavailable
        state = ModelAvailabilityRegistry.state.value[ModelFeature.FlareGuard]!!
        assertEquals(ModelCapabilityPhase.AssetInvalid, state.phase)
        assertFalse(state.canAttemptModelUse)
        assertFalse(state.sessionReady)
    }

    /**
     * Regression test: retained token is acquired before Loading and remains valid during runner creation.
     *
     * This test would fail if the production order were changed to:
     *   reportLoading() -> validatedCapabilityToken()
     *
     * Required observable behavior:
     * 1. Valid capability authorizes a token
     * 2. Production enters Loading
     * 3. Creator receives that pre-Loading token
     * 4. Registry.isCurrent(token) is still true during Loading
     * 5. Runner creation succeeds
     */
    @Test
    fun retainedTokenAcquiredBeforeLoadingRemainsValid() = runBlocking {
        ModelAvailabilityRegistry.applyForTest(
            ModelFeature.FlareGuard,
            ModelCapabilityObservation(
                publisher = ModelCapabilityPublisher.Probe,
                generation = 1L,
                phase = ModelCapabilityPhase.Loadable,
                assetPresent = true,
                assetValid = true,
                runtimeAvailable = true,
                contractSupported = true,
                runnerImplemented = true,
            ),
        )

        val context = createTestContext()
        val mockInterpreter = validInterpreter()
        var capturedToken: ValidatedModelCapabilityToken? = null
        var tokenWasCurrentDuringCreation = false

        val result = FlareGuardModelRunner.loadValidated(context) { ctx, token ->
            capturedToken = token
            // Verify token is still current according to registry during creation
            tokenWasCurrentDuringCreation = ModelAvailabilityRegistry.isCurrent(token)
            createWithInternal(mockInterpreter)
        }

        val ready = assertIs<ModelLoadResult.Ready<FlareGuardModelRunner>>(result)

        // Token was acquired and used
        assertNotNull(capturedToken)
        // Token was still current during runner creation (i.e., not invalidated by our own Loading)
        assertTrue(tokenWasCurrentDuringCreation)
        // Load succeeded
        assertNotNull(ready.runner)

        ready.runner.close()
    }

    private fun assertContractFails(value: Float) {
        assertFailsWith<IllegalArgumentException> {
            FlareGuardContract.assertAlphaInContract(value)
        }
    }

    private fun createWithInternal(interpreter: Interpreter): ModelLoadResult<FlareGuardModelRunner> {
        val entry = pinnedEntry()
        return FlareGuardModelRunner.create(
            factory =
                object : FlareGuardLoaderFactory {
                    override fun loadAsset(): MappedByteBuffer = mappedBuffer()
                    override fun newInterpreter(model: MappedByteBuffer): Interpreter = interpreter
                },
            assetOpen = { validStream(entry) },
            manifestProvider = { entry },
        )
    }

    private fun createWith(interpreter: Interpreter): ModelLoadResult<FlareGuardModelRunner> {
        GlobalModelDiagnostics.resetForTest(true)
        return createWithInternal(interpreter)
    }

    private fun createTestContext(): Context =
        org.robolectric.RuntimeEnvironment.getApplication()

    private fun validInterpreter(): Interpreter {
        val interpreter = mockk<Interpreter>()
        val input = tensor(intArrayOf(1, 2, 2, 3))
        val output = tensor(intArrayOf(1, 2, 2, 1))
        every { interpreter.inputTensorCount } returns 1
        every { interpreter.outputTensorCount } returns 1
        every { interpreter.getInputTensor(0) } returns input
        every { interpreter.getOutputTensor(0) } returns output
        every { interpreter.close() } just runs
        return interpreter
    }

    private fun tensor(shape: IntArray): Tensor =
        mockk<Tensor>().also { tensor ->
            every { tensor.shape() } returns shape
            every { tensor.dataType() } returns DataType.FLOAT32
            every { tensor.quantizationParams().scale } returns 0f
            every { tensor.quantizationParams().zeroPoint } returns 0
        }

    private fun pinnedEntry(): ModelAssetManifestEntry {
        val base = checkNotNull(ModelAssetManifest.byId("flare_masker"))
        return base.copy(
            asset =
                base.asset.copy(
                    sha256 = pinnedStreamHash(),
                    minimumExpectedBytes = validStreamByteCount().toLong(),
                    maximumExpectedBytes = 512L * 1024L * 1024L,
                ),
        )
    }

    private fun validStream(entry: ModelAssetManifestEntry): java.io.InputStream {
        val bytes = validStreamBytes()
        return ByteArrayInputStream(bytes)
    }

    private fun validStreamBytes(): ByteArray =
        ByteArray(validStreamByteCount()) { 7 }

    private fun validStreamByteCount(): Int = 1024

    private fun pinnedStreamHash(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(validStreamBytes())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Produce a real zero-length mapped buffer that satisfies the [MappedByteBuffer]
     * type for the seam; loadAsset's return value is opaque to the loader and the
     * factory immediately throws before inspecting it.
     */
    private fun mappedBuffer(): MappedByteBuffer {
        val tempFile = java.io.File.createTempFile("flare-guard-fake", ".bin")
        tempFile.deleteOnExit()
        return java.io.RandomAccessFile(tempFile, "r").channel.use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, 0L, 0L)
        }
    }

    private object FakeFactory : FlareGuardLoaderFactory {
        override fun loadAsset(): MappedByteBuffer =
            error("loadAsset must not be called when asset missing returns early")

        override fun newInterpreter(model: MappedByteBuffer): Interpreter =
            throw IOException("fake factory never builds an interpreter")
    }
}
