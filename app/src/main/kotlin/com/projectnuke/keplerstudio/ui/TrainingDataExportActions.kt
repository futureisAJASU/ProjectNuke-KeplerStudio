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
        updateUiState { it.copy(message = "\uC0AC\uC6A9 \uC911\uC778 \uD3B8\uC9D1 \uC774\uBBF8\uC9C0\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4.") }
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
        updateUiState { it.copy(message = "Universal Balancer \uD2B8\uB808\uC774\uB2DD row\uB97C \uCD9C\uB825\uD588\uC2B5\uB2C8\uB2E4: ${outFile.name}") }
    }
}
