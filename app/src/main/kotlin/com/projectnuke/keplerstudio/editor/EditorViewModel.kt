package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.heifwriter.HeifWriter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.projectnuke.keplerstudio.bridge.NativePhotoCore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val _uiState = MutableStateFlow(
        EditorUiState(nativeVersion = runCatching { NativePhotoCore.nativeVersion() }.getOrElse { "native load failed: ${it.message}" })
    )
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var nativeSession: Long = 0L
    private var renderJob: Job? = null

    init {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val retention = loadExportHistoryRetention(context)
            val saved = withContext(Dispatchers.IO) {
                pruneSavedExportsIfNeeded(context, loadSavedExportsFromPrefs(context), retention)
            }
            _uiState.update { it.copy(savedExports = saved, exportHistoryRetention = retention) }
            restoreDraftIfAvailable(context)
        }
    }

    fun openImage(uri: Uri) {
        renderJob?.cancel()
        _uiState.update { it.copy(isBusy = true, message = "이미지를 여는 중입니다") }

        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val sourceFile = withContext(Dispatchers.IO) { copyUriToCache(context, uri) }
                val preview = withContext(Dispatchers.IO) {
                    decodeSampledMutableBitmap(sourceFile.absolutePath, maxSide = 2048)
                }

                releaseNativeSession()
                nativeSession = NativePhotoCore.nativeCreateSession(sourceFile.absolutePath)

                _uiState.update {
                    val next = it.copy(
                        isBusy = false,
                        sourcePath = sourceFile.absolutePath,
                        originalPreviewBitmap = preview,
                        previewBitmap = preview,
                        params = EditParams(),
                        revision = it.revision + 1,
                        message = "원본 캐시가 완료되었습니다: ${preview.width}x${preview.height} preview"
                    )
                    saveDraftSnapshot(context, next)
                    next
                }
            } catch (t: Throwable) {
                _uiState.update { it.copy(isBusy = false, message = "이미지를 열지 못했습니다: ${t.message}") }
            }
        }
    }

    fun updateParams(transform: (EditParams) -> EditParams) {
        val current = _uiState.value
        val basePreview = current.originalPreviewBitmap ?: current.previewBitmap ?: return
        val nextParams = transform(current.params)
        val nextRevision = current.revision + 1

        _uiState.update { it.copy(params = nextParams, revision = nextRevision, isBusy = true, message = "미리보기를 렌더링하는 중입니다") }
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            val rendered = withContext(Dispatchers.Default) {
                renderEditedPreview(basePreview, nextParams, nextRevision)
            }
            if (_uiState.value.revision == nextRevision) {
                val context = getApplication<Application>()
                _uiState.update {
                    val next = it.copy(previewBitmap = rendered, isBusy = false, message = "미리보기 렌더링이 완료되었습니다")
                    saveDraftSnapshot(context, next)
                    next
                }
            }
        }
    }

    fun resetAdjustments() {
        val path = _uiState.value.sourcePath ?: return
        viewModelScope.launch {
            val context = getApplication<Application>()
            val preview = withContext(Dispatchers.IO) { decodeSampledMutableBitmap(path, maxSide = 2048) }
            _uiState.update {
                val next = it.copy(
                    originalPreviewBitmap = preview,
                    previewBitmap = preview,
                    params = EditParams(),
                    revision = it.revision + 1,
                    message = "초기화가 완료되었습니다"
                )
                saveDraftSnapshot(context, next)
                next
            }
        }
    }

    fun setExportFormat(format: ExportFormat) {
        val context = getApplication<Application>()
        _uiState.update {
            val next = it.copy(exportFormat = format, message = "파일 형식이 ${format.label}로 설정되었습니다")
            saveDraftSnapshot(context, next)
            next
        }
    }

    fun setExportResolution(resolution: ExportResolution) {
        val context = getApplication<Application>()
        _uiState.update {
            val next = it.copy(exportResolution = resolution, message = "해상도가 ${resolution.label}로 설정되었습니다")
            saveDraftSnapshot(context, next)
            next
        }
    }

    fun setExportHistoryRetention(retention: ExportHistoryRetention) {
        val context = getApplication<Application>()
        saveExportHistoryRetention(context, retention)
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                pruneSavedExportsIfNeeded(context, _uiState.value.savedExports, retention)
            }
            _uiState.update {
                it.copy(
                    exportHistoryRetention = retention,
                    savedExports = saved,
                    message = "내보낸 사진 기록 자동 정리가 ${retention.label}으로 설정되었습니다"
                )
            }
        }
    }

    fun exportPreview() {
        val state = _uiState.value
        val bitmap = state.previewBitmap
        if (bitmap == null) {
            _uiState.update { it.copy(message = "내보낼 이미지가 없습니다") }
            return
        }

        _uiState.update { it.copy(isBusy = true, message = "이미지를 내보내는 중입니다") }
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val fileName = "KeplerStudio_${exportTimestamp()}.${state.exportFormat.extension}"
                val savedUri = withContext(Dispatchers.IO) {
                    val exportBitmap = scaleBitmapForExport(bitmap, state.exportResolution)
                    saveBitmapToGallery(context, exportBitmap, fileName, state.exportFormat)
                }
                val savedItem = SavedExport(
                    displayName = fileName,
                    uriString = savedUri.toString(),
                    formatLabel = state.exportFormat.label,
                    resolutionLabel = state.exportResolution.label,
                    timestampMillis = System.currentTimeMillis()
                )
                val savedExports = withContext(Dispatchers.IO) {
                    rememberSavedExport(context, savedItem, state.exportHistoryRetention)
                }
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        savedExports = savedExports,
                        message = "갤러리에 저장되었습니다: $fileName"
                    )
                }
            } catch (t: Throwable) {
                _uiState.update { it.copy(isBusy = false, message = "내보내기에 실패했습니다: ${t.message}") }
            }
        }
    }

    fun clearDraft() {
        val context = getApplication<Application>()
        clearDraftPrefs(context)
        _uiState.update {
            it.copy(
                draftSavedAtMillis = null,
                message = "자동복구용 임시저장 기록을 삭제했습니다. 현재 편집 화면은 유지됩니다"
            )
        }
    }

    fun cleanupOldTemporarySources() {
        val context = getApplication<Application>()
        val activeSourcePath = _uiState.value.sourcePath
        viewModelScope.launch {
            val removedCount = withContext(Dispatchers.IO) {
                cleanupTemporarySourceFiles(context, activeSourcePath = activeSourcePath)
            }
            _uiState.update {
                it.copy(message = "7일이 지난 임시 원본 캐시를 정리했습니다. 삭제된 파일: ${removedCount}개")
            }
        }
    }

    fun clearSavedExports() {
        val context = getApplication<Application>()
        clearSavedExportsPrefs(context)
        _uiState.update {
            it.copy(
                savedExports = emptyList(),
                message = "내보낸 사진 기록을 모두 비웠습니다. 갤러리 파일은 삭제되지 않습니다"
            )
        }
    }

    fun removeSavedExport(uriString: String) {
        val context = getApplication<Application>()
        val next = _uiState.value.savedExports.filterNot { it.uriString == uriString }
        saveSavedExportsToPrefs(context, next)
        _uiState.update {
            it.copy(
                savedExports = next,
                message = "선택한 내보낸 사진 기록을 삭제했습니다. 갤러리 파일은 삭제되지 않습니다"
            )
        }
    }

    fun updateViewport(viewport: ViewportState) {
        _uiState.update { it.copy(viewport = viewport) }
        // TODO v0.2: viewport가 scale 임계값 이상이면 ROI 타일 렌더 Job 발행.
    }

    private suspend fun restoreDraftIfAvailable(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val sourcePath = prefs.getString(KEY_DRAFT_SOURCE, null) ?: return
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) return

        val params = EditParams(
            exposure = prefs.getFloat(KEY_DRAFT_EXPOSURE, 0f),
            contrast = prefs.getFloat(KEY_DRAFT_CONTRAST, 0f),
            shadows = prefs.getFloat(KEY_DRAFT_SHADOWS, 0f),
            highlights = prefs.getFloat(KEY_DRAFT_HIGHLIGHTS, 0f),
            sharpness = prefs.getFloat(KEY_DRAFT_SHARPNESS, 0f)
        )
        val exportFormat = enumValueOrDefault(prefs.getString(KEY_DRAFT_FORMAT, null), ExportFormat.Jpeg)
        val exportResolution = enumValueOrDefault(prefs.getString(KEY_DRAFT_RESOLUTION, null), ExportResolution.Full)
        val draftSavedAt = prefs.getLong(KEY_DRAFT_SAVED_AT, 0L).takeIf { it > 0L }

        _uiState.update { it.copy(isBusy = true, message = "임시저장된 편집을 불러오는 중입니다") }
        try {
            val preview = withContext(Dispatchers.IO) { decodeSampledMutableBitmap(sourcePath, maxSide = 2048) }
            val nextRevision = _uiState.value.revision + 1
            val rendered = withContext(Dispatchers.Default) { renderEditedPreview(preview, params, nextRevision) }
            releaseNativeSession()
            nativeSession = NativePhotoCore.nativeCreateSession(sourcePath)
            _uiState.update {
                it.copy(
                    isBusy = false,
                    sourcePath = sourcePath,
                    originalPreviewBitmap = preview,
                    previewBitmap = rendered,
                    params = params,
                    exportFormat = exportFormat,
                    exportResolution = exportResolution,
                    draftSavedAtMillis = draftSavedAt,
                    revision = nextRevision,
                    message = "임시저장된 편집을 불러왔습니다"
                )
            }
        } catch (t: Throwable) {
            _uiState.update { it.copy(isBusy = false, message = "임시저장을 불러오지 못했습니다: ${t.message}") }
        }
    }

    private fun releaseNativeSession() {
        if (nativeSession != 0L) {
            runCatching { NativePhotoCore.nativeReleaseSession(nativeSession) }
            nativeSession = 0L
        }
    }

    override fun onCleared() {
        releaseNativeSession()
        super.onCleared()
    }
}

private fun copyUriToCache(context: Context, uri: Uri): File {
    val outFile = File(context.cacheDir, "source_${System.currentTimeMillis()}.img")
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "input stream is null" }
        FileOutputStream(outFile).use { output -> input.copyTo(output) }
    }
    return outFile
}

private fun decodeSampledMutableBitmap(path: String, maxSide: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "지원하지 않는 이미지이거나 디코딩에 실패했습니다" }

    var sample = 1
    val longest = kotlin.math.max(bounds.outWidth, bounds.outHeight)
    while (longest / sample > maxSide) sample *= 2

    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inMutable = true
    }
    return requireNotNull(BitmapFactory.decodeFile(path, options)) { "미리보기 디코딩에 실패했습니다" }
        .copy(Bitmap.Config.ARGB_8888, true)
}

private fun renderEditedPreview(basePreview: Bitmap, params: EditParams, revision: Int): Bitmap {
    val copy = basePreview.copy(Bitmap.Config.ARGB_8888, true)
    NativePhotoCore.nativeRenderPreviewInPlace(
        copy,
        params.exposure,
        params.contrast,
        params.shadows,
        params.highlights,
        params.sharpness,
        revision
    )
    return copy
}

private fun scaleBitmapForExport(bitmap: Bitmap, resolution: ExportResolution): Bitmap {
    if (resolution.scalePercent >= 100) return bitmap
    val scale = resolution.scalePercent / 100f
    val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}

private fun saveBitmapToGallery(
    context: Context,
    bitmap: Bitmap,
    fileName: String,
    format: ExportFormat
): Uri {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/KeplerStudio")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("저장 위치를 만들 수 없습니다")

    try {
        if (format == ExportFormat.Heif) {
            writeHeifToUri(context, uri, bitmap)
        } else {
            writeCompressedBitmapToUri(context, uri, bitmap, format)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    } catch (t: Throwable) {
        resolver.delete(uri, null, null)
        throw t
    }
}

private fun writeCompressedBitmapToUri(
    context: Context,
    uri: Uri,
    bitmap: Bitmap,
    format: ExportFormat
) {
    val compressFormat = when (format) {
        ExportFormat.Jpeg -> Bitmap.CompressFormat.JPEG
        ExportFormat.Png -> Bitmap.CompressFormat.PNG
        ExportFormat.Webp -> Bitmap.CompressFormat.WEBP
        ExportFormat.Heif -> error("HEIF는 별도 인코더를 사용합니다")
    }
    val quality = if (format == ExportFormat.Png) 100 else 95
    context.contentResolver.openOutputStream(uri)?.use { output ->
        check(bitmap.compress(compressFormat, quality, output)) { "이미지 압축에 실패했습니다" }
    } ?: error("저장 스트림을 열 수 없습니다")
}

private fun writeHeifToUri(context: Context, uri: Uri, bitmap: Bitmap) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        error("HEIF 저장은 Android 9 이상에서 지원됩니다")
    }

    val descriptor = context.contentResolver.openFileDescriptor(uri, "w")
        ?: error("HEIF 저장 스트림을 열 수 없습니다")

    descriptor.use { pfd ->
        val writer = HeifWriter.Builder(
            pfd.fileDescriptor,
            bitmap.width,
            bitmap.height,
            HeifWriter.INPUT_MODE_BITMAP
        )
            .setMaxImages(1)
            .setQuality(95)
            .build()

        try {
            writer.start()
            writer.addBitmap(bitmap)
            writer.stop(0)
        } finally {
            writer.close()
        }
    }
}

private fun saveDraftSnapshot(context: Context, state: EditorUiState) {
    val sourcePath = state.sourcePath ?: return
    val savedAt = System.currentTimeMillis()
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
        .putString(KEY_DRAFT_SOURCE, sourcePath)
        .putFloat(KEY_DRAFT_EXPOSURE, state.params.exposure)
        .putFloat(KEY_DRAFT_CONTRAST, state.params.contrast)
        .putFloat(KEY_DRAFT_SHADOWS, state.params.shadows)
        .putFloat(KEY_DRAFT_HIGHLIGHTS, state.params.highlights)
        .putFloat(KEY_DRAFT_SHARPNESS, state.params.sharpness)
        .putString(KEY_DRAFT_FORMAT, state.exportFormat.name)
        .putString(KEY_DRAFT_RESOLUTION, state.exportResolution.name)
        .putLong(KEY_DRAFT_SAVED_AT, savedAt)
        .apply()
}

private fun clearDraftPrefs(context: Context) {
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
        .remove(KEY_DRAFT_SOURCE)
        .remove(KEY_DRAFT_EXPOSURE)
        .remove(KEY_DRAFT_CONTRAST)
        .remove(KEY_DRAFT_SHADOWS)
        .remove(KEY_DRAFT_HIGHLIGHTS)
        .remove(KEY_DRAFT_SHARPNESS)
        .remove(KEY_DRAFT_FORMAT)
        .remove(KEY_DRAFT_RESOLUTION)
        .remove(KEY_DRAFT_SAVED_AT)
        .apply()
}

private fun cleanupTemporarySourceFiles(context: Context, activeSourcePath: String?): Int {
    val now = System.currentTimeMillis()
    val maxAgeMs = 7L * 24L * 60L * 60L * 1000L
    val activePath = activeSourcePath?.let { File(it).absolutePath }
    val files = context.cacheDir.listFiles { file ->
        file.isFile && file.name.startsWith("source_") && file.name.endsWith(".img")
    }.orEmpty()

    var removed = 0
    files.forEach { file ->
        val expired = now - file.lastModified() > maxAgeMs
        val isActive = activePath != null && file.absolutePath == activePath
        if (expired && !isActive && file.delete()) removed += 1
    }
    return removed
}

private fun rememberSavedExport(
    context: Context,
    item: SavedExport,
    retention: ExportHistoryRetention
): List<SavedExport> {
    val next = (listOf(item) + loadSavedExportsFromPrefs(context).filter { it.uriString != item.uriString }).take(60)
    return pruneSavedExportsIfNeeded(context, next, retention)
}

private fun pruneSavedExportsIfNeeded(
    context: Context,
    items: List<SavedExport>,
    retention: ExportHistoryRetention
): List<SavedExport> {
    val days = retention.days
    val pruned = if (days == null) {
        items
    } else {
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        items.filter { it.timestampMillis >= cutoff }
    }
    saveSavedExportsToPrefs(context, pruned)
    return pruned
}

private fun saveSavedExportsToPrefs(context: Context, items: List<SavedExport>) {
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
        .putString(KEY_SAVED_EXPORTS, items.joinToString("\n") { encodeSavedExport(it) })
        .apply()
}

private fun clearSavedExportsPrefs(context: Context) {
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
        .remove(KEY_SAVED_EXPORTS)
        .apply()
}

private fun loadSavedExportsFromPrefs(context: Context): List<SavedExport> {
    val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_SAVED_EXPORTS, null)
        ?: return emptyList()
    return raw.lines().mapNotNull { decodeSavedExport(it) }
}

private fun saveExportHistoryRetention(context: Context, retention: ExportHistoryRetention) {
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
        .putString(KEY_EXPORT_HISTORY_RETENTION, retention.name)
        .apply()
}

private fun loadExportHistoryRetention(context: Context): ExportHistoryRetention =
    enumValueOrDefault(
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_EXPORT_HISTORY_RETENTION, null),
        ExportHistoryRetention.Never
    )

private fun encodeSavedExport(item: SavedExport): String = listOf(
    item.displayName,
    item.uriString,
    item.formatLabel,
    item.resolutionLabel,
    item.timestampMillis.toString()
).joinToString("|") { it.replace("|", " ").replace("\n", " ") }

private fun decodeSavedExport(raw: String): SavedExport? {
    val parts = raw.split("|")
    if (parts.size != 5) return null
    return SavedExport(
        displayName = parts[0],
        uriString = parts[1],
        formatLabel = parts[2],
        resolutionLabel = parts[3],
        timestampMillis = parts[4].toLongOrNull() ?: return null
    )
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T =
    runCatching { enumValueOf<T>(name ?: return default) }.getOrDefault(default)

private fun exportTimestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

private const val PREF_NAME = "kepler_studio_editor"
private const val KEY_SAVED_EXPORTS = "saved_exports"
private const val KEY_EXPORT_HISTORY_RETENTION = "export_history_retention"
private const val KEY_DRAFT_SOURCE = "draft_source"
private const val KEY_DRAFT_EXPOSURE = "draft_exposure"
private const val KEY_DRAFT_CONTRAST = "draft_contrast"
private const val KEY_DRAFT_SHADOWS = "draft_shadows"
private const val KEY_DRAFT_HIGHLIGHTS = "draft_highlights"
private const val KEY_DRAFT_SHARPNESS = "draft_sharpness"
private const val KEY_DRAFT_FORMAT = "draft_format"
private const val KEY_DRAFT_RESOLUTION = "draft_resolution"
private const val KEY_DRAFT_SAVED_AT = "draft_saved_at"
