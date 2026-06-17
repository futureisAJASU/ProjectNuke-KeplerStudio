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

    var statusText by mutableStateOf("로드된 항목이 없습니다")
        private set

    var isModelLoaded by mutableStateOf(false)
        private set

    private var closeableModel: AutoCloseable? = null

    fun load(context: Context, candidate: RemasterModelCandidate) {
        unload()
        activeModel = candidate
        val exists = hasAsset(context, candidate.assetPath)
        if (!exists) {
            isModelLoaded = false
            statusText = "${candidate.title} 슬롯을 선택했습니다. ${candidate.assetPath} 파일을 추가하면 활성화됩니다"
            return
        }

        runCatching {
            closeableModel = when (candidate.id) {
                "edge_masker" -> createImageSegmenter(context, candidate.assetPath)
                "universal_balancer", "flare_guard" -> TfliteModelHandle(createTfliteInterpreter(context, candidate.assetPath))
                else -> null
            }
        }.onSuccess {
            isModelLoaded = true
            statusText = if (closeableModel != null) {
                "${candidate.title} 모델을 로드했습니다"
            } else {
                "${candidate.title} 파일을 확인했습니다. 실제 추론 연결은 준비 중입니다"
            }
        }.onFailure {
            closeableModel = null
            isModelLoaded = false
            statusText = "${candidate.title} 모델 로드에 실패했습니다: ${it.message}"
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
        statusText = "로드된 항목을 해제했습니다"
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
        val segmentMethod = segmenter.javaClass.methods.firstOrNull { method ->
            method.name == "segment" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0].isAssignableFrom(mpImage.javaClass)
        } ?: error("segment 메서드를 찾을 수 없습니다")
        val result = segmentMethod.invoke(segmenter, mpImage)
        val categoryMaskOptional = result.javaClass.methods.firstOrNull { method ->
            method.name == "categoryMask" && method.parameterTypes.isEmpty()
        }?.invoke(result) ?: error("category mask 결과가 없습니다")
        val isPresent = categoryMaskOptional.javaClass.getMethod("isPresent").invoke(categoryMaskOptional) as Boolean
        if (!isPresent) error("category mask가 비어 있습니다")
        val maskImage = categoryMaskOptional.javaClass.getMethod("get").invoke(categoryMaskOptional)
        val rawMask = extractBitmapFromMpImage(maskImage)
        return categoryBitmapToForegroundMask(rawMask, bitmap.width, bitmap.height)
    }

    private fun extractBitmapFromMpImage(maskImage: Any): Bitmap {
        val extractorClass = Class.forName("com.google.mediapipe.framework.image.BitmapExtractor")
        val extractMethod = extractorClass.methods.firstOrNull { method ->
            method.name == "extract" && method.parameterTypes.size == 1 && method.parameterTypes[0].isAssignableFrom(maskImage.javaClass)
        } ?: error("BitmapExtractor.extract 메서드를 찾을 수 없습니다")
        return extractMethod.invoke(null, maskImage) as Bitmap
    }

    private fun categoryBitmapToForegroundMask(rawMask: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val scaledMask = if (rawMask.width == targetWidth && rawMask.height == targetHeight) {
            rawMask
        } else {
            Bitmap.createScaledBitmap(rawMask, targetWidth, targetHeight, false)
        }
        val out = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
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
        if (scaledMask !== rawMask) scaledMask.recycle()
        return out
    }

    private fun hasAsset(context: Context, assetPath: String): Boolean = runCatching {
        context.assets.open(assetPath).use { true }
    }.getOrDefault(false)
}

private class TfliteModelHandle(
    private val interpreter: Interpreter
) : AutoCloseable {
    override fun close() {
        interpreter.close()
    }
}
