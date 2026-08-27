package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
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
 * Ownership rules (Phase N1 / N2):
 *  - every native handle belongs to exactly one [ExynosUpscaleSession] instance;
 *  - load/run/close are serialized by [lifecycleMutex], so close racing a parked
 *    inference settles deterministically and no use-after-close exists;
 *  - physical teardown happens exactly once; repeated close is a no-op;
 *  - physical destruction failure leaves local native ownership cleared to prevent
 *    use-after-close without retrying native destruction;
 *  - each session prepares its model into an exclusive, session-scoped file so cross-session
 *    deletion of a live model file is structurally impossible regardless of how long the
 *    runtime keeps the path open;
 *  - a stale [ValidatedModelCapabilityToken] is rejected before any native work;
 *  - a stale [ModelOperationContext] never reaches native code;
 *  - unsupported devices/NNC variants surface as structured failures with exact raw
 *    EnnReturn statuses — the adapter has no CPU fallback path to misreport.
 */
internal class ExynosUpscaleSession(
    private val context: Context,
    private val native: ExynosEnnNativeInterface = ExynosEnnNative,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val preparedModelFileProvider: ((ModelAssetContract) -> ModelLoadResult<File>)? = null,
) : ModelRunnerContract {

    @Volatile
    override var lifecycle: ModelRunnerLifecycle = ModelRunnerLifecycle.Unloaded
        private set

    @Volatile
    override var descriptor: ModelRunnerDescriptor? = null
        private set

    /**
     * Test diagnostic seam: per-step physical outcome of the most recent native
     * destruction attempt (never overwritten by no-op teardown passes).
     */
    @Volatile
    internal var lastTeardownResult: TeardownResult? = null
        private set

    private val lifecycleMutex = Mutex()

    private var modelId: Long = NO_MODEL_ID
    private var bufferSet: Long = 0L
    private var bufferCount: Int = 0
    private var nativeInitialized: Boolean = false
    private var registrySessionGeneration: Long = NO_REGISTRY_GENERATION
    private var preparedModelFile: File? = null
    private val preparedFileToken = "session-${PREPARED_FILE_SEQUENCE.getAndIncrement()}"

    @Volatile
    internal var preExecuteCheck: (suspend () -> Unit)? = null

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
                        return@withLock settleAfterPhysicalTeardown(
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
                    settleAfterPhysicalTeardown(
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

    private fun providePreparedModel(asset: ModelAssetContract): ModelLoadResult<File> =
        preparedModelFileProvider?.invoke(asset)
            ?: prepareModelFile(context, asset, preparedFileToken)

    private suspend fun loadLocked(
        token: ValidatedModelCapabilityToken,
        generation: Long,
    ): ModelLoadResult<Unit> {
        coroutineContext.ensureActive()
        val manifest =
            ModelAssetManifest.byId(EXYNOS_MODEL_ID)
                ?: return settleAfterPhysicalTeardown(
                    ModelLoadResult.UnsupportedContract("model manifest is not registered"),
                    generation,
                )
        if (token.modelId != EXYNOS_MODEL_ID ||
            manifest.asset.runtimeType != ModelRuntimeType.ExynosEnn ||
            manifest.id != EXYNOS_MODEL_ID
        ) {
            return settleAfterPhysicalTeardown(
                ModelLoadResult.UnsupportedContract("capability token does not match the pinned manifest"),
                generation,
            )
        }
        val preparedFile: File =
            when (val file = providePreparedModel(manifest.asset)) {
                is ModelLoadResult.Ready -> {
                    preparedModelFile = file.runner
                    file.runner
                }
                is ModelLoadResult.AssetMissing,
                is ModelLoadResult.AssetInvalid,
                is ModelLoadResult.UnsupportedContract,
                is ModelLoadResult.RuntimeUnavailable,
                is ModelLoadResult.LoadFailed,
                -> return settleAfterPhysicalTeardown(file, generation)
            }

        coroutineContext.ensureActive()
        val initStatus = native.initialize()
        if (initStatus != EnnStatus.SUCCESS) {
            nativeInitialized = false
            return settleAfterPhysicalTeardown(
                ModelLoadResult.RuntimeUnavailable(
                    "EnnInitialize failed: EnnReturn=${EnnStatus.describe(initStatus)}",
                ),
                generation,
            )
        }
        nativeInitialized = true
        try {
            coroutineContext.ensureActive()
            val openResult = native.openModel(preparedFile.absolutePath)
            if (!openResult.isSuccess) {
                return settleAfterPhysicalTeardown(
                    ModelLoadResult.RuntimeUnavailable(
                        "EnnOpenModel failed: EnnReturn=${EnnStatus.describe(openResult.status)}",
                    ),
                    generation,
                )
            }
            modelId = openResult.modelId

            val allocation = native.allocateAllBuffers(modelId)
            if (!allocation.isSuccess) {
                return settleAfterPhysicalTeardown(
                    ModelLoadResult.RuntimeUnavailable(
                        "EnnAllocateAllBuffers failed: EnnReturn=${EnnStatus.describe(allocation.status)}",
                    ),
                    generation,
                )
            }
            bufferSet = allocation.bufferSet
            val nIn = allocation.nInBuffers
            val nOut = allocation.nOutBuffers
            bufferCount = nIn + nOut
            if (nIn != EXPECTED_TENSOR_COUNT || nOut != EXPECTED_TENSOR_COUNT) {
                return settleAfterPhysicalTeardown(
                    ModelLoadResult.UnsupportedContract(
                        "expected one input and one output tensor, got $nIn/$nOut",
                    ),
                    generation,
                )
            }
            val inputInfo =
                native.getBufferInfoByIndex(modelId, ENN_DIR_IN, 0)
                    ?: return settleAfterPhysicalTeardown(
                        ModelLoadResult.UnsupportedContract("input tensor info unavailable"),
                        generation,
                    )
            val outputInfo =
                native.getBufferInfoByIndex(modelId, ENN_DIR_OUT, 0)
                    ?: return settleAfterPhysicalTeardown(
                        ModelLoadResult.UnsupportedContract("output tensor info unavailable"),
                        generation,
                    )
            val expectedInput =
                intArrayOf(1, INPUT_WIDTH, INPUT_HEIGHT, INPUT_CHANNELS, INPUT_BYTES)
            val expectedOutput =
                intArrayOf(1, OUTPUT_WIDTH, OUTPUT_HEIGHT, OUTPUT_CHANNELS, OUTPUT_BYTES)
            if (!inputInfo.contentEquals(expectedInput)) {
                return settleAfterPhysicalTeardown(
                    ModelLoadResult.UnsupportedContract(
                        "input tensor ${inputInfo.toList()} violates the pinned CHW 3x128x128 FP32 contract",
                    ),
                    generation,
                )
            }
            if (!outputInfo.contentEquals(expectedOutput)) {
                return settleAfterPhysicalTeardown(
                    ModelLoadResult.UnsupportedContract(
                        "output tensor ${outputInfo.toList()} violates the pinned CHW 3x512x512 FP32 contract",
                    ),
                    generation,
                )
            }
            Log.i(
                TAG,
                "ENN session ready; compiler=${native.getMetaInfo(EnnMetaIds.MODEL_COMPILER_NNC, modelId)}",
            )

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

    private suspend fun runLocked(argbPixels: IntArray): ModelRunResult<Bitmap> {
        val inputBuffer = preprocess(argbPixels)
        val inputBytes = ByteArray(INPUT_BYTES)
        inputBuffer.get(inputBytes)
        val memcpyInStatus = native.memcpyHostToDevice(bufferSet, 0, inputBytes)
        if (memcpyInStatus != EnnStatus.SUCCESS) {
            return ModelRunResult.Failure(
                ModelFailure(
                    ModelFailureReason.InvalidInput,
                    "input copy into the ENN buffer failed: EnnReturn=${EnnStatus.describe(memcpyInStatus)}",
                ),
                DeterministicModelFallback.NoResult,
            )
        }
        lifecycle = ModelRunnerLifecycle.Inferencing
        try {
            checkCancellation()
            coroutineContext.ensureActive()
            preExecuteCheck?.invoke()
            // Authoritative coroutine cancellation boundary AFTER the test-only park
            // seam, immediately before native EnnExecuteModel execution.
            coroutineContext.ensureActive()
            val executeStatus = native.execute(modelId)
            if (executeStatus != EnnStatus.SUCCESS) {
                return ModelRunResult.Failure(
                    ModelFailure(
                        ModelFailureReason.InferenceFailed,
                        "EnnExecuteModel failed: EnnReturn=${EnnStatus.describe(executeStatus)}",
                    ),
                    DeterministicModelFallback.NoResult,
                )
            }
        } finally {
            lifecycle = ModelRunnerLifecycle.Loaded
        }
        val outputBytes = ByteArray(OUTPUT_BYTES)
        val memcpyOutStatus = native.memcpyDeviceToHost(bufferSet, ENN_OUTPUT_INDEX, outputBytes)
        if (memcpyOutStatus != EnnStatus.SUCCESS) {
            return ModelRunResult.Failure(
                ModelFailure(
                    ModelFailureReason.InvalidOutput,
                    "output copy from the ENN buffer failed: EnnReturn=${EnnStatus.describe(memcpyOutStatus)}",
                ),
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

    private fun settleAfterPhysicalTeardown(
        result: ModelLoadResult<Unit>,
        generation: Long,
    ): ModelLoadResult<Unit> {
        teardownNativeLocked()
        ModelAvailabilityRegistry.reportLoad(ModelFeature.ExynosUpscale, result, generation)
        lifecycle = ModelRunnerLifecycle.Unloaded
        return result
    }

    /**
     * Total physical teardown primitive; every step attempted exactly once.
     * Failures (returned non-SUCCESS codes or thrown exceptions) are recorded truthfully
     * into [lastTeardownResult] but do not prevent clearing local native ownership.
     */
    private fun teardownNativeLocked(): TeardownResult {
        var releaseBuffersOutcome = NativeStepOutcome.NotAttempted
        var releaseBuffersStatus: Int? = null
        var releaseBuffersDetail: String? = null

        var closeModelOutcome = NativeStepOutcome.NotAttempted
        var closeModelStatus: Int? = null
        var closeModelDetail: String? = null

        var deinitializeOutcome = NativeStepOutcome.NotAttempted
        var deinitializeStatus: Int? = null
        var deinitializeDetail: String? = null

        if (bufferSet != 0L && bufferCount > 0) {
            val releaseResult = runCatching { native.releaseBuffers(bufferSet, bufferCount) }
            releaseResult.onSuccess { status ->
                releaseBuffersStatus = status
                releaseBuffersOutcome =
                    if (status == EnnStatus.SUCCESS) NativeStepOutcome.ReturnedSuccess
                    else NativeStepOutcome.ReturnedFailure
            }.onFailure { throwable ->
                releaseBuffersOutcome = NativeStepOutcome.Threw
                releaseBuffersDetail = throwable.message ?: throwable.javaClass.simpleName
            }
        }
        bufferSet = 0L
        bufferCount = 0

        if (modelId != NO_MODEL_ID) {
            val closeResult = runCatching { native.closeModel(modelId) }
            closeResult.onSuccess { status ->
                closeModelStatus = status
                closeModelOutcome =
                    if (status == EnnStatus.SUCCESS) NativeStepOutcome.ReturnedSuccess
                    else NativeStepOutcome.ReturnedFailure
            }.onFailure { throwable ->
                closeModelOutcome = NativeStepOutcome.Threw
                closeModelDetail = throwable.message ?: throwable.javaClass.simpleName
            }
        }
        modelId = NO_MODEL_ID

        if (nativeInitialized) {
            val deinitResult = runCatching { native.deinitialize() }
            deinitResult.onSuccess { status ->
                deinitializeStatus = status
                deinitializeOutcome =
                    if (status == EnnStatus.SUCCESS) NativeStepOutcome.ReturnedSuccess
                    else NativeStepOutcome.ReturnedFailure
            }.onFailure { throwable ->
                deinitializeOutcome = NativeStepOutcome.Threw
                deinitializeDetail = throwable.message ?: throwable.javaClass.simpleName
            }
            nativeInitialized = false
        }

        preparedModelFile?.let { file ->
            runCatching { file.delete() }
                .onFailure { Log.w(TAG, "Prepared model file deletion failed", it) }
        }
        preparedModelFile = null
        descriptor = null

        val teardown =
            TeardownResult(
                releaseBuffersOutcome = releaseBuffersOutcome,
                releaseBuffersStatus = releaseBuffersStatus,
                releaseBuffersDetail = releaseBuffersDetail,
                closeModelOutcome = closeModelOutcome,
                closeModelStatus = closeModelStatus,
                closeModelDetail = closeModelDetail,
                deinitializeOutcome = deinitializeOutcome,
                deinitializeStatus = deinitializeStatus,
                deinitializeDetail = deinitializeDetail,
            )
        teardown.logWarnings()
        if (teardown.attemptedAny) {
            lastTeardownResult = teardown
        }
        return teardown
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
        private const val ENN_OUTPUT_INDEX = 1

        // enn_buf_dir_e
        private const val ENN_DIR_IN = 0
        private const val ENN_DIR_OUT = 1

        private const val NO_MODEL_ID = -1L
        private const val NO_REGISTRY_GENERATION = -1L

        private val PREPARED_FILE_SEQUENCE = AtomicInteger(0)

        private const val ENN_RUNTIME_UNAVAILABLE_DETAIL =
            "Exynos ENN runtime is unavailable on this device"
    }
}

/** Physical outcome of one native teardown step. */
internal enum class NativeStepOutcome {
    /** The step was skipped because this session never owned the handle. */
    NotAttempted,
    /** The call returned ENN_RET_SUCCESS. */
    ReturnedSuccess,
    /** The call returned a non-SUCCESS EnnReturn; the exact status is recorded. */
    ReturnedFailure,
    /** The call threw before/instead of returning; the throwable detail is recorded. */
    Threw,
}

/**
 * Truthful per-step record of one physical native teardown.
 * Exposed through [ExynosUpscaleSession.lastTeardownResult] as the smallest
 * test-visible diagnostic seam.
 */
internal data class TeardownResult(
    val releaseBuffersOutcome: NativeStepOutcome,
    val releaseBuffersStatus: Int?,
    val releaseBuffersDetail: String?,
    val closeModelOutcome: NativeStepOutcome,
    val closeModelStatus: Int?,
    val closeModelDetail: String?,
    val deinitializeOutcome: NativeStepOutcome,
    val deinitializeStatus: Int?,
    val deinitializeDetail: String?,
) {
    /** True when at least one physical teardown step was attempted. */
    val attemptedAny: Boolean
        get() = releaseBuffersOutcome != NativeStepOutcome.NotAttempted ||
            closeModelOutcome != NativeStepOutcome.NotAttempted ||
            deinitializeOutcome != NativeStepOutcome.NotAttempted

    /** True when every attempted step returned ENN_RET_SUCCESS without throwing. */
    val allAttemptedSucceeded: Boolean
        get() = listOf(releaseBuffersOutcome, closeModelOutcome, deinitializeOutcome)
            .all { it == NativeStepOutcome.NotAttempted || it == NativeStepOutcome.ReturnedSuccess }

    fun logWarnings() {
        if (releaseBuffersOutcome != NativeStepOutcome.NotAttempted && releaseBuffersOutcome != NativeStepOutcome.ReturnedSuccess) {
            Log.w(
                "KeplerExynosUpscale",
                "EnnReleaseBuffers failed: outcome=$releaseBuffersOutcome status=${releaseBuffersStatus?.let { EnnStatus.describe(it) }} detail=$releaseBuffersDetail",
            )
        }
        if (closeModelOutcome != NativeStepOutcome.NotAttempted && closeModelOutcome != NativeStepOutcome.ReturnedSuccess) {
            Log.w(
                "KeplerExynosUpscale",
                "EnnCloseModel failed: outcome=$closeModelOutcome status=${closeModelStatus?.let { EnnStatus.describe(it) }} detail=$closeModelDetail",
            )
        }
        if (deinitializeOutcome != NativeStepOutcome.NotAttempted && deinitializeOutcome != NativeStepOutcome.ReturnedSuccess) {
            Log.w(
                "KeplerExynosUpscale",
                "EnnDeinitialize failed: outcome=$deinitializeOutcome status=${deinitializeStatus?.let { EnnStatus.describe(it) }} detail=$deinitializeDetail",
            )
        }
    }
}

/**
 * Production preparation contract (staged + session-isolated):
 *  1. stream the pinned asset into "<name>.<sessionToken>.tmp";
 *  2. fsync + exact-size + SHA-256 verification complete while staged;
 *  3. atomic renameTo publishes "<name>.<sessionToken>".
 *
 * Multi-session audit (static): two concurrent [ExynosUpscaleSession] instances
 * NEVER share a prepared file path — [sessionToken] makes the path exclusive to one
 * session instance, and teardown deletes ONLY that session's file. The vendored
 * Samsung contract (N0_RUNTIME_CONTRACT.md §5) does not establish whether the ENN runtime
 * keeps reading the model file after EnnOpenModel returns, so path exclusivity —
 * not timing — is the safety mechanism: no session can delete another live session's model file.
 */
internal fun prepareModelFile(
    context: Context,
    asset: ModelAssetContract,
    sessionToken: String,
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
            is ModelAssetValidation.Valid -> {
                val targetDir = File(context.filesDir, "exynos_models").apply { mkdirs() }
                val baseName = File(asset.assetPath).name
                val stagedFile = File(targetDir, "$baseName.$sessionToken.tmp")
                val finalFile = File(targetDir, "$baseName.$sessionToken")
                try {
                    if (stagedFile.exists() && !stagedFile.delete()) {
                        throw IOException("could not remove stale staging file ${stagedFile.name}")
                    }
                    context.assets.open(asset.assetPath).use { input ->
                        copyVerifying(input, stagedFile, validation.sha256, validation.byteCount)
                    }
                    if (finalFile.exists() && !finalFile.delete()) {
                        throw IOException("could not replace prepared file ${finalFile.name}")
                    }
                    if (!stagedFile.renameTo(finalFile)) {
                        throw IOException("could not publish staged model file ${stagedFile.name}")
                    }
                    ModelLoadResult.Ready(finalFile)
                } catch (t: Throwable) {
                    stagedFile.delete()
                    throw t
                }
            }
        }
    }.getOrElse { failure ->
        ModelLoadResult.LoadFailed(failure.message ?: "model preparation failed")
    }

internal fun copyVerifying(
    input: InputStream,
    target: File,
    expectedSha256: String,
    expectedBytes: Long,
) {
    val digest = MessageDigest.getInstance("SHA-256")
    try {
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
    } catch (t: Throwable) {
        // Verification gate failed before publication: the partial staging file
        // must never survive as a (possibly renamed) final artifact.
        target.delete()
        throw t
    }
}
