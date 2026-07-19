package com.projectnuke.keplerstudio.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.projectnuke.keplerstudio.editor.copyOrThrow
import com.projectnuke.keplerstudio.editor.createBitmapOrThrow
import com.projectnuke.keplerstudio.editor.createScaledBitmapOrThrow
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import org.tensorflow.lite.Interpreter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object RemasterModelSession {
    var activeModel by mutableStateOf<RemasterModelCandidate?>(null)
        private set

    var statusText by mutableStateOf("로드된 모델이 없습니다.")
        private set

    var isModelLoaded by mutableStateOf(false)
        private set

    private var closeableModel: AutoCloseable? = null
    private val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val modelMutex = Mutex()
    private var commandGeneration: Long = 0L
    var isModelLoading by mutableStateOf(false)
        private set
    var isInferring by mutableStateOf(false)
        private set

    fun load(context: Context, candidate: RemasterModelCandidate) {
        val generation = ++commandGeneration
        isModelLoading = true
        isModelLoaded = false
        modelScope.launch {
            modelMutex.withLock {
                if (generation != commandGeneration) return@withLock
                runCatching { closeableModel?.close() }
                closeableModel = null
                activeModel = candidate
                if (!hasModelAsset(context, candidate.assetPath)) {
                    isModelLoading = false
                    statusText = "${candidate.title}: 모델 파일 없음"
                    return@withLock
                }
                runCatching {
                    val created = when (candidate.id) {
                        "edge_masker" -> createImageSegmenter(context, candidate.assetPath)
                        "universal_balancer", "flare_masker" -> TfliteModelHandle(createTfliteInterpreter(context, candidate.assetPath))
                        else -> null
                    }
                    if (generation != commandGeneration) {
                        runCatching { created?.close() }
                        return@withLock
                    }
                    closeableModel = created
                }.onSuccess {
                    isModelLoaded = closeableModel != null
                    isModelLoading = false
                    statusText = if (closeableModel != null) "${candidate.title}: 사용 가능" else "${candidate.title}: 실행 경로를 준비하는 중입니다."
                }.onFailure {
                    closeableModel = null
                    isModelLoaded = false
                    isModelLoading = false
                    statusText = "${candidate.title}: 모델 로드에 실패했습니다: ${it.message}"
                }
            }
        }
    }

    suspend fun createForegroundMask(bitmap: Bitmap): Bitmap? = modelMutex.withLock {
        if (activeModel?.id != "edge_masker" || !isModelLoaded) return@withLock null
        val model = closeableModel ?: return@withLock null
        isInferring = true
        try {
            runCatching { createForegroundMaskFromSegmenter(model, bitmap) }.getOrNull()
        } finally {
            isInferring = false
        }
    }

    fun unload() {
        val generation = ++commandGeneration
        isModelLoading = true
        modelScope.launch {
            modelMutex.withLock {
                if (generation != commandGeneration) return@withLock
                runCatching { closeableModel?.close() }
                closeableModel = null
                activeModel = null
                isModelLoaded = false
                isModelLoading = false
                statusText = "로드된 모델이 없습니다."
            }
        }
    }

    suspend fun unloadIdleNow(): Boolean = modelMutex.withLock {
        if (isModelLoading || isInferring) return@withLock false
        ++commandGeneration
        runCatching { closeableModel?.close() }
        closeableModel = null
        activeModel = null
        isModelLoaded = false
        isModelLoading = false
        statusText = "로드된 모델이 없습니다."
        true
    }

    fun hasModelAsset(context: Context, assetPath: String): Boolean {
        if (assetPath.isBlank()) return false
        return runCatching {
            context.assets.open(assetPath).use { true }
        }.getOrDefault(false)
    }

    private fun createImageSegmenter(context: Context, assetPath: String): ImageSegmenter {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(assetPath)
            .build()
        val options = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setOutputCategoryMask(true)
            .setOutputConfidenceMasks(false)
            .build()
        return ImageSegmenter.createFromOptions(context, options)
    }

    private fun createTfliteInterpreter(context: Context, assetPath: String): Interpreter {
        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }
        return Interpreter(loadMappedAsset(context, assetPath), options)
    }

    private fun loadMappedAsset(context: Context, assetPath: String): MappedByteBuffer {
        val descriptor = context.assets.openFd(assetPath)
        descriptor.use { afd ->
            afd.createInputStream().channel.use { channel ->
                return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
    }

    private fun createForegroundMaskFromSegmenter(segmenter: Any, bitmap: Bitmap): Bitmap {
        val imageBuilderClass = Class.forName("com.google.mediapipe.framework.image.BitmapImageBuilder")
        val inputCopy = bitmap.copyOrThrow(Bitmap.Config.ARGB_8888, false)
            ?: error("입력 이미지를 복사하지 못했습니다.")
        var mpImage: Any? = null
        try {
            val imageBuilder = imageBuilderClass.getConstructor(Bitmap::class.java).newInstance(inputCopy)
            mpImage = imageBuilderClass.getMethod("build").invoke(imageBuilder)
        } catch (t: Throwable) {
            if (mpImage == null) inputCopy.recycle()
            throw t
        }
        var categoryMaskImage: Any? = null
        var foregroundMask: Bitmap? = null
        var primaryFailure: Throwable? = null
        try {
            val segmentMethod = segmenter.javaClass.methods.firstOrNull { method ->
                method.name == "segment" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].isAssignableFrom(mpImage!!.javaClass)
            } ?: error("segment 메서드를 찾을 수 없습니다.")
            val result = segmentMethod.invoke(segmenter, mpImage)
            val categoryMaskOptional = result.javaClass.methods.firstOrNull { method ->
                method.name == "categoryMask" && method.parameterTypes.isEmpty()
            }?.invoke(result) ?: error("category mask 결과가 없습니다.")
            val isPresent = categoryMaskOptional.javaClass.getMethod("isPresent").invoke(categoryMaskOptional) as Boolean
            if (!isPresent) error("category mask가 비어 있습니다.")
            categoryMaskImage = categoryMaskOptional.javaClass.getMethod("get").invoke(categoryMaskOptional)
            val rawMask = extractBitmapFromMpImage(categoryMaskImage as Any)
            foregroundMask = categoryBitmapToForegroundMask(rawMask, bitmap.width, bitmap.height)
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
            closeAndCollect {
                if (categoryMaskImage != null) closeMpImage(categoryMaskImage)
            }
            closeAndCollect {
                closeMpImage(mpImage)
            }
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
        val extractMethod = extractorClass.methods.firstOrNull { method ->
            method.name == "extract" && method.parameterTypes.size == 1 && method.parameterTypes[0].isAssignableFrom(maskImage.javaClass)
        } ?: error("BitmapExtractor.extract 메서드를 찾을 수 없습니다.")
        return extractMethod.invoke(null, maskImage) as Bitmap
    }

    private fun categoryBitmapToForegroundMask(rawMask: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        var scaledMask: Bitmap? = null
        var out: Bitmap? = null
        var primaryFailure: Throwable? = null
        try {
            scaledMask = if (rawMask.width == targetWidth && rawMask.height == targetHeight) {
                rawMask
            } else {
                createScaledBitmapOrThrow(rawMask, targetWidth, targetHeight, false)
            }
            out = createBitmapOrThrow(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val inRow = IntArray(targetWidth)
            val outRow = IntArray(targetWidth)
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
            }
            if (cleanupFailures.isNotEmpty()) {
                if (primaryFailure != null) {
                    cleanupFailures.forEach(primaryFailure::addSuppressed)
                    out?.recycle()
                } else {
                    out?.recycle()
                    val cleanupFailure = cleanupFailures.first()
                    cleanupFailures.drop(1).forEach(cleanupFailure::addSuppressed)
                    throw cleanupFailure
                }
            } else if (primaryFailure != null) {
                out?.recycle()
            }
        }
    }

    private fun closeMpImage(image: Any?) {
        when (image) {
            null -> Unit
            is AutoCloseable -> image.close()
            else -> image.javaClass.methods.firstOrNull { method ->
                method.name == "close" && method.parameterTypes.isEmpty()
            }?.invoke(image)
        }
    }

}

private class TfliteModelHandle(
    private val interpreter: Interpreter
) : AutoCloseable {
    override fun close() {
        interpreter.close()
    }
}
