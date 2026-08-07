package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Production preset serialization / import / persistence boundary.
 *
 * Everything that interprets application preset data lives here so it can be tested
 * directly through production entry points. The Composable UI (`PresetToolPanel`)
 * coordinates launchers, IO dispatch, one successful merge/publication, persistence and
 * user-facing status, but does not re-implement any JSON or pipe-format schema.
 */
public data class Preset(
    val id: String,
    val name: String,
    val params: EditParams,
    val timestampMillis: Long,
    val look: PresetColorLook? = null,
)

/** Structured classification of why a preset document import failed. */
public sealed class PresetImportFailure {
    /** Root `format` is missing, wrong-typed, or not `keplerstudio-presets`. */
    public data object UnsupportedFormat : PresetImportFailure()

    /** Root `version` is missing, wrong-typed, negative, zero, fractional, or unsupported. */
    public data object UnsupportedVersion : PresetImportFailure()

    /** Root/presets/entries/params/look structure is malformed. */
    public data object MalformedContent : PresetImportFailure()
}

/** Carries a structured [PresetImportFailure] so the UI can report truthfully. */
public class PresetImportException(
    public val failure: PresetImportFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Historical compatibility rules for the preset JSON document format:
 *
 *  - Version 1 (commit `ea7c005`): root `{format, version:1, exportedAt, presets}` where
 *    each preset is `{id, name, timestampMillis, params}`. `params` has exactly the 14
 *    fields ending at `noiseReduction` and there is no `look` field. On upgrade a valid v1
 *    preset is migrated by synthesizing the legacy preset look from its params.
 *
 *  - Version 2 (introduced in commit `d25812a`): adds the optional `look` field.
 *    Later noise-reduction fields (`luminanceNoiseReduction`, `colorNoiseReduction`,
 *    `noiseDetailProtection`) were added while the document version remained 2, so valid
 *    earlier version-2 exports that contain only the legacy `noiseReduction` field must
 *    stay readable. For v2 an absent `look` stays `null` (never synthesized).
 */
public fun encodePresetDocument(presets: List<Preset>): JSONObject = JSONObject().apply {
    put("format", "keplerstudio-presets")
    put("version", 2)
    put("exportedAt", System.currentTimeMillis())
    put(
        "presets",
        JSONArray().apply {
            presets.forEach { preset ->
                put(
                    JSONObject().apply {
                        put("id", preset.id)
                        put("name", preset.name)
                        put("timestampMillis", preset.timestampMillis)
                        put("params", encodePresetParams(preset.params))
                        // A null look is deliberately omitted so the round trip decodes
                        // back to an absent look (null) and never synthesizes a LUT.
                        presetColorLookToJson(preset.look)?.let { put("look", it) }
                    },
                )
            }
        },
    )
}

/**
 * Decodes and fully validates a preset JSON document. Returns the complete incoming list.
 *
 * This is atomic: every array element, params object and present look is validated before
 * any list is returned. Any failure throws [PresetImportException]; a partial prefix is
 * never produced.
 */
public fun decodePresetDocument(root: JSONObject): List<Preset> {
    val format = root.opt("format")
    if (format !is String || format != "keplerstudio-presets") {
        throw PresetImportException(
            PresetImportFailure.UnsupportedFormat,
            "format field must be the JSON string \"keplerstudio-presets\"",
        )
    }

    val version =
        jsonExactInt(root.opt("version"))
            ?: throw PresetImportException(
                PresetImportFailure.UnsupportedVersion,
                "version must be an exact integral JSON number",
            )
    if (version != 1 && version != 2) {
        throw PresetImportException(
            PresetImportFailure.UnsupportedVersion,
            "unsupported preset document version $version",
        )
    }

    val presetsValue = root.opt("presets")
    if (presetsValue !is JSONArray) {
        throw PresetImportException(
            PresetImportFailure.MalformedContent,
            "presets must be a JSON array",
        )
    }

    val result = ArrayList<Preset>(presetsValue.length())
    for (i in 0 until presetsValue.length()) {
        val entry = presetsValue.opt(i)
        if (entry !is JSONObject) {
            throw PresetImportException(
                PresetImportFailure.MalformedContent,
                "presets[$i] must be a JSON object",
            )
        }
        result += decodePresetEntry(entry, version)
    }
    return result
}

/**
 * Production entry point for raw preset JSON text.
 *
 * Parses the raw JSON text, converts any JSON syntax or parse failure into a structured
 * [PresetImportException] with [PresetImportFailure.MalformedContent], then delegates to
 * [decodePresetDocument] for schema validation. Actual file-read failures must remain
 * file-read failures at the caller.
 */
public fun decodePresetDocumentText(raw: String): List<Preset> {
    val root = try {
        JSONObject(raw)
    } catch (e: JSONException) {
        throw PresetImportException(
            PresetImportFailure.MalformedContent,
            "Invalid JSON syntax: ${e.message}",
            e,
        )
    }
    return decodePresetDocument(root)
}

private fun decodePresetEntry(entry: JSONObject, version: Int): Preset {
    val id = entry.opt("id")
    if (id !is String) {
        throw malformed("id")
    }
    val name = entry.opt("name")
    if (name !is String) {
        throw malformed("name")
    }
    val timestamp =
        jsonExactLong(entry.opt("timestampMillis")) ?: throw malformed("timestampMillis")
    val paramsValue = entry.opt("params")
    if (paramsValue !is JSONObject) {
        throw malformed("params")
    }
    val params = decodePresetParams(paramsValue)
    val look =
        if (version >= 2) {
            decodeLookV2(entry)
        } else {
            // Documented v1 migration: upgrade a valid legacy preset by synthesizing its look.
            createPresetColorLookFromParams(params, strength = v1LegacyLookStrength)
        }
    return Preset(id = id, name = name, params = params, timestampMillis = timestamp, look = look)
}

private fun decodeLookV2(entry: JSONObject): PresetColorLook? {
    if (!entry.has("look")) return null
    val lookValue = entry.opt("look")
    if (lookValue == null || lookValue == JSONObject.NULL) return null
    if (lookValue !is JSONObject) {
        throw malformed("look")
    }
    return presetColorLookFromJson(lookValue) ?: throw malformed("look")
}

private fun malformed(field: String): PresetImportException =
    PresetImportException(PresetImportFailure.MalformedContent, "malformed preset field: $field")

/** Witness value used only by the documented version-1 legacy look migration. */
private const val v1LegacyLookStrength = 0.60f

/** Encode [Preset] into the modern 21-field pipe layout used for local storage. */
public fun encodeStoredPreset(preset: Preset): String = listOf(
    preset.id,
    preset.name,
    preset.timestampMillis.toString(),
    preset.params.exposure.toString(),
    preset.params.contrast.toString(),
    preset.params.shadows.toString(),
    preset.params.highlights.toString(),
    preset.params.whites.toString(),
    preset.params.blacks.toString(),
    preset.params.temperature.toString(),
    preset.params.tint.toString(),
    preset.params.saturation.toString(),
    preset.params.vibrance.toString(),
    preset.params.clarity.toString(),
    preset.params.dehaze.toString(),
    preset.params.sharpness.toString(),
    preset.params.noiseReduction.toString(),
    preset.params.luminanceNoiseReduction.toString(),
    preset.params.colorNoiseReduction.toString(),
    preset.params.noiseDetailProtection.toString(),
    Uri.encode(presetColorLookToJson(preset.look)?.toString().orEmpty()),
).joinToString("|") { it.replace("|", " ").replace("\n", " ") }


/**
 * Decodes a pipe-delimited stored preset. Returns null for an unrecognized or corrupted
 * record (the established per-record local recovery policy).
 *
 * Historical layouts (verified against the production encoder history):
 *  - 17 fields (`1bbd8e7`..`b4f25f3`): no look stored; decodes to a synthesized look.
 *  - 18 fields (`1c781fd`/`b4f25f3`): look stored at index 17.
 *  - 20 fields: accepted by the decoder for backward safety but never produced by any
 *    historical encoder; treated as a no-look layout that migrates to a synthesized look.
 *  - 21 fields (modern): split noise fields at 17..19 and look at index 20.
 *
 * A modern (18/21) record preserves a blank look as `null`, preserves a valid look exactly,
 * and drops the whole record when a nonblank stored look is malformed rather than replacing
 * it with a generated LUT.
 */
public fun decodeStoredPreset(raw: String): Preset? {
    val p = raw.split("|")
    if (p.size != 17 && p.size != 18 && p.size != 20 && p.size != 21) return null

    val hasSplitNoise = p.size == 20 || p.size == 21

    // Every field that is present in the layout must be strictly validated.
    // 17/18-field records do not contain split-noise indices, so they use migration defaults.
    // 20/21-field records must provide valid values at those indices.
    val exposure = strictPipeFloat(p[3], -1f..1f) ?: return null
    val contrast = strictPipeFloat(p[4], -1f..1f) ?: return null
    val shadows = strictPipeFloat(p[5], -1f..1f) ?: return null
    val highlights = strictPipeFloat(p[6], -1f..1f) ?: return null
    val whites = strictPipeFloat(p[7], -1f..1f) ?: return null
    val blacks = strictPipeFloat(p[8], -1f..1f) ?: return null
    val temperature = strictPipeFloat(p[9], -1f..1f) ?: return null
    val tint = strictPipeFloat(p[10], -1f..1f) ?: return null
    val saturation = strictPipeFloat(p[11], -1f..1f) ?: return null
    val vibrance = strictPipeFloat(p[12], -1f..1f) ?: return null
    val clarity = strictPipeFloat(p[13], -1f..1f) ?: return null
    val dehaze = strictPipeFloat(p[14], -1f..1f) ?: return null
    val sharpness = strictPipeFloat(p[15], 0f..1f) ?: return null
    val noiseReduction = strictPipeFloat(p[16], 0f..1f) ?: return null
    val luminanceNoiseReduction = if (hasSplitNoise) strictPipeFloat(p[17], 0f..1f) ?: return null else noiseReduction
    val colorNoiseReduction = if (hasSplitNoise) strictPipeFloat(p[18], 0f..1f) ?: return null else noiseReduction
    val noiseDetailProtection = if (hasSplitNoise) strictPipeFloat(p[19], 0f..1f) ?: return null else 0.50f

    val params =
        EditParams(
            exposure = exposure,
            contrast = contrast,
            shadows = shadows,
            highlights = highlights,
            whites = whites,
            blacks = blacks,
            temperature = temperature,
            tint = tint,
            saturation = saturation,
            vibrance = vibrance,
            clarity = clarity,
            dehaze = dehaze,
            sharpness = sharpness,
            noiseReduction = noiseReduction,
            luminanceNoiseReduction = luminanceNoiseReduction,
            colorNoiseReduction = colorNoiseReduction,
            noiseDetailProtection = noiseDetailProtection,
        )
    val timestamp = p[2].toLongOrNull() ?: return null

    val look =
        when (val result = decodeStoredLook(p, params)) {
            is StoredLookResult.Corrupt -> return null
            is StoredLookResult.Valid -> result.look
        }
    return Preset(id = p[0], name = p[1], params = params, timestampMillis = timestamp, look = look)
}

/**
 * Strictly parses a single pipe-delimited numeric field.
 *
 * Returns null if the value is not a finite Float-compatible number, is non-finite, or falls
 * outside the documented range. A caller must then reject the entire record rather than silently
 * substituting a default.
 */
private fun strictPipeFloat(value: String, range: ClosedFloatingPointRange<Float>): Float? {
    val d = value.toDoubleOrNull() ?: return null
    if (!d.isFinite()) return null
    val start = range.start.toDouble()
    val end = range.endInclusive.toDouble()
    if (d < start || d > end) return null
    return d.toFloat()
}

private sealed class StoredLookResult {
    /** A valid look; `look == null` means a genuine null look. */
    class Valid(val look: PresetColorLook?) : StoredLookResult()

    /** A nonblank stored look could not be decoded; the owning record is corrupted. */
    data object Corrupt : StoredLookResult()
}

private fun decodeStoredLook(p: List<String>, params: EditParams): StoredLookResult {
    val lookIndex =
        when (p.size) {
            18 -> 17
            21 -> 20
            // 17- and 20-field layouts never stored a look -> documented legacy migration.
            else -> return StoredLookResult.Valid(createPresetColorLookFromParams(params, strength = 0.60f))
        }
    val rawField = p.getOrNull(lookIndex) ?: return StoredLookResult.Valid(null)
    if (rawField.isBlank()) return StoredLookResult.Valid(null)
    val parsed =
        runCatching {
            val decoded = Uri.decode(rawField)
            presetColorLookFromJson(JSONObject(decoded))
        }.getOrNull()
            ?: return StoredLookResult.Corrupt
    return StoredLookResult.Valid(parsed)
}


/** Encode [EditParams] into a preset params JSON object (modern 17-field schema). */
public fun encodePresetParams(params: EditParams): JSONObject = JSONObject().apply {
    put("exposure", params.exposure)
    put("contrast", params.contrast)
    put("shadows", params.shadows)
    put("highlights", params.highlights)
    put("whites", params.whites)
    put("blacks", params.blacks)
    put("temperature", params.temperature)
    put("tint", params.tint)
    put("saturation", params.saturation)
    put("vibrance", params.vibrance)
    put("clarity", params.clarity)
    put("dehaze", params.dehaze)
    put("sharpness", params.sharpness)
    put("noiseReduction", params.noiseReduction)
    put("luminanceNoiseReduction", params.luminanceNoiseReduction)
    put("colorNoiseReduction", params.colorNoiseReduction)
    put("noiseDetailProtection", params.noiseDetailProtection)
}

/**
 * Strict [EditParams] decoding. Every field written by production export is required to be a
 * finite JSON Number within its documented range. A malformed explicit value rejects the whole
 * document instead of silently becoming a default.
 *
 * The only absent fields tolerated are the three split noise fields, which did not exist in
 * the older supported v1/v2 schemas; they receive their documented historical migration
 * defaults (`noiseReduction` fallback for luminance/color, `0.5` for detail protection).
 */
private fun decodePresetParams(obj: JSONObject): EditParams {
    val noiseReduction = requiredParam(obj, "noiseReduction", 0f..1f)
    return EditParams(
        exposure = requiredParam(obj, "exposure", -1f..1f),
        contrast = requiredParam(obj, "contrast", -1f..1f),
        shadows = requiredParam(obj, "shadows", -1f..1f),
        highlights = requiredParam(obj, "highlights", -1f..1f),
        whites = requiredParam(obj, "whites", -1f..1f),
        blacks = requiredParam(obj, "blacks", -1f..1f),
        temperature = requiredParam(obj, "temperature", -1f..1f),
        tint = requiredParam(obj, "tint", -1f..1f),
        saturation = requiredParam(obj, "saturation", -1f..1f),
        vibrance = requiredParam(obj, "vibrance", -1f..1f),
        clarity = requiredParam(obj, "clarity", -1f..1f),
        dehaze = requiredParam(obj, "dehaze", -1f..1f),
        sharpness = requiredParam(obj, "sharpness", 0f..1f),
        noiseReduction = noiseReduction,
        luminanceNoiseReduction = optionalParam(obj, "luminanceNoiseReduction", 0f..1f, noiseReduction),
        colorNoiseReduction = optionalParam(obj, "colorNoiseReduction", 0f..1f, noiseReduction),
        noiseDetailProtection = optionalParam(obj, "noiseDetailProtection", 0f..1f, 0.50f),
    )
}

private fun requiredParam(obj: JSONObject, key: String, range: ClosedFloatingPointRange<Float>): Float {
    if (!obj.has(key)) throw malformed("params.$key")
    return strictParam(obj, key, range)
}

private fun optionalParam(
    obj: JSONObject,
    key: String,
    range: ClosedFloatingPointRange<Float>,
    default: Float,
): Float {
    if (!obj.has(key)) return default
    return strictParam(obj, key, range)
}

private fun strictParam(obj: JSONObject, key: String, range: ClosedFloatingPointRange<Float>): Float {
    val value = obj.opt(key)
    if (value !is Number) throw malformed("params.$key")
    val d = value.toDouble()
    if (!d.isFinite()) throw malformed("params.$key")
    // Reject values that fall outside the documented Double range before narrowing to Float.
    // A Double slightly beyond the boundary must not round back onto the Float boundary.
    if (d < range.start.toDouble() || d > range.endInclusive.toDouble()) throw malformed("params.$key")
    return d.toFloat()
}


/**
 * Result of a preset merge settlement. [presets] is the deterministic, already-retained
 * (<= 40) result list. [retainedCount] is its size. [importedCount] reports how many distinct
 * incoming presets were actually retained; [replacedCount] reports how many distinct incoming
 * presets replaced an existing same-name current preset. Callers use these counts so they
 * never claim every parsed entry was stored.
 */
public data class PresetMergeResult(
    public val presets: List<Preset>,
    public val retainedCount: Int,
    public val importedCount: Int,
    public val replacedCount: Int,
)

/**
 * Deterministic merge with the product's existing 40-preset retention limit.
 *
 *  - names are compared case-insensitively
 *  - an incoming preset replaces the existing same-name preset
 *  - duplicates within incoming resolve to the last occurrence (stable insertion order)
 *  - the result is ordered deterministically by descending timestamp, then truncated to 40
 *  - the caller lists are never mutated
 */
public fun mergePresets(current: List<Preset>, incoming: List<Preset>): PresetMergeResult {
    fun lower(name: String): String = name.lowercase(Locale.ROOT)

    val merged = LinkedHashMap<String, Preset>()
    (current + incoming).forEach { preset -> merged[lower(preset.name)] = preset }

    val replacedCount =
        incoming.map { lower(it.name) }.toSet()
            .intersect(current.map { lower(it.name) }.toSet())
            .size

    val settled = merged.values.sortedByDescending { it.timestampMillis }
    val retained = if (settled.size > retentionLimit) settled.take(retentionLimit) else settled
    val retainedNames = retained.map { lower(it.name) }.toSet()
    val importedCount =
        incoming.map { lower(it.name) }.distinct().count { retainedNames.contains(it) }

    return PresetMergeResult(
        presets = retained,
        retainedCount = retained.size,
        importedCount = importedCount,
        replacedCount = replacedCount,
    )
}

/** Product retention limit for merged presets. */
public const val retentionLimit: Int = 40

internal const val PRESET_PREF_NAME = "kepler_studio_presets"
internal const val KEY_PRESETS = "presets"

/** Loads stored presets from local SharedPreferences, skipping individually corrupted records. */
public fun loadPresets(context: Context): List<Preset> {
    val raw =
        context.getSharedPreferences(PRESET_PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PRESETS, null)
            ?: return emptyList()
    return raw.lines().mapNotNull { decodeStoredPreset(it) }
}

/** Persists presets in the modern 21-field pipe layout to local SharedPreferences. */
public fun savePresets(context: Context, presets: List<Preset>) {
    context.getSharedPreferences(PRESET_PREF_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_PRESETS, presets.joinToString("\n") { encodeStoredPreset(it) })
        .apply()
}

/**
 * Returns the exact integral [Long] value of `value` if, and only if, it is a JSON Number with
 * a finite, integral value within [Long] range. Everything else yields null.
 */
internal fun jsonExactLong(value: Any?): Long? {
    if (value !is Number) return null
    val d = value.toDouble()
    if (!d.isFinite()) return null
    if (d != Math.floor(d)) return null
    if (d < Long.MIN_VALUE.toDouble() || d >= 9223372036854775808.0) return null
    return d.toLong()
}

/**
 * Writes a preset document to [outputStream] as UTF-8 text.
 *
 * Schema encoding is delegated to [encodePresetDocument] and the resulting JSON text is written
 * with explicit UTF-8 encoding so the byte/stream boundary is directly testable.
 */
public fun writePresetDocument(outputStream: OutputStream, presets: List<Preset>) {
    val root = encodePresetDocument(presets)
    OutputStreamWriter(outputStream, StandardCharsets.UTF_8).use { writer ->
        writer.write(root.toString(2))
    }
}

/**
 * Reads a preset document from [inputStream] as UTF-8 text.
 *
 * Stream decoding is delegated to the structured raw-text decoder so JSON syntax errors are
 * classified as [PresetImportFailure.MalformedContent].
 */
public fun readPresetDocument(inputStream: InputStream): List<Preset> {
    BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
        val raw = reader.readText()
        return decodePresetDocumentText(raw)
    }
}
