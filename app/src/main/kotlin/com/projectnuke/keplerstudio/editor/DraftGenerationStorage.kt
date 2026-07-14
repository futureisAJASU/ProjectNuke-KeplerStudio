package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.json.JSONObject

internal data class DraftGenerationDirectory(val root: File) {
    val manifestFile get() = File(root, DRAFT_MANIFEST_FILE_NAME)
    val sourceFile get() = File(root, "source.img")
    val thumbnailFile get() = File(root, "thumbnail.jpg")
    fun maskFile(name: String): File = File(root, name)
}

internal fun draftGenerationsRoot(context: Context): File =
    File(context.filesDir, "drafts/generations").apply { mkdirs() }

internal fun draftCurrentGenerationDirectory(context: Context): File =
    draftGenerationsRoot(context).also { it.mkdirs() }

internal fun listDraftGenerationDirectories(context: Context): List<File> {
    val root = draftGenerationsRoot(context)
    return root.listFiles { f -> f.isDirectory && f.name.startsWith(DRAFT_GENERATION_DIR_PREFIX) }
        .orEmpty().toList()
}

internal fun newDraftGenerationDirectory(context: Context): DraftGenerationDirectory {
    val root = draftGenerationsRoot(context)
    val genDir = File(root, "$DRAFT_GENERATION_DIR_PREFIX${UUID.randomUUID()}")
    genDir.mkdirs()
    return DraftGenerationDirectory(genDir)
}

internal fun writeDraftGeneration(
    context: Context,
    genDir: DraftGenerationDirectory,
    manifest: DraftGenerationManifest,
    baseBitmapDirty: Boolean,
    reusableSourceFile: File?,
    dirtyBitmapCopy: Bitmap?,
    maskEntries: List<Pair<SelectionLayer, DraftSelectionLayerEntry>>
): Boolean {
    val sourceFile = genDir.sourceFile
    try {
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
        val thumbnail = dirtyBitmapCopy ?: runCatching { decodeSampledBitmapForDraftThumbnail(sourceFile.absolutePath) }.getOrNull()
        if (thumbnail != null) {
            val tempThumb = File(genDir.root, "thumbnail.${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(tempThumb).use { output ->
                    if (thumbnail.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                        output.fd.sync()
                    }
                }
                if (tempThumb.length() > 0) tempThumb.renameTo(genDir.thumbnailFile)
                tempThumb.takeIf { it.exists() }?.delete()
            } finally {
                if (thumbnail !== dirtyBitmapCopy) thumbnail.recycle()
            }
        }
        val manifestTemp = File(genDir.root, "manifest.${UUID.randomUUID()}.tmp")
        FileOutputStream(manifestTemp).use { output ->
            output.write(manifest.toJson().toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!manifestTemp.renameTo(genDir.manifestFile)) {
            manifestTemp.delete()
            return false
        }
        return true
    } catch (t: Throwable) {
        cleanupIncompleteDraftGeneration(genDir.root)
        throw t
    }
}

internal fun cleanupIncompleteDraftGeneration(genDir: File) {
    genDir.listFiles()?.forEach { it.delete() }
    genDir.delete()
}

internal fun publishDraftGeneration(context: Context, generationId: String): Boolean {
    val prefs = context.getSharedPreferences(PREF_NAME_DRAFT, Context.MODE_PRIVATE)
    return prefs.edit().putString(KEY_DRAFT_GENERATION_ID, generationId).commit()
}

internal fun currentDraftGenerationId(context: Context): String? =
    context.getSharedPreferences(PREF_NAME_DRAFT, Context.MODE_PRIVATE)
        .getString(KEY_DRAFT_GENERATION_ID, null)

internal fun removeDraftGeneration(generationId: String) {
    if (generationId.isNotBlank()) {
        File(generationId).takeIf { it.isDirectory }?.let { dir ->
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }
}

internal fun findCurrentDraftGenerationDirectory(context: Context): DraftGenerationDirectory? {
    val id = currentDraftGenerationId(context) ?: return null
    val dir = File(id)
    if (!dir.isDirectory) return null
    return DraftGenerationDirectory(dir)
}

internal fun loadDraftGenerationManifest(genDir: DraftGenerationDirectory): DraftGenerationManifest? {
    val file = genDir.manifestFile
    if (!file.isFile) return null
    return runCatching {
        val content = file.readText(Charsets.UTF_8)
        parseDraftGenerationManifest(JSONObject(content))
    }.getOrNull()
}

internal fun deleteAllDraftGenerationsExcept(context: Context, keep: File?) {
    val root = draftGenerationsRoot(context)
    root.listFiles()?.forEach { dir ->
        if (dir.isDirectory && dir.name.startsWith(DRAFT_GENERATION_DIR_PREFIX) && dir != keep) {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
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

private fun decodeSampledBitmapForDraftThumbnail(path: String): Bitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / sample > 512) sample *= 2
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    android.graphics.BitmapFactory.decodeFile(path, options)
}.getOrNull()
