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
import com.projectnuke.keplerstudio.editor.ModelAssetValidator
import com.projectnuke.keplerstudio.editor.ModelAvailability
import com.projectnuke.keplerstudio.editor.ModelChannelOrder
import com.projectnuke.keplerstudio.editor.ModelColorSpace
import com.projectnuke.keplerstudio.editor.ModelConfidence
import com.projectnuke.keplerstudio.editor.ModelFailure
import com.projectnuke.keplerstudio.editor.ModelFailureReason
import com.projectnuke.keplerstudio.editor.ModelInputContract
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
    private val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val modelMutex = Mutex()
    private val commandGeneration = AtomicLong()
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
        isModelLoading = true
        isModelLoaded = false
        lifecycle = ModelRunnerLifecycle.Loading
        GlobalModelDiagnostics.publish("RemasterModelSession", "loading")
        modelScope.launch {
            modelMutex.withLock {
                if (generation != commandGeneration.get()) return@withLock
                runCatching { closeableModel?.close() }
                closeableModel = null
                activeModel = candidate
                if (!isSupportedModelContract(candidate)) {
                    isModelLoading = false
                    lifecycle = ModelRunnerLifecycle.Failed
                    GlobalModelDiagnostics.publish("RemasterModelSession", "failed")
                    statusText = "${candidate.title}: unsupported model contract"
                    return@withLock
                }
                if (!hasModelAsset(context, candidate.assetPath)) {
                    isModelLoading = false
                    lifecycle = ModelRunnerLifecycle.Failed
                    GlobalModelDiagnostics.publish("RemasterModelSession", "unloaded")
                    statusText = "${candidate.title}: 모델 파일 없음"
                    return@withLock
                }
                runCatching {
                        val created =
                            when (candidate.id) {
                                "edge_masker" -> createImageSegmenter(context, candidate.assetPath)
                                else -> null
                            }
                        if (generation != commandGeneration.get()) {
                            runCatching { created?.close() }
                            return@withLock
                        }
                        closeableModel = created
                    }
                    .onSuccess {
                        isModelLoaded = closeableModel != null
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
                        closeableModel = null
                        isModelLoaded = false
                        isModelLoading = false
                        lifecycle = ModelRunnerLifecycle.Failed
                        GlobalModelDiagnostics.publish("RemasterModelSession", "unloaded")
                        statusText = "${candidate.title}: 모델 로드에 실패했습니다: ${it.message}"
                    }
            }
        }
    }

    internal suspend fun createForegroundMask(
        bitmap: Bitmap,
        diagnostics: MemoryTrackerScope? = null,
        operation: ModelOperationContext,
    ): Bitmap? =
        when (val result = createForegroundMaskResult(bitmap, diagnostics, operation)) {
            is ModelRunResult.Success -> result.value.adopt()
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
            if (activeModel?.id != "edge_masker" || !isModelLoaded) {
                return@withLock ModelRunResult.Failure(
                    ModelFailure(ModelFailureReason.Closed),
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
                val maskBitmap =
                    createForegroundMaskFromSegmenter(model, bitmap, diagnostics)
                operation.validateOrThrow()
                val trackingGeneration =
                    operation.documentGeneration.takeIf { it != "unspecified" } ?: "session"
                ownedMask =
                    TrackedMask.acquire(
                        bitmap = maskBitmap,
                        scope = diagnostics,
                        owner = "remaster:finalArgbMask",
                        modelId = activeModel?.id ?: "edge_masker",
                        modelVersion = activeModel?.semanticVersion ?: "1.0.0",
                        operationToken = operation.operationToken,
                        documentGeneration = trackingGeneration,
                        confidenceMetrics =
                            ModelConfidence(
                                wholeImageMean = 1f,
                                peak = 1f,
                                activeRegionMean = 1f,
                                activeRegionPercentile = 1f,
                                affectedAreaRatio = 1f,
                                backgroundLeakage = 0f,
                                finalPolicy = 1f,
                            ),
                    )
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
                runCatching { closeableModel?.close() }
                closeableModel = null
                activeModel = null
                isModelLoaded = false
                isModelLoading = false
                lifecycle = ModelRunnerLifecycle.Unloaded
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
            runCatching { closeableModel?.close() }
            closeableModel = null
            activeModel = null
            isModelLoaded = false
            isModelLoading = false
            lifecycle = ModelRunnerLifecycle.Unloaded
            GlobalModelDiagnostics.publish("RemasterModelSession", "unloaded")
            statusText = "로드된 모델이 없습니다."
            true
        }

    fun hasModelAsset(context: Context, assetPath: String): Boolean {
        if (assetPath.isBlank()) return false
        return runCatching { context.assets.open(assetPath).use { true } }.getOrDefault(false)
    }

    fun modelAvailability(
        context: Context,
        candidate: RemasterModelCandidate,
    ): ModelAvailability {
        val manifest =
            ModelAssetManifest.byId(candidate.id)
                ?: return ModelAvailability.ContractUnsupported
        val validation =
            ModelAssetValidator.validate(manifest) { path ->
                runCatching { context.assets.open(path) }.getOrNull()
            }
        return ModelAssetValidator.availability(
            entry = manifest,
            validation = validation,
            loaded = activeModel?.id == candidate.id && isModelLoaded,
            inferenceAvailable =
                activeModel?.id == candidate.id &&
                    isModelLoaded &&
                    manifest.inferenceAdapterImplemented,
        )
    }

    private fun isSupportedModelContract(candidate: RemasterModelCandidate): Boolean {
        val manifest = ModelAssetManifest.byId(candidate.id) ?: return false
        return candidate.id == "edge_masker" &&
            manifest.inferenceAdapterImplemented &&
            manifest.outputSemantic == ModelOutputSemantic.ForegroundCategoryMask &&
            candidate.semanticVersion == manifest.asset.semanticModelVersion &&
            manifest.asset.requiredContractSchemaVersion == ModelAssetManifest.CONTRACT_SCHEMA_VERSION
    }

    private fun createImageSegmenter(context: Context, assetPath: String): ImageSegmenter {
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
    ): Bitmap {
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
        var foregroundMask: Bitmap? = null
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
                    )
            } finally {
                if (!rawMask.isRecycled) rawMask.recycle()
                diagnostics?.release(rawEdge)
            }
            return foregroundMask
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
                    foregroundMask?.recycle()
                } else {
                    foregroundMask?.recycle()
                    val cleanupFailure = cleanupFailures.first()
                    cleanupFailures.drop(1).forEach(cleanupFailure::addSuppressed)
                    throw cleanupFailure
                }
            } else if (primaryFailure != null) {
                foregroundMask?.recycle()
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
     * Build the final ARGB-8888 foreground mask bitmap. The returned bitmap is the
     * caller's responsibility; the caller wraps it into a [TrackedMask] so its
     * diagnostic edge is tracked together with the bitmap identity (no separate
     * raw Long edge to leak).
     */
    private fun categoryBitmapToForegroundMask(
        rawMask: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        diagnostics: MemoryTrackerScope?,
    ): Bitmap {
        var scaledMask: Bitmap? = null
        var out: Bitmap? = null
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
            out = createBitmapOrThrow(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            // Do NOT pre-track the final mask here: the caller wraps it in a TrackedMask
            // which registers the diagnostic edge (with its own labelled owner) on
            // acquire. Tracking here would create a duplicate edge that leaks.
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
                    val mask = if (category > 0) 255 else 0
                    outRow[x] = -0x1000000 or (mask shl 16) or (mask shl 8) or mask
                }
                out.setPixels(outRow, 0, targetWidth, 0, y, targetWidth, 1)
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
                recycleAndCollect { out?.recycle() }
            }
            if (cleanupFailures.isNotEmpty()) {
                if (primaryFailure != null) {
                    cleanupFailures.forEach(primaryFailure::addSuppressed)
                    if (!succeeded) out?.recycle()
                } else {
                    if (!succeeded) out?.recycle()
                    val cleanupFailure = cleanupFailures.first()
                    cleanupFailures.drop(1).forEach(cleanupFailure::addSuppressed)
                    throw cleanupFailure
                }
            } else if (primaryFailure != null) {
                if (!succeeded) out?.recycle()
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
}
