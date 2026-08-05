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
    private val EDGE_FEATURES = listOf(ModelFeature.Remaster, ModelFeature.SubjectSelection)
    var activeModel by mutableStateOf<RemasterModelCandidate?>(null)
        private set

    var statusText by mutableStateOf("로드된 모델이 없습니다.")
        private set

    var isModelLoaded by mutableStateOf(false)
        private set

    private var closeableModel: AutoCloseable? = null
    private var sessionValidationIdentity: ModelSessionValidationIdentity? = null
    private var installedCommandGeneration: Long = 0L
    internal enum class PublicationStage { RunnerCreated, LoaderReady, SessionReady, FieldsInstalled }
    private class ModelTestSeam(
        private val factory: ((Context, String) -> AutoCloseable?)?,
        private val postCreate: (() -> Unit)?,
        private val postReady: (() -> Unit)?,
        private val onStage: (suspend (PublicationStage) -> Unit)? = null,
        private val onClose: (() -> Unit)? = null,
        private val beforeCreate: (suspend () -> Unit)? = null,
    ) {
        @Volatile private var active = true

        fun create(context: Context, assetPath: String): AutoCloseable? {
            check(active) { "model test owner closed before runner creation" }
            return factory?.invoke(context, assetPath)
        }

        fun afterCreate() { if (active) postCreate?.invoke() }
        fun afterReady() { if (active) postReady?.invoke() }
        suspend fun atStage(stage: PublicationStage) { if (active) onStage?.invoke(stage) }
        suspend fun beforeRunnerCreate() { if (active) beforeCreate?.invoke() }
        fun deactivate() {
            if (active) {
                active = false
                runCatching { onClose?.invoke() }
            }
        }
    }
    private val modelTestOwnerLock = Any()
    @Volatile private var installedModelTestOwner: ModelTestSeam? = null
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
        val generation = commandGeneration.incrementAndGet()
        val seam = synchronized(modelTestOwnerLock) { installedModelTestOwner }
        isModelLoading = true
        isModelLoaded = false
        lifecycle = ModelRunnerLifecycle.Loading
        statusText = "모델을 불러오는 중입니다."
        GlobalModelDiagnostics.publish("RemasterModelSession", "loading")
        modelScope.launch {
            modelMutex.withLock {
                if (generation != commandGeneration.get()) return@withLock
                val result = publishCandidateLocked(
                    context.applicationContext,
                    candidate,
                    ModelFeature.Remaster,
                    generation,
                    seam,
                )
                if (generation == commandGeneration.get()) {
                    statusText = statusTextFor(candidate, result)
                }
            }
        }
    }

    internal suspend fun ensureEdgeLoaded(context: Context): ModelLoadResult<Unit> {
        val generation = commandGeneration.incrementAndGet()
        val seam = synchronized(modelTestOwnerLock) { installedModelTestOwner }
        return modelMutex.withLock {
            if (generation != commandGeneration.get()) {
                return@withLock ModelLoadResult.RuntimeUnavailable("model load was superseded")
            }
            val validation = ModelAvailabilityRegistry.validatedCapabilityToken(ModelFeature.SubjectSelection)
            val token = (validation as? ModelLoadResult.Ready)?.runner
                ?: return@withLock validation.asUnitFailure().also {
                    closeInstalledRunnerLocked()
                    isModelLoading = false
                    isModelLoaded = false
                    lifecycle = ModelRunnerLifecycle.Failed
                    statusText = "모델을 불러오지 못했습니다."
                }
            if (
                activeModel?.id == "edge_masker" &&
                    isModelLoaded &&
                    closeableModel != null &&
                    sessionValidationIdentity == token.sessionIdentity()
            ) {
                statusText = "${activeModel?.title ?: "Edge Masker"} 모델을 사용할 수 있습니다."
                return@withLock ModelLoadResult.Ready(Unit)
            }
            val candidate = OnDeviceRemasterModels.firstOrNull { it.id == "edge_masker" }
                ?: return@withLock ModelLoadResult.RuntimeUnavailable("Edge Masker runner is not registered").also {
                    closeInstalledRunnerLocked()
                    isModelLoading = false
                    isModelLoaded = false
                    lifecycle = ModelRunnerLifecycle.Failed
                    statusText = "모델을 불러오지 못했습니다."
                }
            isModelLoading = true
            isModelLoaded = false
            lifecycle = ModelRunnerLifecycle.Loading
            statusText = "모델을 불러오는 중입니다."
            publishCandidateLocked(
                context.applicationContext,
                candidate,
                ModelFeature.SubjectSelection,
                generation,
                seam,
            ).also { result -> statusText = statusTextFor(candidate, result) }
        }
    }

    private fun statusTextFor(
        candidate: RemasterModelCandidate,
        result: ModelLoadResult<Unit>,
    ): String =
        when (result) {
            is ModelLoadResult.Ready -> "${candidate.title} 모델을 사용할 수 있습니다."
            else -> "${candidate.title} 모델을 불러오지 못했습니다."
        }

    private suspend fun publishCandidateLocked(
        context: Context,
        candidate: RemasterModelCandidate,
        feature: ModelFeature,
        generation: Long,
        seam: ModelTestSeam?,
    ): ModelLoadResult<Unit> {
        var loadGeneration = 0L
        val localRunner = LocalRunnerOwner()
        var publishedSessionGeneration = 0L
        var fieldsInstalled = false
        var failureResult: ModelLoadResult<Unit>? = null
        try {
            closeInstalledRunnerLocked()
            val validation = ModelAvailabilityRegistry.validatedCapabilityToken(feature)
            val token = (validation as? ModelLoadResult.Ready)?.runner
                ?: return validation.asUnitFailure().also { failureResult = it }
            if (
                !isSupportedModelContract(candidate) ||
                    candidate.id != token.modelId ||
                    candidate.assetPath != token.approvedAssetPath ||
                    ModelAssetManifest.byId(candidate.id)?.asset?.sha256 != token.approvedAssetSha256 ||
                    ModelAssetManifest.byId(candidate.id)?.asset?.packagingVersion != token.packagingVersion
            ) {
                loadGeneration = ModelAvailabilityRegistry.reportEdgeLoading()
                return ModelLoadResult.UnsupportedContract("unsupported Edge Masker contract")
                    .also { failureResult = it }
            }
            ModelAvailabilityRegistry.loaderRejection(feature)?.let { rejection ->
                return rejection.also { failureResult = it }
            }

            loadGeneration = ModelAvailabilityRegistry.reportEdgeLoading()

            seam?.beforeRunnerCreate()
            localRunner.install(createImageSegmenter(context, token.approvedAssetPath, seam))
            seam?.afterCreate()
            seam?.atStage(PublicationStage.RunnerCreated)
            checkPublicationCurrent(generation, token, "runner creation")

            ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit), loadGeneration)
            seam?.atStage(PublicationStage.LoaderReady)
            checkPublicationCurrent(generation, token, "Loader Ready")

            publishedSessionGeneration = ModelAvailabilityRegistry.reportSessionReady(EDGE_FEATURES)
            seam?.afterReady()
            seam?.atStage(PublicationStage.SessionReady)
            checkPublicationCurrent(generation, token, "Session Ready")

            closeInstalledRunnerLocked()
            closeableModel = localRunner.peek()
            activeModel = candidate
            sessionValidationIdentity = token.sessionIdentity()
            registrySessionGeneration = publishedSessionGeneration
            installedCommandGeneration = generation
            fieldsInstalled = true
            seam?.atStage(PublicationStage.FieldsInstalled)
            checkPublicationCurrent(generation, token, "field installation")
            check(closeableModel === localRunner.peek()) { "installed runner identity changed" }

            localRunner.transfer()
            isModelLoaded = true
            isModelLoading = false
            lifecycle = ModelRunnerLifecycle.Loaded
            GlobalModelDiagnostics.publish("RemasterModelSession", "loaded")
            statusText = "${candidate.title} 모델을 사용할 수 있습니다."
            return ModelLoadResult.Ready(Unit)
        } catch (cancelled: CancellationException) {
            failureResult = ModelLoadResult.LoadFailed(cancelled.message ?: "model load cancelled")
            throw cancelled
        } catch (failure: Throwable) {
            return ModelLoadResult.LoadFailed(failure.message ?: "Edge Masker load failed")
                .also { failureResult = it }
        } finally {
            val failed = failureResult
            if (failed != null) {
                val superseded = generation != commandGeneration.get()
                if (fieldsInstalled && installedCommandGeneration == generation) {
                    closeableModel = null
                    activeModel = null
                    sessionValidationIdentity = null
                    registrySessionGeneration = 0L
                    installedCommandGeneration = 0L
                }
                localRunner.close()
                if (publishedSessionGeneration != 0L) {
                    ModelAvailabilityRegistry.reportSessionClosed(EDGE_FEATURES, publishedSessionGeneration)
                    if (registrySessionGeneration == publishedSessionGeneration) registrySessionGeneration = 0L
                }
                if (loadGeneration != 0L) {
                    ModelAvailabilityRegistry.reportEdgeLoad(
                        if (superseded) ModelLoadResult.Ready(Unit) else failed,
                        loadGeneration,
                    )
                }
                if (!superseded) {
                    isModelLoaded = closeableModel != null
                    isModelLoading = false
                    lifecycle = if (isModelLoaded) ModelRunnerLifecycle.Loaded else ModelRunnerLifecycle.Failed
                    GlobalModelDiagnostics.publish(
                        "RemasterModelSession",
                        if (isModelLoaded) "loaded" else "unloaded",
                    )
                }
            }
        }
    }

    private fun checkPublicationCurrent(
        generation: Long,
        token: com.projectnuke.keplerstudio.editor.ValidatedModelCapabilityToken,
        stage: String,
    ) {
        check(generation == commandGeneration.get() && ModelAvailabilityRegistry.isCurrent(token)) {
            "model load was superseded after $stage"
        }
    }

    private fun closeInstalledRunnerLocked() {
        val runner = closeableModel
        closeableModel = null
        publishSessionClosed()
        activeModel = null
        sessionValidationIdentity = null
        installedCommandGeneration = 0L
        isModelLoaded = false
        runCatching { runner?.close() }
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
                installedCommandGeneration = 0L
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
            installedCommandGeneration = 0L
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
            EDGE_FEATURES,
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

    private fun createImageSegmenter(context: Context, assetPath: String, testSeam: ModelTestSeam? = null): AutoCloseable {
        testSeam?.create(context, assetPath)?.let { return it }
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

    /** One owner-bound model seam, captured before an async command starts. */
    internal fun installTestSeam(
        factory: ((Context, String) -> AutoCloseable?)? = null,
        postCreate: (() -> Unit)? = null,
        postReady: (() -> Unit)? = null,
        onStage: (suspend (PublicationStage) -> Unit)? = null,
        onClose: (() -> Unit)? = null,
        beforeCreate: (suspend () -> Unit)? = null,
    ): AutoCloseable {
        val seam = ModelTestSeam(factory, postCreate, postReady, onStage, onClose, beforeCreate)
        synchronized(modelTestOwnerLock) {
            check(installedModelTestOwner == null) { "model test owner already installed" }
            installedModelTestOwner = seam
        }
        return AutoCloseable {
            seam.deactivate()
            synchronized(modelTestOwnerLock) {
                if (installedModelTestOwner === seam) installedModelTestOwner = null
            }
        }
    }

    internal fun installedTestSeamCount(): Int = synchronized(modelTestOwnerLock) { if (installedModelTestOwner == null) 0 else 1 }

    internal fun validationIdentityForTest(): ModelSessionValidationIdentity? = sessionValidationIdentity
    internal fun sessionGenerationForTest(): Long = registrySessionGeneration
    internal fun installedRunnerForTest(): AutoCloseable? = closeableModel
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

    fun peek(): AutoCloseable = checkNotNull(runner)

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
