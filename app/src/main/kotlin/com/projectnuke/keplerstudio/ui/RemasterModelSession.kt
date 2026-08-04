package com.projectnuke.keplerstudio.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.projectnuke.keplerstudio.editor.GlobalModelDiagnostics
import com.projectnuke.keplerstudio.editor.DeterministicModelFallback
import com.projectnuke.keplerstudio.editor.MemoryTrackerScope
import com.projectnuke.keplerstudio.editor.ModelAlphaHandling
import com.projectnuke.keplerstudio.editor.ModelAssetManifest
import com.projectnuke.keplerstudio.editor.ModelChannelOrder
import com.projectnuke.keplerstudio.editor.ModelColorSpace
import com.projectnuke.keplerstudio.editor.ModelConfidence
import com.projectnuke.keplerstudio.editor.ModelFailure
import com.projectnuke.keplerstudio.editor.ModelFailureReason
import com.projectnuke.keplerstudio.editor.ModelAvailabilityRegistry
import com.projectnuke.keplerstudio.editor.ModelCapabilityPhase
import com.projectnuke.keplerstudio.editor.ModelFeature
import com.projectnuke.keplerstudio.editor.ModelInputContract
import com.projectnuke.keplerstudio.editor.ModelLoadResult
import com.projectnuke.keplerstudio.editor.ModelOperationContext
import com.projectnuke.keplerstudio.editor.ModelOutputContract
import com.projectnuke.keplerstudio.editor.ModelOutputSemantic
import com.projectnuke.keplerstudio.editor.ModelResizePolicy
import com.projectnuke.keplerstudio.editor.ModelRunResult
import com.projectnuke.keplerstudio.editor.ModelRunnerContract
import com.projectnuke.keplerstudio.editor.ModelRunnerDescriptor
import com.projectnuke.keplerstudio.editor.ModelRunnerLifecycle
import com.projectnuke.keplerstudio.editor.ModelTensorDataType
import com.projectnuke.keplerstudio.editor.ModelTensorLayout
import com.projectnuke.keplerstudio.editor.TrackedMask
import com.projectnuke.keplerstudio.editor.copyOrThrow
import com.projectnuke.keplerstudio.editor.createBitmapOrThrow
import com.projectnuke.keplerstudio.editor.createScaledBitmapOrThrow
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object RemasterModelSession : ModelRunnerContract {
    var activeModel by mutableStateOf<RemasterModelCandidate?>(null)
        private set

    var statusText by mutableStateOf("로드된 모델이 없습니다.")
        private set

    var isModelLoaded by mutableStateOf(false)
        private set

    private var closeableModel: AutoCloseable? = null
    private var sessionValidationIdentity: ModelSessionValidationIdentity? = null
    private var runnerFactoryOverride: ((Context, String) -> AutoCloseable?)? = null
    private var runnerPostCreateHookForTest: (() -> Unit)? = null
    private var runnerPostPublicationHookForTest: (() -> Unit)? = null
    private val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val modelMutex = Mutex()
    private val commandGeneration = AtomicLong()
    private var registrySessionGeneration: Long = 0L
    var isModelLoading by mutableStateOf(false)
        private set

    var isInferring by mutableStateOf(false)
        private set

    override var lifecycle by mutableStateOf(ModelRunnerLifecycle.Unloaded)
        private set

    override val descriptor: ModelRunnerDescriptor?
        get() =
            activeModel?.let { candidate ->
                val manifest = ModelAssetManifest.byId(candidate.id) ?: return@let null
                ModelRunnerDescriptor(
                    modelId = candidate.id,
                    asset = manifest.asset,
                    input =
                        ModelInputContract(
                            width = null,
                            height = null,
                            batch = 1,
                            channels = 3,
                            layout = ModelTensorLayout.NHWC,
                            dataType = ModelTensorDataType.Float32,
                            quantization = null,
                            channelOrder = ModelChannelOrder.RGB,
                            colorSpace = ModelColorSpace.SRGB,
                            normalization = "runtime model contract",
                            acceptedBitmapConfigs = setOf(Bitmap.Config.ARGB_8888),
                            resizePolicy = ModelResizePolicy.RuntimeDefined,
                            alphaHandling = ModelAlphaHandling.Ignore,
                        ),
                    output =
                        ModelOutputContract(
                            width = null,
                            height = null,
                            channelsOrClasses = 1,
                            layout = ModelTensorLayout.HW,
                            dataType = ModelTensorDataType.Float32,
                            quantization = null,
                            semantic = manifest.outputSemantic,
                            valueRange = 0f..1f,
                            confidenceMeaning =
                                if (manifest.outputSemantic ==
                                    ModelOutputSemantic.ForegroundCategoryMask
                                ) {
                                    "MediaPipe category mask"
                                } else {
                                    null
                                },
                        ),
                    inferenceAdapterImplemented = manifest.inferenceAdapterImplemented,
                    productionReady = manifest.productionReady,
                    knownMemoryBytes = null,
                    hasUnknownRuntimeMemory = true,
                )
            }

    fun load(context: Context, candidate: RemasterModelCandidate) {
        val applicationContext = context.applicationContext
        val validation = ModelAvailabilityRegistry.validatedCapabilityToken(ModelFeature.Remaster)
        val validationToken = (validation as? ModelLoadResult.Ready)?.runner ?: return
        val generation = commandGeneration.incrementAndGet()
        val registryLoadGeneration = ModelAvailabilityRegistry.reportEdgeLoading()
        isModelLoading = true
        isModelLoaded = false
        lifecycle = ModelRunnerLifecycle.Loading
        GlobalModelDiagnostics.publish("RemasterModelSession", "loading")
        modelScope.launch {
            modelMutex.withLock {
                if (generation != commandGeneration.get()) return@withLock
                if (!ModelAvailabilityRegistry.isCurrent(validationToken)) {
                    isModelLoading = false
                    lifecycle = ModelRunnerLifecycle.Failed
                    ModelAvailabilityRegistry.reportEdgeLoad(
                        ModelLoadResult.RuntimeUnavailable("model validation became stale before load"),
                        registryLoadGeneration,
                    )
                    GlobalModelDiagnostics.publish("RemasterModelSession", "failed")
                    statusText = "${candidate.title}: model validation became stale"
                    return@withLock
                }
                publishSessionClosed()
                runCatching { closeableModel?.close() }
                closeableModel = null
                sessionValidationIdentity = null
                activeModel = candidate
                if (!isSupportedModelContract(candidate) ||
                    candidate.id != validationToken.modelId ||
                    candidate.assetPath != validationToken.approvedAssetPath ||
                    ModelAssetManifest.byId(candidate.id)?.asset?.sha256 !=
                        validationToken.approvedAssetSha256 ||
                    ModelAssetManifest.byId(candidate.id)?.asset?.packagingVersion !=
                        validationToken.packagingVersion
                ) {
                    isModelLoading = false
                    lifecycle = ModelRunnerLifecycle.Failed
                    GlobalModelDiagnostics.publish("RemasterModelSession", "failed")
                    statusText = "${candidate.title}: unsupported model contract"
                    ModelAvailabilityRegistry.reportEdgeLoad(
                        ModelLoadResult.UnsupportedContract("unsupported Edge Masker contract"),
                        registryLoadGeneration,
                    )
                    return@withLock
                }
                if (ModelAvailabilityRegistry.loaderRejection(ModelFeature.Remaster) is ModelLoadResult.AssetMissing) {
                    isModelLoading = false
                    lifecycle = ModelRunnerLifecycle.Failed
                    ModelAvailabilityRegistry.reportEdgeLoad(
                        ModelLoadResult.AssetMissing(candidate.assetPath),
                        registryLoadGeneration,
                    )
                    GlobalModelDiagnostics.publish("RemasterModelSession", "unloaded")
                    statusText = "${candidate.title}: 모델 파일 없음"
                    return@withLock
                }
                try {
                runCatching {
                        val runnerOwner = LocalRunnerOwner()
                        runnerOwner.install(
                            when (candidate.id) {
                                "edge_masker" -> createImageSegmenter(applicationContext, validationToken.approvedAssetPath)
                                else -> error("unsupported runner")
                            }
                        )
                        try {
                            runnerPostCreateHookForTest?.invoke()
                            if (generation != commandGeneration.get()) {
                                runnerOwner.close()
                                isModelLoading = false
                                lifecycle = ModelRunnerLifecycle.Failed
                                return@withLock
                            }
                        } catch (failure: Throwable) {
                            runnerOwner.close()
                            throw failure
                        }
                        closeableModel = runnerOwner.transfer()
                    }
                    .onSuccess {
                        if (generation != commandGeneration.get() ||
                            !ModelAvailabilityRegistry.isCurrent(validationToken)
) {
                            val model = closeableModel
                            closeableModel = null
                            runCatching { model?.close() }
                            sessionValidationIdentity = null
                            isModelLoaded = false
                            isModelLoading = false
                            lifecycle = ModelRunnerLifecycle.Failed
                            ModelAvailabilityRegistry.reportEdgeLoad(
                                ModelLoadResult.RuntimeUnavailable("model validation became stale after load"),
                                registryLoadGeneration,
                            )
                            GlobalModelDiagnostics.publish("RemasterModelSession", "failed")
                            statusText = "${candidate.title}: model validation became stale"
                            return@onSuccess
                        }
                        isModelLoaded = closeableModel != null
                        if (isModelLoaded) {
                            sessionValidationIdentity = validationToken.sessionIdentity()
                            ModelAvailabilityRegistry.reportEdgeLoad(
                                ModelLoadResult.Ready(Unit),
                                registryLoadGeneration,
                            )
                                registrySessionGeneration =
                                ModelAvailabilityRegistry.reportSessionReady(
                                    listOf(
                                        ModelFeature.Remaster,
                                        ModelFeature.SubjectSelection,
                                    )
                                )
                            runnerPostPublicationHookForTest?.invoke()
                        } else {
                            ModelAvailabilityRegistry.reportEdgeLoad(
                                ModelLoadResult.LoadFailed("runner creation returned null"),
                                registryLoadGeneration,
                            )
                        }
                        isModelLoading = false
                        lifecycle =
                            if (closeableModel != null) ModelRunnerLifecycle.Loaded
                            else ModelRunnerLifecycle.Failed
                        GlobalModelDiagnostics.publish(
                            "RemasterModelSession",
                            if (closeableModel != null) "loaded" else "unloaded",
                        )
                        statusText =
                            if (closeableModel != null) "${candidate.title}: 사용 가능"
                            else "${candidate.title}: 실행 경로를 준비하는 중입니다."
                    }
.onFailure {
                        val model = closeableModel
                        closeableModel = null
                        publishSessionClosed()
                        runCatching { model?.close() }
                        sessionValidationIdentity = null
                        isModelLoaded = false
                        activeModel = null
                        ModelAvailabilityRegistry.reportEdgeLoad(
                            ModelLoadResult.LoadFailed(
                                it.message ?: "Edge Masker load failed"
                            ),
                            registryLoadGeneration,
                        )
                        isModelLoading = false
                        lifecycle = ModelRunnerLifecycle.Failed
                        GlobalModelDiagnostics.publish("RemasterModelSession", "unloaded")
                        statusText = "${candidate.title}: 모델 로드에 실패했습니다: ${it.message}"
                    }
} catch (failure: Throwable) {
                val model = closeableModel
                closeableModel = null
                publishSessionClosed()
                runCatching { model?.close() }
                sessionValidationIdentity = null
                isModelLoaded = false
                activeModel = null
                isModelLoading = false
                lifecycle = ModelRunnerLifecycle.Failed
                ModelAvailabilityRegistry.reportEdgeLoad(
                    ModelLoadResult.LoadFailed(failure.message ?: "Edge Masker publication failed"),
                    registryLoadGeneration,
                )
            }
        }
    }
    }

    internal suspend fun ensureEdgeLoaded(context: Context): ModelLoadResult<Unit> =
        modelMutex.withLock {
            val applicationContext = context.applicationContext
            val validation =
                ModelAvailabilityRegistry.validatedCapabilityToken(ModelFeature.SubjectSelection)
            val validationToken =
                (validation as? ModelLoadResult.Ready)?.runner
                    ?: return@withLock validation.asUnitFailure()
            if (activeModel?.id == "edge_masker" && isModelLoaded && closeableModel != null) {
                if (sessionValidationIdentity == validationToken.sessionIdentity()) {
                    return@withLock ModelLoadResult.Ready(Unit)
                }
                publishSessionClosed()
                runCatching { closeableModel?.close() }
                closeableModel = null
                sessionValidationIdentity = null
                activeModel = null
                isModelLoaded = false
            }
            val candidate =
                OnDeviceRemasterModels.firstOrNull { it.id == "edge_masker" }
                    ?: return@withLock ModelLoadResult.RuntimeUnavailable(
                        "Edge Masker runner is not registered"
                    )
            val loadGeneration = ModelAvailabilityRegistry.reportEdgeLoading()
            activeModel = candidate
            isModelLoading = true
            lifecycle = ModelRunnerLifecycle.Loading
            if (!isSupportedModelContract(candidate)) {
                isModelLoading = false
                lifecycle = ModelRunnerLifecycle.Failed
                return@withLock ModelLoadResult.UnsupportedContract(
                    "unsupported Edge Masker contract"
                ).also {
                    ModelAvailabilityRegistry.reportEdgeLoad(it, loadGeneration)
                }
            }
            val runnerOwner = LocalRunnerOwner()
            return@withLock try {
                if (!ModelAvailabilityRegistry.isCurrent(validationToken)) {
                    isModelLoading = false
                    lifecycle = ModelRunnerLifecycle.Failed
                    ModelAvailabilityRegistry.reportEdgeLoad(
                        ModelLoadResult.RuntimeUnavailable("model validation became stale before load"),
                        loadGeneration,
                    )
                    return@withLock ModelLoadResult.RuntimeUnavailable(
                        "model validation became stale before load"
                    )
                }
                publishSessionClosed()
                runCatching { closeableModel?.close() }
                sessionValidationIdentity = null
                runnerOwner.install(
                    createImageSegmenter(applicationContext, validationToken.approvedAssetPath)
                )
                runnerPostCreateHookForTest?.invoke()
                if (!ModelAvailabilityRegistry.isCurrent(validationToken)) {
                    runnerOwner.close()
                    isModelLoaded = false
                    isModelLoading = false
                    lifecycle = ModelRunnerLifecycle.Failed
                    val stale = ModelLoadResult.RuntimeUnavailable("model validation became stale after load")
                    ModelAvailabilityRegistry.reportEdgeLoad(stale, loadGeneration)
                    return@withLock stale
                }
                closeableModel = runnerOwner.transfer()
                isModelLoaded = true
                sessionValidationIdentity = validationToken.sessionIdentity()
                isModelLoading = false
                lifecycle = ModelRunnerLifecycle.Loaded
                ModelLoadResult.Ready(Unit).also {
                    ModelAvailabilityRegistry.reportEdgeLoad(it, loadGeneration)
                    registrySessionGeneration =
                        ModelAvailabilityRegistry.reportSessionReady(
                            listOf(ModelFeature.Remaster, ModelFeature.SubjectSelection)
                        )
                    runnerPostPublicationHookForTest?.invoke()
                }
            } catch (failure: Throwable) {
                runnerOwner.close()
                publishSessionClosed()
                runCatching { closeableModel?.close() }
                closeableModel = null
                sessionValidationIdentity = null
                isModelLoaded = false
                isModelLoading = false
                activeModel = null
                lifecycle = ModelRunnerLifecycle.Failed
                ModelLoadResult.LoadFailed(
                    failure.message ?: "Edge Masker load failed"
                ).also {
                    ModelAvailabilityRegistry.reportEdgeLoad(it, loadGeneration)
                }
            }
        }

    internal suspend fun createForegroundMask(
        bitmap: Bitmap,
        diagnostics: MemoryTrackerScope? = null,
        operation: ModelOperationContext,
    ): Bitmap? =
        when (val result = createForegroundMaskResult(bitmap, diagnostics, operation)) {
            is ModelRunResult.Success -> result.value.adoptOrNull()
            is ModelRunResult.Failure -> null
        }

    /**
     * Returns the foreground mask as a [TrackedMask] carrying its diagnostic edge,
     * model identity, operation token and document generation. This replaces the
     * raw `(Long) -> Unit` edge callback that allowed a Bitmap and a separately
     * mutable Long to drift; the caller transfers/adopts exactly once.
     */
    internal suspend fun createForegroundMaskResult(
        bitmap: Bitmap,
        diagnostics: MemoryTrackerScope? = null,
        operation: ModelOperationContext,
    ): ModelRunResult<TrackedMask> =
        modelMutex.withLock {
            val capability =
                ModelAvailabilityRegistry.state.value[ModelFeature.SubjectSelection]
            if (capability?.phase != ModelCapabilityPhase.Ready || !capability.sessionActive) {
                return@withLock ModelRunResult.Failure(
                    ModelFailure(
                        modelCapabilityFailureReason(capability?.phase),
                        capability?.lastFailure?.detail,
                    ),
                    DeterministicModelFallback.NoResult,
                )
            }
            if (activeModel?.id != "edge_masker" || !isModelLoaded) {
                return@withLock ModelRunResult.Failure(
                    ModelFailure(ModelFailureReason.Closed),
                    DeterministicModelFallback.NoResult,
                )
            }
            val currentToken =
                (ModelAvailabilityRegistry.validatedCapabilityToken(ModelFeature.SubjectSelection)
                    as? ModelLoadResult.Ready)?.runner
                    ?: return@withLock ModelRunResult.Failure(
                        ModelFailure(ModelFailureReason.CapabilityUnknown),
                        DeterministicModelFallback.NoResult,
                    )
            if (sessionValidationIdentity != currentToken.sessionIdentity() ||
                !ModelAvailabilityRegistry.isCurrent(currentToken)
            ) {
                return@withLock ModelRunResult.Failure(
                    ModelFailure(ModelFailureReason.RuntimeUnavailable, "model validation epoch is stale"),
                    DeterministicModelFallback.NoResult,
                )
            }
            val model =
                closeableModel
                    ?: return@withLock ModelRunResult.Failure(
                        ModelFailure(ModelFailureReason.Closed),
                        DeterministicModelFallback.NoResult,
                    )
            operation.validateOrThrow()
            isInferring = true
            lifecycle = ModelRunnerLifecycle.Inferencing
            GlobalModelDiagnostics.publish("RemasterModelSession", "inferring")
            // Track the foreground mask through a TrackedMask so the bitmap and
            // diagnostic edge settle together exactly once, regardless of outcome.
            // Built-late: we allocate the TrackedMask on success.
            var ownedMask: TrackedMask? = null
            try {
                operation.validateOrThrow()
                ownedMask =
                    createForegroundMaskFromSegmenter(
                        model,
                        bitmap,
                        diagnostics,
                        operation,
                        activeModel?.id ?: "edge_masker",
                        activeModel?.semanticVersion ?: "1.0.0",
                    )
                operation.validateOrThrow()
                ModelRunResult.Success(
                    ownedMask,
                    confidence = 1f,
                    ownedMask.confidenceMetrics,
                )
            } catch (cancelled: CancellationException) {
                ownedMask?.recycleAndRelease()
                throw cancelled
            } catch (failure: Throwable) {
                ownedMask?.recycleAndRelease()
                ModelRunResult.Failure(
                    ModelFailure(ModelFailureReason.InferenceFailed, failure.message),
                    DeterministicModelFallback.NoResult,
                )
            } finally {
                isInferring = false
                lifecycle =
                    if (isModelLoaded) ModelRunnerLifecycle.Loaded
                    else ModelRunnerLifecycle.Unloaded
                GlobalModelDiagnostics.publish(
                    "RemasterModelSession",
                    if (isModelLoaded) "loaded" else "unloaded",
                )
            }
        }

    fun unload() {
        val generation = commandGeneration.incrementAndGet()
        isModelLoading = true
        lifecycle = ModelRunnerLifecycle.Closing
        GlobalModelDiagnostics.publish("RemasterModelSession", "closing")
        modelScope.launch {
            modelMutex.withLock {
                if (generation != commandGeneration.get()) return@withLock
                val sessionClosePublished = publishSessionClosed()
                runCatching { closeableModel?.close() }
                closeableModel = null
                sessionValidationIdentity = null
                activeModel = null
                isModelLoaded = false
                isModelLoading = false
                lifecycle = ModelRunnerLifecycle.Unloaded
                if (!sessionClosePublished) ModelAvailabilityRegistry.reportEdgeUnloaded()
                GlobalModelDiagnostics.publish("RemasterModelSession", "unloaded")
                statusText = "로드된 모델이 없습니다."
            }
        }
    }

    suspend fun unloadIdleNow(): Boolean =
        modelMutex.withLock {
            if (isModelLoading || isInferring) return@withLock false
            commandGeneration.incrementAndGet()
            GlobalModelDiagnostics.publish("RemasterModelSession", "closing")
            lifecycle = ModelRunnerLifecycle.Closing
            val sessionClosePublished = publishSessionClosed()
            runCatching { closeableModel?.close() }
            closeableModel = null
            sessionValidationIdentity = null
            activeModel = null
            isModelLoaded = false
            isModelLoading = false
            lifecycle = ModelRunnerLifecycle.Unloaded
            if (!sessionClosePublished) ModelAvailabilityRegistry.reportEdgeUnloaded()
            GlobalModelDiagnostics.publish("RemasterModelSession", "unloaded")
            statusText = "로드된 모델이 없습니다."
            true
        }

    private fun publishSessionClosed(): Boolean {
        if (registrySessionGeneration == 0L) return false
        ModelAvailabilityRegistry.reportSessionClosed(
            listOf(ModelFeature.Remaster, ModelFeature.SubjectSelection),
            registrySessionGeneration,
        )
        registrySessionGeneration = 0L
        return true
    }

    private fun isSupportedModelContract(candidate: RemasterModelCandidate): Boolean {
        val manifest = ModelAssetManifest.byId(candidate.id) ?: return false
        return candidate.id == "edge_masker" &&
            manifest.inferenceAdapterImplemented &&
            manifest.outputSemantic == ModelOutputSemantic.ForegroundCategoryMask &&
            candidate.semanticVersion == manifest.asset.semanticModelVersion &&
            manifest.asset.requiredContractSchemaVersion == ModelAssetManifest.CONTRACT_SCHEMA_VERSION
    }

    private fun createImageSegmenter(context: Context, assetPath: String): AutoCloseable {
        runnerFactoryOverride?.invoke(context, assetPath)?.let { return it }
        val baseOptions = BaseOptions.builder().setModelAssetPath(assetPath).build()
        val options =
            ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setOutputCategoryMask(true)
                .setOutputConfidenceMasks(false)
                .build()
        return ImageSegmenter.createFromOptions(context, options)
    }

    private fun createForegroundMaskFromSegmenter(
        segmenter: Any,
        bitmap: Bitmap,
        diagnostics: MemoryTrackerScope?,
        operation: ModelOperationContext,
        modelId: String,
        modelVersion: String,
    ): TrackedMask {
        val imageBuilderClass =
            Class.forName("com.google.mediapipe.framework.image.BitmapImageBuilder")
        val inputCopy =
            bitmap.copyOrThrow(Bitmap.Config.ARGB_8888, false) ?: error("입력 이미지를 복사하지 못했습니다.")
        val inputEdge = diagnostics?.track(inputCopy, "remaster:modelInputCopy") ?: 0L
        var mpImage: Any? = null
        try {
            val imageBuilder =
                imageBuilderClass.getConstructor(Bitmap::class.java).newInstance(inputCopy)
            mpImage = imageBuilderClass.getMethod("build").invoke(imageBuilder)
        } catch (t: Throwable) {
            if (mpImage == null) inputCopy.recycle()
            throw t
        }
        var categoryMaskImage: Any? = null
        var foregroundMask: TrackedMask? = null
        var primaryFailure: Throwable? = null
        try {
            val segmentMethod =
                segmenter.javaClass.methods.firstOrNull { method ->
                    method.name == "segment" &&
                        method.parameterTypes.size == 1 &&
                        method.parameterTypes[0].isAssignableFrom(mpImage!!.javaClass)
                } ?: error("segment 메서드를 찾을 수 없습니다.")
            val result = segmentMethod.invoke(segmenter, mpImage)
            val categoryMaskOptional =
                result.javaClass.methods
                    .firstOrNull { method ->
                        method.name == "categoryMask" && method.parameterTypes.isEmpty()
                    }
                    ?.invoke(result) ?: error("category mask 결과가 없습니다.")
            val isPresent =
                categoryMaskOptional.javaClass.getMethod("isPresent").invoke(categoryMaskOptional)
                    as Boolean
            if (!isPresent) error("category mask가 비어 있습니다.")
            categoryMaskImage =
                categoryMaskOptional.javaClass.getMethod("get").invoke(categoryMaskOptional)
            val rawMask = extractBitmapFromMpImage(categoryMaskImage as Any)
            val rawEdge = diagnostics?.track(rawMask, "remaster:rawCategoryMask") ?: 0L
            try {
                foregroundMask =
                    categoryBitmapToForegroundMask(
                        rawMask,
                        bitmap.width,
                        bitmap.height,
                        diagnostics,
                        operation,
                        modelId,
                        modelVersion,
                        requireNotNull(ModelAssetManifest.byId(modelId)?.foregroundCategoryIds) {
                            "Foreground category mapping is not declared for $modelId"
                        },
                    )
            } finally {
                if (!rawMask.isRecycled) rawMask.recycle()
                diagnostics?.release(rawEdge)
            }
            return checkNotNull(foregroundMask)
        } catch (t: Throwable) {
            primaryFailure = t
            throw t
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun closeAndCollect(action: () -> Unit) {
                try {
                    action()
                } catch (cleanup: Throwable) {
                    cleanupFailures += cleanup
                }
            }
            closeAndCollect { if (categoryMaskImage != null) closeMpImage(categoryMaskImage) }
            closeAndCollect { closeMpImage(mpImage) }
            if (!inputCopy.isRecycled) inputCopy.recycle()
            diagnostics?.release(inputEdge)
            if (cleanupFailures.isNotEmpty()) {
                if (primaryFailure != null) {
                    cleanupFailures.forEach(primaryFailure::addSuppressed)
                    foregroundMask?.recycleAndRelease()
                } else {
                    foregroundMask?.recycleAndRelease()
                    val cleanupFailure = cleanupFailures.first()
                    cleanupFailures.drop(1).forEach(cleanupFailure::addSuppressed)
                    throw cleanupFailure
                }
            } else if (primaryFailure != null) {
                foregroundMask?.recycleAndRelease()
            }
        }
    }

    private fun extractBitmapFromMpImage(maskImage: Any): Bitmap {
        val extractorClass = Class.forName("com.google.mediapipe.framework.image.BitmapExtractor")
        val extractMethod =
            extractorClass.methods.firstOrNull { method ->
                method.name == "extract" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].isAssignableFrom(maskImage.javaClass)
            } ?: error("BitmapExtractor.extract 메서드를 찾을 수 없습니다.")
        return extractMethod.invoke(null, maskImage) as Bitmap
    }

    /**
     * Builds and immediately tracks the final ARGB mask while raw/scaled masks and
     * conversion rows are still resident.
     */
    private fun categoryBitmapToForegroundMask(
        rawMask: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        diagnostics: MemoryTrackerScope?,
        operation: ModelOperationContext,
        modelId: String,
        modelVersion: String,
        foregroundCategoryIds: Set<Int>,
    ): TrackedMask {
        require(foregroundCategoryIds.isNotEmpty())
        var scaledMask: Bitmap? = null
        var out: TrackedMask? = null
        var scaledEdge = 0L
        var rowTransient = 0L
        var succeeded = false
        var primaryFailure: Throwable? = null
        try {
            scaledMask =
                if (rawMask.width == targetWidth && rawMask.height == targetHeight) {
                    rawMask
                } else {
                    createScaledBitmapOrThrow(rawMask, targetWidth, targetHeight, false)
                }
            if (scaledMask !== rawMask)
                scaledEdge = diagnostics?.track(scaledMask!!, "remaster:scaledCategoryMask") ?: 0L
            val finalBitmap = createBitmapOrThrow(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            out =
                TrackedMask.acquire(
                    finalBitmap,
                    diagnostics,
                    "remaster:finalArgbMask",
                    modelId,
                    modelVersion,
                    operation.operationToken,
                    operation.documentGeneration,
                    ModelConfidence(1f, 1f, 1f, 1f, 1f, 0f, finalPolicy = 1f),
                )
            val inRow = IntArray(targetWidth)
            val outRow = IntArray(targetWidth)
            rowTransient =
                diagnostics?.trackTransientBytes(
                    "remaster:maskRows",
                    targetWidth.toLong() * Int.SIZE_BYTES * 2L,
                ) ?: 0L
            for (y in 0 until targetHeight) {
                scaledMask.getPixels(inRow, 0, targetWidth, 0, y, targetWidth, 1)
                for (x in 0 until targetWidth) {
                    val pixel = inRow[x]
                    val alpha = (pixel ushr 24) and 0xff
                    val r = (pixel ushr 16) and 0xff
                    val g = (pixel ushr 8) and 0xff
                    val b = pixel and 0xff
                    val rgbMax = max(r, max(g, b))
                    val category = if (rgbMax > 0) rgbMax else if (alpha in 1..249) alpha else 0
                    val mask = categoryMaskAlpha(category, foregroundCategoryIds)
                    outRow[x] = -0x1000000 or (mask shl 16) or (mask shl 8) or mask
                }
                finalBitmap.setPixels(outRow, 0, targetWidth, 0, y, targetWidth, 1)
            }
            succeeded = true
            return out
        } catch (t: Throwable) {
            primaryFailure = t
            throw t
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun recycleAndCollect(action: () -> Unit) {
                try {
                    action()
                } catch (cleanup: Throwable) {
                    cleanupFailures += cleanup
                }
            }
            recycleAndCollect {
                if (scaledMask != null && scaledMask !== rawMask) scaledMask.recycle()
                diagnostics?.release(scaledEdge)
            }
            recycleAndCollect { diagnostics?.releaseTransient(rowTransient) }
            if (!succeeded) {
                recycleAndCollect { out?.recycleAndRelease() }
            }
            if (cleanupFailures.isNotEmpty()) {
                if (primaryFailure != null) {
                    cleanupFailures.forEach(primaryFailure::addSuppressed)
                    if (!succeeded) out?.recycleAndRelease()
                } else {
                    if (!succeeded) out?.recycleAndRelease()
                    val cleanupFailure = cleanupFailures.first()
                    cleanupFailures.drop(1).forEach(cleanupFailure::addSuppressed)
                    throw cleanupFailure
                }
            } else if (primaryFailure != null) {
                if (!succeeded) out?.recycleAndRelease()
            }
        }
    }

    private fun closeMpImage(image: Any?) {
        when (image) {
            null -> Unit
            is AutoCloseable -> image.close()
            else ->
                image.javaClass.methods
                    .firstOrNull { method ->
                        method.name == "close" && method.parameterTypes.isEmpty()
                    }
                    ?.invoke(image)
        }
    }

    /** Test seam used by lifecycle tests; production always uses the MediaPipe factory below. */
    internal fun installRunnerFactoryForTest(factory: (Context, String) -> AutoCloseable?) {
        runnerFactoryOverride = factory
    }

    internal fun clearRunnerFactoryForTest() {
        runnerFactoryOverride = null
        runnerPostCreateHookForTest = null
        runnerPostPublicationHookForTest = null
    }

    internal fun installRunnerPostCreateFailureForTest(failure: () -> Unit) {
        runnerPostCreateHookForTest = failure
    }

    internal fun installRunnerPostPublicationFailureForTest(failure: () -> Unit) {
        runnerPostPublicationHookForTest = failure
    }
}

/** Immutable validation facts bound to a loaded native/model session. */
internal data class ModelSessionValidationIdentity(
    val modelId: String,
    val validationEpoch: Long,
    val approvedAssetPath: String,
    val approvedAssetSha256: String?,
    val packagingVersion: String,
    val semanticVersion: String,
    val contractSchema: Int,
    val runtimeType: com.projectnuke.keplerstudio.editor.ModelRuntimeType,
)

/** Runner remains local until registry/session publication has completed. */
private class LocalRunnerOwner {
    private var runner: AutoCloseable? = null

    fun install(value: AutoCloseable) {
        check(runner == null)
        runner = value
    }

    fun transfer(): AutoCloseable = checkNotNull(runner).also { runner = null }

    fun close() {
        val value = runner ?: return
        runner = null
        runCatching { value.close() }
    }
}

internal fun com.projectnuke.keplerstudio.editor.ValidatedModelCapabilityToken.sessionIdentity(): ModelSessionValidationIdentity =
    ModelSessionValidationIdentity(
        modelId = modelId,
        validationEpoch = validationGeneration,
        approvedAssetPath = approvedAssetPath,
        approvedAssetSha256 = approvedAssetSha256,
        packagingVersion = packagingVersion,
        semanticVersion = semanticVersion,
        contractSchema = contractSchema,
        runtimeType = runtimeType,
    )

private fun ModelLoadResult<*>.asUnitFailure(): ModelLoadResult<Unit> =
    when (this) {
        is ModelLoadResult.AssetMissing -> ModelLoadResult.AssetMissing(detail)
        is ModelLoadResult.AssetInvalid -> ModelLoadResult.AssetInvalid(detail)
        is ModelLoadResult.UnsupportedContract -> ModelLoadResult.UnsupportedContract(detail)
        is ModelLoadResult.RuntimeUnavailable -> ModelLoadResult.RuntimeUnavailable(detail)
        is ModelLoadResult.LoadFailed -> ModelLoadResult.LoadFailed(detail)
        is ModelLoadResult.Ready -> error("ready result cannot be converted to a failure")
    }

internal fun categoryMaskAlpha(category: Int, foregroundCategoryIds: Set<Int>): Int {
    require(foregroundCategoryIds.isNotEmpty()) { "Foreground category mapping must be explicit" }
    return if (category in foregroundCategoryIds) 255 else 0
}

private fun modelCapabilityFailureReason(phase: ModelCapabilityPhase?): ModelFailureReason =
    when (phase) {
        ModelCapabilityPhase.AssetMissing -> ModelFailureReason.AssetMissing
        ModelCapabilityPhase.AssetInvalid -> ModelFailureReason.AssetInvalid
        ModelCapabilityPhase.ContractUnsupported -> ModelFailureReason.ContractUnsupported
        ModelCapabilityPhase.RuntimeUnavailable,
        ModelCapabilityPhase.RunnerUnavailable -> ModelFailureReason.RuntimeUnavailable
        ModelCapabilityPhase.Unknown,
        ModelCapabilityPhase.Probing,
        ModelCapabilityPhase.Loading,
        null -> ModelFailureReason.CapabilityUnknown
        ModelCapabilityPhase.Failed -> ModelFailureReason.LoadingFailed
        ModelCapabilityPhase.Loadable,
        ModelCapabilityPhase.Ready,
        ModelCapabilityPhase.Unloaded -> ModelFailureReason.Closed
    }
