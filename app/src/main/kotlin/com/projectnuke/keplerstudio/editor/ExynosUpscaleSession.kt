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
 *
 * Load terminal-state policy:
 *  - expected structured rejection ([ModelLoadResult.RuntimeUnavailable],
 *    [ModelLoadResult.UnsupportedContract], asset rejection) settles to
 *    [ModelRunnerLifecycle.Unloaded];
 *  - caller cancellation settles to [ModelRunnerLifecycle.Failed];
 *  - an unexpected backend [Throwable] settles to [ModelRunnerLifecycle.Failed];
 *  - physical teardown is always total and never overwrites the Failed terminal state.
 *
 * Bring-up diagnostics: [lastLoadDiagnostics]/[lastRunDiagnostics] snapshot the ACTUAL
 * per-stage native boundary results (raw EnnReturn values, reached flags, throwable
 * stage/detail) — they are updated the moment each native call returns and are never
 * inferred from the overall [ModelLoadResult]/[ModelRunResult].
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

    /**
     * Smallest session-native load-stage truth: replaced when a load attempt passes the
     * lifecycle/token gates and updated the moment each native call returns.
     */
    @Volatile
    internal var lastLoadDiagnostics: ExynosLoadDiagnostics = ExynosLoadDiagnostics()
        private set

    internal val loadDiagnosticsHistory: List<ExynosLoadDiagnostics>
        get() = loadDiagnosticsHistoryInternal.toList()

    /**
     * Smallest session-native run-stage truth: replaced when a run passes all gates and
     * native work begins; updated the moment each native call returns.
     */
    @Volatile
    internal var lastRunDiagnostics: ExynosRunDiagnostics = ExynosRunDiagnostics()
        private set

    internal val runDiagnosticsHistory: List<ExynosRunDiagnostics>
        get() = runDiagnosticsHistoryInternal.toList()

    /**
     * Read-only N2 probe snapshot of the session-owned prepared model file while it is live.
     * The caller must not retain, mutate, or delete the returned file; [close] owns cleanup.
     */
    internal fun preparedModelFileForDiagnostics(): File? = preparedModelFile

    /**
     * Test-only seam for deterministic prepared-file deletion outcomes.
     * Production value is exactly `File.delete`.
     */
    @Volatile
    internal var preparedFileDeleter: (File) -> Boolean = { it.delete() }

    private val lifecycleMutex = Mutex()

    private var modelId: Long = NO_MODEL_ID
    private var bufferSet: Long = 0L
    private var bufferCount: Int = 0
    private var nativeInitialized: Boolean = false
    private var registrySessionGeneration: Long = NO_REGISTRY_GENERATION
    private var preparedModelFile: File? = null
    private val loadDiagnosticsHistoryInternal = mutableListOf<ExynosLoadDiagnostics>()
    private val runDiagnosticsHistoryInternal = mutableListOf<ExynosRunDiagnostics>()
    private val preparedFileToken = "session-${PREPARED_FILE_SEQUENCE.getAndIncrement()}"

    @Volatile
    internal var preExecuteCheck: (suspend () -> Unit)? = null

    @Volatile
    internal var preH2dCheck: (suspend () -> Unit)? = null

    @Volatile
    internal var preD2hCheck: (suspend () -> Unit)? = null

    /** Internal probe-only access to metadata from this session's currently loaded model. */
    internal fun getEnnMetaInfo(metaId: Int): String? {
        if (lifecycle != ModelRunnerLifecycle.Loaded || modelId == NO_MODEL_ID) return null
        return native.getMetaInfo(metaId, modelId)
    }

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
                val diagnostics = ExynosLoadDiagnostics()
                lastLoadDiagnostics = diagnostics
                loadDiagnosticsHistoryInternal += diagnostics
                try {
                    if (!native.probeRuntime()) {
                        return@withLock settleAfterPhysicalTeardown(
                            ModelLoadResult.RuntimeUnavailable(ENN_RUNTIME_UNAVAILABLE_DETAIL),
                            generation,
                        )
                    }
                    loadLocked(token, generation, diagnostics)
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
                    // Unexpected backend failure policy: physical teardown must be total,
                    // but the terminal lifecycle stays Failed — teardown never overwrites it.
                    settleAfterPhysicalTeardown(
                        ModelLoadResult.LoadFailed(t.message ?: "ENN load failed"),
                        generation,
                        terminalLifecycle = ModelRunnerLifecycle.Failed,
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
        attemptLabel: String? = null,
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
                    runLocked(argbPixels, attemptLabel)
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

    /**
     * N3 narrow raw-output observation seam.
     *
     * Runs one x4 upscale over raw FP32 CHW RGB input bytes (exactly [INPUT_BYTES],
     * the same canonical bytes fed to the PyTorch reference) and returns the raw FP32
     * D2H output bytes BEFORE any clamp, scale-by-255, round, Bitmap, or PNG post-processing.
     *
     * Ownership contract is unchanged: the real [ExynosUpscaleSession] still owns the
     * model, buffers, H2D, execute, D2H, and lifecycle. This seam only observes/copies
     * the D2H tensor and hashes the exact H2D payload for input-parity evidence. It is
     * never invoked by any production/N2 path and has zero effect when unused.
     */
    internal suspend fun runRawFp32Chw(
        inputBytes: ByteArray,
        operationContext: ModelOperationContext,
        attemptLabel: String? = null,
    ): ExynosRawRunResult =
        withContext(ioDispatcher) {
            lifecycleMutex.withLock {
                if (operationContext.isCancelled()) {
                    return@withLock ExynosRawRunResult.cancelled()
                }
                if (!operationContext.isCurrent(
                        operationContext.operationToken,
                        operationContext.documentGeneration,
                    )
                ) {
                    return@withLock ExynosRawRunResult.failure("stale operation context")
                }
                if (lifecycle != ModelRunnerLifecycle.Loaded) {
                    return@withLock ExynosRawRunResult.failure("session is not Loaded")
                }
                if (inputBytes.size != INPUT_BYTES) {
                    return@withLock ExynosRawRunResult.failure(
                        "raw input must be exactly $INPUT_BYTES bytes (FP32 CHW 3x${INPUT_WIDTH}x${INPUT_HEIGHT})",
                    )
                }
                try {
                    rawRunLocked(inputBytes, attemptLabel)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    // A native boundary threw. The shared core already recorded the
                    // throwing stage/detail into [lastRunDiagnostics]; surface it as a
                    // structured failure (never an exception) so a tiled orchestrator can
                    // treat a native throw as one tile's failure, not a process-wide abort.
                    val diag = lastRunDiagnostics
                    ExynosRawRunResult(
                        diag.h2dStatus,
                        diag.executeStatus,
                        diag.d2hStatus,
                        null,
                        null,
                        throwableStage = diag.throwableStage,
                        throwableDetail = diag.throwableDetail ?: (t.message ?: t.javaClass.simpleName),
                    ).also {
                        val stage = diag.throwableStage ?: "unknown"
                        it.log("native boundary threw at $stage: ${diag.throwableDetail ?: t.message}")
                    }
                }
            }
        }

    /**
     * Terminal ownership boundary; safe to call repeatedly and from any thread.
     * When a previous teardown could not delete the prepared model file, calling close()
     * again is the explicit later cleanup attempt that retries the deletion of that
     * session-owned cleanup debt.
     */
    suspend fun close() {
        withContext(NonCancellable + ioDispatcher) {
            lifecycleMutex.withLock {
                if (lifecycle == ModelRunnerLifecycle.Closing) return@withLock
                if (lifecycle == ModelRunnerLifecycle.Unloaded) {
                    if (preparedModelFile == null) return@withLock
                    // Explicit later cleanup attempt for retained prepared-file debt only;
                    // every native handle is already cleared so this is a no-op for them.
                    teardownNativeLocked()
                    return@withLock
                }
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
        diagnostics: ExynosLoadDiagnostics,
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
        val initStatus = nativeLoadStage(diagnostics, "initialize") { native.initialize() }
        diagnostics.initializeStatus = initStatus
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
        coroutineContext.ensureActive()
        val openResult =
            nativeLoadStage(diagnostics, "openModel") { native.openModel(preparedFile.absolutePath) }
        diagnostics.openModelStatus = openResult.status
        if (!openResult.isSuccess) {
            return settleAfterPhysicalTeardown(
                ModelLoadResult.RuntimeUnavailable(
                    "EnnOpenModel failed: EnnReturn=${EnnStatus.describe(openResult.status)}",
                ),
                generation,
            )
        }
        modelId = openResult.modelId

        val allocation =
            nativeLoadStage(diagnostics, "allocateAllBuffers") { native.allocateAllBuffers(modelId) }
        diagnostics.allocationStatus = allocation.status
        diagnostics.allocationNIn = allocation.nInBuffers
        diagnostics.allocationNOut = allocation.nOutBuffers
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
        // From here on every rejection is a tensor/contract verdict, not a native call failure.
        diagnostics.contractValidationReached = true
        if (nIn != EXPECTED_TENSOR_COUNT || nOut != EXPECTED_TENSOR_COUNT) {
            return settleAfterPhysicalTeardown(
                ModelLoadResult.UnsupportedContract(
                    "expected one input and one output tensor, got $nIn/$nOut",
                ),
                generation,
            )
        }
        val inputInfo =
            nativeLoadStage(diagnostics, "inputBufferInfo") {
                native.getBufferInfoByIndex(modelId, ENN_DIR_IN, 0)
            }
        diagnostics.inputBufferInfoQueried = true
        diagnostics.inputBufferInfo = inputInfo
        if (inputInfo == null) {
            return settleAfterPhysicalTeardown(
                ModelLoadResult.UnsupportedContract("input tensor info unavailable"),
                generation,
            )
        }
        val outputInfo =
            nativeLoadStage(diagnostics, "outputBufferInfo") {
                native.getBufferInfoByIndex(modelId, ENN_DIR_OUT, 0)
            }
        diagnostics.outputBufferInfoQueried = true
        diagnostics.outputBufferInfo = outputInfo
        if (outputInfo == null) {
            return settleAfterPhysicalTeardown(
                ModelLoadResult.UnsupportedContract("output tensor info unavailable"),
                generation,
            )
        }
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
        diagnostics.contractValidationPassed = true
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
    }

    /** Runs [block], recording the throwing stage/detail on [diagnostics] before rethrowing. */
    private fun <T> nativeLoadStage(
        diagnostics: ExynosLoadDiagnostics,
        stage: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (t: Throwable) {
            diagnostics.throwableStage = stage
            diagnostics.throwableDetail = t.message ?: t.javaClass.simpleName
            throw t
        }

    /** Runs [block], recording the throwing stage/detail on [diagnostics] before rethrowing. */
    private fun <T> nativeRunStage(
        diagnostics: ExynosRunDiagnostics,
        stage: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (t: Throwable) {
            diagnostics.throwableStage = stage
            diagnostics.throwableDetail = t.message ?: t.javaClass.simpleName
            throw t
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

    private suspend fun runLocked(argbPixels: IntArray, attemptLabel: String? = null): ModelRunResult<Bitmap> {
        val inputBuffer = preprocess(argbPixels)
        val inputBytes = ByteArray(INPUT_BYTES)
        inputBuffer.get(inputBytes)
        return when (val outcome = executeNativeLocked(inputBytes, attemptLabel)) {
            is NativeRunOutcome.Success -> {
                val decodeResult = buildOutputBitmap(outcome.outputBytes)
                if (decodeResult is ModelRunResult.Success) {
                    lastRunDiagnostics.outputDecodePassed = true
                }
                decodeResult
            }
            is NativeRunOutcome.H2dFailed ->
                ModelRunResult.Failure(
                    ModelFailure(
                        ModelFailureReason.InvalidInput,
                        "input copy into the ENN buffer failed: EnnReturn=${EnnStatus.describe(outcome.h2dStatus)}",
                    ),
                    DeterministicModelFallback.NoResult,
                )
            is NativeRunOutcome.ExecuteFailed ->
                ModelRunResult.Failure(
                    ModelFailure(
                        ModelFailureReason.InferenceFailed,
                        "EnnExecuteModel failed: EnnReturn=${EnnStatus.describe(outcome.executeStatus)}",
                    ),
                    DeterministicModelFallback.NoResult,
                )
            is NativeRunOutcome.D2hFailed ->
                ModelRunResult.Failure(
                    ModelFailure(
                        ModelFailureReason.InvalidOutput,
                        "output copy from the ENN buffer failed: EnnReturn=${EnnStatus.describe(outcome.d2hStatus)}",
                    ),
                    DeterministicModelFallback.NoResult,
                )
        }
    }

    /**
     * Raw-run core mirroring [runLocked]'s discipline (same lifecycle flips, same native
     * boundaries, same diagnostic recording) but feeding the exact H2D payload provided by
     * the caller and surfacing the untouched D2H payload instead of a decoded bitmap.
     */
    private suspend fun rawRunLocked(inputBytes: ByteArray, attemptLabel: String?): ExynosRawRunResult {
        val inputSha = runCatching { sha256Bytes(inputBytes) }.getOrNull()
        return when (val outcome = executeNativeLocked(inputBytes, attemptLabel)) {
            is NativeRunOutcome.Success ->
                ExynosRawRunResult(
                    outcome.h2dStatus, outcome.executeStatus, outcome.d2hStatus, inputSha, outcome.outputBytes,
                )
            is NativeRunOutcome.H2dFailed ->
                ExynosRawRunResult(outcome.h2dStatus, null, null, inputSha, null)
                    .also { it.log("H2D failed: EnnReturn=${EnnStatus.describe(outcome.h2dStatus)}") }
            is NativeRunOutcome.ExecuteFailed ->
                ExynosRawRunResult(outcome.h2dStatus, outcome.executeStatus, null, inputSha, null)
                    .also { it.log("execute failed: EnnReturn=${EnnStatus.describe(outcome.executeStatus)}") }
            is NativeRunOutcome.D2hFailed ->
                ExynosRawRunResult(outcome.h2dStatus, outcome.executeStatus, outcome.d2hStatus, inputSha, null)
                    .also { it.log("D2H failed: EnnReturn=${EnnStatus.describe(outcome.d2hStatus)}") }
        }
    }

    /**
     * THE single source of truth for a native inference's physical boundaries.
     *
     * Every production path (Bitmap [run] and raw [runRawFp32Chw]) funnels through this
     * method, so there is exactly one implementation of:
     *   - lifecycle flip (Loaded -> Inferencing -> Loaded),
     *   - H2D (`memcpyHostToDevice`),
     *   - authoritative cancellation/staleness checks (`checkCancellation`,
     *     `coroutineContext.ensureActive`) plus the parked-fake test seams
     *     ([preH2dCheck]/[preExecuteCheck]/[preD2hCheck]),
     *   - EnnExecuteModel boundary recording (`executeReached` set immediately before the
     *     call so a throw still proves the boundary was entered),
     *   - D2H (`memcpyDeviceToHost`),
     *   - per-stage diagnostic recording.
     *
     * Native status failures are returned as a structured [NativeRunOutcome] (never an
     * exception) so callers can distinguish each stage. A native call that THROWS still
     * propagates here and is handled by each wrapper's own policy.
     */
    private suspend fun executeNativeLocked(
        inputBytes: ByteArray,
        attemptLabel: String?,
    ): NativeRunOutcome {
        val diagnostics = ExynosRunDiagnostics()
        lastRunDiagnostics = diagnostics
        diagnostics.attemptLabel = attemptLabel
        runDiagnosticsHistoryInternal += diagnostics
        lifecycle = ModelRunnerLifecycle.Inferencing
        try {
            preH2dCheck?.invoke()
            coroutineContext.ensureActive()
            val h2dStatus =
                nativeRunStage(diagnostics, "h2d") { native.memcpyHostToDevice(bufferSet, 0, inputBytes) }
            diagnostics.h2dStatus = h2dStatus
            if (h2dStatus != EnnStatus.SUCCESS) return NativeRunOutcome.H2dFailed(h2dStatus)

            checkCancellation()
            coroutineContext.ensureActive()
            preExecuteCheck?.invoke()
            // Authoritative coroutine cancellation boundary AFTER the test-only park
            // seam, immediately before native EnnExecuteModel execution.
            coroutineContext.ensureActive()
            diagnostics.executeReached = true
            val executeStatus = nativeRunStage(diagnostics, "execute") { native.execute(modelId) }
            diagnostics.executeStatus = executeStatus
            if (executeStatus != EnnStatus.SUCCESS) {
                return NativeRunOutcome.ExecuteFailed(h2dStatus, executeStatus)
            }

            val outputBytes = ByteArray(OUTPUT_BYTES)
            preD2hCheck?.invoke()
            coroutineContext.ensureActive()
            val d2hStatus =
                nativeRunStage(diagnostics, "d2h") {
                    native.memcpyDeviceToHost(bufferSet, ENN_OUTPUT_INDEX, outputBytes)
                }
            diagnostics.d2hStatus = d2hStatus
            if (d2hStatus != EnnStatus.SUCCESS) {
                return NativeRunOutcome.D2hFailed(h2dStatus, executeStatus, d2hStatus)
            }
            return NativeRunOutcome.Success(h2dStatus, executeStatus, d2hStatus, outputBytes)
        } finally {
            lifecycle = ModelRunnerLifecycle.Loaded
        }
    }

    private sealed interface NativeRunOutcome {
        val h2dStatus: Int?
        val executeStatus: Int?
        val d2hStatus: Int?

        data class Success(
            override val h2dStatus: Int,
            override val executeStatus: Int,
            override val d2hStatus: Int,
            val outputBytes: ByteArray,
        ) : NativeRunOutcome

        data class H2dFailed(override val h2dStatus: Int) : NativeRunOutcome {
            override val executeStatus: Int? = null
            override val d2hStatus: Int? = null
        }

        data class ExecuteFailed(
            override val h2dStatus: Int,
            override val executeStatus: Int,
        ) : NativeRunOutcome {
            override val d2hStatus: Int? = null
        }

        data class D2hFailed(
            override val h2dStatus: Int,
            override val executeStatus: Int,
            override val d2hStatus: Int,
        ) : NativeRunOutcome
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
            val red = quantizeFp32PixelToUint8(r)
            val green = quantizeFp32PixelToUint8(g)
            val blue = quantizeFp32PixelToUint8(b)
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

    /**
     * Total physical settlement for a failed load: releases every owned native handle,
     * publishes the load result to the registry, and applies the intended terminal
     * lifecycle. "Physical resources fully settled" is deliberately separate from the
     * session terminal state: expected structured rejections pass [terminalLifecycle]
     * = [ModelRunnerLifecycle.Unloaded]; unexpected backend [Throwable]s pass
     * [ModelRunnerLifecycle.Failed] so total teardown never overwrites Failed.
     */
    private fun settleAfterPhysicalTeardown(
        result: ModelLoadResult<Unit>,
        generation: Long,
        terminalLifecycle: ModelRunnerLifecycle = ModelRunnerLifecycle.Unloaded,
    ): ModelLoadResult<Unit> {
        teardownNativeLocked()
        ModelAvailabilityRegistry.reportLoad(ModelFeature.ExynosUpscale, result, generation)
        lifecycle = terminalLifecycle
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

        val prepared = preparedModelFile
        var preparedFileOutcome = PreparedFileOutcome.NotOwned
        var preparedFilePath: String? = null
        var preparedFileDetail: String? = null
        if (prepared != null) {
            preparedFilePath = prepared.absolutePath
            val deleteResult = runCatching { preparedFileDeleter(prepared) }
            preparedFileOutcome =
                classifyPreparedFileDeletion(deleteResult, prepared.exists())
            if (preparedFileOutcome == PreparedFileOutcome.Threw) {
                preparedFileDetail =
                    deleteResult.exceptionOrNull()?.let {
                        it.message ?: it.javaClass.simpleName
                    }
            }
            if (preparedFileOutcome == PreparedFileOutcome.Deleted ||
                preparedFileOutcome == PreparedFileOutcome.AlreadyAbsent
            ) {
                preparedModelFile = null
            }
            // DeleteFailed/Threw: the physical file remains, so the path is RETAINED as
            // session-owned cleanup debt until an explicit later cleanup attempt (close()
            // on this session). The reference is never dropped while the file may exist.
        }
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
                preparedFileOutcome = preparedFileOutcome,
                preparedFilePath = preparedFilePath,
                preparedFileDetail = preparedFileDetail,
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

/**
 * Smallest session-native load diagnostic snapshot. Every field is written the moment
 * the corresponding native boundary returns — never inferred afterwards from the
 * overall [ModelLoadResult]. A null status means the stage was NOT reached or the call
 * threw; in the thrown case [throwableStage]/[throwableDetail] identify it.
 */
internal class ExynosLoadDiagnostics {
    /** Raw EnnReturn of EnnInitialize; null = not reached / threw. */
    @Volatile var initializeStatus: Int? = null
    /** Raw EnnReturn of EnnOpenModel; null = not reached / threw. */
    @Volatile var openModelStatus: Int? = null
    /** Raw EnnReturn of EnnAllocateAllBuffers; null = not reached / threw. */
    @Volatile var allocationStatus: Int? = null
    /** Buffer counts from the raw allocation result (-1 = not reached / threw). */
    @Volatile var allocationNIn: Int = -1
    @Volatile var allocationNOut: Int = -1
    /** True once EnnGetBufferInfoByIndex(IN) returned (info may still be null). */
    @Volatile var inputBufferInfoQueried: Boolean = false
    @Volatile var inputBufferInfo: IntArray? = null
    /** True once EnnGetBufferInfoByIndex(OUT) returned (info may still be null). */
    @Volatile var outputBufferInfoQueried: Boolean = false
    @Volatile var outputBufferInfo: IntArray? = null
    /** True once allocation succeeded and the tensor contract gate was entered. */
    @Volatile var contractValidationReached: Boolean = false
    /** True only when every pinned contract check passed. */
    @Volatile var contractValidationPassed: Boolean = false
    /** Stage whose native call threw before returning, if any. */
    @Volatile var throwableStage: String? = null
    @Volatile var throwableDetail: String? = null
}

/**
 * Smallest session-native run diagnostic snapshot. Same truth rule as
 * [ExynosLoadDiagnostics]: written at each native boundary, never inferred from the
 * overall [ModelRunResult]. A null status means the stage was NOT reached or the call
 * threw (see [throwableStage]/[throwableDetail]).
 */
internal class ExynosRunDiagnostics {
    @Volatile var attemptLabel: String? = null
    /** Raw EnnReturn of the H2D copy; null = not reached / threw. */
    @Volatile var h2dStatus: Int? = null
    /**
     * Set true on the line immediately before EnnExecuteModel is invoked — never
     * earlier — so a throw still proves the native execute boundary was entered.
     */
    @Volatile var executeReached: Boolean = false
    /** Raw EnnReturn of EnnExecuteModel; null = not reached / threw. */
    @Volatile var executeStatus: Int? = null
    /** Raw EnnReturn of the D2H copy; null = not reached / threw. */
    @Volatile var d2hStatus: Int? = null
    /** True only when the D2H payload decoded into the pinned output bitmap contract. */
    @Volatile var outputDecodePassed: Boolean = false
    /** Stage whose native call threw before returning, if any. */
    @Volatile var throwableStage: String? = null
    @Volatile var throwableDetail: String? = null
}

internal enum class NpuProofStatus {
    NOT_EXECUTED,
    EXECUTION_FAILED,
    EXECUTED_EVIDENCE_INCOMPLETE,
    OBSERVED,
}

/**
 * Result of the N3 raw-output observation seam.
 * [outputBytes] is the untouched FP32 D2H payload (3x512x512, little-endian) on success.
 * [inputSha256] is the hash of the exact bytes handed to H2D (input-parity evidence).
 */
internal data class ExynosRawRunResult(
    val h2dStatus: Int?,
    val executeStatus: Int?,
    val d2hStatus: Int?,
    val inputSha256: String?,
    val outputBytes: ByteArray?,
    val throwableStage: String? = null,
    val throwableDetail: String? = null,
) {
    val reachedExecute: Boolean
        get() = executeStatus != null
    val threw: Boolean
        get() = throwableStage != null
    val succeeded: Boolean
        get() =
            h2dStatus == EnnStatus.SUCCESS &&
                executeStatus == EnnStatus.SUCCESS &&
                d2hStatus == EnnStatus.SUCCESS &&
                outputBytes != null

    fun log(reason: String) {
        Log.w("KeplerExynosUpscale", "raw run incomplete: $reason")
    }

    companion object {
        fun cancelled() = ExynosRawRunResult(null, null, null, null, null)
        fun failure(reason: String): ExynosRawRunResult = cancelled().also { it.log(reason) }
    }
}

/** SHA-256 of the exact bytes handed to the ENN input buffer (little-endian hex). */
internal fun sha256Bytes(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { byte -> "%02x".format(byte) }

/**
 * Quantizes one clamped FP32 pixel value in [0,1] to uint8 using nearest rounding
 * (round half to even). This mirrors the upstream Real-ESRGAN helper, which converts
 * with `(output * 255.0).round()` — numpy round is half-to-even — rather than a
 * truncation toward zero.
 */
internal fun quantizeFp32PixelToUint8(value: Float): Int =
    Math.rint((value.coerceIn(0f, 1f) * 255f).toDouble()).toInt()

internal data class NpuProofDecision(
    val ennExecuteObserved: Boolean,
    val npuExecutionObserved: Boolean,
    val status: NpuProofStatus,
)

internal fun decideNpuProof(
    executeReached: Boolean,
    executeStatus: Int?,
    compilerNpu: String?,
): NpuProofDecision {
    if (!executeReached) return NpuProofDecision(false, false, NpuProofStatus.NOT_EXECUTED)
    if (executeStatus != EnnStatus.SUCCESS) {
        return NpuProofDecision(false, false, NpuProofStatus.EXECUTION_FAILED)
    }
    val positiveIdentity = !compilerNpu.isNullOrBlank() && !compilerNpu.equals("unavailable", ignoreCase = true)
    return NpuProofDecision(
        ennExecuteObserved = true,
        npuExecutionObserved = positiveIdentity,
        status = if (positiveIdentity) NpuProofStatus.OBSERVED else NpuProofStatus.EXECUTED_EVIDENCE_INCOMPLETE,
    )
}

internal fun npuProofAcceptanceFailure(
    decision: NpuProofDecision,
    earlierFailure: Throwable?,
): AssertionError? {
    if (earlierFailure != null || decision.status == NpuProofStatus.OBSERVED) return null
    val detail = when (decision.status) {
        NpuProofStatus.NOT_EXECUTED -> "EnnExecuteModel was not reached"
        NpuProofStatus.EXECUTION_FAILED -> "EnnExecuteModel did not return ENN_RET_SUCCESS"
        NpuProofStatus.EXECUTED_EVIDENCE_INCOMPLETE ->
            "EnnExecuteModel succeeded but MODEL_COMPILER_NPU identity was unavailable"
        NpuProofStatus.OBSERVED -> error("unreachable")
    }
    return AssertionError("N2 accelerator proof incomplete: $detail")
}

internal data class ProbeReportFinalization(
    val persisted: Boolean,
    val primaryFailure: Throwable?,
    val writeFailure: Throwable?,
)

internal fun finalizeProbeReport(
    writeReport: () -> Unit,
    originalFailure: Throwable?,
    closeFailure: Throwable?,
): ProbeReportFinalization {
    val writeFailure = runCatching { writeReport() }.exceptionOrNull()
    val primary = originalFailure ?: closeFailure ?: writeFailure
    if (closeFailure != null && primary !== closeFailure) primary?.addSuppressed(closeFailure)
    if (writeFailure != null && primary !== writeFailure) primary?.addSuppressed(writeFailure)
    return ProbeReportFinalization(writeFailure == null, primary, writeFailure)
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

/** Physical outcome of deleting this session's prepared model file during teardown. */
internal enum class PreparedFileOutcome {
    /** This teardown owned no prepared file (never prepared, or already settled). */
    NotOwned,
    /** delete() returned true; no physical file remains. */
    Deleted,
    /** delete() returned false AND no file exists at the path — nothing was left to delete. */
    AlreadyAbsent,
    /** delete() returned false AND the file still exists — physical cleanup debt retained. */
    DeleteFailed,
    /** delete() threw; the file may still exist — physical cleanup debt retained. */
    Threw,
}

/**
 * Truthful prepared-file delete classification. [deleteResult] is the captured
 * `File.delete()` call; [fileStillExists] is observed AFTER the attempt. A `false`
 * return is only a failure when a physical file actually remains.
 */
internal fun classifyPreparedFileDeletion(
    deleteResult: Result<Boolean>,
    fileStillExists: Boolean,
): PreparedFileOutcome =
    when {
        deleteResult.isFailure -> PreparedFileOutcome.Threw
        deleteResult.getOrDefault(false) -> PreparedFileOutcome.Deleted
        fileStillExists -> PreparedFileOutcome.DeleteFailed
        else -> PreparedFileOutcome.AlreadyAbsent
    }

/**
 * Truthful per-step record of one physical native teardown, including the prepared
 * model file deletion. Exposed through [ExynosUpscaleSession.lastTeardownResult] as the
 * smallest test-visible diagnostic seam.
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
    val preparedFileOutcome: PreparedFileOutcome,
    val preparedFilePath: String?,
    val preparedFileDetail: String?,
) {
    /** True when at least one physical teardown step was attempted. */
    val attemptedAny: Boolean
        get() = releaseBuffersOutcome != NativeStepOutcome.NotAttempted ||
            closeModelOutcome != NativeStepOutcome.NotAttempted ||
            deinitializeOutcome != NativeStepOutcome.NotAttempted ||
            preparedFileOutcome != PreparedFileOutcome.NotOwned

    /** True when every attempted step returned ENN_RET_SUCCESS without throwing. */
    val allAttemptedSucceeded: Boolean
        get() = listOf(releaseBuffersOutcome, closeModelOutcome, deinitializeOutcome)
            .all { it == NativeStepOutcome.NotAttempted || it == NativeStepOutcome.ReturnedSuccess } &&
            (
                preparedFileOutcome == PreparedFileOutcome.NotOwned ||
                    preparedFileOutcome == PreparedFileOutcome.Deleted ||
                    preparedFileOutcome == PreparedFileOutcome.AlreadyAbsent
            )

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
        if (preparedFileOutcome == PreparedFileOutcome.DeleteFailed || preparedFileOutcome == PreparedFileOutcome.Threw) {
            Log.w(
                "KeplerExynosUpscale",
                "Prepared model file deletion failed (cleanup debt retained by session): " +
                    "outcome=$preparedFileOutcome path=$preparedFilePath detail=$preparedFileDetail",
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
                    deleteStagingFileTruthfully(stagedFile, t)
                    throw t
                }
            }
        }
    }.getOrElse { failure ->
        ModelLoadResult.LoadFailed(failure.message ?: "model preparation failed")
    }

private const val PREPARED_FILE_TAG = "KeplerExynosUpscale"

/**
 * Best-effort staging-file cleanup that never silently claims success: when the file
 * still exists after a delete failure (or delete threw), the residual is logged as a
 * warning and attached to [failure] as a suppressed exception.
 */
internal fun deleteStagingFileTruthfully(
    file: File,
    failure: Throwable,
    deleter: (File) -> Boolean = { it.delete() },
) {
    val deleteResult = runCatching { deleter(file) }
    val residual = file.exists()
    if (deleteResult.isFailure || (deleteResult.getOrDefault(false) == false && residual)) {
        Log.w(
            PREPARED_FILE_TAG,
            "staging cleanup incomplete: residual staging file ${file.absolutePath}",
            deleteResult.exceptionOrNull(),
        )
        failure.addSuppressed(
            IOException("staging cleanup incomplete: ${file.absolutePath} still exists")
        )
    }
}

internal fun copyVerifying(
    input: InputStream,
    target: File,
    expectedSha256: String,
    expectedBytes: Long,
    stagingDeleter: (File) -> Boolean = { it.delete() },
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
        deleteStagingFileTruthfully(target, t, stagingDeleter)
        throw t
    }
}
