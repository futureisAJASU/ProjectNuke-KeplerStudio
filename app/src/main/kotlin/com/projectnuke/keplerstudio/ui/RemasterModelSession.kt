package com.projectnuke.keplerstudio.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import org.tensorflow.lite.Interpreter

object RemasterModelSession {
    var activeModel by mutableStateOf<RemasterModelCandidate?>(null)
        private set

    var statusText by mutableStateOf("로드된 모델이 없습니다.")
        private set

    var isModelLoaded by mutableStateOf(false)
        private set

    private var closeableModel: AutoCloseable? = null

    fun load(context: Context, candidate: RemasterModelCandidate) {
        unload()
        activeModel = candidate
        if (!hasModelAsset(context, candidate.assetPath)) {
            isModelLoaded = false
            statusText = "${candidate.title}: 모델 파일 없음"
            return
        }

        runCatching {
            closeableModel = when (candidate.id) {
                "edge_masker" -> createImageSegmenter(context, candidate.assetPath)
                "universal_balancer", "flare_masker" -> TfliteModelHandle(createTfliteInterpreter(context, candidate.assetPath))
                else -> null
            }
        }.onSuccess {
            isModelLoaded = closeableModel != null
            statusText = if (closeableModel != null) {
                "${candidate.title}: 사용 가능"
            } else {
                "${candidate.title}: 모델 파일은 있지만 실행 경로는 준비 중입니다."
            }
        }.onFailure {
            closeableModel = null
            isModelLoaded = false
            statusText = "${candidate.title}: 모델 로드에 실패했습니다: ${it.message}"
        }
    }

    fun createForegroundMask(bitmap: Bitmap): Bitmap? {
        if (activeModel?.id != "edge_masker" || !isModelLoaded) return null
        val model = closeableModel ?: return null
        return runCatching { createForegroundMaskFromSegmenter(model, bitmap) }.getOrNull()
    }

    fun unload() {
        runCatching { closeableModel?.close() }
        closeableModel = null
        activeModel = null
        isModelLoaded = false
        statusText = "로드된 모델이 없습니다."
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
        val imageBuilder = imageBuilderClass.getConstructor(Bitmap::class.java).newInstance(bitmap)
        val mpImage = imageBuilderClass.getMethod("build").invoke(imageBuilder)
        try {
            val segmentMethod = segmenter.javaClass.methods.firstOrNull { method ->
                method.name == "segment" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].isAssignableFrom(mpImage.javaClass)
            } ?: error("segment 硫붿꽌?쒕? 李얠쓣 ???놁뒿?덈떎.")
            val result = segmentMethod.invoke(segmenter, mpImage)
            val categoryMaskOptional = result.javaClass.methods.firstOrNull { method ->
                method.name == "categoryMask" && method.parameterTypes.isEmpty()
            }?.invoke(result) ?: error("category mask 寃곌낵媛 ?놁뒿?덈떎.")
            val isPresent = categoryMaskOptional.javaClass.getMethod("isPresent").invoke(categoryMaskOptional) as Boolean
            if (!isPresent) error("category mask媛 鍮꾩뼱 ?덉뒿?덈떎.")
            val maskImage = categoryMaskOptional.javaClass.getMethod("get").invoke(categoryMaskOptional)
            try {
                val rawMask = extractBitmapFromMpImage(maskImage as Any)
                return categoryBitmapToForegroundMask(rawMask, bitmap.width, bitmap.height)
            } finally {
                closeMpImage(maskImage)
            }
        } finally {
            closeMpImage(mpImage)
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
        val scaledMask = if (rawMask.width == targetWidth && rawMask.height == targetHeight) {
            rawMask
        } else {
            Bitmap.createScaledBitmap(rawMask, targetWidth, targetHeight, false)
        }
        var out: Bitmap? = null
        try {
            out = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
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
            out?.recycle()
            throw t
        } finally {
            if (scaledMask !== rawMask) scaledMask.recycle()
            rawMask.recycle()
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
