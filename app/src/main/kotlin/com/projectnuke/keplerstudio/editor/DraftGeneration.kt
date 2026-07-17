package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class DraftGenerationManifest(
    val formatVersion: Int,
    val generationId: String,
    val savedAtMillis: Long,
    val draftOperationEpoch: Long,
    val editorRevision: Int,
    val originalSourceIdentity: String?,
    val sourceIdentity: String?,
    val baseContentToken: String,
    val baseBitmapDirty: Boolean,
    val sourceFileName: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val thumbnailFileName: String,
    val thumbnailWidth: Int,
    val thumbnailHeight: Int,
    val params: EditParams,
    val noiseEngine: String,
    val detailEngine: String,
    val toneEngine: String,
    val hazeEngine: String,
    val presetLook: PresetColorLook?,
    val activeQuickEffects: List<ActiveQuickEffect>,
    val exportFormat: String,
    val exportResolution: String,
    val cropState: CropState,
    val selectionLayers: List<DraftSelectionLayerEntry>,
    val activeSelectionLayerId: String?,
    val selectionPaintSettings: SelectionPaintSettings,
    val showSelectionOverlay: Boolean
)

internal data class DraftSelectionLayerEntry(
    val id: String,
    val name: String,
    val kind: String,
    val enabled: Boolean,
    val inverted: Boolean,
    val opacity: Float,
    val localParams: EditParams,
    val maskFileName: String,
    val maskWidth: Int,
    val maskHeight: Int,
    val sourceIdentity: String
)

internal fun DraftGenerationManifest.toJson(): JSONObject = JSONObject().apply {
    put("formatVersion", formatVersion)
    put("generationId", generationId)
    put("savedAtMillis", savedAtMillis)
    put("draftOperationEpoch", draftOperationEpoch)
    put("editorRevision", editorRevision)
    put("originalSourceIdentity", originalSourceIdentity ?: JSONObject.NULL)
    put("sourceIdentity", sourceIdentity ?: JSONObject.NULL)
    put("baseContentToken", baseContentToken)
    put("baseBitmapDirty", baseBitmapDirty)
    put("sourceFileName", sourceFileName)
    put("sourceWidth", sourceWidth)
    put("sourceHeight", sourceHeight)
    put("thumbnailFileName", thumbnailFileName)
    put("thumbnailWidth", thumbnailWidth)
    put("thumbnailHeight", thumbnailHeight)
    put("params", params.toJsonObject())
    put("noiseEngine", noiseEngine)
    put("detailEngine", detailEngine)
    put("toneEngine", toneEngine)
    put("hazeEngine", hazeEngine)
    put("presetLook", presetLook?.let { presetColorLookToJson(it) } ?: JSONObject.NULL)
    put("activeQuickEffects", JSONArray().apply {
        activeQuickEffects.forEach { put(JSONObject().apply { put("kind", it.kind.name); put("strength", it.strength.name) }) }
    })
    put("exportFormat", exportFormat)
    put("exportResolution", exportResolution)
    put("cropState", cropState.toJsonObject())
    put("selectionLayers", JSONArray().apply {
        selectionLayers.forEach { layer ->
            put(JSONObject().apply {
                put("id", layer.id)
                put("name", layer.name)
                put("kind", layer.kind)
                put("enabled", layer.enabled)
                put("inverted", layer.inverted)
                put("opacity", layer.opacity)
                put("localParams", layer.localParams.toJsonObject())
                put("maskFileName", layer.maskFileName)
                put("maskWidth", layer.maskWidth)
                put("maskHeight", layer.maskHeight)
                put("sourceIdentity", layer.sourceIdentity)
            })
        }
    })
    put("activeSelectionLayerId", activeSelectionLayerId ?: JSONObject.NULL)
    put("selectionPaintSettings", JSONObject().apply {
        put("mode", selectionPaintSettings.mode.name)
        put("sizePx", selectionPaintSettings.sizePx)
        put("feather", selectionPaintSettings.feather)
        put("strength", selectionPaintSettings.strength)
    })
    put("showSelectionOverlay", showSelectionOverlay)
}

internal fun parseDraftGenerationManifest(json: JSONObject): DraftGenerationManifest? = runCatching {
    val formatVersion = json.requiredInt("formatVersion")
    if (formatVersion != DRAFT_FORMAT_VERSION) return null
    val layerArray = json.requiredArray("selectionLayers")
    val layers = ArrayList<DraftSelectionLayerEntry>(layerArray.length())
    for (i in 0 until layerArray.length()) {
        val layerObj = layerArray.getJSONObject(i)
        layers += DraftSelectionLayerEntry(
            id = layerObj.requiredString("id"),
            name = layerObj.requiredString("name", allowBlank = true),
            kind = layerObj.requiredEnum<SelectionLayerKind>("kind").name,
            enabled = layerObj.requiredBoolean("enabled"),
            inverted = layerObj.requiredBoolean("inverted"),
            opacity = layerObj.requiredFloat("opacity", 0f..1f),
            localParams = layerObj.requiredEditParams("localParams"),
            maskFileName = layerObj.requiredString("maskFileName"),
            maskWidth = layerObj.requiredPositiveInt("maskWidth"),
            maskHeight = layerObj.requiredPositiveInt("maskHeight"),
            sourceIdentity = layerObj.requiredString("sourceIdentity")
        )
    }
    val paintObj = json.requiredObject("selectionPaintSettings")
    val paintSettings = SelectionPaintSettings(
        mode = paintObj.requiredEnum("mode"),
        sizePx = paintObj.requiredFloat("sizePx", 1f..4096f),
        feather = paintObj.requiredFloat("feather", 0f..1f),
        strength = paintObj.requiredFloat("strength", 0f..1f)
    )
    val quickArray = json.requiredArray("activeQuickEffects")
    val quickEffects = ArrayList<ActiveQuickEffect>(quickArray.length())
    for (i in 0 until quickArray.length()) {
        val qObj = quickArray.getJSONObject(i)
        quickEffects += ActiveQuickEffect(
            kind = qObj.requiredEnum("kind"),
            strength = qObj.requiredEnum("strength")
        )
    }
    val cropObject = json.requiredObject("cropState")
    val cropState = CropState(
        aspectRatio = cropObject.requiredEnum("aspectRatio"),
        cropLeft = cropObject.requiredFloat("cropLeft", 0f..1f),
        cropTop = cropObject.requiredFloat("cropTop", 0f..1f),
        cropRight = cropObject.requiredFloat("cropRight", 0f..1f),
        cropBottom = cropObject.requiredFloat("cropBottom", 0f..1f),
        rotationDegrees = cropObject.requiredInt("rotationDegrees"),
        straightenDegrees = cropObject.requiredFloat("straightenDegrees", -45f..45f),
        flipHorizontal = cropObject.requiredBoolean("flipHorizontal")
    )
    val presetObject = json.optJSONObject("presetLook")
    val parsedPreset = presetObject?.let { presetColorLookFromJson(it) }
    if (presetObject != null && parsedPreset == null) return null
    val activeLayerId = json.optionalString("activeSelectionLayerId")
    DraftGenerationManifest(
        formatVersion = formatVersion,
        generationId = json.requiredString("generationId"),
        savedAtMillis = json.requiredPositiveLong("savedAtMillis"),
        draftOperationEpoch = json.requiredNonNegativeLong("draftOperationEpoch"),
        editorRevision = json.requiredNonNegativeInt("editorRevision"),
        originalSourceIdentity = json.optionalString("originalSourceIdentity"),
        sourceIdentity = json.requiredString("sourceIdentity"),
        baseContentToken = json.requiredString("baseContentToken"),
        baseBitmapDirty = json.requiredBoolean("baseBitmapDirty"),
        sourceFileName = json.requiredString("sourceFileName"),
        sourceWidth = json.requiredPositiveInt("sourceWidth"),
        sourceHeight = json.requiredPositiveInt("sourceHeight"),
        thumbnailFileName = json.requiredString("thumbnailFileName"),
        thumbnailWidth = json.requiredPositiveInt("thumbnailWidth"),
        thumbnailHeight = json.requiredPositiveInt("thumbnailHeight"),
        params = json.requiredEditParams("params"),
        noiseEngine = json.requiredEnum<NoiseEngine>("noiseEngine").name,
        detailEngine = json.requiredEnum<DetailEngine>("detailEngine").name,
        toneEngine = json.requiredEnum<ToneEngine>("toneEngine").name,
        hazeEngine = json.requiredEnum<DehazeEngine>("hazeEngine").name,
        presetLook = parsedPreset,
        activeQuickEffects = quickEffects,
        exportFormat = json.requiredEnum<ExportFormat>("exportFormat").name,
        exportResolution = json.requiredEnum<ExportResolution>("exportResolution").name,
        cropState = cropState,
        selectionLayers = layers,
        activeSelectionLayerId = activeLayerId,
        selectionPaintSettings = paintSettings,
        showSelectionOverlay = json.requiredBoolean("showSelectionOverlay")
    )
}.getOrNull()

private fun JSONObject.requiredObject(key: String): JSONObject = get(key) as? JSONObject ?: error("missing object: $key")
private fun JSONObject.requiredArray(key: String): JSONArray = get(key) as? JSONArray ?: error("missing array: $key")
private fun JSONObject.requiredBoolean(key: String): Boolean = get(key) as? Boolean ?: error("missing boolean: $key")
private fun JSONObject.requiredString(key: String, allowBlank: Boolean = false): String =
    (get(key) as? String)?.takeIf { allowBlank || it.isNotBlank() } ?: error("missing string: $key")

private fun JSONObject.optionalString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return requiredString(key)
}

private fun JSONObject.requiredNumber(key: String): Number = get(key) as? Number ?: error("missing number: $key")
private fun JSONObject.requiredInt(key: String): Int {
    val value = requiredNumber(key).toDouble()
    check(value.isFinite() && value % 1.0 == 0.0 && value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble())
    return value.toInt()
}
private fun JSONObject.requiredPositiveInt(key: String): Int = requiredInt(key).also { check(it > 0) }
private fun JSONObject.requiredNonNegativeInt(key: String): Int = requiredInt(key).also { check(it >= 0) }
private fun JSONObject.requiredLong(key: String): Long {
    val number = requiredNumber(key)
    val value = number.toDouble()
    check(value.isFinite() && value % 1.0 == 0.0)
    return number.toLong()
}
private fun JSONObject.requiredPositiveLong(key: String): Long = requiredLong(key).also { check(it > 0L) }
private fun JSONObject.requiredNonNegativeLong(key: String): Long = requiredLong(key).also { check(it >= 0L) }
private fun JSONObject.requiredFloat(key: String, range: ClosedFloatingPointRange<Float>): Float =
    requiredNumber(key).toFloat().also { check(it.isFinite() && it in range) }

private inline fun <reified T : Enum<T>> JSONObject.requiredEnum(key: String): T =
    enumValueOf<T>(requiredString(key))

private fun JSONObject.requiredEditParams(key: String): EditParams {
    val value = requiredObject(key)
    return EditParams(
        exposure = value.requiredFloat("exposure", -1f..1f),
        contrast = value.requiredFloat("contrast", -1f..1f),
        shadows = value.requiredFloat("shadows", -1f..1f),
        highlights = value.requiredFloat("highlights", -1f..1f),
        whites = value.requiredFloat("whites", -1f..1f),
        blacks = value.requiredFloat("blacks", -1f..1f),
        temperature = value.requiredFloat("temperature", -1f..1f),
        tint = value.requiredFloat("tint", -1f..1f),
        saturation = value.requiredFloat("saturation", -1f..1f),
        vibrance = value.requiredFloat("vibrance", -1f..1f),
        clarity = value.requiredFloat("clarity", -1f..1f),
        dehaze = value.requiredFloat("dehaze", -1f..1f),
        sharpness = value.requiredFloat("sharpness", 0f..1f),
        noiseReduction = value.requiredFloat("noiseReduction", 0f..1f),
        luminanceNoiseReduction = value.requiredFloat("luminanceNoiseReduction", 0f..1f),
        colorNoiseReduction = value.requiredFloat("colorNoiseReduction", 0f..1f),
        noiseDetailProtection = value.requiredFloat("noiseDetailProtection", 0f..1f)
    )
}
