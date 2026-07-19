package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap

data class EditorUiState(
    val isBusy: Boolean = false,
    val sourcePath: String? = null,
    val baseBitmapDirty: Boolean = false,
    val baseContentToken: String = "",
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
    val draftSourcePath: String? = null,
    val draftBaseContentToken: String? = null,
    val draftGenerationId: String? = null,
    val draftGenerationSourcePath: String? = null,
    val draftGenerationThumbnailPath: String? = null,
    val recoveryDebugInfo: RecoveryDebugInfo? = null,
    val showRecoveryDebugCard: Boolean = false,
    val viewport: ViewportState = ViewportState(),
    val selectionLayers: List<SelectionLayer> = emptyList(),
    val activeSelectionLayerId: String? = null,
    val selectionPaintSettings: SelectionPaintSettings = SelectionPaintSettings(),
    val showSelectionOverlay: Boolean = true,
    val activeQuickEffects: List<ActiveQuickEffect> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val flareGuardRuntimeStatus: String? = null,
    val memoryRecoveryRequest: MemoryRecoveryRequest? = null,
    val revision: Int = 0,
    val nativeVersion: String = "",
    val message: String? = null
)

data class MemoryRecoveryRequest(
    val token: Long,
    val mayMoveOldHistory: Boolean
)

data class RecoveryDebugInfo(
    val draftSourcePath: String?,
    val draftSourceExists: Boolean,
    val filesDirDraftPath: String,
    val filesDirDraftExists: Boolean
)

enum class QuickEffectKind {
    SpotCleanup,
    ChromaticAberrationReduction,
    VignetteCorrection,
    OpticsCorrection,
    SoftBlur
}

enum class QuickEffectStrength {
    Weak,
    Medium,
    Strong
}

data class ActiveQuickEffect(
    val kind: QuickEffectKind,
    val strength: QuickEffectStrength = QuickEffectStrength.Medium
)
