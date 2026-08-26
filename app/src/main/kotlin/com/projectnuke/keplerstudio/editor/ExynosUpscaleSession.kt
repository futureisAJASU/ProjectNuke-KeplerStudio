package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * One authoritative Samsung ENN inference session for Real_ESRGAN_General_x4v3.
 *
 * Ownership rules (Phase N1):
 *  - every native handle belongs to exactly one [ExynosUpscaleSession] instance;
 *  - load/run/close are serialized by [lifecycleMutex], so close racing a parked
 *    inference settles deterministically and no use-after-close exists;
 *  - physical teardown happens exactly once; repeated close is a no-op;
 *  - a stale [ValidatedModelCapabilityToken] is rejected before any native work;
 *  - a stale [ModelOperationContext] never reaches native code;
 *  - unsupported devices/NNC variants surface as structured failures — the adapter
 *    has no CPU fallback path to misreport.
 */
internal class ExynosUpscaleSession(
    private val context: Context,
    private val native: ExynosEnnNativeInterface = ExynosEnnNative,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val preparedModelFileProvider: (ModelAssetContract) -> ModelLoadResult<File> =
        { asset -> prepareModelFile(context, asset) },
) : ModelRunnerContract {

    @Volatile
    override var lifecycle: ModelRunnerLifecycle = ModelRunnerLifecycle.Unloaded
        private set

    @Volatile
    override var descriptor: ModelRunnerDescriptor? = null
        private set

    private val lifecycleMutex = Mutex()

    private var modelId: Long = NO_MODEL_ID
    private var bufferSet: Long = 0L
    private var bufferCount: Int = 0
    private var nativeInitialized: Boolean = false
    private var registrySessionGeneration: Long = NO_REGISTRY_GENERATION
    private var preparedModelFile: File? = null

    suspend fun load(token: ValidatedModelCapabilityToken): ModelLoadResult<Unit> =
        withContext(ioDispatcher) {
            lifecycleMutex.withLock {
                when (lifecycle) {
                    ModelRunnerLifecycle.Loading,
                    ModelRunnerLifecycle.Loaded,
                    ModelRunnerLifecycle.Inferencing,
                    ModelRunnerLifecycle.Closing,
                    ->
                        return@withLock ModelLoadResult.LoadFailed(
                            "another authoritative load already owns this session",
                        )
                    ModelRunnerLifecycle.Failed ->
                        return@withLock ModelLoadResult.LoadFailed(
                            "session is in terminal Failed state",
                        )
                    else -> Unit
                }
                if (!ModelAvailabilityRegistry.isCurrent(token)) {
                    return@withLock ModelLoadResult.LoadFailed("capability token is stale")
                }
                val generation =
                    ModelAvailabilityRegistry.reportLoading(ModelFeature.ExynosUpscale)
                lifecycle = ModelRunnerLifecycle.Loading
                try {
                    if (!native.probeRuntime()) {
                        lifecycle = ModelRunnerLifecycle.Failed
                        return@withLock settled(
                            ModelLoadResult.RuntimeUnavailable(ENN_RUNTIME_UNAVAILABLE_DETAIL),
                            generation,
                        )
                    }
                    loadLocked(token, generation)
                } catch (cancelled: CancellationException) {
                    teardownNativeLocked()
                    lifecycle = ModelRunnerLifecycle.Failed
                    ModelAvailabilityRegistry.reportLoad(
                        ModelFeature.ExynosUpscale,
                        ModelLoadResult.LoadFailed("load cancelled"),
                        generation,
                    )
                    throw cancelled
                } catch (t: Throwable) {
                    Log.e(TAG, "ENN load failed unexpectedly", t)
                    teardownNativeLocked()
                    lifecycle = ModelRunnerLifecycle.Failed
                    settled(
                        ModelLoadResult.LoadFailed(t.message ?: "ENN load failed"),
                        generation,
                    )
                }
            }
        }

    /**
     * Runs one x4 upscale over an ARGB pixel buffer of exactly [INPUT_WIDTH] x [INPUT_HEIGHT]
     * and returns the upscaled RGB result as a new bitmap owned by the caller.
     */
    suspend fun run(
        argbPixels: IntArray,
        operationContext: ModelOperationContext,
    ): ModelRunResult<Bitmap> {
        return withContext(ioDispatcher) {
            lifecycleMutex.withLock {
                if (operationContext.isCancelled()) {
                    return@withLock cancelledRunFailure()
                }
                if (!operationContext.isCurrent(
                        operationContext.operationToken,
                        operationContext.documentGeneration,
                    )
                ) {
                    return@withLock staleRunFailure()
                }
                when (lifecycle) {
                    ModelRunnerLifecycle.Loaded -> Unit
                    ModelRunnerLifecycle.Inferencing ->
                        return@withLock closedRunFailure("another inference owns this session")
                    else ->
                        return@withLock closedRunFailure("session is not Loaded")
                }
                if (argbPixels.size != INPUT_WIDTH * INPUT_HEIGHT) {
                    return@withLock ModelRunResult.Failure(
                        ModelFailure(
                            ModelFailureReason.InvalidInput,
                            "input must be exactly ${INPUT_WIDTH}x$INPUT_HEIGHT ARGB pixels",
                        ),
                        DeterministicModelFallback.NoResult,
                    )
                }
                try {
                    runLocked(argbPixels)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    Log.e(TAG, "ENN inference failed unexpectedly", t)
                    ModelRunResult.Failure(
                        ModelFailure(ModelFailureReason.InferenceFailed, t.message),
                        DeterministicModelFallback.NoResult,
                    )
                }
            }
        }
    }

    /** Terminal ownership boundary; safe to call repeatedly and from any thread. */
    suspend fun close() {
        withContext(NonCancellable + ioDispatcher) {
            lifecycleMutex.withLock {
                if (lifecycle == ModelRunnerLifecycle.Closing || lifecycle == ModelRunnerLifecycle.Unloaded) return@withLock
                lifecycle = ModelRunnerLifecycle.Closing
                try {
                    teardownNativeLocked()
                    if (registrySessionGeneration != NO_REGISTRY_GENERATION) {
                        ModelAvailabilityRegistry.reportSessionClosed(
                            listOf(ModelFeature.ExynosUpscale),
                            registrySessionGeneration,
                        )
                        registrySessionGeneration = NO_REGISTRY_GENERATION
                    }
                } finally {
                    lifecycle = ModelRunnerLifecycle.Unloaded
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Internals — all called while holding [lifecycleMutex]
    // ------------------------------------------------------------------

    private suspend fun loadLocked(
        token: ValidatedModelCapabilityToken,
        generation: Long,
    ): ModelLoadResult<Unit> {
        coroutineContext.ensureActive()
        val manifest =
            ModelAssetManifest.byId(EXYNOS_MODEL_ID)
                ?: return settled(
                    ModelLoadResult.UnsupportedContract("model manifest is not registered"),
                    generation,
                )
        if (token.modelId != EXYNOS_MODEL_ID ||
            manifest.asset.runtimeType != ModelRuntimeType.ExynosEnn ||
            manifest.id != EXYNOS_MODEL_ID
        ) {
            return settled(
                ModelLoadResult.UnsupportedContract("capability token does not match the pinned manifest"),
                generation,
            )
        }
        val preparedFile: File =
            when (val file = preparedModelFileProvider(manifest.asset)) {
                is ModelLoadResult.Ready -> {
                    preparedModelFile = file.runner
                    file.runner
                }
                is ModelLoadResult.AssetMissing,
                is ModelLoadResult.AssetInvalid,
                is ModelLoadResult.UnsupportedContract,
                is ModelLoadResult.RuntimeUnavailable,
                is ModelLoadResult.LoadFailed,
                -> return settled(file, generation)
            }

        coroutineContext.ensureActive()
        if (native.initialize() != EnnStatus.SUCCESS) {
            nativeInitialized = false
            return settled(
                ModelLoadResult.RuntimeUnavailable("EnnInitialize failed"),
                generation,
            )
        }
        nativeInitialized = true
        try {
            coroutineContext.ensureActive()
            modelId = native.openModel(preparedFile.absolutePath)
            if (modelId < 0L) {
                return settleAfterDeinitOnly(
                    ModelLoadResult.RuntimeUnavailable(OPEN_MODEL_FAILURE_DETAIL),
                    generation,
                )
            }
            val allocation = native.allocateAllBuffers(modelId)
            if (allocation == null || allocation.size != ALLOCATION_INFO_SIZE) {
                return settleAfterCloseModel(
                    ModelLoadResult.RuntimeUnavailable(BUFFER_ALLOCATION_FAILURE_DETAIL),
                    generation,
                )
            }
            bufferSet = allocation[BUFFER_SET_INDEX]
            val nIn = allocation[N_IN_INDEX].toInt()
            val nOut = allocation[N_OUT_INDEX].toInt()
            bufferCount = nIn + nOut
            if (nIn != EXPECTED_TENSOR_COUNT || nOut != EXPECTED_TENSOR_COUNT) {
                return settleAfterFullTeardown(
                    ModelLoadResult.UnsupportedContract(
                        "expected one input and one output tensor, got $nIn/$nOut",
                    ),
                    generation,
                )
            }
            val inputInfo =
                native.getBufferInfoByIndex(modelId, ENN_DIR_IN, 0)
                    ?: return settleAfterFullTeardown(
                        ModelLoadResult.UnsupportedContract("input tensor info unavailable"),
                        generation,
                    )
            val outputInfo =
                native.getBufferInfoByIndex(modelId, ENN_DIR_OUT, 0)
                    ?: return settleAfterFullTeardown(
                        ModelLoadResult.UnsupportedContract("output tensor info unavailable"),
                        generation,
                    )
            val expectedInput =
                intArrayOf(1, INPUT_WIDTH, INPUT_HEIGHT, INPUT_CHANNELS, INPUT_BYTES)
            val expectedOutput =
                intArrayOf(1, OUTPUT_WIDTH, OUTPUT_HEIGHT, OUTPUT_CHANNELS, OUTPUT_BYTES)
            if (!inputInfo.contentEquals(expectedInput)) {
                return settleAfterFullTeardown(
                    ModelLoadResult.UnsupportedContract(
                        "input tensor ${inputInfo.toList()} violates the pinned CHW 3x128x128 FP32 contract",
                    ),
                    generation,
                )
            }
            if (!outputInfo.contentEquals(expectedOutput)) {
                return settleAfterFullTeardown(
                    ModelLoadResult.UnsupportedContract(
                        "output tensor ${outputInfo.toList()} violates the pinned CHW 3x512x512 FP32 contract",
                    ),
                    generation,
                )
            }
            Log.i(TAG, "ENN session ready; compiler=${native.getMetaInfo(META_MODEL_COMPILER_NNC, modelId)}")

            descriptor = buildDescriptor(manifest)
            lifecycle = ModelRunnerLifecycle.Loaded
            registrySessionGeneration =
                ModelAvailabilityRegistry.reportSessionReady(listOf(ModelFeature.ExynosUpscale))
            ModelAvailabilityRegistry.reportLoad(
                ModelFeature.ExynosUpscale,
                ModelLoadResult.Ready(Unit),
                generation,
            )
            return ModelLoadResult.Ready(Unit)
        } catch (t: Throwable) {
            teardownNativeLocked()
            lifecycle = ModelRunnerLifecycle.Failed
            throw t
        }
    }

    private fun buildDescriptor(manifest: ModelAssetManifestEntry): ModelRunnerDescriptor =
        ModelRunnerDescriptor(
            modelId = manifest.id,
            asset = manifest.asset,
            input =
                ModelInputContract(
                    width = INPUT_WIDTH,
                    height = INPUT_HEIGHT,
                    channels = INPUT_CHANNELS,
                    layout = ModelTensorLayout.NCHW,
                    dataType = ModelTensorDataType.Float32,
                    quantization = null,
                    channelOrder = ModelChannelOrder.RGB,
                    colorSpace = ModelColorSpace.SRGB,
                    normalization = "pixel / 255.0 into [0, 1]; no mean/std offset",
                    acceptedBitmapConfigs = setOf(Bitmap.Config.ARGB_8888),
                    resizePolicy = ModelResizePolicy.Exact,
                    alphaHandling = ModelAlphaHandling.Ignore,
                ),
            output =
                ModelOutputContract(
                    width = OUTPUT_WIDTH,
                    height = OUTPUT_HEIGHT,
                    channelsOrClasses = OUTPUT_CHANNELS,
                    layout = ModelTensorLayout.NCHW,
                    dataType = ModelTensorDataType.Float32,
                    quantization = null,
                    semantic = ModelOutputSemantic.RestorationImage,
                    valueRange = 0f..1f,
                    confidenceMeaning = null,
                ),
            inferenceAdapterImplemented = true,
            productionReady = false,
            knownMemoryBytes = INPUT_BYTES.toLong() + OUTPUT_BYTES,
            hasUnknownRuntimeMemory = true,
        )

    private fun runLocked(argbPixels: IntArray): ModelRunResult<Bitmap> {
        val inputBuffer = preprocess(argbPixels)
        val inputBytes = ByteArray(INPUT_BYTES)
        inputBuffer.get(inputBytes)
        if (!native.memcpyHostToDevice(bufferSet, 0, inputBytes)) {
            return ModelRunResult.Failure(
                ModelFailure(ModelFailureReason.InvalidInput, "input copy into the ENN buffer failed"),
                DeterministicModelFallback.NoResult,
            )
        }
        lifecycle = ModelRunnerLifecycle.Inferencing
        try {
            checkCancellation()
            if (!native.execute(modelId)) {
                return ModelRunResult.Failure(
                    ModelFailure(ModelFailureReason.InferenceFailed, "EnnExecuteModel failed"),
                    DeterministicModelFallback.NoResult,
                )
            }
        } finally {
            lifecycle = ModelRunnerLifecycle.Loaded
        }
        val outputBytes = ByteArray(OUTPUT_BYTES)
        if (!native.memcpyDeviceToHost(bufferSet, ENN_OUTPUT_INDEX, outputBytes)) {
            return ModelRunResult.Failure(
                ModelFailure(ModelFailureReason.InvalidOutput, "output copy from the ENN buffer failed"),
                DeterministicModelFallback.NoResult,
            )
        }
        return buildOutputBitmap(outputBytes)
    }

    private fun buildOutputBitmap(outputBytes: ByteArray): ModelRunResult<Bitmap> {
        val floats =
            ByteBuffer.wrap(outputBytes)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        if (floats.remaining() != OUTPUT_WIDTH * OUTPUT_HEIGHT * OUTPUT_CHANNELS) {
            return ModelRunResult.Failure(
                ModelFailure(ModelFailureReason.InvalidOutput, "output tensor size mismatch"),
                DeterministicModelFallback.NoResult,
            )
        }
        val planeSize = OUTPUT_WIDTH * OUTPUT_HEIGHT
        val pixels = IntArray(planeSize)
        for (i in 0 until planeSize) {
            val r = floats.get(i)
            val g = floats.get(planeSize + i)
            val b = floats.get(2 * planeSize + i)
            if (!(r.isFinite() && g.isFinite() && b.isFinite())) {
                return ModelRunResult.Failure(
                    ModelFailure(ModelFailureReason.InvalidOutput, "output contains NaN/Inf values"),
                    DeterministicModelFallback.NoResult,
                )
            }
            val red = (r.coerceIn(0f, 1f) * 255f).toInt()
            val green = (g.coerceIn(0f, 1f) * 255f).toInt()
            val blue = (b.coerceIn(0f, 1f) * 255f).toInt()
            pixels[i] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }
        val bitmap = Bitmap.createBitmap(OUTPUT_WIDTH, OUTPUT_HEIGHT, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, OUTPUT_WIDTH, 0, 0, OUTPUT_WIDTH, OUTPUT_HEIGHT)
        return ModelRunResult.Success(bitmap, confidence = 1f, confidenceMetrics = null)
    }

    /** CHW float32 RGB normalized to [0,1] in native byte order. */
    private fun preprocess(argbPixels: IntArray): ByteBuffer {
        val totalPixels = argbPixels.size
        val floats = FloatArray(totalPixels * INPUT_CHANNELS)
        for (i in 0 until totalPixels) {
            val color = argbPixels[i]
            floats[i] = ((color shr 16) and 0xFF) / 255f
            floats[totalPixels + i] = ((color shr 8) and 0xFF) / 255f
            floats[2 * totalPixels + i] = (color and 0xFF) / 255f
        }
        return ByteBuffer.allocateDirect(floats.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .also { it.asFloatBuffer().put(floats) }
    }

    private fun settled(result: ModelLoadResult<Unit>, generation: Long): ModelLoadResult<Unit> {
        ModelAvailabilityRegistry.reportLoad(ModelFeature.ExynosUpscale, result, generation)
        if (result !is ModelLoadResult.Ready) {
            teardownNativeLocked()
            lifecycle = ModelRunnerLifecycle.Unloaded
        }
        return result
    }

    /** OpenModel failed before any handle beyond the framework context existed. */
    private fun settleAfterDeinitOnly(result: ModelLoadResult<Unit>, generation: Long): ModelLoadResult<Unit> {
        modelId = NO_MODEL_ID
        runCatching { native.deinitialize() }
            .onFailure { Log.w(TAG, "EnnDeinitialize failed", it) }
        nativeInitialized = false
        preparedModelFile?.let { file ->
            runCatching { file.delete() }
                .onFailure { Log.w(TAG, "Prepared model file deletion failed", it) }
        }
        preparedModelFile = null
        ModelAvailabilityRegistry.reportLoad(ModelFeature.ExynosUpscale, result, generation)
        lifecycle = ModelRunnerLifecycle.Unloaded
        return result
    }

    /** CloseModel succeeded; buffers were never allocated or are already released. */
    private fun settleAfterCloseModel(result: ModelLoadResult<Unit>, generation: Long): ModelLoadResult<Unit> {
        runCatching { native.closeModel(modelId) }
            .onFailure { Log.w(TAG, "EnnCloseModel failed", it) }
        modelId = NO_MODEL_ID
        runCatching { native.deinitialize() }
            .onFailure { Log.w(TAG, "EnnDeinitialize failed", it) }
        nativeInitialized = false
        preparedModelFile?.let { file ->
            runCatching { file.delete() }
                .onFailure { Log.w(TAG, "Prepared model file deletion failed", it) }
        }
        preparedModelFile = null
        ModelAvailabilityRegistry.reportLoad(ModelFeature.ExynosUpscale, result, generation)
        lifecycle = ModelRunnerLifecycle.Unloaded
        return result
    }

    /** Buffers/model/context all exist; release them in reverse order. */
    private fun settleAfterFullTeardown(result: ModelLoadResult<Unit>, generation: Long): ModelLoadResult<Unit> {
        teardownNativeLocked()
        ModelAvailabilityRegistry.reportLoad(ModelFeature.ExynosUpscale, result, generation)
        lifecycle = ModelRunnerLifecycle.Unloaded
        return result
    }

    /**
     * Total physical teardown; every step attempted exactly once.
     * Failures are logged truthfully but do not prevent clearing local ownership.
     */
    private fun teardownNativeLocked() {
        if (bufferSet != 0L && bufferCount > 0) {
            runCatching { native.releaseBuffers(bufferSet, bufferCount) }
                .onFailure { Log.w(TAG, "EnnReleaseBuffers failed", it) }
        }
        bufferSet = 0L
        bufferCount = 0
        if (modelId != NO_MODEL_ID) {
            runCatching { native.closeModel(modelId) }
                .onFailure { Log.w(TAG, "EnnCloseModel failed", it) }
        }
        modelId = NO_MODEL_ID
        if (nativeInitialized) {
            runCatching { native.deinitialize() }
                .onFailure { Log.w(TAG, "EnnDeinitialize failed", it) }
            nativeInitialized = false
        }
        preparedModelFile?.let { file ->
            runCatching { file.delete() }
                .onFailure { Log.w(TAG, "Prepared model file deletion failed", it) }
        }
        preparedModelFile = null
        descriptor = null
    }

    private fun staleRunFailure(): ModelRunResult<Bitmap> =
        ModelRunResult.Failure(
            ModelFailure(ModelFailureReason.StaleGeneration),
            DeterministicModelFallback.NoResult,
        )

    private fun cancelledRunFailure(): ModelRunResult<Bitmap> =
        ModelRunResult.Failure(
            ModelFailure(ModelFailureReason.Cancelled),
            DeterministicModelFallback.NoResult,
        )

    private fun closedRunFailure(detail: String): ModelRunResult<Bitmap> =
        ModelRunResult.Failure(
            ModelFailure(ModelFailureReason.Closed, detail),
            DeterministicModelFallback.NoResult,
        )

    private fun checkCancellation() {
        if (lifecycle == ModelRunnerLifecycle.Closing || lifecycle == ModelRunnerLifecycle.Unloaded) {
            throw CancellationException("session closing or closed")
        }
    }

    companion object {
        private const val TAG = "KeplerExynosUpscale"
        internal const val EXYNOS_MODEL_ID = "exynos_real_esrgan_x4v3"

        internal const val INPUT_WIDTH = 128
        internal const val INPUT_HEIGHT = 128
        internal const val INPUT_CHANNELS = 3
        internal const val INPUT_BYTES = INPUT_WIDTH * INPUT_HEIGHT * INPUT_CHANNELS * 4

        internal const val OUTPUT_WIDTH = 512
        internal const val OUTPUT_HEIGHT = 512
        internal const val OUTPUT_CHANNELS = 3
        internal const val OUTPUT_BYTES = OUTPUT_WIDTH * OUTPUT_HEIGHT * OUTPUT_CHANNELS * 4

        private const val EXPECTED_TENSOR_COUNT = 1
        private const val ALLOCATION_INFO_SIZE = 3
        private const val BUFFER_SET_INDEX = 0
        private const val N_IN_INDEX = 1
        private const val N_OUT_INDEX = 2
        private const val ENN_OUTPUT_INDEX = 1

        // enn_buf_dir_e
        private const val ENN_DIR_IN = 0
        private const val ENN_DIR_OUT = 1

        // EnnMetaTypeId
        private const val META_MODEL_COMPILER_NNC = 120

        private const val NO_MODEL_ID = -1L
        private const val NO_REGISTRY_GENERATION = -1L

        private const val ENN_RUNTIME_UNAVAILABLE_DETAIL =
            "Exynos ENN runtime is unavailable on this device"
        private const val OPEN_MODEL_FAILURE_DETAIL =
            "EnnOpenModel failed — NNC variant incompatible with this device"
        private const val BUFFER_ALLOCATION_FAILURE_DETAIL =
            "EnnAllocateAllBuffers failed — NNC variant likely incompatible with this device"
    }
}

/** EnnReturn codes used by the adapter. */
internal object EnnStatus {
    const val SUCCESS = 0
}

/**
 * Production preparation: validate the packaged NNC against its pinned manifest entry,
 * copy it to app-private storage, and verify size + SHA-256 during the copy so the file
 * handed to EnnOpenModel is provably the pinned artifact.
 */
private fun prepareModelFile(
    context: Context,
    asset: ModelAssetContract,
): ModelLoadResult<File> =
    runCatching {
        val manifest =
            requireNotNull(ModelAssetManifest.byId(ExynosUpscaleSession.EXYNOS_MODEL_ID)) {
                "exynos manifest entry missing"
            }
        when (
            val validation =
                ModelAssetValidator.validate(manifest) { path ->
                    runCatching { context.assets.open(path) }.getOrNull()
                }
        ) {
            ModelAssetValidation.Missing -> return@runCatching ModelLoadResult.AssetMissing(null)
            is ModelAssetValidation.Invalid ->
                return@runCatching ModelLoadResult.AssetInvalid(validation.detail)
            is ModelAssetValidation.Valid -> validation
        }.let { validation ->
            if (validation !is ModelAssetValidation.Valid) {
                return@runCatching ModelLoadResult.AssetInvalid("model asset validation failed")
            }
            val targetDir = File(context.filesDir, "exynos_models").apply { mkdirs() }
            val target = File(targetDir, File(asset.assetPath).name)
            context.assets.open(asset.assetPath).use { input ->
                copyVerifying(input, target, validation.sha256, validation.byteCount)
            }
            ModelLoadResult.Ready(target)
        }
    }.getOrElse { failure ->
        ModelLoadResult.LoadFailed(failure.message ?: "model preparation failed")
    }

private fun copyVerifying(
    input: InputStream,
    target: File,
    expectedSha256: String,
    expectedBytes: Long,
) {
    val digest = MessageDigest.getInstance("SHA-256")
    FileOutputStream(target).use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
            total += read
        }
        output.flush()
        output.fd.sync()
        check(total == expectedBytes) { "copied model size mismatch: $total" }
        val hash = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        check(hash.equals(expectedSha256, ignoreCase = true)) { "copied model SHA-256 mismatch" }
    }
}
