package com.projectnuke.keplerstudio.ui

import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.buildUniversalBalancerTrainingRow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun EditorViewModel.exportUniversalBalancerTrainingRow() {
    val state = uiState.value
    val sourcePath = state.sourcePath
    val preview = state.originalPreviewBitmap ?: state.previewBitmap
    if (sourcePath == null || preview == null) {
        updateUiState { it.copy(message = "?숈뒿 ?곗씠?곕? 留뚮뱾 ?대?吏媛 ?놁뒿?덈떎") }
        return
    }

    val context = appApplication()
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
    updateUiState { it.copy(message = "Universal Balancer ?숈뒿 row瑜???ν뻽?듬땲?? ${outFile.name}") }
}
