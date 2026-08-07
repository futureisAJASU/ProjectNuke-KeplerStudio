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
import kotlin.test.assertTrue

/**
 * Production preset JSON document codec tests. Only input fixture builders are re-implemented
 * here; all decoding goes through the real production [decodePresetDocument] entry point.
 */
@RunWith(RobolectricTestRunner::class)
class PresetCodecTest {

    // ---- Fixture builders (construct input data only) -----------------------

    private fun validLookJson(): JSONObject =
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

    private fun paramsObj(): JSONObject =
        JSONObject().apply {
            put("exposure", 0.05)
            put("contrast", 0.1)
            put("shadows", -0.2)
            put("highlights", 0.3)
            put("whites", 0.0)
            put("blacks", -0.1)
            put("temperature", 0.4)
            put("tint", -0.3)
            put("saturation", 0.2)
            put("vibrance", 0.1)
            put("clarity", -0.2)
            put("dehaze", 0.3)
            put("sharpness", 0.5)
            put("noiseReduction", 0.3)
            put("luminanceNoiseReduction", 0.4)
            put("colorNoiseReduction", 0.25)
            put("noiseDetailProtection", 0.7)
        }

    private fun entry(
        name: String = "test",
        id: String = "id-1",
        ts: Long = 1000L,
        params: JSONObject = paramsObj(),
        look: JSONObject? = null,
    ): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("name", name)
            put("timestampMillis", ts)
            put("params", params)
            if (look != null) put("look", look)
        }

    private fun root(version: Int = 2, presets: JSONArray = JSONArray().put(entry())): JSONObject =
        JSONObject().apply {
            put("format", "keplerstudio-presets")
            put("version", version)
            put("presets", presets)
        }

    private fun assertMalformed(block: () -> Any?) {
        val e = assertThrows(PresetImportException::class.java) { block() }
        assertEquals(PresetImportFailure.MalformedContent, e.failure)
    }

    private fun assertUnsupportedFormat(block: () -> Any?) {
        val e = assertThrows(PresetImportException::class.java) { block() }
        assertEquals(PresetImportFailure.UnsupportedFormat, e.failure)
    }

    private fun assertUnsupportedVersion(block: () -> Any?) {
        val e = assertThrows(PresetImportException::class.java) { block() }
        assertEquals(PresetImportFailure.UnsupportedVersion, e.failure)
    }

    // ---- Valid documents -----------------------------------------------------

    @Test
    fun validVersion1DocumentImports() {
        val doc =
            root(version = 1).apply {
                put(
                    "presets",
                    JSONArray().put(
                        entry(
                            name = "v1",
                            params =
                                paramsObj().apply {
                                    remove("luminanceNoiseReduction")
                                    remove("colorNoiseReduction")
                                    remove("noiseDetailProtection")
                                },
                        ),
                    ),
                )
            }
        val presets = decodePresetDocument(doc)
        assertEquals(1, presets.size)
        assertEquals("v1", presets[0].name)
        assertEquals(0.3f, presets[0].params.noiseReduction)
        // v1 legacy migration: luminance/color fold back to noiseReduction, detail to 0.5.
        assertEquals(0.3f, presets[0].params.luminanceNoiseReduction)
        assertEquals(0.3f, presets[0].params.colorNoiseReduction)
        assertEquals(0.5f, presets[0].params.noiseDetailProtection)
    }

    @Test
    fun validCurrentVersion2DocumentImports() {
        val presets = decodePresetDocument(root(version = 2))
        assertEquals(1, presets.size)
        assertEquals(0.4f, presets[0].params.luminanceNoiseReduction)
        assertEquals(0.25f, presets[0].params.colorNoiseReduction)
        assertEquals(0.7f, presets[0].params.noiseDetailProtection)
    }

    // ---- Root format ---------------------------------------------------------

    @Test
    fun wrongFormatRejected() {
        assertUnsupportedFormat {
            decodePresetDocument(root().apply { put("format", "other") })
        }
    }

    @Test
    fun missingFormatRejected() {
        assertUnsupportedFormat {
            decodePresetDocument(root().apply { remove("format") })
        }
    }

    @Test
    fun wrongTypedFormatRejected() {
        // Number, boolean, object, array and null must not be coerced to a string.
        assertUnsupportedFormat { decodePresetDocument(root().apply { put("format", 3) }) }
        assertUnsupportedFormat { decodePresetDocument(root().apply { put("format", true) }) }
        assertUnsupportedFormat { decodePresetDocument(root().apply { put("format", JSONObject.NULL) }) }
        assertUnsupportedFormat { decodePresetDocument(root().apply { put("format", JSONObject()) }) }
        assertUnsupportedFormat { decodePresetDocument(root().apply { put("format", JSONArray()) }) }
    }

    // ---- Root version --------------------------------------------------------

    @Test
    fun missingVersionRejected() {
        assertUnsupportedVersion {
            decodePresetDocument(root().apply { remove("version") })
        }
    }

    @Test
    fun unsupportedVersionsRejected() {
        assertUnsupportedVersion { decodePresetDocument(root(version = 0)) }
        assertUnsupportedVersion { decodePresetDocument(root(version = -1)) }
        assertUnsupportedVersion { decodePresetDocument(root(version = 3)) }
    }

    @Test
    fun fractionalVersionRejected() {
        assertUnsupportedVersion { decodePresetDocument(root().apply { put("version", 1.5) }) }
    }

    @Test
    fun stringVersionRejected() {
        assertUnsupportedVersion { decodePresetDocument(root().apply { put("version", "1") }) }
        assertUnsupportedVersion { decodePresetDocument(root().apply { put("version", "2") }) }
        assertUnsupportedVersion { decodePresetDocument(root().apply { put("version", "garbage") }) }
    }

    // ---- Root presets --------------------------------------------------------

    @Test
    fun missingPresetsRejected() {
        assertMalformed {
            decodePresetDocument(root().apply { remove("presets") })
        }
    }

    @Test
    fun wrongTypedPresetsRejected() {
        assertMalformed { decodePresetDocument(root().apply { put("presets", JSONObject()) }) }
        assertMalformed { decodePresetDocument(root().apply { put("presets", "not array") }) }
        assertMalformed { decodePresetDocument(root().apply { put("presets", 5) }) }
        assertMalformed { decodePresetDocument(root().apply { put("presets", JSONObject.NULL) }) }
        assertMalformed { decodePresetDocument(root().apply { put("presets", true) }) }
    }

    @Test
    fun validEmptyPresetsArrayAccepted() {
        val presets = decodePresetDocument(root().apply { put("presets", JSONArray()) })
        assertEquals(0, presets.size)
    }

    // ---- Preset entries ------------------------------------------------------

    @Test
    fun nonObjectEntryRejectsWholeDocument() {
        val array = JSONArray().put(entry("ok")).put("not-an-object").put(entry("also-ok"))
        assertMalformed { decodePresetDocument(root().apply { put("presets", array) }) }
    }

    @Test
    fun oneInvalidEntryAmongValidRejectsWholeDocument() {
        // A valid prefix + one invalid entry must not produce a partial import.
        val array =
            JSONArray()
                .put(entry("a"))
                .put(entry("b"))
                .put(
                    entry(
                        "c",
                        params =
                            paramsObj().apply {
                                put("exposure", "1.5") // malformed type
                            },
                    ),
                )
                .put(entry("d"))
        assertMalformed { decodePresetDocument(root().apply { put("presets", array) }) }
    }

    // ---- Params object -------------------------------------------------------

    @Test
    fun missingRequiredParamsObjectRejected() {
        val preset =
            JSONObject().apply {
                put("id", "id")
                put("name", "n")
                put("timestampMillis", 1L)
            }
        assertMalformed {
            decodePresetDocument(root().apply { put("presets", JSONArray().put(preset)) })
        }
    }

        @Test
    fun wrongTypedParamsObjectRejected() {
        val preset = entry().apply { put("params", JSONObject.NULL) }
        assertMalformed {
            decodePresetDocument(root().apply { put("presets", JSONArray().put(preset)) })
        }
    }

    @Test
    fun malformedExplicitParameterRejected() {
        // A JSON string / null for a numeric field must be rejected, not defaulted to zero.
        val strParams = entry(params = paramsObj().apply { put("contrast", "0.5") })
        assertMalformed {
            decodePresetDocument(root().apply { put("presets", JSONArray().put(strParams)) })
        }
        val nullParams = entry(params = paramsObj().apply { put("contrast", JSONObject.NULL) })
        assertMalformed {
            decodePresetDocument(root().apply { put("presets", JSONArray().put(nullParams)) })
        }
        val boolParams = entry(params = paramsObj().apply { put("contrast", true) })
        assertMalformed {
            decodePresetDocument(root().apply { put("presets", JSONArray().put(boolParams)) })
        }
    }

    @Test
    fun nonFiniteParameterRejected() {
        // Android org.json cannot hold non-finite numbers: constructing such a value is
        // rejected at the JSON boundary before it could reach the strict numeric helper.
        val body =
            """{"format":"keplerstudio-presets","version":2,"presets":[{"id":"i","name":"n","timestampMillis":1,
               "params":{"exposure":1e999}}]}"""
        assertThrows(JSONException::class.java) { JSONObject(body) }
        // The production strict helper itself also rejects non-finite values.
        assertNull(jsonFiniteDouble(Double.POSITIVE_INFINITY))
        assertNull(jsonFiniteDouble(Double.NEGATIVE_INFINITY))
        assertNull(jsonFiniteDouble(Double.NaN))
    }

    @Test
    fun outOfRangeMinusOneToOneRejected() {
        assertMalformed {
            decodePresetDocument(
                root().apply { put("presets", JSONArray().put(entry(params = paramsObj().apply { put("exposure", 1.2f) }))) },
            )
        }
        assertMalformed {
            decodePresetDocument(
                root().apply { put("presets", JSONArray().put(entry(params = paramsObj().apply { put("temperature", -2f) }))) },
            )
        }
    }

    @Test
    fun outOfRangeZeroToOneRejected() {
        assertMalformed {
            decodePresetDocument(
                root().apply { put("presets", JSONArray().put(entry(params = paramsObj().apply { put("sharpness", 1.5f) }))) },
            )
        }
        assertMalformed {
            decodePresetDocument(
                root().apply { put("presets", JSONArray().put(entry(params = paramsObj().apply { put("noiseReduction", -0.5f) }))) },
            )
        }
    }

    @Test
    fun validHistoricalNoiseFieldFallbackPreserved() {
        // Early v2 exports predate the split noise fields; they must stay readable.
        val doc =
            root(version = 2).apply {
                put(
                    "presets",
                    JSONArray().put(
                        entry(
                            params =
                                paramsObj().apply {
                                    remove("luminanceNoiseReduction")
                                    remove("colorNoiseReduction")
                                    remove("noiseDetailProtection")
                                },
                        ),
                    ),
                )
            }
        val presets = decodePresetDocument(doc)
        assertEquals(0.3f, presets[0].params.luminanceNoiseReduction)
        assertEquals(0.3f, presets[0].params.colorNoiseReduction)
        assertEquals(0.5f, presets[0].params.noiseDetailProtection)
    }

    // ---- Look behavior -------------------------------------------------------

    @Test
    fun version1LegacyLookMigrationPreserved() {
        val doc =
            root(version = 1).apply {
                put(
                    "presets",
                    JSONArray().put(
                        entry(
                            params =
                                paramsObj().apply {
                                    remove("luminanceNoiseReduction")
                                    remove("colorNoiseReduction")
                                    remove("noiseDetailProtection")
                                },
                        ),
                    ),
                )
            }
        val presets = decodePresetDocument(doc)
        val look = presets[0].look
        assertNotNull(look)
        // Documented v1 migration synthesizes the legacy look from params.
        assertEquals(9, look.size)
        assertEquals(0.60f, look.strength)
    }

    @Test
    fun version2AbsentLookRemainsNull() {
        val presets = decodePresetDocument(root(version = 2))
        assertNull(presets[0].look)
    }

    @Test
    fun version2ExplicitNullLookRemainsNull() {
        val preset = entry(look = null).apply { put("look", JSONObject.NULL) }
        val presets = decodePresetDocument(root(version = 2).apply { put("presets", JSONArray().put(preset)) })
        assertNull(presets[0].look)
    }

    @Test
    fun version2ValidLookPreservedExactly() {
        val look = validLookJson()
        val presets =
            decodePresetDocument(
                root(version = 2).apply {
                    put("presets", JSONArray().put(entry(look = look)))
                },
            )
        val decoded = presets[0].look
        assertNotNull(decoded)
        assertEquals(2, decoded.size)
        assertEquals(0.5f, decoded.strength)
        assertEquals(24, decoded.values.size)
        decoded.values.forEachIndexed { index, value -> assertEquals(0.1f, value) }
    }

    @Test
    fun version2MalformedLookRejectsDocument() {
        val badLook =
            JSONObject().apply {
                put("type", "rgb_3d_lut")
                put("version", 1)
                put("size", 2)
                put("strength", 0.5)
                put(
                    "values",
                    JSONArray().apply {
                        repeat(3) { put(0.1) } // wrong count
                    },
                )
            }
        assertMalformed {
            decodePresetDocument(
                root(version = 2).apply {
                    put("presets", JSONArray().put(entry(look = badLook)))
                },
            )
        }
    }

    @Test
    fun version2MalformedLookWrongTypeRejectsDocument() {
        // look present but not an object (e.g. a string) must reject, not synthesize.
        val preset = entry(look = null).apply { put("look", "garbage") }
        assertMalformed {
            decodePresetDocument(root(version = 2).apply { put("presets", JSONArray().put(preset)) })
        }
    }

    @Test
    fun version2UnsupportedLookVersionRejectsDocument() {
        val badLook =
            validLookJson().apply {
                put("version", 2)
            }
        assertMalformed {
            decodePresetDocument(
                root(version = 2).apply {
                    put("presets", JSONArray().put(entry(look = badLook)))
                },
            )
        }
    }

    @Test
    fun noPartialPrefixImportAfterLaterInvalidEntry() {
        val array =
            JSONArray()
                .put(entry("first-valid"))
                .put(entry("second-valid"))
                .put("corrupt-not-object")
        assertMalformed {
            decodePresetDocument(root().apply { put("presets", array) })
        }
    }

    // ---- Export / import round trip ------------------------------------------

    @Test
    fun version2ExportRoundTripsEquivalentData() {
        val lookValues = FloatArray(24) { 0.01f * (it + 1) }
        val look = PresetColorLook(size = 2, strength = 0.6f, values = lookValues)
        val expected =
            listOf(
                Preset(
                    id = "id-kr-1",
                    name = "한국어 프리셋",
                    timestampMillis = 1000L,
                    params =
                        EditParams(
                            exposure = 0.05f,
                            contrast = 0.1f,
                            shadows = -0.2f,
                            highlights = 0.3f,
                            whites = 0.0f,
                            blacks = -0.1f,
                            temperature = 0.4f,
                            tint = -0.3f,
                            saturation = 0.2f,
                            vibrance = 0.1f,
                            clarity = -0.2f,
                            dehaze = 0.3f,
                            sharpness = 0.5f,
                            noiseReduction = 0.3f,
                            luminanceNoiseReduction = 0.4f,
                            colorNoiseReduction = 0.25f,
                            noiseDetailProtection = 0.7f,
                        ),
                    look = look,
                ),
                Preset(
                    id = "id-null-2",
                    name = "Null Look",
                    timestampMillis = 2000L,
                    params = EditParams(),
                    look = null,
                ),
                Preset(
                    id = "id-3",
                    name = "세 번째 프리셋",
                    timestampMillis = 3000L,
                    params = EditParams(sharpness = 1f, exposure = -1f),
                    look = null,
                ),
            )

        val decoded = decodePresetDocument(encodePresetDocument(expected))

        assertEquals(expected.size, decoded.size)
        expected.zip(decoded).forEach { (want, got) ->
            assertEquals(want.id, got.id)
            assertEquals(want.name, got.name)
            assertEquals(want.timestampMillis, got.timestampMillis)
            assertEquals(want.params, got.params)
            assertEquals(want.look == null, got.look == null)
            if (want.look != null && got.look != null) {
                assertEquals(want.look.size, got.look.size)
                assertEquals(want.look.strength, got.look.strength)
                assertEquals(want.look.values.size, got.look.values.size)
                want.look.values.forEachIndexed { index, value -> assertEquals(value, got.look.values[index]) }
            }
        }
        // The null look is exported without a "look" field and decodes back to null.
        assertNull(decoded[1].look)
        assertNull(decoded[2].look)
    }

    @Test
    fun roundTripKoreanNameSurvivesUtf8() {
        val preset = Preset(id = "1", name = "한국어 프리셋 이름", params = EditParams(), timestampMillis = 7L)
        val decoded = decodePresetDocument(encodePresetDocument(listOf(preset)))
        assertEquals("한국어 프리셋 이름", decoded.single().name)
    }
}




