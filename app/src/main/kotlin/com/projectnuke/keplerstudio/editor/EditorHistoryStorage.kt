package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.util.IdentityHashMap
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal enum class HistoryPayloadState { Hot, Cold, Loading, Spilling, Adopting, Discarded }

internal data class EditorHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val documentGeneration: String,
    var hotSnapshot: EditorHistorySnapshot?,
    var coldDirectory: File? = null,
    var payloadState: HistoryPayloadState = HistoryPayloadState.Hot
) {
    fun bitmapBytes(): Long = hotSnapshot?.bitmapBytes() ?: 0L
}

internal class EditorHistoryStorage(context: Context) {
    private val root = File(context.filesDir, "editor_history_v2")

    init {
        root.mkdirs()
    }

    @Synchronized
    fun spill(entry: EditorHistoryEntry): Boolean {
        val snapshot = entry.hotSnapshot ?: return entry.coldDirectory?.isCompleteHistoryDirectory() == true
        if (entry.payloadState != HistoryPayloadState.Hot) return false
        val diskReserve = 8L * 1024L * 1024L
        if (root.usableSpace < BitmapMemoryBudget.saturatingAdd(snapshot.bitmapBytes(), diskReserve)) return false
        entry.payloadState = HistoryPayloadState.Spilling
        val staging = File(root, ".staging-${entry.id}-${UUID.randomUUID()}")
        val published = File(root, entry.id)
        return try {
            staging.mkdirs()
            val manifest = snapshotManifest(entry, snapshot, staging)
            writeSynced(File(staging, MANIFEST), manifest.toString().toByteArray(Charsets.UTF_8))
            writeSynced(File(staging, COMPLETE), "ok".toByteArray(Charsets.US_ASCII))
            if (entry.payloadState == HistoryPayloadState.Discarded) {
                staging.deleteRecursively()
                snapshot.recycleBitmaps()
                entry.hotSnapshot = null
                return false
            }
            if (published.exists()) published.deleteRecursively()
            check(staging.renameTo(published)) { "history publish failed" }
            entry.coldDirectory = published
            entry.hotSnapshot = null
            entry.payloadState = HistoryPayloadState.Cold
            snapshot.recycleBitmaps()
            true
        } catch (_: Throwable) {
            staging.deleteRecursively()
            if (entry.payloadState == HistoryPayloadState.Discarded) {
                snapshot.recycleBitmaps()
                entry.hotSnapshot = null
                entry.coldDirectory?.takeIf(::isOwnedDirectory)?.deleteRecursively()
                entry.coldDirectory = null
            } else {
                entry.payloadState = HistoryPayloadState.Hot
            }
            false
        }
    }

    @Synchronized
    fun load(entry: EditorHistoryEntry, expectedGeneration: String): EditorHistorySnapshot? {
        val directory = entry.coldDirectory ?: return entry.hotSnapshot
        if (entry.documentGeneration != expectedGeneration || !directory.isCompleteHistoryDirectory()) return null
        if (entry.payloadState != HistoryPayloadState.Cold) return null
        entry.payloadState = HistoryPayloadState.Loading
        val owned = ArrayList<Bitmap>()
        return try {
            val json = JSONObject(File(directory, MANIFEST).readText(Charsets.UTF_8))
            check(json.getString("entryId") == entry.id)
            check(json.getString("documentGeneration") == expectedGeneration)
            val bitmapSpecs = json.getJSONArray("bitmaps")
            var requiredBytes = 0L
            for (i in 0 until bitmapSpecs.length()) {
                val spec = bitmapSpecs.getJSONObject(i)
                check(File(directory, spec.getString("file")).isFile)
                requiredBytes = BitmapMemoryBudget.saturatingAdd(
                    requiredBytes,
                    BitmapMemoryBudget.bytes(spec.getInt("width"), spec.getInt("height"), Bitmap.Config.ARGB_8888)
                )
            }
            if (!BitmapMemoryBudget.canAllocate(requiredBytes)) throw BitmapAllocationRejectedException(requiredBytes)
            val bitmaps = HashMap<String, Bitmap>()
            for (i in 0 until bitmapSpecs.length()) {
                val spec = bitmapSpecs.getJSONObject(i)
                val bitmap = decodeMutableBitmapOrThrow(File(directory, spec.getString("file")).absolutePath)
                owned += bitmap
                check(bitmap.width == spec.getInt("width") && bitmap.height == spec.getInt("height"))
                check(spec.getString("config") == Bitmap.Config.ARGB_8888.name && bitmap.config == Bitmap.Config.ARGB_8888)
                bitmaps[spec.getString("key")] = bitmap
            }
            val snapshot = parseSnapshot(json.getJSONObject("metadata"), bitmaps)
            check(entry.payloadState != HistoryPayloadState.Discarded) { "history entry discarded" }
            owned.clear()
            if (entry.payloadState != HistoryPayloadState.Discarded) entry.payloadState = HistoryPayloadState.Cold
            snapshot
        } catch (_: Throwable) {
            owned.forEach { if (!it.isRecycled) it.recycle() }
            if (entry.payloadState != HistoryPayloadState.Discarded) entry.payloadState = HistoryPayloadState.Cold
            null
        }
    }

    @Synchronized
    fun delete(entry: EditorHistoryEntry) {
        if (entry.payloadState == HistoryPayloadState.Spilling) {
            entry.payloadState = HistoryPayloadState.Discarded
            return
        }
        entry.payloadState = HistoryPayloadState.Discarded
        entry.hotSnapshot?.recycleBitmaps()
        entry.hotSnapshot = null
        entry.coldDirectory?.takeIf(::isOwnedDirectory)?.deleteRecursively()
        entry.coldDirectory = null
    }

    @Synchronized
    fun deleteColdPayload(entry: EditorHistoryEntry) {
        entry.coldDirectory?.takeIf(::isOwnedDirectory)?.deleteRecursively()
        entry.coldDirectory = null
    }

    @Synchronized
    fun trimDisk(entries: Collection<EditorHistoryEntry>, protected: Set<String>, budgetBytes: Long): Set<String> {
        val removed = LinkedHashSet<String>()
        val effectiveBudget = budgetBytes
        var total = entries.fold(0L) { bytes, entry ->
            BitmapMemoryBudget.saturatingAdd(bytes, entry.coldDirectory?.directoryBytes() ?: 0L)
        }
        if (total <= effectiveBudget) return emptySet()
        val evictionOrder = entries.filter { it.id !in protected } + entries.filter { it.id in protected }
        evictionOrder.forEach { entry ->
            if (total <= effectiveBudget) return@forEach
            val directory = entry.coldDirectory ?: return@forEach
            if (entry.payloadState != HistoryPayloadState.Cold) return@forEach
            val bytes = directory.directoryBytes()
            if (isOwnedDirectory(directory)) directory.deleteRecursively()
            entry.coldDirectory = null
            removed += entry.id
            total = (total - bytes).coerceAtLeast(0L)
        }
        return removed
    }

    @Synchronized
    fun cleanupIncomplete() {
        root.listFiles()?.forEach { directory ->
            if (isOwnedDirectory(directory)) directory.deleteRecursively()
        }
    }

    private fun snapshotManifest(entry: EditorHistoryEntry, snapshot: EditorHistorySnapshot, staging: File): JSONObject {
        val bitmapKeys = IdentityHashMap<Bitmap, String>()
        val bitmapSpecs = JSONArray()
        fun persist(bitmap: Bitmap?): String? {
            bitmap ?: return null
            bitmapKeys[bitmap]?.let { return it }
            val key = "bitmap-${bitmapKeys.size}"
            val fileName = "$key.png"
            FileOutputStream(File(staging, fileName)).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.fd.sync()
            }
            bitmapKeys[bitmap] = key
            bitmapSpecs.put(JSONObject().apply {
                put("key", key)
                put("file", fileName)
                put("width", bitmap.width)
                put("height", bitmap.height)
                put("config", bitmap.config?.name ?: Bitmap.Config.ARGB_8888.name)
            })
            return key
        }
        val metadata = JSONObject().apply {
            put("params", snapshot.params.toJsonObject())
            put("noiseEngine", snapshot.noiseEngine.name)
            put("detailEngine", snapshot.detailEngine.name)
            put("toneEngine", snapshot.toneEngine.name)
            put("hazeEngine", snapshot.hazeEngine.name)
            put("baseBitmapDirty", snapshot.baseBitmapDirty)
            put("baseContentToken", snapshot.baseContentToken)
            put("previewKey", persist(snapshot.previewBitmap))
            put("originalKey", persist(snapshot.originalPreviewBitmap))
            put("presetLook", snapshot.presetLook?.let(::presetToJson))
            put("cropState", snapshot.cropState.toJsonObject())
            put("activeSelectionLayerId", snapshot.activeSelectionLayerId)
            put("paintMode", snapshot.selectionPaintSettings.mode.name)
            put("paintSize", snapshot.selectionPaintSettings.sizePx)
            put("paintFeather", snapshot.selectionPaintSettings.feather)
            put("paintStrength", snapshot.selectionPaintSettings.strength)
            put("showSelectionOverlay", snapshot.showSelectionOverlay)
            put("flareGuardRuntimeStatus", snapshot.flareGuardRuntimeStatus)
            put("quickEffects", JSONArray(snapshot.activeQuickEffects.map { "${it.kind.name}:${it.strength.name}" }))
            put("layers", JSONArray().apply {
                snapshot.selectionLayers.forEach { layer ->
                    put(JSONObject().apply {
                        put("id", layer.id)
                        put("name", layer.name)
                        put("kind", layer.kind.name)
                        put("bitmapKey", persist(layer.bitmap))
                        put("enabled", layer.enabled)
                        put("inverted", layer.inverted)
                        put("opacity", layer.opacity)
                        put("localParams", layer.localParams.toJsonObject())
                    })
                }
            })
        }
        return JSONObject().apply {
            put("version", 2)
            put("entryId", entry.id)
            put("documentGeneration", entry.documentGeneration)
            put("bitmaps", bitmapSpecs)
            put("metadata", metadata)
        }
    }

    private fun parseSnapshot(json: JSONObject, bitmaps: Map<String, Bitmap>): EditorHistorySnapshot {
        fun bitmap(key: String): Bitmap? = json.optString(key).takeIf(String::isNotBlank)?.let(bitmaps::get)
        val layers = json.getJSONArray("layers").let { array ->
            List(array.length()) { index ->
                val layer = array.getJSONObject(index)
                SelectionLayer(
                    id = layer.getString("id"),
                    name = layer.getString("name"),
                    kind = enumValueOrDefault(layer.getString("kind"), SelectionLayerKind.Brush),
                    bitmap = checkNotNull(bitmaps[layer.getString("bitmapKey")]),
                    enabled = layer.optBoolean("enabled", true),
                    inverted = layer.optBoolean("inverted", false),
                    opacity = layer.optDouble("opacity", 1.0).toFloat(),
                    localParams = checkNotNull(parseEditParamsFromJson(layer.getJSONObject("localParams")))
                )
            }
        }
        val quickEffects = json.optJSONArray("quickEffects")?.let { array ->
            List(array.length()) { index ->
                val parts = array.getString(index).split(':', limit = 2)
                ActiveQuickEffect(enumValueOrDefault(parts[0], QuickEffectKind.SpotCleanup), enumValueOrDefault(parts.getOrElse(1) { "" }, QuickEffectStrength.Medium))
            }
        }.orEmpty()
        return EditorHistorySnapshot(
            params = checkNotNull(parseEditParamsFromJson(json.getJSONObject("params"))),
            noiseEngine = enumValueOrDefault(json.getString("noiseEngine"), NoiseEngine.FastEdgeAware),
            detailEngine = enumValueOrDefault(json.getString("detailEngine"), DetailEngine.MaskedUnsharp),
            toneEngine = enumValueOrDefault(json.getString("toneEngine"), ToneEngine.HistogramAuto),
            hazeEngine = enumValueOrDefault(json.getString("hazeEngine"), DehazeEngine.FastContrast),
            baseBitmapDirty = json.optBoolean("baseBitmapDirty"),
            baseContentToken = json.getString("baseContentToken"),
            previewBitmap = bitmap("previewKey"),
            originalPreviewBitmap = bitmap("originalKey"),
            presetLook = json.optJSONObject("presetLook")?.let(::presetFromJson),
            cropState = checkNotNull(parseCropStateFromJson(json.getJSONObject("cropState"))),
            selectionLayers = layers,
            activeSelectionLayerId = json.optString("activeSelectionLayerId").takeIf(String::isNotBlank),
            selectionPaintSettings = SelectionPaintSettings(
                mode = enumValueOrDefault(json.optString("paintMode"), SelectionPaintMode.Add),
                sizePx = json.optDouble("paintSize", 120.0).toFloat(),
                feather = json.optDouble("paintFeather", 0.55).toFloat(),
                strength = json.optDouble("paintStrength", 0.70).toFloat()
            ),
            showSelectionOverlay = json.optBoolean("showSelectionOverlay", true),
            activeQuickEffects = quickEffects,
            flareGuardRuntimeStatus = json.optString("flareGuardRuntimeStatus").takeIf(String::isNotBlank)
        )
    }

    private fun presetToJson(look: PresetColorLook): JSONObject = JSONObject().apply {
        put("size", look.size)
        put("strength", look.strength)
        put("values", JSONArray(look.values.toList()))
    }

    private fun presetFromJson(json: JSONObject): PresetColorLook {
        val values = json.getJSONArray("values")
        return PresetColorLook(json.getInt("size"), json.getDouble("strength").toFloat(), FloatArray(values.length()) { values.getDouble(it).toFloat() })
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun isOwnedDirectory(file: File): Boolean = runCatching {
        file.canonicalPath.startsWith(root.canonicalPath + File.separator)
    }.getOrDefault(false)

    private fun File.isCompleteHistoryDirectory(): Boolean = isDirectory && File(this, COMPLETE).isFile && File(this, MANIFEST).isFile
    private fun File.directoryBytes(): Long = walkTopDown().filter(File::isFile).fold(0L) { total, file -> BitmapMemoryBudget.saturatingAdd(total, file.length()) }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(default)

    private companion object {
        const val MANIFEST = "manifest.json"
        const val COMPLETE = "complete"
    }
}
