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
    val version = if (obj.has("version")) obj.optInt("version", 1) else 1
    if (version != 1) return null
    val size = obj.optInt("size", 0)
    val valuesArray = obj.optJSONArray("values") ?: return null
    if (size < 2) return null
    val expectedCount = size.toLong() * size * size * 3
    if (expectedCount < 0 || valuesArray.length().toLong() != expectedCount) return null

    val strength = obj.optDouble("strength", 0.72)
    if (!strength.isFinite()) return null
    val strengthFloat = strength.toFloat().coerceIn(0f, 1f)

    val values = FloatArray(valuesArray.length()) { index ->
        val raw = valuesArray.optDouble(index, 0.0)
        if (!raw.isFinite()) return null
        raw.toFloat().coerceIn(0f, 1f)
    }
    return PresetColorLook(
        size = size,
        strength = strengthFloat,
        values = values
    )
}

fun presetColorLookSummary(look: PresetColorLook?): String =
    if (look == null) "색감 룩 없음" else "색감 룩 포함 · ${look.size}³ LUT · 강도 ${String.format("%.2f", look.strength)}"
