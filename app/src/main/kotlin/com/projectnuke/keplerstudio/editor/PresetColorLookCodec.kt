package com.projectnuke.keplerstudio.editor

import org.json.JSONArray
import org.json.JSONObject

fun presetColorLookToJson(look: PresetColorLook?): JSONObject? {
    if (look == null) return null
    return JSONObject().apply {
        put("type", "rgb_3d_lut")
        put("version", 1)
        put("size", look.size)
        put("strength", look.strength)
        put("values", JSONArray().apply {
            look.values.forEach { value -> put(value.toDouble()) }
        })
    }
}

fun presetColorLookFromJson(obj: JSONObject?): PresetColorLook? {
    if (obj == null) return null
    if (obj.optString("type") != "rgb_3d_lut") return null
    val size = obj.optInt("size", 0)
    val valuesArray = obj.optJSONArray("values") ?: return null
    if (size < 2 || valuesArray.length() != size * size * size * 3) return null
    val values = FloatArray(valuesArray.length()) { index ->
        valuesArray.optDouble(index, 0.0).toFloat().coerceIn(0f, 1f)
    }
    return PresetColorLook(
        size = size,
        strength = obj.optDouble("strength", 0.72).toFloat().coerceIn(0f, 1f),
        values = values
    )
}

fun presetColorLookSummary(look: PresetColorLook?): String =
    if (look == null) "LUT 없음" else "${look.size}³ LUT · 강도 ${String.format("%.2f", look.strength)}"
