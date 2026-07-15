package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.bridge.NativePhotoCore
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.applyActiveQuickEffectsToBitmap
import com.projectnuke.keplerstudio.editor.copyOrThrow
import com.projectnuke.keplerstudio.editor.engineSelection
import com.projectnuke.keplerstudio.editor.FlareGuardMode
import com.projectnuke.keplerstudio.editor.newBaseContentToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun EditorViewModel.applyFlareOriginalMvp() {
    applyFlareRuleFallbackInternal(FlareGuardMode.NightLight, "번짐 완화", 0.28f)
}

fun EditorViewModel.applySunFlareOriginalMvp() {
    applyFlareRuleFallbackInternal(FlareGuardMode.DaySun, "태양 번짐 완화", 0.24f)
}

private fun EditorViewModel.applyFlareRuleFallbackInternal(mode: FlareGuardMode, title: String, strength: Float) {
    if (isShuttingDown()) return
    if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable()) return

    val current = prepareForExternalEdit()
    val baseOriginal = current.originalPreviewBitmap ?: current.previewBitmap
    if (baseOriginal == null) {
        updateUiState { it.copy(message = "$title 적용할 이미지가 없습니다.") }
        return
    }

    var undoSnapshot: com.projectnuke.keplerstudio.editor.EditorHistorySnapshot? = captureCurrentHistorySnapshot() ?: return
    var ownedBase: Bitmap? = runCatching { baseOriginal.copyOrThrow(Bitmap.Config.ARGB_8888, true) }.getOrElse {
        recycleHistorySnapshot(checkNotNull(undoSnapshot))
        undoSnapshot = null
        updateUiState { it.copy(message = "이미지를 준비하지 못했습니다.") }
        return
    }

    val sourcePath = current.sourcePath
    val baseToken = current.baseContentToken
    val params = current.params
    val engines = current.engineSelection()
    val presetLook = current.presetLook
    val quickEffects = current.activeQuickEffects
    val nextRevision = current.revision + 1

    updateUiState {
        it.copy(
            isBusy = true,
            revision = nextRevision,
            message = "$title 처리 중입니다.",
            flareGuardRuntimeStatus = "규칙 기반 보정으로 처리 중입니다."
        )
    }

    launchManagedEdit { operationToken ->
        var ownedPreview: Bitmap? = null
        try {
            withContext(Dispatchers.Default) {
                val result = NativePhotoCore.nativeApplyFlareGuardInPlace(
                    checkNotNull(ownedBase),
                    mode.ordinal,
                    strength.coerceIn(0f, 1f),
                    nextRevision
                )
                if (result < 0) {
                    error("nativeApplyFlareGuardInPlace failed: $result")
                }
            }
            withContext(Dispatchers.Default) {
                val copy = checkNotNull(ownedBase).copyOrThrow(Bitmap.Config.ARGB_8888, true)
                ownedPreview = copy
                val result = NativePhotoCore.nativeRenderPreviewInPlace(
                    copy,
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
                    params.luminanceNoiseReduction,
                    params.colorNoiseReduction,
                    params.noiseDetailProtection,
                    engines.noiseEngine.nativeId,
                    engines.detailEngine.nativeId,
                    engines.toneEngine.nativeId,
                    engines.hazeEngine.nativeId,
                    nextRevision,
                    presetLook
                )
                if (result < 0) {
                    throw IllegalStateException("native flare preview render failed: code=$result")
                }
                applyActiveQuickEffectsToBitmap(copy, quickEffects, nextRevision)
            }
            val adoptedFlare = checkNotNull(ownedBase)
            val adoptedPreview = checkNotNull(ownedPreview)
            if (isManagedEditCurrent(operationToken, nextRevision) &&
                uiState.value.sourcePath == sourcePath &&
                uiState.value.baseContentToken == baseToken &&
                !isShuttingDown()) {
                updateUiStateAndRecycleReplaced {
                    it.copy(
                        originalPreviewBitmap = adoptedFlare,
                        previewBitmap = adoptedPreview,
                        baseBitmapDirty = true,
                        baseContentToken = newBaseContentToken(),
                        isBusy = false,
                        message = "규칙 기반 보정으로 번짐을 완화했습니다.",
                        flareGuardRuntimeStatus = "규칙 기반 보정으로 번짐을 완화했습니다."
                    )
                }
                commitUndoSnapshot(undoSnapshot!!, clearRedo = true)
                undoSnapshot = null
                ownedBase = null
                ownedPreview = null
                scheduleDraftAutosave()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            if (isManagedEditCurrent(operationToken, nextRevision) &&
                uiState.value.sourcePath == sourcePath &&
                uiState.value.baseContentToken == baseToken &&
                !isShuttingDown()) {
                updateUiState {
                    it.copy(
                        isBusy = false,
                        message = "번짐 완화에 실패했습니다.",
                        flareGuardRuntimeStatus = "번짐 완화에 실패했습니다."
                    )
                }
            }
        } finally {
            ownedBase?.let { if (!it.isRecycled) it.recycle() }
            ownedPreview?.let { if (!it.isRecycled) it.recycle() }
            undoSnapshot?.let(::recycleHistorySnapshot)
        }
    }
}
