package com.projectnuke.keplerstudio.editor

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PresetColorLookCodecTest {

    private fun validJson(): JSONObject =
        JSONObject().apply {
            put("type", "rgb_3d_lut")
            put("version", 1)
            put("size", 2)
            put("strength", 0.5)
            put(
                "values",
                JSONArray().apply {
                    repeat(24) { put(0.1) }
                },
            )
        }

    private fun valuesJson(count: Int = 24): String =
        (1..count).joinToString(",", "[", "]") { "0.1" }

    @Test
    fun validLookRoundTrips() {
        val values = FloatArray(24) { it * 0.02f }
        val look = PresetColorLook(size = 2, strength = 0.5f, values = values)

        val decoded = presetColorLookFromJson(presetColorLookToJson(look))

        assertNotNull(decoded)
        assertEquals(2, decoded.size)
        assertEquals(0.5f, decoded.strength)
        assertEquals(values.size, decoded.values.size)
        values.forEachIndexed { index, value -> assertEquals(value, decoded.values[index]) }
    }

    @Test
    fun missingVersionAcceptedAsLegacy() {
        val json = validJson().apply { remove("version") }

        val decoded = presetColorLookFromJson(json)

        assertNotNull(decoded)
        assertEquals(2, decoded.size)
        assertEquals(0.5f, decoded.strength)
    }

    @Test
    fun unsupportedVersionRejected() {
        assertNull(presetColorLookFromJson(validJson().apply { put("version", 2) }))
    }

    @Test
    fun wrongValueCountRejected() {
        val json =
            validJson().apply {
                put(
                    "values",
                    JSONArray().apply {
                        repeat(3) { put(0.1) }
                    },
                )
            }

        assertNull(presetColorLookFromJson(json))
    }

    @Test
    fun nonFiniteStrengthRejected() {
        // 1e999 overflows to +Infinity; -1e999 to -Infinity (portable across org.json impls).
        val json = """{"type":"rgb_3d_lut","version":1,"size":2,"strength":1e999,"values":${valuesJson()}}"""
        val negative = """{"type":"rgb_3d_lut","version":1,"size":2,"strength":-1e999,"values":${valuesJson()}}"""
        assertNull(presetColorLookFromJson(JSONObject(json)))
        assertNull(presetColorLookFromJson(JSONObject(negative)))
    }

    @Test
    fun nonFiniteLutElementRejected() {
        val entries = (1..23).map { "0.1" } + "1e999"
        val json = """{"type":"rgb_3d_lut","version":1,"size":2,"strength":0.5,"values":[${entries.joinToString(",")}]}"""

        assertNull(presetColorLookFromJson(JSONObject(json)))
    }

    @Test
    fun finiteOutOfRangeValuesStillClamped() {
        val json =
            validJson().apply {
                put("strength", 1.7)
                put(
                    "values",
                    JSONArray().apply {
                        repeat(24) { put(if (it % 2 == 0) 9.0 else -3.0) }
                    },
                )
            }

        val decoded = presetColorLookFromJson(json)

        assertNotNull(decoded)
        assertEquals(1.0f, decoded.strength)
        assertEquals(24, decoded.values.size)
        decoded.values.forEachIndexed { index, value ->
            assertEquals(if (index % 2 == 0) 1.0f else 0.0f, value)
        }
    }
}
