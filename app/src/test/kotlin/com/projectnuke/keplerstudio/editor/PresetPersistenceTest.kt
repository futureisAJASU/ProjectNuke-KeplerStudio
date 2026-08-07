package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Local SharedPreferences pipe-layout persistence tests. All decoding goes through the real
 * production [decodeStoredPreset] / [loadPresets] / [savePresets]. Fixtures are built from the
 * actual historical layouts verified against the production encoder history.
 */
@RunWith(RobolectricTestRunner::class)
class PresetPersistenceTest {

    private val app = RuntimeEnvironment.getApplication()

    @Before
    fun clearPresets() {
        app.getSharedPreferences(PRESET_PREF_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun lookJson(): String =
        JSONObject()
            .apply {
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
            .toString()

    /**
     * Builds a pipe record from the historical field layouts:
     *  - base = id|name|ts + exposure..dehaze(12) + sharpness + noiseReduction (17 fields)
     *  - splitNoise appends luminance, color, detailProtection
     *  - look appends the Uri-encoded look JSON (18 when alone, 21 when splitNoise)
     */
    private fun body(
        id: String = "id-1",
        name: String = "기본",
        ts: String = "1000",
        splitNoise: Boolean = false,
        look: String? = null,
    ): String {
        val params =
            listOf(
                "0.1", "0.2", "-0.3", "0.4", "0.5", "-0.6", "0.7", "-0.8", "0.9", "-0.1", "0.2", "-0.3", "0.5", "0.25",
            )
        val parts = mutableListOf(id, name, ts)
        parts += params
        if (splitNoise) parts += listOf("0.4", "0.2", "0.7")
        if (look != null) parts += Uri.encode(look)
        return parts.joinToString("|")
    }

    @Test
    fun modernPresetSaveLoadWithValidLook() {
        val values = FloatArray(24) { 0.01f * (it + 1) }
        val preset = Preset("id", "모던", EditParams(), 1000L, PresetColorLook(2, 0.5f, values))
        savePresets(app, listOf(preset))

        val loaded = loadPresets(app)
        assertEquals(1, loaded.size)
        assertEquals("모던", loaded[0].name)
        val look = loaded[0].look
        assertNotNull(look)
        assertEquals(2, look.size)
        assertEquals(0.5f, look.strength)
        assertEquals(values.size, look.values.size)
        values.forEachIndexed { index, value -> assertEquals(value, look.values[index]) }
    }

    @Test
    fun modernPresetSaveLoadWithNullLook() {
        val preset = Preset("id", "null-look", EditParams(), 2000L, look = null)
        savePresets(app, listOf(preset))

        val loaded = loadPresets(app)
        assertEquals(1, loaded.size)
        assertNull(loaded[0].look)
        assertEquals(2000L, loaded[0].timestampMillis)
    }

    @Test
    fun modernMalformedNonblankLookDoesNotSynthesizeAnotherLook() {
        // A modern 21-field record with a nonblank but malformed look is dropped entirely.
        val corrupted = body(id = "id", name = "bad", splitNoise = true, look = "garbage-not-json")
        assertNull(decodeStoredPreset(corrupted))
    }

    @Test
    fun validHistorical17FieldFixture() {
        val decoded = decodeStoredPreset(body())
        assertNotNull(decoded)
        assertEquals("기본", decoded.name)
        assertEquals(1000L, decoded.timestampMillis)
        // Legacy noise migration.
        assertEquals(0.25f, decoded.params.noiseReduction)
        assertEquals(0.25f, decoded.params.luminanceNoiseReduction)
        assertEquals(0.25f, decoded.params.colorNoiseReduction)
        assertEquals(0.50f, decoded.params.noiseDetailProtection)
        // 17-field never stored a look -> legacy synthesized migration.
        assertNotNull(decoded.look)
    }

    @Test
    fun validHistorical18FieldFixture() {
        val decoded = decodeStoredPreset(body(look = lookJson()))
        assertNotNull(decoded)
        assertEquals("기본", decoded.name)
        assertEquals(0.25f, decoded.params.luminanceNoiseReduction)
        assertEquals(0.50f, decoded.params.noiseDetailProtection)
    }

    @Test
    fun validHistorical20FieldFixtureDecodesWithSynthesizedLook() {
        // No historical encoder produced a 20-field record; the decoder still defensively
        // accepts one as a no-look layout that migrates to a synthesized look.
        val decoded = decodeStoredPreset(body(splitNoise = true))
        assertNotNull(decoded)
        assertEquals(0.4f, decoded.params.luminanceNoiseReduction)
        assertEquals(0.2f, decoded.params.colorNoiseReduction)
        assertEquals(0.7f, decoded.params.noiseDetailProtection)
        assertNotNull(decoded.look)
    }

    @Test
    fun validCurrent21FieldFixture() {
        val decoded = decodeStoredPreset(body(splitNoise = true, look = lookJson()))
        assertNotNull(decoded)
        assertEquals(0.4f, decoded.params.luminanceNoiseReduction)
        assertEquals(0.2f, decoded.params.colorNoiseReduction)
        assertEquals(0.7f, decoded.params.noiseDetailProtection)
        val look = decoded.look
        assertNotNull(look)
        assertEquals(2, look.size)
        assertEquals(0.5f, look.strength)
    }

        @Test
    fun legacyNoiseFieldMigration() {
        // 17- and 18-field records fold luminance/color back to the legacy noiseReduction.
        val legacy17 = decodeStoredPreset(body())
        assertNotNull(legacy17)
        assertEquals(0.25f, legacy17.params.luminanceNoiseReduction)
        assertEquals(0.25f, legacy17.params.colorNoiseReduction)
    }

    @Test
    fun seventeenFieldLegacyLayoutPreservesKoreanName() {
        val decoded = decodeStoredPreset(body(name = "아름다운 풍경"))
        assertNotNull(decoded)
        assertEquals("아름다운 풍경", decoded.name)
    }

    @Test
    fun unknownFieldCountRejected() {
        // A 19-field record (17 base + 2 stray) is outside the supported layouts and is rejected.
        val nineteen = body() + "|0.1|0.2"
        assertEquals(19, nineteen.split("|").size)
        assertNull(decodeStoredPreset(nineteen))
    }

    @Test
    fun oneCorruptedRecordDoesNotAffectUnrelatedValidRecords() {
        val validLine = body(splitNoise = true, look = lookJson())
        val corruptLine = body(id = "corrupt", name = "corrupt", splitNoise = true, look = "not-json")
        app.getSharedPreferences(PRESET_PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRESETS, "$validLine\n$corruptLine")
            .commit()

        val loaded = loadPresets(app)
        assertEquals(1, loaded.size)
        assertEquals("기본", loaded[0].name)
        assertNotNull(loaded[0].look)
    }

    // ---- Corrupted local numeric fields --------------------------------------

    private fun corruptField(base: String, fieldIndex: Int, newValue: String): String {
        val fields = base.split("|").toMutableList()
        fields[fieldIndex] = newValue
        return fields.joinToString("|")
    }

    @Test
    fun modern21FieldMalformedTextExposureReturnsNull() {
        val record = corruptField(body(splitNoise = true, look = lookJson()), 3, "garbage")
        assertNull(decodeStoredPreset(record))
    }

    @Test
    fun modern21FieldNanNoiseReductionReturnsNull() {
        val record = corruptField(body(splitNoise = true, look = lookJson()), 16, "NaN")
        assertNull(decodeStoredPreset(record))
    }

    @Test
    fun modern21FieldInfinityContrastReturnsNull() {
        val record = corruptField(body(splitNoise = true, look = lookJson()), 4, "Infinity")
        assertNull(decodeStoredPreset(record))
    }

    @Test
    fun modern21FieldNegativeInfinityTemperatureReturnsNull() {
        val record = corruptField(body(splitNoise = true, look = lookJson()), 9, "-Infinity")
        assertNull(decodeStoredPreset(record))
    }

    @Test
    fun modern21FieldExposureBelowMinusOneReturnsNull() {
        val record = corruptField(body(splitNoise = true, look = lookJson()), 3, "-1.1")
        assertNull(decodeStoredPreset(record))
    }

    @Test
    fun modern21FieldExposureAboveOneReturnsNull() {
        val record = corruptField(body(splitNoise = true, look = lookJson()), 3, "1.1")
        assertNull(decodeStoredPreset(record))
    }

    @Test
    fun modern21FieldSharpnessNegativeReturnsNull() {
        val record = corruptField(body(splitNoise = true, look = lookJson()), 15, "-0.1")
        assertNull(decodeStoredPreset(record))
    }

    @Test
    fun modern21FieldSharpnessAboveOneReturnsNull() {
        val record = corruptField(body(splitNoise = true, look = lookJson()), 15, "1.1")
        assertNull(decodeStoredPreset(record))
    }

    @Test
    fun modern21FieldMalformedLuminanceNoiseReturnsNull() {
        val record = corruptField(body(splitNoise = true, look = lookJson()), 17, "garbage")
        assertNull(decodeStoredPreset(record))
    }

    @Test
    fun modern21FieldMalformedColorNoiseReturnsNull() {
        val record = corruptField(body(splitNoise = true, look = lookJson()), 18, "garbage")
        assertNull(decodeStoredPreset(record))
    }

    @Test
    fun modern21FieldMalformedDetailProtectionReturnsNull() {
        val record = corruptField(body(splitNoise = true, look = lookJson()), 19, "garbage")
        assertNull(decodeStoredPreset(record))
    }

    @Test
    fun legacy17FieldCorruptedNoiseReductionReturnsNullRatherThanZero() {
        val record = corruptField(body(), 16, "garbage")
        assertNull(decodeStoredPreset(record))
    }

    @Test
    fun regressionPipeFloatExposureSlightlyAboveOneRejected() {
        val record = corruptField(body(splitNoise = true, look = lookJson()), 3, "1.00000001")
        assertNull(decodeStoredPreset(record))
    }

    @Test
    fun regressionPipeFloatExposureSlightlyBelowMinusOneRejected() {
        val record = corruptField(body(splitNoise = true, look = lookJson()), 3, "-1.00000001")
        assertNull(decodeStoredPreset(record))
    }

    @Test
    fun regressionPipeFloatSharpnessSlightlyAboveOneRejected() {
        val record = corruptField(body(splitNoise = true, look = lookJson()), 15, "1.00000001")
        assertNull(decodeStoredPreset(record))
    }

    @Test
    fun regressionPipeFloatSharpnessSlightlyBelowZeroRejected() {
        val record = corruptField(body(splitNoise = true, look = lookJson()), 15, "-0.00000001")
        assertNull(decodeStoredPreset(record))
    }

    @Test
    fun mixedSharedPreferencesSkipsOnlyCorruptedRecord() {
        val valid1 = body(id = "valid-1", name = "valid-1", splitNoise = true, look = lookJson())
        val valid2 = body(id = "valid-2", name = "valid-2", splitNoise = true, look = lookJson())
        val corrupt = corruptField(body(id = "corrupt", splitNoise = true, look = lookJson()), 3, "garbage")

        app.getSharedPreferences(PRESET_PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRESETS, "$valid1\n$corrupt\n$valid2")
            .commit()

        val loaded = loadPresets(app)
        assertEquals(2, loaded.size)
        assertEquals("valid-1", loaded[0].name)
        assertEquals("valid-2", loaded[1].name)
    }
}
