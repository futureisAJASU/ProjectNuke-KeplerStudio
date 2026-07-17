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
    val formatVersion = json.optInt("formatVersion", -1)
    if (formatVersion != DRAFT_FORMAT_VERSION) return null
    val layerArray = json.optJSONArray("selectionLayers") ?: JSONArray()
    val layers = ArrayList<DraftSelectionLayerEntry>(layerArray.length())
    for (i in 0 until layerArray.length()) {
        val layerObj = layerArray.getJSONObject(i)
        layers += DraftSelectionLayerEntry(
            id = layerObj.optString("id"),
            name = layerObj.optString("name"),
            kind = layerObj.optString("kind"),
            enabled = layerObj.optBoolean("enabled", true),
            inverted = layerObj.optBoolean("inverted", false),
            opacity = layerObj.optDouble("opacity", 1.0).toFloat(),
            localParams = parseEditParamsFromJson(layerObj.optJSONObject("localParams")) ?: EditParams(),
            maskFileName = layerObj.optString("maskFileName"),
            maskWidth = layerObj.optInt("maskWidth", 0),
            maskHeight = layerObj.optInt("maskHeight", 0),
            sourceIdentity = layerObj.optString("sourceIdentity")
        )
    }
    val paintObj = json.optJSONObject("selectionPaintSettings")
    val paintSettings = if (paintObj != null) {
        SelectionPaintSettings(
            mode = runCatching { SelectionPaintMode.valueOf(paintObj.optString("mode")) }.getOrNull() ?: return null,
            sizePx = paintObj.optDouble("sizePx", 120.0).toFloat(),
            feather = paintObj.optDouble("feather", 0.55).toFloat(),
            strength = paintObj.optDouble("strength", 0.70).toFloat()
        )
    } else SelectionPaintSettings()
    val quickArray = json.optJSONArray("activeQuickEffects") ?: JSONArray()
    val quickEffects = ArrayList<ActiveQuickEffect>(quickArray.length())
    for (i in 0 until quickArray.length()) {
        val qObj = quickArray.getJSONObject(i)
        quickEffects += ActiveQuickEffect(
            kind = runCatching { QuickEffectKind.valueOf(qObj.optString("kind")) }.getOrNull() ?: return null,
            strength = runCatching { QuickEffectStrength.valueOf(qObj.optString("strength")) }.getOrNull() ?: return null
        )
    }
    val cropObject = json.optJSONObject("cropState")
    if (cropObject != null && runCatching {
            CropAspectRatio.valueOf(cropObject.optString("aspectRatio", CropAspectRatio.Original.name))
        }.isFailure) return null
    val presetObject = json.optJSONObject("presetLook")
    val parsedPreset = presetObject?.let { presetColorLookFromJson(it) }
    if (presetObject != null && parsedPreset == null) return null
    DraftGenerationManifest(
        formatVersion = formatVersion,
        generationId = json.optString("generationId"),
        savedAtMillis = json.optLong("savedAtMillis", 0L),
        draftOperationEpoch = json.optLong("draftOperationEpoch", Long.MIN_VALUE),
        editorRevision = json.optInt("editorRevision", -1),
        originalSourceIdentity = json.optString("originalSourceIdentity").takeIf { it.isNotBlank() },
        sourceIdentity = json.optString("sourceIdentity").takeIf { it.isNotBlank() },
        baseContentToken = json.optString("baseContentToken"),
        baseBitmapDirty = json.optBoolean("baseBitmapDirty", false),
        sourceFileName = json.optString("sourceFileName"),
        sourceWidth = json.optInt("sourceWidth", 0),
        sourceHeight = json.optInt("sourceHeight", 0),
        thumbnailFileName = json.optString("thumbnailFileName"),
        thumbnailWidth = json.optInt("thumbnailWidth", 0),
        thumbnailHeight = json.optInt("thumbnailHeight", 0),
        params = parseEditParamsFromJson(json.optJSONObject("params")) ?: EditParams(),
        noiseEngine = json.optString("noiseEngine"),
        detailEngine = json.optString("detailEngine"),
        toneEngine = json.optString("toneEngine"),
        hazeEngine = json.optString("hazeEngine"),
        presetLook = parsedPreset,
        activeQuickEffects = quickEffects,
        exportFormat = json.optString("exportFormat"),
        exportResolution = json.optString("exportResolution"),
        cropState = cropObject?.let { parseCropStateFromJson(it) } ?: CropState(),
        selectionLayers = layers,
        activeSelectionLayerId = json.optString("activeSelectionLayerId").takeIf { it.isNotBlank() },
        selectionPaintSettings = paintSettings,
        showSelectionOverlay = json.optBoolean("showSelectionOverlay", true)
    )
}.getOrNull()
