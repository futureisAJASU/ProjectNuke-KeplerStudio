package com.projectnuke.keplerstudio.ui

import androidx.lifecycle.viewModelScope
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.buildUniversalBalancerTrainingRow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun EditorViewModel.exportUniversalBalancerTrainingRow() {
    val state = uiState.value
    val sourcePath = state.sourcePath
    val preview = state.originalPreviewBitmap ?: state.previewBitmap
    if (sourcePath == null || preview == null) {
        updateUiState { it.copy(message = "학습 데이터를 만들 이미지가 없습니다.") }
        return
    }

    val context = appApplication()
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

    viewModelScope.launch {
        val outFile = withContext(Dispatchers.IO) {
            val outDir = File(context.getExternalFilesDir(null), "training/universal_balancer")
            outDir.mkdirs()
            val file = File(outDir, "universal_balancer_rows.jsonl")
            file.appendText(row + "\n")
            file
        }
        updateUiState { it.copy(message = "Universal Balancer 학습 row를 저장했습니다: ${outFile.name}") }
    }
}
