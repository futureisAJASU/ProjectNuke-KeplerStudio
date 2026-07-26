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
import kotlin.test.assertIs
import kotlin.test.assertTrue
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
class FlareGuardModelLoaderLifecycleTest {
    @Test
    fun assetMissingSettlesDiagnosticAndReturnsAssetMissing() {
        GlobalModelDiagnostics.resetForTest(true)
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
        GlobalModelDiagnostics.resetForTest(true)
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
        GlobalModelDiagnostics.resetForTest(true)
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
        GlobalModelDiagnostics.resetForTest(true)
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
        GlobalModelDiagnostics.resetForTest(true)
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
        GlobalModelDiagnostics.resetForTest(true)
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
        GlobalModelDiagnostics.resetForTest(true)
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
        GlobalModelDiagnostics.resetForTest(true)
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

    private fun assertContractFails(value: Float) {
        assertFailsWith<IllegalArgumentException> {
            FlareGuardContract.assertAlphaInContract(value)
        }
    }

    private fun createWith(interpreter: Interpreter): ModelLoadResult<FlareGuardModelRunner> {
        GlobalModelDiagnostics.resetForTest(true)
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
