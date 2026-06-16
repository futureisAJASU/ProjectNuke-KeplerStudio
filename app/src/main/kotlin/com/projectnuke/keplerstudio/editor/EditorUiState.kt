package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap

data class EditorUiState(
    val isBusy: Boolean = false,
    val sourcePath: String? = null,
    val originalPreviewBitmap: Bitmap? = null,
    val previewBitmap: Bitmap? = null,
    val params: EditParams = EditParams(),
    val exportFormat: ExportFormat = ExportFormat.Jpeg,
    val exportResolution: ExportResolution = ExportResolution.Preview,
    val savedExports: List<SavedExport> = emptyList(),
    val draftSavedAtMillis: Long? = null,
    val viewport: ViewportState = ViewportState(),
    val revision: Int = 0,
    val nativeVersion: String = "",
    val message: String? = null
)
