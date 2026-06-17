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
import kotlin.math.ln
import kotlin.math.max
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
            val engines = loadEngineSelection(context)
            val saved = withContext(Dispatchers.IO) {
                pruneSavedExportsIfNeeded(context, loadSavedExportsFromPrefs(context), retention)
            }
            _uiState.update {
                it.copy(
                    savedExports = saved,
                    exportHistoryRetention = retention,
                    noiseEngine = engines.noiseEngine,
                    detailEngine = engines.detailEngine,
                    toneEngine = engines.toneEngine,
                    hazeEngine = engines.hazeEngine
                )
            }
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
                renderEditedPreview(basePreview, nextParams, current.engineSelection(), nextRevision)
            }
            if (_uiState.value.revision == nextRevision) {
                val context = getApplication<Application>()
                _uiState.update {
                    val next = it.copy(previewBitmap = rendered, isBusy = false, message = "미리보기 렌더링이 완료되었습니다")
                    saveDraftSnapshot(context, next)
                    next
                }
            } else {
                rendered.recycle()
            }
        }
    }

    fun applyAutoEnhance() {
        val current = _uiState.value
        val basePreview = current.originalPreviewBitmap ?: current.previewBitmap
        if (basePreview == null) {
            _uiState.update { it.copy(message = "자동 보정을 적용할 이미지가 없습니다") }
            return
        }

        val nextRevision = current.revision + 1
        renderJob?.cancel()
        _uiState.update { it.copy(isBusy = true, revision = nextRevision, message = "자동 보정값을 분석하는 중입니다") }

        renderJob = viewModelScope.launch {
            try {
                val nextParams = withContext(Dispatchers.Default) { computeAutoEnhanceParams(basePreview) }
                val rendered = withContext(Dispatchers.Default) {
                    renderEditedPreview(basePreview, nextParams, current.engineSelection(), nextRevision)
                }
                if (_uiState.value.revision == nextRevision) {
                    val context = getApplication<Application>()
                    _uiState.update {
                        val next = it.copy(
                            params = nextParams,
                            previewBitmap = rendered,
                            isBusy = false,
                            message = "자동 보정이 적용되었습니다"
                        )
                        saveDraftSnapshot(context, next)
                        next
                    }
                } else {
                    rendered.recycle()
                }
            } catch (t: Throwable) {
                _uiState.update { it.copy(isBusy = false, message = "자동 보정에 실패했습니다: ${t.message}") }
            }
        }
    }

    fun setNoiseEngine(engine: NoiseEngine) {
        applyEngineChange(noiseEngine = engine, message = "노이즈 감소 엔진이 ${engine.label}으로 설정되었습니다")
    }

    fun setDetailEngine(engine: DetailEngine) {
        applyEngineChange(detailEngine = engine, message = "디테일 엔진이 ${engine.label}으로 설정되었습니다")
    }

    fun setToneEngine(engine: ToneEngine) {
        applyEngineChange(toneEngine = engine, message = "톤 엔진이 ${engine.label}으로 설정되었습니다")
    }

    fun setHazeEngine(engine: DehazeEngine) {
        applyEngineChange(hazeEngine = engine, message = "디헤이즈 엔진이 ${engine.label}으로 설정되었습니다")
    }

    private fun applyEngineChange(
        noiseEngine: NoiseEngine? = null,
        detailEngine: DetailEngine? = null,
        toneEngine: ToneEngine? = null,
        hazeEngine: DehazeEngine? = null,
        message: String
    ) {
        val current = _uiState.value
        val nextEngines = EngineSelection(
            noiseEngine = noiseEngine ?: current.noiseEngine,
            detailEngine = detailEngine ?: current.detailEngine,
            toneEngine = toneEngine ?: current.toneEngine,
            hazeEngine = hazeEngine ?: current.hazeEngine
        )
        val context = getApplication<Application>()
        saveEngineSelection(context, nextEngines)

        val basePreview = current.originalPreviewBitmap ?: current.previewBitmap
        if (basePreview == null) {
            _uiState.update {
                it.copy(
                    noiseEngine = nextEngines.noiseEngine,
                    detailEngine = nextEngines.detailEngine,
                    toneEngine = nextEngines.toneEngine,
                    hazeEngine = nextEngines.hazeEngine,
                    message = message
                )
            }
            return
        }

        val nextRevision = current.revision + 1
        _uiState.update {
            it.copy(
                noiseEngine = nextEngines.noiseEngine,
                detailEngine = nextEngines.detailEngine,
                toneEngine = nextEngines.toneEngine,
                hazeEngine = nextEngines.hazeEngine,
                revision = nextRevision,
                isBusy = true,
                message = "$message. 미리보기를 다시 렌더링하는 중입니다"
            )
        }
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            val rendered = withContext(Dispatchers.Default) {
                renderEditedPreview(basePreview, current.params, nextEngines, nextRevision)
            }
            if (_uiState.value.revision == nextRevision) {
                _uiState.update { it.copy(previewBitmap = rendered, isBusy = false, message = message) }
            } else {
                rendered.recycle()
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
        val sourcePath = state.sourcePath
        if (sourcePath == null) {
            _uiState.update { it.copy(message = "내보낼 원본 이미지가 없습니다") }
            return
        }

        _uiState.update { it.copy(isBusy = true, message = "원본 기준으로 내보내는 중입니다") }
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val fileName = "KeplerStudio_${exportTimestamp()}.${state.exportFormat.extension}"
                val savedUri = withContext(Dispatchers.IO) {
                    val exportBitmap = renderEditedExport(
                        sourcePath = sourcePath,
                        params = state.params,
                        resolution = state.exportResolution,
                        engines = state.engineSelection(),
                        revision = state.revision + 1
                    )
                    try {
                        saveBitmapToGallery(context, exportBitmap, fileName, state.exportFormat)
                    } finally {
                        exportBitmap.recycle()
                    }
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
            whites = prefs.getFloat(KEY_DRAFT_WHITES, 0f),
            blacks = prefs.getFloat(KEY_DRAFT_BLACKS, 0f),
            temperature = prefs.getFloat(KEY_DRAFT_TEMPERATURE, 0f),
            tint = prefs.getFloat(KEY_DRAFT_TINT, 0f),
            saturation = prefs.getFloat(KEY_DRAFT_SATURATION, 0f),
            vibrance = prefs.getFloat(KEY_DRAFT_VIBRANCE, 0f),
            clarity = prefs.getFloat(KEY_DRAFT_CLARITY, 0f),
            dehaze = prefs.getFloat(KEY_DRAFT_DEHAZE, 0f),
            sharpness = prefs.getFloat(KEY_DRAFT_SHARPNESS, 0f),
            noiseReduction = prefs.getFloat(KEY_DRAFT_NOISE_REDUCTION, 0f)
        )
        val exportFormat = enumValueOrDefault(prefs.getString(KEY_DRAFT_FORMAT, null), ExportFormat.Jpeg)
        val exportResolution = enumValueOrDefault(prefs.getString(KEY_DRAFT_RESOLUTION, null), ExportResolution.Full)
        val draftSavedAt = prefs.getLong(KEY_DRAFT_SAVED_AT, 0L).takeIf { it > 0L }
        val engines = _uiState.value.engineSelection()

        _uiState.update { it.copy(isBusy = true, message = "임시저장된 편집을 불러오는 중입니다") }
        try {
            val preview = withContext(Dispatchers.IO) { decodeSampledMutableBitmap(sourcePath, maxSide = 2048) }
            val nextRevision = _uiState.value.revision + 1
            val rendered = withContext(Dispatchers.Default) { renderEditedPreview(preview, params, engines, nextRevision) }
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

private data class EngineSelection(
    val noiseEngine: NoiseEngine,
    val detailEngine: DetailEngine,
    val toneEngine: ToneEngine,
    val hazeEngine: DehazeEngine
)

private fun EditorUiState.engineSelection(): EngineSelection = EngineSelection(
    noiseEngine = noiseEngine,
    detailEngine = detailEngine,
    toneEngine = toneEngine,
    hazeEngine = hazeEngine
)

private data class LumaStats(
    val p01: Float,
    val p05: Float,
    val p50: Float,
    val p95: Float,
    val p99: Float,
    val mean: Float,
    val chromaMean: Float
)

private fun computeAutoEnhanceParams(bitmap: Bitmap): EditParams {
    val stats = analyzeBitmap(bitmap)
    val safeMedian = stats.p50.coerceAtLeast(0.015f)
    val exposure = (ln(0.46f / safeMedian) / ln(2f)).toFloat().coerceIn(-0.70f, 0.70f)

    val range = (stats.p95 - stats.p05).coerceAtLeast(0.01f)
    val contrast = ((0.58f - range) * 0.70f).coerceIn(-0.22f, 0.38f)
    val shadows = ((0.24f - stats.p05) * 0.85f).coerceIn(-0.18f, 0.42f)
    val highlights = ((stats.p95 - 0.78f) * 0.95f).coerceIn(-0.18f, 0.42f)
    val whites = ((0.97f - stats.p99) * 0.65f).coerceIn(-0.20f, 0.30f)
    val blacks = ((0.025f - stats.p01) * 1.05f).coerceIn(-0.32f, 0.22f)
    val vibrance = ((0.30f - stats.chromaMean) * 0.72f).coerceIn(0.00f, 0.28f)
    val saturation = if (stats.chromaMean < 0.10f) 0.04f else 0.00f
    val clarity = if (range < 0.48f) 0.12f else 0.07f
    val dehaze = if (stats.p05 > 0.10f && stats.p95 < 0.86f) 0.10f else 0.02f
    val noiseReduction = if (stats.mean < 0.34f) 0.20f else 0.08f

    return EditParams(
        exposure = exposure,
        contrast = contrast,
        shadows = shadows,
        highlights = highlights,
        whites = whites,
        blacks = blacks,
        temperature = 0f,
        tint = 0f,
        saturation = saturation,
        vibrance = vibrance,
        clarity = clarity,
        dehaze = dehaze,
        sharpness = 0.16f,
        noiseReduction = noiseReduction
    )
}

private fun analyzeBitmap(bitmap: Bitmap): LumaStats {
    val histogram = IntArray(256)
    var count = 0
    var lumaSum = 0f
    var chromaSum = 0f
    val step = max(1, max(bitmap.width, bitmap.height) / 512)
    val row = IntArray(bitmap.width)

    var y = 0
    while (y < bitmap.height) {
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        var x = 0
        while (x < bitmap.width) {
            val pixel = row[x]
            val r = ((pixel shr 16) and 0xff) / 255f
            val g = ((pixel shr 8) and 0xff) / 255f
            val b = (pixel and 0xff) / 255f
            val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
            val maxC = max(r, max(g, b))
            val minC = kotlin.math.min(r, kotlin.math.min(g, b))
            histogram[(luma * 255f).roundToInt().coerceIn(0, 255)] += 1
            lumaSum += luma
            chromaSum += (maxC - minC).coerceIn(0f, 1f)
            count += 1
            x += step
        }
        y += step
    }

    if (count <= 0) {
        return LumaStats(0f, 0f, 0.5f, 1f, 1f, 0.5f, 0.1f)
    }

    return LumaStats(
        p01 = percentileFromHistogram(histogram, count, 0.01f),
        p05 = percentileFromHistogram(histogram, count, 0.05f),
        p50 = percentileFromHistogram(histogram, count, 0.50f),
        p95 = percentileFromHistogram(histogram, count, 0.95f),
        p99 = percentileFromHistogram(histogram, count, 0.99f),
        mean = lumaSum / count,
        chromaMean = chromaSum / count
    )
}

private fun percentileFromHistogram(histogram: IntArray, count: Int, percentile: Float): Float {
    val target = (count * percentile).roundToInt().coerceIn(1, count)
    var accum = 0
    for (i in histogram.indices) {
        accum += histogram[i]
        if (accum >= target) return i / 255f
    }
    return 1f
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
    val longest = max(bounds.outWidth, bounds.outHeight)
    while (longest / sample > maxSide) sample *= 2

    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inMutable = true
    }
    return requireNotNull(BitmapFactory.decodeFile(path, options)) { "미리보기 디코딩에 실패했습니다" }
        .copy(Bitmap.Config.ARGB_8888, true)
}

private fun renderEditedPreview(basePreview: Bitmap, params: EditParams, engines: EngineSelection, revision: Int): Bitmap {
    val copy = basePreview.copy(Bitmap.Config.ARGB_8888, true)
    renderBitmapInNative(copy, params, engines, revision)
    return copy
}

private fun renderEditedExport(
    sourcePath: String,
    params: EditParams,
    resolution: ExportResolution,
    engines: EngineSelection,
    revision: Int
): Bitmap {
    // TODO v0.2: replace whole-bitmap export with ROI/tile rendering to reduce peak memory use.
    val decoded = decodeSampledMutableBitmap(sourcePath, maxSide = EXPORT_MAX_SIDE)
    renderBitmapInNative(decoded, params, engines, revision)

    val scaled = scaleBitmapForExport(decoded, resolution)
    if (scaled !== decoded) decoded.recycle()
    return scaled
}

private fun renderBitmapInNative(bitmap: Bitmap, params: EditParams, engines: EngineSelection, revision: Int): Int =
    NativePhotoCore.nativeRenderPreviewInPlace(
        bitmap,
        params.exposure,
        params.contrast,
        params.shadows,
        params.highlights,
        params.whites,
        params.blacks,
        params.temperature,
        params.tint,
        params.saturation,
        params.vibrance,
        params.clarity,
        params.dehaze,
        params.sharpness,
        params.noiseReduction,
        engines.noiseEngine.nativeId,
        engines.detailEngine.nativeId,
        engines.toneEngine.nativeId,
        engines.hazeEngine.nativeId,
        revision
    )

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
        .putFloat(KEY_DRAFT_WHITES, state.params.whites)
        .putFloat(KEY_DRAFT_BLACKS, state.params.blacks)
        .putFloat(KEY_DRAFT_TEMPERATURE, state.params.temperature)
        .putFloat(KEY_DRAFT_TINT, state.params.tint)
        .putFloat(KEY_DRAFT_SATURATION, state.params.saturation)
        .putFloat(KEY_DRAFT_VIBRANCE, state.params.vibrance)
        .putFloat(KEY_DRAFT_CLARITY, state.params.clarity)
        .putFloat(KEY_DRAFT_DEHAZE, state.params.dehaze)
        .putFloat(KEY_DRAFT_SHARPNESS, state.params.sharpness)
        .putFloat(KEY_DRAFT_NOISE_REDUCTION, state.params.noiseReduction)
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
        .remove(KEY_DRAFT_WHITES)
        .remove(KEY_DRAFT_BLACKS)
        .remove(KEY_DRAFT_TEMPERATURE)
        .remove(KEY_DRAFT_TINT)
        .remove(KEY_DRAFT_SATURATION)
        .remove(KEY_DRAFT_VIBRANCE)
        .remove(KEY_DRAFT_CLARITY)
        .remove(KEY_DRAFT_DEHAZE)
        .remove(KEY_DRAFT_SHARPNESS)
        .remove(KEY_DRAFT_NOISE_REDUCTION)
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

private fun saveEngineSelection(context: Context, engines: EngineSelection) {
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
        .putString(KEY_NOISE_ENGINE, engines.noiseEngine.name)
        .putString(KEY_DETAIL_ENGINE, engines.detailEngine.name)
        .putString(KEY_TONE_ENGINE, engines.toneEngine.name)
        .putString(KEY_HAZE_ENGINE, engines.hazeEngine.name)
        .apply()
}

private fun loadEngineSelection(context: Context): EngineSelection {
    val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    return EngineSelection(
        noiseEngine = enumValueOrDefault(prefs.getString(KEY_NOISE_ENGINE, null), NoiseEngine.FastEdgeAware),
        detailEngine = enumValueOrDefault(prefs.getString(KEY_DETAIL_ENGINE, null), DetailEngine.MaskedUnsharp),
        toneEngine = enumValueOrDefault(prefs.getString(KEY_TONE_ENGINE, null), ToneEngine.HistogramAuto),
        hazeEngine = enumValueOrDefault(prefs.getString(KEY_HAZE_ENGINE, null), DehazeEngine.FastContrast)
    )
}

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

private const val EXPORT_MAX_SIDE = 8192
private const val PREF_NAME = "kepler_studio_editor"
private const val KEY_SAVED_EXPORTS = "saved_exports"
private const val KEY_EXPORT_HISTORY_RETENTION = "export_history_retention"
private const val KEY_NOISE_ENGINE = "noise_engine"
private const val KEY_DETAIL_ENGINE = "detail_engine"
private const val KEY_TONE_ENGINE = "tone_engine"
private const val KEY_HAZE_ENGINE = "haze_engine"
private const val KEY_DRAFT_SOURCE = "draft_source"
private const val KEY_DRAFT_EXPOSURE = "draft_exposure"
private const val KEY_DRAFT_CONTRAST = "draft_contrast"
private const val KEY_DRAFT_SHADOWS = "draft_shadows"
private const val KEY_DRAFT_HIGHLIGHTS = "draft_highlights"
private const val KEY_DRAFT_WHITES = "draft_whites"
private const val KEY_DRAFT_BLACKS = "draft_blacks"
private const val KEY_DRAFT_TEMPERATURE = "draft_temperature"
private const val KEY_DRAFT_TINT = "draft_tint"
private const val KEY_DRAFT_SATURATION = "draft_saturation"
private const val KEY_DRAFT_VIBRANCE = "draft_vibrance"
private const val KEY_DRAFT_CLARITY = "draft_clarity"
private const val KEY_DRAFT_DEHAZE = "draft_dehaze"
private const val KEY_DRAFT_SHARPNESS = "draft_sharpness"
private const val KEY_DRAFT_NOISE_REDUCTION = "draft_noise_reduction"
private const val KEY_DRAFT_FORMAT = "draft_format"
private const val KEY_DRAFT_RESOLUTION = "draft_resolution"
private const val KEY_DRAFT_SAVED_AT = "draft_saved_at"
