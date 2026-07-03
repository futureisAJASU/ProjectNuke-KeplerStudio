package com.projectnuke.keplerstudio.editor

import org.json.JSONArray
import org.json.JSONObject

private val UniversalBalancerOutputOrder = listOf(
    "exposure",
    "contrast",
    "shadows",
    "highlights",
    "whites",
    "blacks",
    "temperature",
    "tint",
    "saturation",
    "vibrance",
    "clarity",
    "dehaze",
    "sharpness",
    "noiseReduction",
    "luminanceNoiseReduction",
    "colorNoiseReduction",
    "noiseDetailProtection"
)

fun EditParams.toUniversalBalancerTargetVector(): List<Float> = listOf(
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
)

fun buildUniversalBalancerTrainingRow(
    id: String,
    imagePath: String,
    width: Int,
    height: Int,
    source: String,
    params: EditParams,
    sceneTags: List<String> = emptyList()
): String {
    val target = JSONObject().apply {
        put("exposure", params.exposure.toDouble())
        put("contrast", params.contrast.toDouble())
        put("shadows", params.shadows.toDouble())
        put("highlights", params.highlights.toDouble())
        put("whites", params.whites.toDouble())
        put("blacks", params.blacks.toDouble())
        put("temperature", params.temperature.toDouble())
        put("tint", params.tint.toDouble())
        put("saturation", params.saturation.toDouble())
        put("vibrance", params.vibrance.toDouble())
        put("clarity", params.clarity.toDouble())
        put("dehaze", params.dehaze.toDouble())
        put("sharpness", params.sharpness.toDouble())
        put("noiseReduction", params.noiseReduction.toDouble())
        put("luminanceNoiseReduction", params.luminanceNoiseReduction.toDouble())
        put("colorNoiseReduction", params.colorNoiseReduction.toDouble())
        put("noiseDetailProtection", params.noiseDetailProtection.toDouble())
    }
    return JSONObject().apply {
        put("id", id)
        put("image_path", imagePath)
        put("source", source)
        put("width", width)
        put("height", height)
        put("scene_tags", JSONArray(sceneTags))
        put("target_order", JSONArray(UniversalBalancerOutputOrder))
        put("target", target)
        put("target_vector", JSONArray(params.toUniversalBalancerTargetVector()))
    }.toString()
}
