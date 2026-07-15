package com.projectnuke.keplerstudio.editor

import kotlin.math.roundToInt
import org.json.JSONObject

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
    val noiseReduction: Float = 0f,  //  0.0 .. +1.0 legacy
    val luminanceNoiseReduction: Float = noiseReduction, //  0.0 .. +1.0
    val colorNoiseReduction: Float = noiseReduction,     //  0.0 .. +1.0
    val noiseDetailProtection: Float = 0.50f             //  0.0 .. +1.0
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
            noiseReduction,
            luminanceNoiseReduction,
            colorNoiseReduction,
            noiseDetailProtection
        ).fold(17) { acc, v -> acc * 31 + (v * 1000f).roundToInt() }
    }
}

internal fun EditParams.toJsonObject(): JSONObject = JSONObject().apply {
    put("exposure", exposure)
    put("contrast", contrast)
    put("shadows", shadows)
    put("highlights", highlights)
    put("whites", whites)
    put("blacks", blacks)
    put("temperature", temperature)
    put("tint", tint)
    put("saturation", saturation)
    put("vibrance", vibrance)
    put("clarity", clarity)
    put("dehaze", dehaze)
    put("sharpness", sharpness)
    put("noiseReduction", noiseReduction)
    put("luminanceNoiseReduction", luminanceNoiseReduction)
    put("colorNoiseReduction", colorNoiseReduction)
    put("noiseDetailProtection", noiseDetailProtection)
}

internal fun parseEditParamsFromJson(json: JSONObject?): EditParams? {
    if (json == null) return null
    return EditParams(
        exposure = json.optDouble("exposure", 0.0).toFloat(),
        contrast = json.optDouble("contrast", 0.0).toFloat(),
        shadows = json.optDouble("shadows", 0.0).toFloat(),
        highlights = json.optDouble("highlights", 0.0).toFloat(),
        whites = json.optDouble("whites", 0.0).toFloat(),
        blacks = json.optDouble("blacks", 0.0).toFloat(),
        temperature = json.optDouble("temperature", 0.0).toFloat(),
        tint = json.optDouble("tint", 0.0).toFloat(),
        saturation = json.optDouble("saturation", 0.0).toFloat(),
        vibrance = json.optDouble("vibrance", 0.0).toFloat(),
        clarity = json.optDouble("clarity", 0.0).toFloat(),
        dehaze = json.optDouble("dehaze", 0.0).toFloat(),
        sharpness = json.optDouble("sharpness", 0.0).toFloat(),
        noiseReduction = json.optDouble("noiseReduction", 0.0).toFloat(),
        luminanceNoiseReduction = json.optDouble("luminanceNoiseReduction", json.optDouble("noiseReduction", 0.0)).toFloat(),
        colorNoiseReduction = json.optDouble("colorNoiseReduction", json.optDouble("noiseReduction", 0.0)).toFloat(),
        noiseDetailProtection = json.optDouble("noiseDetailProtection", 0.50).toFloat()
    )
}
