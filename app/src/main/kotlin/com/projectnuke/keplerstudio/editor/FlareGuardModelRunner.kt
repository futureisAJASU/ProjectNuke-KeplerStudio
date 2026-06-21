package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

private const val FLARE_MASKER_MODEL_ASSET = "models/flare_guard.tflite"

/**
 * Runtime slot for the current Flare Masker model.
 *
 * Expected v1 contract:
 * - input: RGB tile/full-preview, NHWC [1, H, W, 3], FLOAT32 preferred
 * - output: grayscale flare alpha mask, usually [1, H, W, 1]
 *
 * This model only returns a grayscale flare alpha mask. It is not a restoration
 * model; callers should use it for mask-assisted correction or selection.
 */
class FlareGuardModelRunner private constructor(
    private val interpreter: Interpreter,
    val inputWidth: Int,
    val inputHeight: Int,
    private val inputLayout: TensorLayout,
    private val inputType: DataType,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val outputChannels: Int,
    private val outputLayout: TensorLayout,
    private val outputType: DataType
) : AutoCloseable {

    data class MaskResult(
        val mask: Bitmap,
        val meanAlpha: Float,
        val maxAlpha: Float
    )

    fun predictMaskOrNull(source: Bitmap): MaskResult? = runCatching {
        val resized = if (source.width == inputWidth && source.height == inputHeight) {
            source
        } else {
            Bitmap.createScaledBitmap(source, inputWidth, inputHeight, true)
        }

        try {
            val pixels = IntArray(inputWidth * inputHeight)
            resized.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

            val inputBuffer = ByteBuffer
                .allocateDirect(inputWidth * inputHeight * 3 * bytesPerElement(inputType))
                .order(ByteOrder.nativeOrder())
            writeInput(inputBuffer, pixels)
            inputBuffer.rewind()

            val outputElementCount = outputWidth * outputHeight * outputChannels
            val outputBuffer = ByteBuffer
                .allocateDirect(outputElementCount * bytesPerElement(outputType))
                .order(ByteOrder.nativeOrder())
            interpreter.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()

            readMask(outputBuffer)
        } finally {
            if (resized !== source) resized.recycle()
        }
    }.getOrNull()

    private fun writeInput(buffer: ByteBuffer, pixels: IntArray) {
        when (inputLayout) {
            TensorLayout.NHWC -> {
                for (pixel in pixels) {
                    writeChannel(buffer, (pixel ushr 16) and 0xff)
                    writeChannel(buffer, (pixel ushr 8) and 0xff)
                    writeChannel(buffer, pixel and 0xff)
                }
            }
            TensorLayout.NCHW -> {
                for (channel in 0 until 3) {
                    for (pixel in pixels) {
                        val value = when (channel) {
                            0 -> (pixel ushr 16) and 0xff
                            1 -> (pixel ushr 8) and 0xff
                            else -> pixel and 0xff
                        }
                        writeChannel(buffer, value)
                    }
                }
            }
        }
    }

    private fun writeChannel(buffer: ByteBuffer, value: Int) {
        when (inputType) {
            DataType.FLOAT32 -> buffer.putFloat((value / 255f).coerceIn(0f, 1f))
            DataType.UINT8 -> buffer.put(value.coerceIn(0, 255).toByte())
            DataType.INT8 -> buffer.put((value - 128).coerceIn(-128, 127).toByte())
            else -> error("Unsupported FlareGuard input tensor type: $inputType")
        }
    }

    private fun readMask(buffer: ByteBuffer): MaskResult {
        val values = FloatArray(outputWidth * outputHeight * outputChannels)
        for (i in values.indices) {
            values[i] = readOutputValue(buffer).coerceIn(0f, 1f)
        }

        val outPixels = IntArray(outputWidth * outputHeight)
        var sum = 0f
        var max = 0f
        for (y in 0 until outputHeight) {
            for (x in 0 until outputWidth) {
                val alpha = readOutputPixel(values, x, y).coerceIn(0f, 1f)
                sum += alpha
                if (alpha > max) max = alpha
                val v = (alpha * 255f).roundToInt().coerceIn(0, 255)
                outPixels[y * outputWidth + x] = -0x1000000 or (v shl 16) or (v shl 8) or v
            }
        }

        val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(outPixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)
        val mean = if (outPixels.isEmpty()) 0f else sum / outPixels.size
        return MaskResult(mask = bitmap, meanAlpha = mean, maxAlpha = max)
    }

    private fun readOutputPixel(values: FloatArray, x: Int, y: Int): Float {
        return when (outputLayout) {
            TensorLayout.NHWC -> {
                val base = (y * outputWidth + x) * outputChannels
                if (outputChannels == 1) {
                    values[base]
                } else {
                    var sum = 0f
                    val channels = outputChannels.coerceAtMost(3)
                    for (c in 0 until channels) sum += values[base + c]
                    sum / channels
                }
            }
            TensorLayout.NCHW -> {
                if (outputChannels == 1) {
                    values[y * outputWidth + x]
                } else {
                    var sum = 0f
                    val plane = outputWidth * outputHeight
                    val channels = outputChannels.coerceAtMost(3)
                    for (c in 0 until channels) sum += values[c * plane + y * outputWidth + x]
                    sum / channels
                }
            }
        }
    }

    private fun readOutputValue(buffer: ByteBuffer): Float {
        return when (outputType) {
            DataType.FLOAT32 -> buffer.getFloat()
            DataType.UINT8 -> (buffer.get().toInt() and 0xff) / 255f
            DataType.INT8 -> ((buffer.get().toInt() + 128).coerceIn(0, 255)) / 255f
            else -> error("Unsupported FlareGuard output tensor type: $outputType")
        }
    }

    override fun close() {
        interpreter.close()
    }

    companion object {
        fun createOrNull(context: Context): FlareGuardModelRunner? = runCatching {
            val options = Interpreter.Options().apply {
                setNumThreads(2)
                setUseXNNPACK(true)
            }
            val interpreter = Interpreter(loadMappedAsset(context, FLARE_MASKER_MODEL_ASSET), options)
            val inputTensor = interpreter.getInputTensor(0)
            val outputTensor = interpreter.getOutputTensor(0)
            val inputShape = parseInputShape(inputTensor.shape())
            val outputShape = parseOutputShape(outputTensor.shape(), inputShape.width, inputShape.height)

            FlareGuardModelRunner(
                interpreter = interpreter,
                inputWidth = inputShape.width,
                inputHeight = inputShape.height,
                inputLayout = inputShape.layout,
                inputType = inputTensor.dataType(),
                outputWidth = outputShape.width,
                outputHeight = outputShape.height,
                outputChannels = outputShape.channels,
                outputLayout = outputShape.layout,
                outputType = outputTensor.dataType()
            )
        }.getOrNull()

        private fun loadMappedAsset(context: Context, assetPath: String): MappedByteBuffer {
            try {
                context.assets.openFd(assetPath).use { assetFileDescriptor ->
                    FileInputStream(assetFileDescriptor.fileDescriptor).channel.use { channel ->
                        return channel.map(
                            FileChannel.MapMode.READ_ONLY,
                            assetFileDescriptor.startOffset,
                            assetFileDescriptor.declaredLength
                        )
                    }
                }
            } catch (error: IOException) {
                throw error
            }
        }
    }
}

private enum class TensorLayout {
    NHWC,
    NCHW
}

private data class TensorImageShape(
    val width: Int,
    val height: Int,
    val channels: Int,
    val layout: TensorLayout
)

private fun parseInputShape(shape: IntArray): TensorImageShape {
    require(shape.all { it > 0 }) { "Dynamic FlareGuard input shape is not supported yet: ${shape.contentToString()}" }
    require(shape.size == 4 && shape[0] == 1) { "Expected 4D FlareGuard input tensor, got ${shape.contentToString()}" }
    return when {
        shape[3] == 3 -> TensorImageShape(width = shape[2], height = shape[1], channels = 3, layout = TensorLayout.NHWC)
        shape[1] == 3 -> TensorImageShape(width = shape[3], height = shape[2], channels = 3, layout = TensorLayout.NCHW)
        else -> error("Expected RGB FlareGuard input tensor, got ${shape.contentToString()}")
    }
}

private fun parseOutputShape(shape: IntArray, fallbackWidth: Int, fallbackHeight: Int): TensorImageShape {
    require(shape.all { it > 0 }) { "Dynamic FlareGuard output shape is not supported yet: ${shape.contentToString()}" }
    return when {
        shape.size == 4 && shape[0] == 1 && shape[3] in 1..4 -> {
            TensorImageShape(width = shape[2], height = shape[1], channels = shape[3], layout = TensorLayout.NHWC)
        }
        shape.size == 4 && shape[0] == 1 && shape[1] in 1..4 -> {
            TensorImageShape(width = shape[3], height = shape[2], channels = shape[1], layout = TensorLayout.NCHW)
        }
        shape.size == 3 && shape[0] == 1 -> {
            TensorImageShape(width = shape[2], height = shape[1], channels = 1, layout = TensorLayout.NHWC)
        }
        shape.size == 2 -> {
            TensorImageShape(width = shape[1], height = shape[0], channels = 1, layout = TensorLayout.NHWC)
        }
        else -> {
            TensorImageShape(width = fallbackWidth, height = fallbackHeight, channels = 1, layout = TensorLayout.NHWC)
        }
    }
}

private fun bytesPerElement(type: DataType): Int {
    return when (type) {
        DataType.FLOAT32 -> 4
        DataType.UINT8 -> 1
        DataType.INT8 -> 1
        else -> error("Unsupported FlareGuard tensor type: $type")
    }
}
