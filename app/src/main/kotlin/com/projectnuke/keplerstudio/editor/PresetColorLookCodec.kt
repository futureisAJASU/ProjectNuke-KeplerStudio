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

    // A missing "version" is legacy version 1. When present it must be exactly integral 1.
    if (obj.has("version")) {
        val version = jsonExactInt(obj.opt("version"))
        if (version != 1) return null
    }

    // "size" must be present and an exact integer of at least 2.
    if (!obj.has("size")) return null
    val size = jsonExactInt(obj.opt("size")) ?: return null
    if (size < 2) return null

    // Strict overflow-checked count before any allocation or iteration.
    val expectedCount = cubicLutValueCount(size) ?: return null

    val valuesArray = obj.optJSONArray("values") ?: return null
    if (valuesArray.length() != expectedCount) return null

    val strength =
        if (obj.has("strength")) {
            val s = jsonFiniteDouble(obj.opt("strength")) ?: return null
            s.toFloat().coerceIn(0f, 1f)
        } else {
            0.72f
        }

    val values = FloatArray(expectedCount) { index ->
        val raw = jsonFiniteDouble(valuesArray.opt(index)) ?: return null
        raw.toFloat().coerceIn(0f, 1f)
    }
    return PresetColorLook(size = size, strength = strength, values = values)
}

fun presetColorLookSummary(look: PresetColorLook?): String =
    if (look == null) "색감 룩 없음" else "색감 룩 포함 · ${look.size}³ LUT · 강도 ${String.format("%.2f", look.strength)}"

/**
 * Returns the exact integral [Int] value of `value` if, and only if, it is a JSON
 * Number (a non-null [Number]) with finite, integral value within [Int] range.
 * Everything else (strings, booleans, null, objects, arrays, non-finite numbers,
 * fractional or out-of-range numbers) yields null.
 */
internal fun jsonExactInt(value: Any?): Int? {
    if (value !is Number) return null
    val d = value.toDouble()
    if (!d.isFinite()) return null
    if (d != Math.floor(d)) return null
    if (d < Int.MIN_VALUE || d > Int.MAX_VALUE) return null
    return d.toInt()
}

/**
 * Returns the finite [Double] value of `value` if, and only if, it is a JSON Number.
 * Everything else (strings, booleans, null, objects, arrays and non-finite numbers)
 * yields null.
 */
internal fun jsonFiniteDouble(value: Any?): Double? {
    if (value !is Number) return null
    val d = value.toDouble()
    if (!d.isFinite()) return null
    return d
}

/**
 * Computes the exact LUT value count `size * size * size * 3` using overflow-checked
 * arithmetic. Returns null when size is invalid (size < 2) or when the exact product
 * overflows [Long], wraps negative, or exceeds [Int.MAX_VALUE]. All rejections happen
 * before any allocation or iteration by the caller.
 */
internal fun cubicLutValueCount(size: Int): Int? {
    if (size < 2) return null
    val s = size.toLong()
    var n = s
    repeat(2) {
        // All operands are non-negative, so Long overflow can only wrap upward.
        if (n > Long.MAX_VALUE / s) return null
        n *= s
    }
    if (n > Long.MAX_VALUE / 3L) return null
    n *= 3L
    if (n < 0L || n > Int.MAX_VALUE.toLong()) return null
    return n.toInt()
}
