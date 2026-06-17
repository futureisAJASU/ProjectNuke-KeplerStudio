package com.projectnuke.keplerstudio.ui

import android.app.Application
import com.projectnuke.keplerstudio.editor.EditorUiState
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.buildUniversalBalancerTrainingRow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

fun EditorViewModel.exportUniversalBalancerTrainingRow() {
    val state = uiState.value
    val sourcePath = state.sourcePath
    val preview = state.originalPreviewBitmap ?: state.previewBitmap
    if (sourcePath == null || preview == null) {
        editorFlowTraining().update { it.copy(message = "학습 데이터를 만들 이미지가 없습니다") }
        return
    }

    val context = getApplication<Application>()
    val outDir = File(context.getExternalFilesDir(null), "training/universal_balancer")
    outDir.mkdirs()
    val id = "ub_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
    val row = buildUniversalBalancerTrainingRow(
        id = id,
        imagePath = sourcePath,
        width = preview.width,
        height = preview.height,
        source = "kepler_manual_edit_v1",
        params = state.params,
        sceneTags = emptyList()
    )
    val outFile = File(outDir, "universal_balancer_rows.jsonl")
    outFile.appendText(row + "\n")
    editorFlowTraining().update {
        it.copy(message = "Universal Balancer 학습 row를 저장했습니다: ${outFile.name}")
    }
}

@Suppress("UNCHECKED_CAST")
private fun EditorViewModel.editorFlowTraining(): MutableStateFlow<EditorUiState> {
    val field = EditorViewModel::class.java.getDeclaredField("_uiState")
    field.isAccessible = true
    return field.get(this) as MutableStateFlow<EditorUiState>
}
