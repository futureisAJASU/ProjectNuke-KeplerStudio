package com.projectnuke.keplerstudio.editor

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Decoder behaviour is asserted exclusively through the production entry point
 * [presetColorLookFromJson] (with Robolectric so the Android platform `org.json`
 * implementation is used), except for the strict production numeric/count helpers
 * [jsonExactInt], [jsonFiniteDouble] and [cubicLutValueCount] which are tested
 * directly. No parsing or count logic is re-implemented in these tests.
 */
@RunWith(RobolectricTestRunner::class)
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

    // ---- Version -----------------------------------------------------------

    @Test
    fun missingVersionAcceptedAsLegacy() {
        val json = validJson().apply { remove("version") }

        val decoded = presetColorLookFromJson(json)

        assertNotNull(decoded)
        assertEquals(2, decoded.size)
        assertEquals(0.5f, decoded.strength)
    }

    @Test
    fun explicitNumericVersionAccepted() {
        val decoded = presetColorLookFromJson(validJson().apply { put("version", 1) })

        assertNotNull(decoded)
        assertEquals(2, decoded.size)
    }

    @Test
    fun unsupportedVersionsRejected() {
        listOf(0, 2, -1).forEach { version ->
            assertNull(presetColorLookFromJson(validJson().apply { put("version", version) }))
        }
    }

    @Test
    fun fractionalVersionsRejected() {
        assertNull(presetColorLookFromJson(validJson().apply { put("version", 1.1) }))
        assertNull(presetColorLookFromJson(validJson().apply { put("version", 1.9) }))
    }

    @Test
    fun stringVersionsRejected() {
        assertNull(presetColorLookFromJson(validJson().apply { put("version", "1") }))
        assertNull(presetColorLookFromJson(validJson().apply { put("version", "garbage") }))
    }

    @Test
    fun nullVersionRejected() {
        assertNull(presetColorLookFromJson(validJson().apply { put("version", JSONObject.NULL) }))
    }

    @Test
    fun booleanVersionRejected() {
        assertNull(presetColorLookFromJson(validJson().apply { put("version", true) }))
    }

    @Test
    fun objectAndArrayVersionsRejected() {
        assertNull(presetColorLookFromJson(validJson().apply { put("version", JSONObject()) }))
        assertNull(presetColorLookFromJson(validJson().apply { put("version", JSONArray()) }))
    }

    // ---- Strength ----------------------------------------------------------

    @Test
    fun missingStrengthUsesLegacyDefault() {
        val decoded = presetColorLookFromJson(validJson().apply { remove("strength") })

        assertNotNull(decoded)
        assertEquals(0.72f, decoded.strength)
    }

    @Test
    fun outOfRangeFiniteStrengthClamped() {
        val decodedLow = presetColorLookFromJson(validJson().apply { put("strength", -0.5) })
        val decodedHigh = presetColorLookFromJson(validJson().apply { put("strength", 1.7) })

        assertNotNull(decodedLow)
        assertNotNull(decodedHigh)
        assertEquals(0.0f, decodedLow.strength)
        assertEquals(1.0f, decodedHigh.strength)
    }

    @Test
    fun malformedStrengthValuesRejected() {
        assertNull(presetColorLookFromJson(validJson().apply { put("strength", "0.5") }))
        assertNull(presetColorLookFromJson(validJson().apply { put("strength", JSONObject.NULL) }))
        assertNull(presetColorLookFromJson(validJson().apply { put("strength", false) }))
        assertNull(presetColorLookFromJson(validJson().apply { put("strength", JSONObject()) }))
        assertNull(presetColorLookFromJson(validJson().apply { put("strength", JSONArray()) }))
    }

    // ---- LUT values ---------------------------------------------------------

    @Test
    fun finiteOutOfRangeValuesStillClamped() {
        val json =
            validJson().apply {
                put(
                    "values",
                    JSONArray().apply {
                        repeat(24) { put(if (it % 2 == 0) 9.0 else -3.0) }
                    },
                )
            }

        val decoded = presetColorLookFromJson(json)

        assertNotNull(decoded)
        assertEquals(24, decoded.values.size)
        decoded.values.forEachIndexed { index, value ->
            assertEquals(if (index % 2 == 0) 1.0f else 0.0f, value)
        }
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
    fun malformedLutElementsRejected() {
        // String, null, boolean, object and array elements (with otherwise valid LUT).
        val cases = listOf(
            "\"x\"", // JSON string element
            "null", // JSON null
            "true", // JSON boolean
            "{}", // JSON object
            "[]", // JSON array
        )
        cases.forEach { bad ->
            val entries = (1..23).map { "0.1" } + bad
            val json = """{"type":"rgb_3d_lut","version":1,"size":2,"strength":0.5,"values":[${entries.joinToString(",")}]}"""
            assertNull(presetColorLookFromJson(JSONObject(json)))
        }
    }

    // ---- Non-finite numbers ------------------------------------------------

    @Test
    fun androidParserRejectsNonFiniteStrengthExponentDuringParse() {
        // In the object-value position Android's org.json rejects 1e999 at parse time.
        val body = """{"type":"rgb_3d_lut","version":1,"size":2,"strength":1e999,"values":${valuesJson()}}"""
        val negative = """{"type":"rgb_3d_lut","version":1,"size":2,"strength":-1e999,"values":${valuesJson()}}"""

        assertThrows(JSONException::class.java) { JSONObject(body) }
        assertThrows(JSONException::class.java) { JSONObject(negative) }
    }

    // ---- Production strict numeric/count helpers -----------------------------

    @Test
    fun finiteExtractorRejectsInfiniteNumbers() {
        assertNull(jsonFiniteDouble(Double.POSITIVE_INFINITY))
        assertNull(jsonFiniteDouble(Double.NEGATIVE_INFINITY))
        assertNull(jsonExactInt(Double.POSITIVE_INFINITY))
        assertNull(jsonExactInt(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun countForSizeTwoIsTwentyFour() {
        assertEquals(24, cubicLutValueCount(2))
    }

    @Test
    fun countAboveIntMaxRejected() {
        // 895^3 * 3 == 2_150_752_125 which exceeds Int.MAX_VALUE but fits Long.
        assertEquals(null, cubicLutValueCount(895))
        assertNull(presetColorLookFromJson(validJson().apply { put("size", 895) }))
    }

    @Test
    fun positiveWrapCandidateRejectedByOverflowCheckedCount() {
        // A raw Long product wraps to a positive value for size 3_000_000, so a
        // naive <0 check would not catch it. The production overflow-checked path must.
        assertEquals(null, cubicLutValueCount(3_000_000))
        assertNull(presetColorLookFromJson(validJson().apply { put("size", 3_000_000) }))
    }

    // ---- Size coverage -----------------------------------------------------
    // These exercise the production strict integer parser through the real
    // entry point [presetColorLookFromJson]; no size parser is re-implemented here.

    @Test
    fun sizeMissingRejected() {
        assertNull(presetColorLookFromJson(validJson().apply { remove("size") }))
    }

    @Test
    fun sizeBelowTwoRejected() {
        assertNull(presetColorLookFromJson(validJson().apply { put("size", 1) }))
        assertNull(presetColorLookFromJson(validJson().apply { put("size", 0) }))
        assertNull(presetColorLookFromJson(validJson().apply { put("size", -5) }))
    }

    @Test
    fun sizeStringRejected() {
        // A JSON string "2" must not be coerced to the number 2.
        assertNull(presetColorLookFromJson(validJson().apply { put("size", "2") }))
        assertNull(presetColorLookFromJson(validJson().apply { put("size", "garbage") }))
    }

    @Test
    fun sizeNullRejected() {
        assertNull(presetColorLookFromJson(validJson().apply { put("size", JSONObject.NULL) }))
    }

    @Test
    fun sizeBooleanRejected() {
        assertNull(presetColorLookFromJson(validJson().apply { put("size", true) }))
        assertNull(presetColorLookFromJson(validJson().apply { put("size", false) }))
    }

        @Test
    fun sizeFractionalRejected() {
        // 2.5 has a non-zero fractional part and is not an exact integer.
        assertNull(presetColorLookFromJson(validJson().apply { put("size", 2.5) }))
        assertNull(presetColorLookFromJson(validJson().apply { put("size", 3.14) }))
    }

    @Test
    fun sizeArrayOrObjectRejected() {
        assertNull(presetColorLookFromJson(validJson().apply { put("size", JSONArray()) }))
        assertNull(presetColorLookFromJson(validJson().apply { put("size", JSONObject()) }))
    }

    @Test
    fun sizeExactIntegralNumberAccepted() {
        // A JSON number that is integral (e.g. 2.0) is accepted because it is an exact integer.
        assertNotNull(
            presetColorLookFromJson(
                validJson().apply {
                    put("size", 2.0)
                },
            ),
        )
    }


    // ---- Export/import round trip --------------------------------------------

    @Test
    fun validLookRoundTripsAllFields() {
        val values = FloatArray(24) { it * 0.02f }
        val look = PresetColorLook(size = 2, strength = 0.5f, values = values)

        val decoded = presetColorLookFromJson(presetColorLookToJson(look))

        assertNotNull(decoded)
        assertEquals(look.size, decoded.size)
        assertEquals(look.strength, decoded.strength)
        assertEquals(look.values.size, decoded.values.size)
        values.forEachIndexed { index, value -> assertEquals(value, decoded.values[index]) }
    }
}
