package com.projectnuke.keplerstudio.editor

import kotlin.math.roundToInt

data class EditParams(
    val exposure: Float = 0f,        // -1.0 .. +1.0 EV
    val contrast: Float = 0f,        // -1.0 .. +1.0
    val shadows: Float = 0f,         // -1.0 .. +1.0
    val highlights: Float = 0f,      // -1.0 .. +1.0
    val whites: Float = 0f,          // -1.0 .. +1.0
    val blacks: Float = 0f,          // -1.0 .. +1.0
    val temperature: Float = 0f,     // -1.0 cool .. +1.0 warm
    val tint: Float = 0f,            // -1.0 green .. +1.0 magenta
    val saturation: Float = 0f,      // -1.0 .. +1.0
    val vibrance: Float = 0f,        // -1.0 .. +1.0
    val clarity: Float = 0f,         // -1.0 .. +1.0
    val dehaze: Float = 0f,          // -1.0 .. +1.0
    val sharpness: Float = 0f,       //  0.0 .. +1.0
    val noiseReduction: Float = 0f   //  0.0 .. +1.0
) {
    fun stableHash(): Int {
        return listOf(
            exposure,
            contrast,
            shadows,
            highlights,
            whites,
            blacks,
            temperature,
            tint,
            saturation,
            vibrance,
            clarity,
            dehaze,
            sharpness,
            noiseReduction
        ).fold(17) { acc, v -> acc * 31 + (v * 1000f).roundToInt() }
    }
}
