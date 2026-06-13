package com.projectnuke.keplerstudio.editor

import kotlin.math.roundToInt

data class EditParams(
    val exposure: Float = 0f,     // -1.0 .. +1.0
    val contrast: Float = 0f,     // -1.0 .. +1.0
    val shadows: Float = 0f,      // -1.0 .. +1.0
    val highlights: Float = 0f,   // -1.0 .. +1.0
    val sharpness: Float = 0f     //  0.0 .. +1.0, MVP에서는 native placeholder
) {
    fun stableHash(): Int {
        return listOf(exposure, contrast, shadows, highlights, sharpness)
            .fold(17) { acc, v -> acc * 31 + (v * 1000f).roundToInt() }
    }
}
