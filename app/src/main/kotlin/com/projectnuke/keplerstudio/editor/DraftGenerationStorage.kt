package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.json.JSONObject

internal const val DRAFT_COMPLETION_FILE_NAME = "complete"

internal data class DraftGenerationDirectory(val root: File) {
    val manifestFile get() = File(root, DRAFT_MANIFEST_FILE_NAME)
    val sourceFile get() = File(root, "source.img")
    val thumbnailFile get() = File(root, "thumbnail.jpg")
    val completionFile get() = File(root, DRAFT_COMPLETION_FILE_NAME)
    fun maskFile(name: String): File = File(root, name)
}

internal data class ValidatedDraftGeneration(
    val directory: DraftGenerationDirectory,
    val manifest: DraftGenerationManifest,
    val sourceFile: File,
    val thumbnailFile: File,
    val maskFiles: List<File>
)

internal fun draftGenerationsRoot(context: Context): File =
    File(context.filesDir, "drafts/generations").apply { mkdirs() }

internal fun newDraftGenerationDirectory(context: Context): DraftGenerationDirectory {
    val root = draftGenerationsRoot(context)
    val genDir = File(root, "$DRAFT_GENERATION_STAGING_PREFIX${UUID.randomUUID()}")
    genDir.mkdirs()
    return DraftGenerationDirectory(genDir)
}

internal fun finalizeDraftGeneration(context: Context, staging: DraftGenerationDirectory, generationId: String): DraftGenerationDirectory? {
    val root = draftGenerationsRoot(context).canonicalFile
    val source = runCatching { staging.root.canonicalFile }.getOrNull() ?: return null
    val target = runCatching { File(root, "$DRAFT_GENERATION_DIR_PREFIX$generationId").canonicalFile }.getOrNull() ?: return null
    if (source.parentFile != root || target.parentFile != root || !staging.completionFile.isFile || target.exists()) return null
    return if (source.renameTo(target)) DraftGenerationDirectory(target) else null
}

internal fun writeDraftGeneration(
    context: Context,
    genDir: DraftGenerationDirectory,
    manifest: DraftGenerationManifest,
    baseBitmapDirty: Boolean,
    reusableSourceFile: File?,
    dirtyBitmapCopy: Bitmap?,
    editedPreviewCopy: Bitmap,
    maskEntries: List<Pair<SelectionLayer, DraftSelectionLayerEntry>>,
    isCurrent: () -> Boolean
): Boolean {
    val sourceFile = genDir.sourceFile
    try {
        if (!isCurrent()) return false
        if (baseBitmapDirty && dirtyBitmapCopy != null) {
            val tempSrc = File(genDir.root, "source.${UUID.randomUUID()}.tmp")
            FileOutputStream(tempSrc).use { output ->
                check(dirtyBitmapCopy.compress(Bitmap.CompressFormat.PNG, 100, output)) { "draft source encode failed" }
                output.fd.sync()
            }
            if (!tempSrc.renameTo(sourceFile)) {
                tempSrc.delete()
                return false
            }
        } else if (reusableSourceFile != null) {
            val tempSrc = File(genDir.root, "source.${UUID.randomUUID()}.tmp")
            copyDraftFile(reusableSourceFile, tempSrc)
            if (!tempSrc.renameTo(sourceFile)) {
                tempSrc.delete()
                return false
            }
        }
        for ((layer, entry) in maskEntries) {
            val maskFile = genDir.maskFile(entry.maskFileName)
            val tempMask = File(genDir.root, "${entry.maskFileName}.${UUID.randomUUID()}.tmp")
            FileOutputStream(tempMask).use { output ->
                check(layer.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "mask encode failed" }
                output.fd.sync()
            }
            if (!tempMask.renameTo(maskFile)) {
                tempMask.delete()
                return false
            }
        }
        val (thumbnailWidth, thumbnailHeight) = draftThumbnailDimensions(editedPreviewCopy.width, editedPreviewCopy.height)
        val thumbnail = if (thumbnailWidth == editedPreviewCopy.width && thumbnailHeight == editedPreviewCopy.height) {
            editedPreviewCopy
        } else {
            Bitmap.createScaledBitmap(editedPreviewCopy, thumbnailWidth, thumbnailHeight, true)
        }
        val tempThumb = File(genDir.root, "thumbnail.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(tempThumb).use { output ->
                check(thumbnail.compress(Bitmap.CompressFormat.JPEG, 90, output)) { "draft thumbnail encode failed" }
                output.fd.sync()
            }
        } finally {
            if (thumbnail !== editedPreviewCopy && !thumbnail.isRecycled) thumbnail.recycle()
        }
        if (tempThumb.length() <= 0 || !tempThumb.renameTo(genDir.thumbnailFile)) {
            tempThumb.delete()
            return false
        }
        if (!isCurrent()) return false
        val manifestTemp = File(genDir.root, "manifest.${UUID.randomUUID()}.tmp")
        FileOutputStream(manifestTemp).use { output ->
            output.write(manifest.toJson().toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!manifestTemp.renameTo(genDir.manifestFile)) {
            manifestTemp.delete()
            return false
        }
        if (!isCurrent()) return false
        FileOutputStream(genDir.completionFile).use { output -> output.fd.sync() }
        return true
    } catch (t: Throwable) {
        deleteDraftDirectory(context, genDir)
        throw t
    }
}

internal fun draftThumbnailDimensions(width: Int, height: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 0 to 0
    val longest = maxOf(width, height)
    if (longest <= 512) return width to height
    val scale = 512f / longest.toFloat()
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}

internal fun deleteDraftDirectory(context: Context, directory: DraftGenerationDirectory) {
    val root = draftGenerationsRoot(context).canonicalFile
    val target = runCatching { directory.root.canonicalFile }.getOrNull() ?: return
    if (target.parentFile != root) return
    target.listFiles()?.forEach { file ->
        val deleted = file.delete()
        if (!deleted) Log.w(FLARE_GUARD_AI_TAG, "Failed to delete draft generation file: ${file.absolutePath}")
    }
    val deleted = target.delete()
    if (!deleted) Log.w(FLARE_GUARD_AI_TAG, "Failed to delete draft generation directory: ${target.absolutePath}")
}

internal fun publishDraftGeneration(context: Context, generationId: String): Boolean {
    val root = draftGenerationsRoot(context).canonicalFile
    val candidate = File(root, generationId).canonicalFile
    if (candidate.parentFile != root || !candidate.isDirectory || !DraftGenerationDirectory(candidate).completionFile.isFile) return false
    val prefs = context.getSharedPreferences(PREF_NAME_DRAFT, Context.MODE_PRIVATE)
    return prefs.edit().putString(KEY_DRAFT_GENERATION_ID, generationId).commit()
}

internal fun currentDraftGenerationId(context: Context): String? =
    context.getSharedPreferences(PREF_NAME_DRAFT, Context.MODE_PRIVATE)
        .getString(KEY_DRAFT_GENERATION_ID, null)

internal fun clearCurrentDraftGenerationPointer(context: Context): Boolean =
    context.getSharedPreferences(PREF_NAME_DRAFT, Context.MODE_PRIVATE)
        .edit().remove(KEY_DRAFT_GENERATION_ID).commit()

internal fun findCurrentDraftGenerationDirectory(context: Context): DraftGenerationDirectory? {
    val id = currentDraftGenerationId(context) ?: return null
    val root = draftGenerationsRoot(context).canonicalFile
    val dir = runCatching { File(root, id).canonicalFile }.getOrNull() ?: return null
    if (dir.parentFile != root || !dir.isDirectory || !DraftGenerationDirectory(dir).completionFile.isFile) return null
    return DraftGenerationDirectory(dir)
}

internal fun findDraftGenerationDirectory(context: Context, generationId: String): DraftGenerationDirectory? {
    if (!generationId.startsWith(DRAFT_GENERATION_DIR_PREFIX) || !isSafeDraftBasename(generationId)) return null
    val root = draftGenerationsRoot(context).canonicalFile
    val dir = runCatching { File(root, generationId).canonicalFile }.getOrNull() ?: return null
    if (dir.parentFile != root || !dir.isDirectory) return null
    val generation = DraftGenerationDirectory(dir)
    return generation.takeIf { it.completionFile.isFile }
}

internal fun deleteDraftGenerationById(context: Context, generationId: String) {
    if (!generationId.startsWith(DRAFT_GENERATION_DIR_PREFIX) || !isSafeDraftBasename(generationId)) return
    val root = draftGenerationsRoot(context).canonicalFile
    val directory = runCatching { File(root, generationId).canonicalFile }.getOrNull() ?: return
    if (directory.parentFile == root && directory.isDirectory && currentDraftGenerationId(context) != generationId) {
        deleteDraftDirectory(context, DraftGenerationDirectory(directory))
    }
}

internal fun validateCurrentDraftGeneration(context: Context): ValidatedDraftGeneration? {
    val pointer = currentDraftGenerationId(context) ?: return null
    if (!pointer.startsWith(DRAFT_GENERATION_DIR_PREFIX) || !isSafeDraftBasename(pointer)) return null
    val directory = findCurrentDraftGenerationDirectory(context) ?: return null
    return validateDraftGeneration(directory, pointer.removePrefix(DRAFT_GENERATION_DIR_PREFIX))
}

internal fun validateDraftGeneration(directory: DraftGenerationDirectory, expectedId: String): ValidatedDraftGeneration? = runCatching {
    val manifest = loadDraftGenerationManifest(directory) ?: return null
    if (manifest.generationId != expectedId || manifest.formatVersion != DRAFT_FORMAT_VERSION) return null
    if (manifest.savedAtMillis <= 0L || manifest.draftOperationEpoch < 0L || manifest.editorRevision < 0) return null
    if (manifest.sourceWidth <= 0 || manifest.sourceHeight <= 0 || manifest.thumbnailWidth <= 0 || manifest.thumbnailHeight <= 0) return null
    if (manifest.thumbnailWidth > 512 || manifest.thumbnailHeight > 512) return null
    if (manifest.baseContentToken.isBlank() || manifest.sourceIdentity != manifest.baseContentToken) return null
    if (!manifest.hasValidEditorValues()) return null
    val ids = manifest.selectionLayers.map { it.id }
    if (ids.any { it.isBlank() } || ids.toSet().size != ids.size) return null
    val payloadNames = listOf(manifest.sourceFileName, manifest.thumbnailFileName) +
        manifest.selectionLayers.map { it.maskFileName }
    if (payloadNames.toSet().size != payloadNames.size) return null
    val source = containedPayload(directory, manifest.sourceFileName) ?: return null
    val thumbnail = containedPayload(directory, manifest.thumbnailFileName) ?: return null
    if (!source.isFile || source.length() <= 0L || !thumbnail.isFile || thumbnail.length() <= 0L) return null
    val sourceBounds = decodeBounds(source) ?: return null
    if (sourceBounds.first != manifest.sourceWidth || sourceBounds.second != manifest.sourceHeight) return null
    val thumbnailBounds = decodeBounds(thumbnail) ?: return null
    if (thumbnailBounds.first != manifest.thumbnailWidth || thumbnailBounds.second != manifest.thumbnailHeight) return null
    var maskGeometry: Pair<Int, Int>? = null
    val masks = manifest.selectionLayers.map { layer ->
        if (layer.sourceIdentity != manifest.baseContentToken || layer.maskWidth <= 0 || layer.maskHeight <= 0) return null
        val file = containedPayload(directory, layer.maskFileName) ?: return null
        val bounds = decodeBounds(file) ?: return null
        if (bounds.first != layer.maskWidth || bounds.second != layer.maskHeight) return null
        if (maskGeometry == null) maskGeometry = bounds else if (maskGeometry != bounds) return null
        file
    }
    ValidatedDraftGeneration(directory, manifest, source, thumbnail, masks)
}.getOrNull()

internal fun loadDraftGenerationManifest(genDir: DraftGenerationDirectory): DraftGenerationManifest? {
    val file = genDir.manifestFile
    if (!file.isFile) return null
    return runCatching {
        val content = file.readText(Charsets.UTF_8)
        parseDraftGenerationManifest(JSONObject(content))
    }.getOrNull()
}

internal fun deleteAllDraftGenerationsExcept(context: Context, keep: File?) {
    val root = draftGenerationsRoot(context).canonicalFile
    val kept = keep?.let { runCatching { it.canonicalFile }.getOrNull() }
    root.listFiles()?.forEach { dir ->
        val contained = runCatching { dir.canonicalFile }.getOrNull()
        if (contained?.parentFile == root && contained.isDirectory && contained != kept &&
            (contained.name.startsWith(DRAFT_GENERATION_DIR_PREFIX) || contained.name.startsWith(DRAFT_GENERATION_STAGING_PREFIX))) {
            deleteDraftDirectory(context, DraftGenerationDirectory(contained))
        }
    }
}

private fun copyDraftFile(source: File, destination: File) {
    source.inputStream().use { input ->
        FileOutputStream(destination).use { output ->
            input.copyTo(output)
            output.fd.sync()
        }
    }
}

private fun containedPayload(directory: DraftGenerationDirectory, name: String): File? {
    if (!isSafeDraftBasename(name)) return null
    val root = runCatching { directory.root.canonicalFile }.getOrNull() ?: return null
    val file = runCatching { File(root, name).canonicalFile }.getOrNull() ?: return null
    return file.takeIf { it.parentFile == root }
}

private fun isSafeDraftBasename(name: String): Boolean =
    name.isNotBlank() && name != "." && name != ".." && name == File(name).name &&
        !name.contains('/') && !name.contains('\\')

private fun decodeBounds(file: File): Pair<Int, Int>? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
}

private fun DraftGenerationManifest.hasValidEditorValues(): Boolean = runCatching {
    NoiseEngine.valueOf(noiseEngine)
    DetailEngine.valueOf(detailEngine)
    ToneEngine.valueOf(toneEngine)
    DehazeEngine.valueOf(hazeEngine)
    ExportFormat.valueOf(exportFormat)
    ExportResolution.valueOf(exportResolution)
    selectionLayers.forEach {
        SelectionLayerKind.valueOf(it.kind)
        if (!it.opacity.isFinite() || it.opacity !in 0f..1f) return false
    }
    val active = activeSelectionLayerId
    if (active != null && selectionLayers.none { it.id == active }) return false
    if (!params.isValidDraftParams() || selectionLayers.any { !it.localParams.isValidDraftParams() }) return false
    if (!selectionPaintSettings.sizePx.isFinite() || selectionPaintSettings.sizePx !in 1f..4096f ||
        !selectionPaintSettings.feather.isFinite() || selectionPaintSettings.feather !in 0f..1f ||
        !selectionPaintSettings.strength.isFinite() || selectionPaintSettings.strength !in 0f..1f) return false
    val crop = cropState
    val cropValues = listOf(crop.cropLeft, crop.cropTop, crop.cropRight, crop.cropBottom, crop.straightenDegrees)
    if (cropValues.any { !it.isFinite() } || crop.cropLeft !in 0f..1f || crop.cropTop !in 0f..1f ||
        crop.cropRight !in 0f..1f || crop.cropBottom !in 0f..1f || crop.cropRight <= crop.cropLeft ||
        crop.cropBottom <= crop.cropTop || crop.straightenDegrees !in -45f..45f || crop.rotationDegrees !in 0..359) return false
    true
}.getOrDefault(false)

private fun EditParams.isValidDraftParams(): Boolean {
    val signed = listOf(exposure, contrast, shadows, highlights, whites, blacks, temperature, tint, saturation, vibrance, clarity, dehaze)
    val unsigned = listOf(sharpness, noiseReduction, luminanceNoiseReduction, colorNoiseReduction, noiseDetailProtection)
    return signed.all { it.isFinite() && it in -1f..1f } && unsigned.all { it.isFinite() && it in 0f..1f }
}
