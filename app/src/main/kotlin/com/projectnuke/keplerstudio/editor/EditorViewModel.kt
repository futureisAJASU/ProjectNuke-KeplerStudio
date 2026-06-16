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
import kotlin.math.max

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val _uiState = MutableStateFlow(
        EditorUiState(nativeVersion = runCatching { NativePhotoCore.nativeVersion() }.getOrElse { "native load failed: ${it.message}" })
    )
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var nativeSession: Long = 0L
    private var renderJob: Job? = null

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
                    it.copy(
                        isBusy = false,
                        sourcePath = sourceFile.absolutePath,
                        originalPreviewBitmap = preview,
                        previewBitmap = preview,
                        params = EditParams(),
                        revision = it.revision + 1,
                        message = "원본 캐시가 완료되었습니다: ${preview.width}x${preview.height} preview"
                    )
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
                val copy = basePreview.copy(Bitmap.Config.ARGB_8888, true)
                NativePhotoCore.nativeRenderPreviewInPlace(
                    copy,
                    nextParams.exposure,
                    nextParams.contrast,
                    nextParams.shadows,
                    nextParams.highlights,
                    nextParams.sharpness,
                    nextRevision
                )
                copy
            }
            if (_uiState.value.revision == nextRevision) {
                _uiState.update { it.copy(previewBitmap = rendered, isBusy = false, message = "미리보기 렌더링이 완료되었습니다") }
            }
        }
    }

    fun resetAdjustments() {
        val path = _uiState.value.sourcePath ?: return
        viewModelScope.launch {
            val preview = withContext(Dispatchers.IO) { decodeSampledMutableBitmap(path, maxSide = 2048) }
            _uiState.update {
                it.copy(
                    originalPreviewBitmap = preview,
                    previewBitmap = preview,
                    params = EditParams(),
                    revision = it.revision + 1,
                    message = "초기화가 완료되었습니다"
                )
            }
        }
    }

    fun exportPreview() {
        val bitmap = _uiState.value.previewBitmap
        if (bitmap == null) {
            _uiState.update { it.copy(message = "내보낼 이미지가 없습니다") }
            return
        }

        _uiState.update { it.copy(isBusy = true, message = "이미지를 내보내는 중입니다") }
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val fileName = "KeplerStudio_${exportTimestamp()}.jpg"
                withContext(Dispatchers.IO) {
                    saveBitmapToGallery(context, bitmap, fileName)
                }
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        message = "갤러리에 저장되었습니다: $fileName"
                    )
                }
            } catch (t: Throwable) {
                _uiState.update { it.copy(isBusy = false, message = "내보내기에 실패했습니다: ${t.message}") }
            }
        }
    }

    fun updateViewport(viewport: ViewportState) {
        _uiState.update { it.copy(viewport = viewport) }
        // TODO v0.2: viewport가 scale 임계값 이상이면 ROI 타일 렌더 Job 발행.
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

private fun saveBitmapToGallery(context: Context, bitmap: Bitmap, fileName: String) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/KeplerStudio")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("저장 위치를 만들 수 없습니다")

    try {
        resolver.openOutputStream(uri)?.use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) { "이미지 압축에 실패했습니다" }
        } ?: error("저장 스트림을 열 수 없습니다")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    } catch (t: Throwable) {
        resolver.delete(uri, null, null)
        throw t
    }
}

private fun exportTimestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
