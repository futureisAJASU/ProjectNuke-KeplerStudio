package com.projectnuke.keplerstudio.editor

import kotlin.math.max
import kotlin.math.min
import org.json.JSONObject

enum class CropAspectRatio(val label: String, val ratio: Float?) {
    Free("자유", null),
    Original("원본", -1f),
    Square("1:1", 1f),
    FourThree("4:3", 4f / 3f),
    ThreeFour("3:4", 3f / 4f),
    SixteenNine("16:9", 16f / 9f),
    NineSixteen("9:16", 9f / 16f)
}

data class CropState(
    val aspectRatio: CropAspectRatio = CropAspectRatio.Original,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val rotationDegrees: Int = 0,
    val straightenDegrees: Float = 0f,
    val flipHorizontal: Boolean = false
) {
    val cropWidth: Float get() = (cropRight - cropLeft).coerceIn(0f, 1f)
    val cropHeight: Float get() = (cropBottom - cropTop).coerceIn(0f, 1f)
}

internal fun CropState.toJsonObject(): JSONObject = JSONObject().apply {
    put("aspectRatio", aspectRatio.name)
    put("cropLeft", cropLeft)
    put("cropTop", cropTop)
    put("cropRight", cropRight)
    put("cropBottom", cropBottom)
    put("rotationDegrees", rotationDegrees)
    put("straightenDegrees", straightenDegrees)
    put("flipHorizontal", flipHorizontal)
}

internal fun parseCropStateFromJson(json: JSONObject): CropState? = runCatching {
    val aspectName = json.optString("aspectRatio", CropAspectRatio.Original.name)
    val aspect = runCatching { CropAspectRatio.valueOf(aspectName) }.getOrDefault(CropAspectRatio.Original)
    CropState(
        aspectRatio = aspect,
        cropLeft = json.optDouble("cropLeft", 0.0).toFloat(),
        cropTop = json.optDouble("cropTop", 0.0).toFloat(),
        cropRight = json.optDouble("cropRight", 1.0).toFloat(),
        cropBottom = json.optDouble("cropBottom", 1.0).toFloat(),
        rotationDegrees = json.optInt("rotationDegrees", 0),
        straightenDegrees = json.optDouble("straightenDegrees", 0.0).toFloat(),
        flipHorizontal = json.optBoolean("flipHorizontal", false)
    )
}.getOrNull()

private fun normalizeRange(a: Float, b: Float, minSize: Float): Pair<Float, Float> {
    val safeMinSize = minSize.coerceIn(0f, 1f)
    var start = min(a, b).coerceIn(0f, 1f)
    var end = max(a, b).coerceIn(0f, 1f)

    if (end - start < safeMinSize) {
        val center = ((start + end) * 0.5f).coerceIn(0f, 1f)
        start = center - safeMinSize * 0.5f
        end = center + safeMinSize * 0.5f

        if (start < 0f) {
            end -= start
            start = 0f
        }
        if (end > 1f) {
            start -= end - 1f
            end = 1f
        }

        start = start.coerceIn(0f, 1f)
        end = end.coerceIn(0f, 1f)
    }

    return start to end
}

fun CropState.normalized(minSize: Float = 0.08f): CropState {
    val (left, right) = normalizeRange(cropLeft, cropRight, minSize)
    val (top, bottom) = normalizeRange(cropTop, cropBottom, minSize)
    return copy(
        cropLeft = left,
        cropTop = top,
        cropRight = right,
        cropBottom = bottom,
        rotationDegrees = ((rotationDegrees % 360) + 360) % 360,
        straightenDegrees = straightenDegrees.coerceIn(-45f, 45f)
    )
}

fun centeredCropForAspect(imageWidth: Int, imageHeight: Int, aspect: CropAspectRatio): CropState {
    val imageRatio = if (imageHeight > 0) imageWidth.toFloat() / imageHeight.toFloat() else 1f
    val targetRatio = when (aspect) {
        CropAspectRatio.Free -> null
        CropAspectRatio.Original -> imageRatio
        else -> aspect.ratio
    }
    if (targetRatio == null || targetRatio <= 0f) {
        return CropState(aspectRatio = aspect)
    }
    val normalizedWidth: Float
    val normalizedHeight: Float
    if (imageRatio > targetRatio) {
        normalizedHeight = 1f
        normalizedWidth = (targetRatio / imageRatio).coerceIn(0.08f, 1f)
    } else {
        normalizedWidth = 1f
        normalizedHeight = (imageRatio / targetRatio).coerceIn(0.08f, 1f)
    }
    val left = (1f - normalizedWidth) / 2f
    val top = (1f - normalizedHeight) / 2f
    return CropState(
        aspectRatio = aspect,
        cropLeft = left,
        cropTop = top,
        cropRight = left + normalizedWidth,
        cropBottom = top + normalizedHeight
    )
}
