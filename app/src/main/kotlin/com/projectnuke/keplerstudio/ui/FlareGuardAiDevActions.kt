package com.projectnuke.keplerstudio.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.projectnuke.keplerstudio.editor.EditParams
import com.projectnuke.keplerstudio.editor.EditorUiState
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.FlareGuardMode
import com.projectnuke.keplerstudio.editor.applyFlareGuardModelOrRuleV0
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

private const val FLARE_GUARD_AI_TAG = "KeplerFlareAI"
private const val EDITOR_HISTORY_MAX = 8

private data class EditorHistorySnapshot(
    val params: EditParams,
    val previewBitmap: Bitmap?,
    val originalPreviewBitmap: Bitmap?,
    val revision: Int
)

private data class EditorHistoryStacks(
    val undo: ArrayDeque<EditorHistorySnapshot> = ArrayDeque(),
    val redo: ArrayDeque<EditorHistorySnapshot> = ArrayDeque()
)

private val editorHistory = WeakHashMap<EditorViewModel, EditorHistoryStacks>()

fun EditorViewModel.openImageWithExifOrientation(
    context: Context,
    uri: Uri
) {
    val stateFlow = mutableEditorStateFlowOrNull()
    if (stateFlow == null) {
        Log.e(FLARE_GUARD_AI_TAG, "Editor state reflection failed; cannot open image with EXIF orientation")
        return
    }

    val appContext = context.applicationContext
    editorHistory.remove(this)
    stateFlow.update { it.copy(isBusy = true, message = "이미지를 여는 중입니다") }

    viewModelScope.launch {
        try {
            val sourceFile = withContext(Dispatchers.IO) { copyUriToCacheForEditor(appContext, uri) }
            val preview = withContext(Dispatchers.IO) {
                decodeSampledMutableBitmapWithExif(sourceFile.absolutePath, maxSide = 2048)
            }
            Log.i(
                FLARE_GUARD_AI_TAG,
                "Opened image with EXIF orientation: ${sourceFile.name} preview=${preview.width}x${preview.height}"
            )
            stateFlow.update { state ->
                state.copy(
                    isBusy = false,
                    sourcePath = sourceFile.absolutePath,
                    originalPreviewBitmap = preview,
                    previewBitmap = preview,
                    params = EditParams(),
                    revision = state.revision + 1,
                    message = "원본 캐시가 완료되었습니다: ${preview.width}x${preview.height} preview"
                )
            }
        } catch (t: Throwable) {
            Log.e(FLARE_GUARD_AI_TAG, "Open image with EXIF orientation failed", t)
            stateFlow.update { it.copy(isBusy = false, message = "이미지를 열지 못했습니다: ${t.message}") }
        }
    }
}

fun EditorViewModel.applyParamChangeWithUndo(transform: (EditParams) -> EditParams) {
    pushUndoSnapshot(clearRedo = true)
    updateParams(transform)
}

fun EditorViewModel.undoDevEdit() {
    val stateFlow = mutableEditorStateFlowOrNull() ?: return
    val stacks = historyStacks()
    if (stacks.undo.isEmpty()) {
        stateFlow.update { it.copy(message = "되돌릴 편집 기록이 없습니다") }
        return
    }

    val current = stateFlow.value
    stacks.redo.addLast(current.toHistorySnapshot())
    val snapshot = stacks.undo.removeLast()
    Log.i(FLARE_GUARD_AI_TAG, "Undo editor snapshot: undo=${stacks.undo.size} redo=${stacks.redo.size}")
    stateFlow.update { state ->
        state.copy(
            params = snapshot.params,
            previewBitmap = snapshot.previewBitmap,
            originalPreviewBitmap = snapshot.originalPreviewBitmap,
            revision = state.revision + 1,
            isBusy = false,
            message = "실행 취소했습니다"
        )
    }
}

fun EditorViewModel.redoDevEdit() {
    val stateFlow = mutableEditorStateFlowOrNull() ?: return
    val stacks = historyStacks()
    if (stacks.redo.isEmpty()) {
        stateFlow.update { it.copy(message = "다시 실행할 편집 기록이 없습니다") }
        return
    }

    val current = stateFlow.value
    stacks.undo.addLast(current.toHistorySnapshot())
    trimUndoStack(stacks.undo)
    val snapshot = stacks.redo.removeLast()
    Log.i(FLARE_GUARD_AI_TAG, "Redo editor snapshot: undo=${stacks.undo.size} redo=${stacks.redo.size}")
    stateFlow.update { state ->
        state.copy(
            params = snapshot.params,
            previewBitmap = snapshot.previewBitmap,
            originalPreviewBitmap = snapshot.originalPreviewBitmap,
            revision = state.revision + 1,
            isBusy = false,
            message = "다시 실행했습니다"
        )
    }
}

fun EditorViewModel.rotatePreview90ForDev() {
    val stateFlow = mutableEditorStateFlowOrNull() ?: return
    val current = stateFlow.value
    val preview = current.previewBitmap
    if (preview == null) {
        stateFlow.update { it.copy(message = "회전할 이미지가 없습니다") }
        return
    }

    pushUndoSnapshot(clearRedo = true)
    val original = current.originalPreviewBitmap
    val rotatedPreview = rotateBitmap90(preview)
    val rotatedOriginal = original?.let { rotateBitmap90(it) }
    Log.i(FLARE_GUARD_AI_TAG, "Rotated preview manually: ${preview.width}x${preview.height} -> ${rotatedPreview.width}x${rotatedPreview.height}")
    stateFlow.update { state ->
        state.copy(
            previewBitmap = rotatedPreview,
            originalPreviewBitmap = rotatedOriginal ?: rotatedPreview,
            revision = state.revision + 1,
            message = "미리보기를 90도 회전했습니다"
        )
    }
}

/**
 * Temporary dev action for validating the optional Flare Guard TFLite path.
 *
 * This intentionally lives next to the Remaster dev UI so we can test model loading
 * without reshaping the main EditorViewModel state pipeline yet.
 */
fun EditorViewModel.applyFlareGuardAiPreview(
    context: Context,
    mode: FlareGuardMode
) {
    val stateFlow = mutableEditorStateFlowOrNull()
    if (stateFlow == null) {
        Log.e(FLARE_GUARD_AI_TAG, "Editor state reflection failed; cannot apply Flare Guard AI preview")
        return
    }

    val current = uiState.value
    val source = current.previewBitmap ?: current.originalPreviewBitmap
    if (source == null) {
        stateFlow.update { it.copy(message = "번짐 완화를 적용할 이미지가 없습니다") }
        return
    }

    pushUndoSnapshot(clearRedo = true)
    val label = when (mode) {
        FlareGuardMode.NightLight -> "번짐 완화"
        FlareGuardMode.DaySun -> "태양 번짐 완화"
    }
    val nextRevision = current.revision + 1
    val appContext = context.applicationContext

    Log.i(
        FLARE_GUARD_AI_TAG,
        "Starting $label preview: mode=$mode source=${source.width}x${source.height} revision=$nextRevision"
    )
    stateFlow.update {
        it.copy(
            isBusy = true,
            revision = nextRevision,
            message = "$label AI 경로를 실행하는 중입니다"
        )
    }

    viewModelScope.launch {
        try {
            val rendered = withContext(Dispatchers.Default) {
                applyFlareGuardModelOrRuleV0(
                    context = appContext,
                    source = source,
                    mode = mode
                )
            }

            stateFlow.update { state ->
                if (state.revision == nextRevision) {
                    Log.i(
                        FLARE_GUARD_AI_TAG,
                        "Finished $label preview: output=${rendered.width}x${rendered.height} revision=$nextRevision"
                    )
                    state.copy(
                        previewBitmap = rendered,
                        isBusy = false,
                        message = "$label 적용이 완료되었습니다"
                    )
                } else {
                    Log.w(
                        FLARE_GUARD_AI_TAG,
                        "Discarded stale $label preview: expected=$nextRevision actual=${state.revision}"
                    )
                    rendered.recycle()
                    state
                }
            }
        } catch (t: Throwable) {
            Log.e(FLARE_GUARD_AI_TAG, "$label preview failed", t)
            stateFlow.update {
                it.copy(
                    isBusy = false,
                    message = "$label 적용에 실패했습니다: ${t.message}"
                )
            }
        }
    }
}

private fun EditorViewModel.pushUndoSnapshot(clearRedo: Boolean) {
    val state = uiState.value
    if (state.previewBitmap == null && state.originalPreviewBitmap == null) return
    val stacks = historyStacks()
    stacks.undo.addLast(state.toHistorySnapshot())
    trimUndoStack(stacks.undo)
    if (clearRedo) stacks.redo.clear()
    Log.i(FLARE_GUARD_AI_TAG, "Pushed undo snapshot: undo=${stacks.undo.size} redo=${stacks.redo.size}")
}

private fun EditorViewModel.historyStacks(): EditorHistoryStacks =
    editorHistory.getOrPut(this) { EditorHistoryStacks() }

private fun trimUndoStack(stack: ArrayDeque<EditorHistorySnapshot>) {
    while (stack.size > EDITOR_HISTORY_MAX) {
        val removed = stack.removeFirst()
        removed.previewBitmap?.recycle()
        if (removed.originalPreviewBitmap !== removed.previewBitmap) removed.originalPreviewBitmap?.recycle()
    }
}

private fun EditorUiState.toHistorySnapshot(): EditorHistorySnapshot = EditorHistorySnapshot(
    params = params,
    previewBitmap = previewBitmap?.copy(Bitmap.Config.ARGB_8888, false),
    originalPreviewBitmap = originalPreviewBitmap?.copy(Bitmap.Config.ARGB_8888, false),
    revision = revision
)

private fun copyUriToCacheForEditor(context: Context, uri: Uri): File {
    val outFile = File(context.cacheDir, "source_${System.currentTimeMillis()}.img")
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "input stream is null" }
        FileOutputStream(outFile).use { output -> input.copyTo(output) }
    }
    return outFile
}

private fun decodeSampledMutableBitmapWithExif(path: String, maxSide: Int): Bitmap {
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
    val decoded = requireNotNull(BitmapFactory.decodeFile(path, options)) { "미리보기 디코딩에 실패했습니다" }
        .copy(Bitmap.Config.ARGB_8888, true)

    return applyExifOrientation(path, decoded)
}

private fun applyExifOrientation(path: String, bitmap: Bitmap): Bitmap {
    val orientation = runCatching {
        ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        else -> return bitmap
    }

    val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (transformed !== bitmap) bitmap.recycle()
    Log.i(FLARE_GUARD_AI_TAG, "Applied EXIF orientation=$orientation -> ${transformed.width}x${transformed.height}")
    return transformed.copy(Bitmap.Config.ARGB_8888, true)
}

private fun rotateBitmap90(bitmap: Bitmap): Bitmap {
    val matrix = Matrix().apply { postRotate(90f) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

@Suppress("UNCHECKED_CAST")
private fun EditorViewModel.mutableEditorStateFlowOrNull(): MutableStateFlow<EditorUiState>? {
    return runCatching {
        val field = EditorViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        field.get(this) as? MutableStateFlow<EditorUiState>
    }.getOrNull()
}
