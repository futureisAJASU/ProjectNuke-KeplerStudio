package com.projectnuke.keplerstudio.editor

import kotlin.math.max
import kotlin.math.min
import org.json.JSONObject

enum class CropAspectRatio(private val legacyLabel: String, val ratio: Float?) {
    Free("?먯쑀", null),
    Original("?먮낯", -1f),
    Square("1:1", 1f),
    FourThree("4:3", 4f / 3f),
    ThreeFour("3:4", 3f / 4f),
    SixteenNine("16:9", 16f / 9f),
    NineSixteen("9:16", 9f / 16f);

    val label: String
        get() = when (this) {
            Free -> "자유"
            Original -> "원본"
            else -> legacyLabel
        }
}

data class CropState(
    val aspectRatio: CropAspectRatio = CropAspectRatio.Original,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val rotationDegrees: Int = 0,
    val straightenDegrees: Float = 0f,
    val flipHorizontal: Boolean = false,
) {
    val cropWidth: Float get() = (cropRight - cropLeft).coerceIn(0f, 1f)
    val cropHeight: Float get() = (cropBottom - cropTop).coerceIn(0f, 1f)
    fun validate(): CropState = normalized(0.001f)
}

fun CropState.isFinite(): Boolean =
    cropLeft.isFinite() && cropTop.isFinite() && cropRight.isFinite() && cropBottom.isFinite() &&
        straightenDegrees.isFinite() && cropLeft in 0f..1f && cropTop in 0f..1f &&
        cropRight in 0f..1f && cropBottom in 0f..1f && cropLeft < cropRight && cropTop < cropBottom

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
    val aspect = runCatching {
        CropAspectRatio.valueOf(json.optString("aspectRatio", CropAspectRatio.Original.name))
    }.getOrDefault(CropAspectRatio.Original)
    CropState(
        aspectRatio = aspect,
        cropLeft = json.optDouble("cropLeft", 0.0).toFiniteOrDefault(0f),
        cropTop = json.optDouble("cropTop", 0.0).toFiniteOrDefault(0f),
        cropRight = json.optDouble("cropRight", 1.0).toFiniteOrDefault(1f),
        cropBottom = json.optDouble("cropBottom", 1.0).toFiniteOrDefault(1f),
        rotationDegrees = json.optInt("rotationDegrees", 0),
        straightenDegrees = json.optDouble("straightenDegrees", 0.0).toFiniteOrDefault(0f),
        flipHorizontal = json.optBoolean("flipHorizontal", false),
    ).validate()
}.getOrNull()

private fun Double.toFiniteOrDefault(default: Float): Float = toFloat().let { if (it.isFinite()) it else default }

private fun normalizeRange(a: Float, b: Float, minSize: Float): Pair<Float, Float> {
    val safeMinSize = minSize.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.001f
    var start = min(a, b).coerceIn(0f, 1f)
    var end = max(a, b).coerceIn(0f, 1f)
    if (end - start < safeMinSize) {
        val center = ((start + end) * 0.5f).coerceIn(0f, 1f)
        start = (center - safeMinSize * 0.5f).coerceAtLeast(0f)
        end = (start + safeMinSize).coerceAtMost(1f)
        start = (end - safeMinSize).coerceAtLeast(0f)
    }
    return start to end
}

fun CropState.normalized(minSize: Float = 0.08f): CropState {
    val safeLeft = cropLeft.takeIf { it.isFinite() } ?: 0f
    val safeTop = cropTop.takeIf { it.isFinite() } ?: 0f
    val safeRight = cropRight.takeIf { it.isFinite() } ?: 1f
    val safeBottom = cropBottom.takeIf { it.isFinite() } ?: 1f
    val (left, right) = normalizeRange(safeLeft, safeRight, minSize)
    val (top, bottom) = normalizeRange(safeTop, safeBottom, minSize)
    return copy(
        cropLeft = left,
        cropTop = top,
        cropRight = right,
        cropBottom = bottom,
        rotationDegrees = ((rotationDegrees % 360) + 360) % 360,
        straightenDegrees = (straightenDegrees.takeIf { it.isFinite() } ?: 0f).coerceIn(-45f, 45f),
    )
}

fun centeredCropForAspect(imageWidth: Int, imageHeight: Int, aspect: CropAspectRatio): CropState {
    val imageRatio = if (imageHeight > 0) imageWidth.toFloat() / imageHeight.toFloat() else 1f
    val targetRatio = when (aspect) {
        CropAspectRatio.Free -> null
        CropAspectRatio.Original -> imageRatio
        else -> aspect.ratio
    }
    if (targetRatio == null || targetRatio <= 0f) return CropState(aspectRatio = aspect)
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
        cropBottom = top + normalizedHeight,
    )
}
