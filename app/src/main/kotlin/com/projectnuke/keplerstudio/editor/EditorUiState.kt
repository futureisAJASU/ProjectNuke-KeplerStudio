package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap

data class EditorUiState(
    val isBusy: Boolean = false,
    val sourcePath: String? = null,
    val originalPreviewBitmap: Bitmap? = null,
    val previewBitmap: Bitmap? = null,
    val params: EditParams = EditParams(),
    val presetLook: PresetColorLook? = null,
    val cropState: CropState = CropState(),
    val exportFormat: ExportFormat = ExportFormat.Jpeg,
    val exportResolution: ExportResolution = ExportResolution.Full,
    val exportHistoryRetention: ExportHistoryRetention = ExportHistoryRetention.Never,
    val noiseEngine: NoiseEngine = NoiseEngine.FastEdgeAware,
    val detailEngine: DetailEngine = DetailEngine.MaskedUnsharp,
    val toneEngine: ToneEngine = ToneEngine.HistogramAuto,
    val hazeEngine: DehazeEngine = DehazeEngine.FastContrast,
    val savedExports: List<SavedExport> = emptyList(),
    val draftSavedAtMillis: Long? = null,
    val viewport: ViewportState = ViewportState(),
    val selectionLayers: List<SelectionLayer> = emptyList(),
    val activeSelectionLayerId: String? = null,
    val selectionPaintSettings: SelectionPaintSettings = SelectionPaintSettings(),
    val showSelectionOverlay: Boolean = true,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val flareGuardRuntimeStatus: String? = null,
    val revision: Int = 0,
    val nativeVersion: String = "",
    val message: String? = null
)
