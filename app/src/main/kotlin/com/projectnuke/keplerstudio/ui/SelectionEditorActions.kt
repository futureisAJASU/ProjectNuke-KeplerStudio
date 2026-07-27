package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.bridge.NativePhotoCore
import com.projectnuke.keplerstudio.editor.BitmapAllocationRejectedException
import com.projectnuke.keplerstudio.editor.BitmapMemoryBudget
import com.projectnuke.keplerstudio.editor.AlgorithmMode
import com.projectnuke.keplerstudio.editor.EditParams
import com.projectnuke.keplerstudio.editor.EditorHistorySnapshot
import com.projectnuke.keplerstudio.editor.EditorUiState
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.ExperimentalAlgorithmController
import com.projectnuke.keplerstudio.editor.HistorySnapshotStorage
import com.projectnuke.keplerstudio.editor.MemoryRetryAction
import com.projectnuke.keplerstudio.editor.ModelOperationContext
import com.projectnuke.keplerstudio.editor.ModelRunResult
import com.projectnuke.keplerstudio.editor.PreparedResourceHandoff
import com.projectnuke.keplerstudio.editor.SelectionLayer
import com.projectnuke.keplerstudio.editor.SelectionLayerKind
import com.projectnuke.keplerstudio.editor.SelectionPaintMode
import com.projectnuke.keplerstudio.editor.SelectionPaintSettings
import com.projectnuke.keplerstudio.editor.beginMemoryTracking
import com.projectnuke.keplerstudio.editor.copyOrThrow
import com.projectnuke.keplerstudio.editor.createScaledBitmapOrThrow
import com.projectnuke.keplerstudio.editor.engineSelection
import com.projectnuke.keplerstudio.editor.newBaseContentToken
import com.projectnuke.keplerstudio.editor.renderEditedPreview
import com.projectnuke.keplerstudio.editor.refineTrackedSubjectSelectionV2
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

fun EditorViewModel.addSubjectSelectionFromEdgeModel() {
    if (!canEnterEditorAction(allowMaskSupersession = true)) return
    invalidateSelectionPreview()
    val state = prepareForExternalEdit()
    val base = state.originalPreviewBitmap ?: state.previewBitmap
    val sourcePath = state.sourcePath
    val sourceRevision = state.revision
    val subjectAlgorithm = ExperimentalAlgorithmController.current().subjectSelection
    val modelAvailable =
        RemasterModelSession.activeModel?.id == "edge_masker" &&
            RemasterModelSession.isModelLoaded
    val manualMaskAtEntry =
        if (subjectAlgorithm == AlgorithmMode.V1) {
            null
        } else {
            state.selectionLayers
                .firstOrNull { it.id == state.activeSelectionLayerId && it.enabled }
                ?.bitmap
        }
    val busyMessage =
        "\uD53C\uC0AC\uCCB4 \uB9C8\uC2A4\uD06C\uB97C \uC0DD\uC131\uD558\uB294 \uC911\uC785\uB2C8\uB2E4."
    if (base == null) {
        updateUiState {
            it.copy(
                message =
                    "\uB9C8\uC2A4\uD06C\uB97C \uB9CC\uB4E4 \uC774\uBBF8\uC9C0\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4."
            )
        }
        return
    }
    if (!modelAvailable && manualMaskAtEntry == null) {
        updateUiState {
            it.copy(
                message =
                    "Edge Masker \uBAA8\uB378\uC744 \uBA3C\uC800 \uB85C\uB4DC\uD574 \uC8FC\uC138\uC694."
            )
        }
        return
    }

    val selectionTracker =
        beginMemoryTracking(
            "addSubjectSelection",
            snapshotState = "inferring",
            transientReserveBytes = BitmapMemoryBudget.operationReserveBytes(),
        )
    var undoSnapshot: EditorHistorySnapshot? =
        captureCurrentHistorySnapshot(HistorySnapshotStorage.Exact)
    var ownedBase: Bitmap? =
        try {
            base.copyOrThrow(mutable = false).also {
                selectionTracker?.track(it, "subjectSelection:base")
            }
        } catch (t: Throwable) {
            selectionTracker?.end()
            undoSnapshot?.let(::recycleHistorySnapshot)
            updateUiState { it.copy(message = "마스크 입력 이미지를 준비하지 못했습니다.") }
            if (t is BitmapAllocationRejectedException)
                requestAllocationRecovery(MemoryRetryAction.SubjectSelection, t.requiredBytes)
            return
        }
    var ownedManualMask: Bitmap? =
        try {
            manualMaskAtEntry?.copyOrThrow(mutable = false)?.also {
                selectionTracker?.track(it, "subjectSelection:manualInput")
            }
        } catch (t: Throwable) {
            ownedBase?.takeUnless(Bitmap::isRecycled)?.recycle()
            ownedBase = null
            selectionTracker?.end()
            undoSnapshot?.let(::recycleHistorySnapshot)
            updateUiState { it.copy(message = "Selection mask preparation failed.") }
            if (t is BitmapAllocationRejectedException)
                requestAllocationRecovery(MemoryRetryAction.SubjectSelection, t.requiredBytes)
            return
        }
    updateUiState { it.copy(isBusy = true, message = busyMessage) }
    launchManagedEditWithPreparedResources(
        { operationToken ->
            var ownedBaseOwned = ownedBase
            ownedBase = null
            var undoSnapshotOwned = undoSnapshot
            undoSnapshot = null
            var ownedManualMaskOwned = ownedManualMask
            ownedManualMask = null
            var pendingLayerBitmap: Bitmap? = null
            try {
                val inferenceJob = currentCoroutineContext()[Job]
                val modelOperation =
                    ModelOperationContext(
                        operationToken = operationToken,
                        documentGeneration = currentDocumentGeneration(),
                        documentIdentity = sourcePath,
                        isCurrent = { token, generation ->
                            val live = uiState.value
                            isManagedEditTokenCurrent(token) &&
                                generation == currentDocumentGeneration() &&
                                live.sourcePath == sourcePath &&
                                live.baseContentToken == state.baseContentToken &&
                                live.revision == sourceRevision
                        },
                        isCancelled = { inferenceJob?.isActive == false || isShuttingDown() },
                    )
                val layer =
                    withContext(Dispatchers.Default) {
                        val rawTracked =
                            if (modelAvailable) {
                                val maskResult =
                                    RemasterModelSession.createForegroundMaskResult(
                                        checkNotNull(ownedBaseOwned),
                                        selectionTracker,
                                        modelOperation,
                                    )
                                (maskResult as? ModelRunResult.Success)?.value
                            } else {
                                val manual =
                                    checkNotNull(ownedManualMaskOwned).also {
                                        ownedManualMaskOwned = null
                                    }
                                com.projectnuke.keplerstudio.editor.TrackedMask.acquire(
                                    bitmap = manual,
                                    scope = selectionTracker,
                                    owner = "subjectSelectionV2:manualInput",
                                    modelId = "manual-selection",
                                    modelVersion = "v2",
                                    operationToken = operationToken,
                                    documentGeneration = modelOperation.documentGeneration,
                                    confidenceMetrics =
                                        com.projectnuke.keplerstudio.editor.ModelConfidence(
                                            wholeImageMean = 1f,
                                            peak = 1f,
                                            activeRegionMean = 1f,
                                            activeRegionPercentile = 1f,
                                            affectedAreaRatio = 1f,
                                            backgroundLeakage = 0f,
                                            finalPolicy = 1f,
                                        ),
                                )
                            }
                        val tracked =
                            rawTracked
                                ?: error(
                                    "\uB9C8\uC2A4\uD06C\uB97C \uC0DD\uC131\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4."
                                )
                        val finalTracked =
                            if (subjectAlgorithm == AlgorithmMode.V1) {
                                tracked
                            } else {
                                refineTrackedSubjectSelectionV2(
                                    tracked,
                                    checkNotNull(ownedBaseOwned).width,
                                    checkNotNull(ownedBaseOwned).height,
                                    selectionTracker,
                                    modelOperation,
                                )
                            }
                        // Subject-selection adoption: copy the validated mask into an
                        // own bitmap, then release the model mask's diagnostic edge
                        // exactly once (and recycle the source bitmap) via TrackedMask.
                        val ownedMask =
                            try {
                                finalTracked.bitmap.copyOrThrow(Bitmap.Config.ARGB_8888, true)
                            } finally {
                                finalTracked.recycleAndRelease()
                            }
                        if (!ownedMask.hasForegroundPixel()) {
                            ownedMask.recycle()
                            return@withContext null
                        }
                        pendingLayerBitmap = ownedMask
                        selectionTracker?.track(ownedMask, "subjectSelection:mask")
                        SelectionLayer(
                            id = newSelectionId(),
                            name = "\uD53C\uC0AC\uCCB4 \uB9C8\uC2A4\uD06C",
                            kind = SelectionLayerKind.Subject,
                            bitmap = ownedMask,
                        )
                    }
                        ?: run {
                            pendingLayerBitmap?.recycle()
                            pendingLayerBitmap = null
                            undoSnapshotOwned?.let(::recycleHistorySnapshot)
                            undoSnapshotOwned = null
                            val current = uiState.value
                            if (
                                isManagedEditCurrent(operationToken, sourceRevision) &&
                                    current.sourcePath == sourcePath &&
                                    current.revision == sourceRevision
                            ) {
                                updateUiState {
                                    it.copy(
                                        isBusy = false,
                                        message =
                                            "\uD53C\uC0AC\uCCB4\uB97C \uAC10\uC9C0\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4.",
                                    )
                                }
                            }
                            return@launchManagedEditWithPreparedResources
                        }
                var applied = false
                updateUiState { current ->
                    if (
                        !isManagedEditCurrent(operationToken, sourceRevision) ||
                            current.sourcePath != sourcePath ||
                            current.revision != sourceRevision
                    ) {
                        current
                    } else {
                        applied = true
                        current.copy(
                            isBusy = false,
                            selectionLayers = current.selectionLayers + layer,
                            activeSelectionLayerId = layer.id,
                            message =
                                "\uD53C\uC0AC\uCCB4 \uB9C8\uC2A4\uD06C\uB97C \uCD94\uAC00\uD588\uC2B5\uB2C8\uB2E4.",
                        )
                    }
                }
                if (!applied) {
                    pendingLayerBitmap?.recycle()
                    pendingLayerBitmap = null
                    undoSnapshotOwned?.let(::recycleHistorySnapshot)
                    undoSnapshotOwned = null
                    val current = uiState.value
                    return@launchManagedEditWithPreparedResources
                }
                settleAdoptedEditHistory(undoSnapshotOwned)
                undoSnapshotOwned = null
                pendingLayerBitmap = null
                markMemoryRetrySucceeded(MemoryRetryAction.SubjectSelection)
                persistDraftSnapshot()
            } catch (ce: CancellationException) {
                pendingLayerBitmap?.recycle()
                undoSnapshotOwned?.let(::recycleHistorySnapshot)
                undoSnapshotOwned = null
                throw ce
            } catch (t: Throwable) {
                pendingLayerBitmap?.recycle()
                undoSnapshotOwned?.let(::recycleHistorySnapshot)
                undoSnapshotOwned = null
                val current = uiState.value
                if (
                    isManagedEditCurrent(operationToken, sourceRevision) &&
                        current.sourcePath == sourcePath &&
                        current.revision == sourceRevision
                ) {
                    updateUiState {
                        it.copy(
                            isBusy = false,
                            message =
                                "\uD53C\uC0AC\uCCB4 \uB9C8\uC2A4\uD06C \uC0DD\uC131\uC5D0 \uC2E4\uD328\uD588\uC2B5\uB2C8\uB2E4: ${t.message}",
                        )
                    }
                }
                if (
                    t is BitmapAllocationRejectedException &&
                        current.sourcePath == sourcePath &&
                        current.revision == sourceRevision
                ) {
                    requestAllocationRecovery(MemoryRetryAction.SubjectSelection, t.requiredBytes)
                }
            } finally {
                ownedBaseOwned?.takeIf { !it.isRecycled }?.recycle()
                ownedManualMaskOwned?.takeIf { !it.isRecycled }?.recycle()
                undoSnapshotOwned?.let(::recycleHistorySnapshot)
                selectionTracker?.end()
            }
        },
        handoff =
            PreparedResourceHandoff.create(
                "subjectSelection",
                {
                    ownedBase?.takeIf { !it.isRecycled }?.recycle()
                    ownedBase = null
                },
                {
                    ownedManualMask?.takeIf { !it.isRecycled }?.recycle()
                    ownedManualMask = null
                },
                {
                    undoSnapshot?.let(::recycleHistorySnapshot)
                    undoSnapshot = null
                },
                { selectionTracker?.end() },
                {
                    val live = uiState.value
                    if (live.revision == sourceRevision &&
                        live.sourcePath == sourcePath &&
                        live.baseContentToken == state.baseContentToken) {
                        updateUiState { it.copy(isBusy = false) }
                    }
                },
            ),
    )
}

fun EditorViewModel.createBrushSelection() {
    createBrushSelectionInternal()
}

fun EditorViewModel.selectSelectionLayer(id: String) {
    if (!canEnterEditorAction(allowMaskSupersession = true)) return
    invalidateSelectionPreview()
    updateUiState { it.copy(activeSelectionLayerId = id, message = "마스크를 선택했습니다.") }
}

fun EditorViewModel.deleteActiveSelectionLayer() {
    if (!canEnterEditorAction(allowMaskSupersession = true)) return
    invalidateSelectionPreview()
    val state = prepareForExternalEdit()
    val activeId = state.activeSelectionLayerId ?: return
    if (
        !applySynchronousEditWithHistory { current ->
            val nextLayers = current.selectionLayers.filterNot { it.id == activeId }
            current.copy(
                selectionLayers = nextLayers,
                activeSelectionLayerId = nextLayers.lastOrNull()?.id,
                message = "선택한 마스크를 삭제했습니다.",
            )
        }
    )
        return
    persistDraftSnapshot()
}

fun EditorViewModel.invertActiveSelectionLayer() {
    if (!canEnterEditorAction(allowMaskSupersession = true)) return
    invalidateSelectionPreview()
    val state = prepareForExternalEdit()
    val activeId =
        state.activeSelectionLayerId
            ?: run {
                updateUiState { it.copy(message = "먼저 마스크를 선택해 주세요.") }
                return
            }
    if (
        !applySynchronousEditWithHistory { current ->
            current.copy(
                selectionLayers =
                    current.selectionLayers.map { layer ->
                        if (layer.id == activeId) layer.copy(inverted = !layer.inverted) else layer
                    },
                message = "마스크 반전을 전환했습니다.",
            )
        }
    )
        return
    persistDraftSnapshot()
}

fun EditorViewModel.clearActiveSelectionLayer() {
    if (!canEnterEditorAction(allowMaskSupersession = true)) return
    invalidateSelectionPreview()
    val state = prepareForExternalEdit()
    val activeId =
        state.activeSelectionLayerId
            ?: run {
                updateUiState {
                    it.copy(
                        message =
                            "\uBA3C\uC800 \uB9C8\uC2A4\uD06C\uB97C \uC120\uD0DD\uD574 \uC8FC\uC138\uC694."
                    )
                }
                return
            }
    if (
        !applySynchronousEditWithHistory { current ->
            var changed = false
            current.copy(
                selectionLayers =
                    current.selectionLayers.map { layer ->
                        if (layer.id == activeId) {
                            layer.bitmap.eraseColor(0xFF000000.toInt())
                            changed = true
                            layer
                        } else {
                            layer
                        }
                    },
                revision = current.revision + if (changed) 1 else 0,
                message = "\uB9C8\uC2A4\uD06C\uB97C \uBE44\uC6E0\uC2B5\uB2C8\uB2E4.",
            )
        }
    )
        return
    persistDraftSnapshot()
}

fun EditorViewModel.updateSelectionPaintSettings(
    transform: (SelectionPaintSettings) -> SelectionPaintSettings
) {
    if (!canEnterEditorAction(allowMaskSupersession = true)) return
    updateUiState { it.copy(selectionPaintSettings = transform(it.selectionPaintSettings)) }
}

fun EditorViewModel.paintActiveSelectionAt(maskX: Float, maskY: Float) {
    if (!canEnterEditorAction(allowMaskSupersession = true)) return
    if (!hasActiveBrushStroke()) return
    val state = uiState.value
    val activeId = state.activeSelectionLayerId
    val layer = state.selectionLayers.firstOrNull { it.id == activeId }
    if (activeId == null || layer == null || !isBrushStrokeCurrent(activeId)) {
        cancelBrushStroke()
        return
    }
    val painted = applyPaintStroke(layer.bitmap, maskX, maskY, state.selectionPaintSettings)
    if (painted) {
        markBrushChanged(true)
        nextBrushPreviewEpoch()
    }
}

fun EditorViewModel.updateActiveSelectionParams(transform: (EditParams) -> EditParams) {
    if (!canEnterEditorAction(allowMaskSupersession = true)) return
    invalidateSelectionPreview()
    val state = prepareForExternalEdit()
    val activeId =
        state.activeSelectionLayerId
            ?: run {
                updateUiState { it.copy(message = "먼저 마스크를 선택해 주세요.") }
                return
            }
    updateUiState { current ->
        current.copy(
            selectionLayers =
                current.selectionLayers.map { layer ->
                    if (layer.id == activeId) layer.copy(localParams = transform(layer.localParams))
                    else layer
                },
            message = "마스크 보정값을 변경했습니다.",
        )
    }
}

fun EditorViewModel.applyActiveSelectionLocalEdit() {
    if (isShuttingDown()) return
    if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable()) return
    invalidateSelectionPreview()
    val state = prepareForExternalEdit()
    val base = state.originalPreviewBitmap ?: state.previewBitmap
    val layer = state.selectionLayers.firstOrNull { it.id == state.activeSelectionLayerId }
    if (base == null || layer == null) {
        updateUiState { it.copy(message = "적용할 마스크 또는 이미지가 없습니다.") }
        return
    }
    var undoSnapshot: EditorHistorySnapshot? = captureCurrentHistorySnapshot()
    val nextRevision = state.revision + 1
    updateUiState {
        it.copy(isBusy = true, revision = nextRevision, message = "마스크 보정을 적용하는 중입니다.")
    }
    launchManagedEditWithPreparedResources(
        { operationToken ->
        var renderedOriginal: Bitmap? = null
        var renderedPreview: Bitmap? = null
        val selectionTracker =
            beginMemoryTracking(
                "applyActiveSelectionLocalEdit",
                snapshotState = "rendering",
                transientReserveBytes = BitmapMemoryBudget.operationReserveBytes(),
            )
        try {
            renderedOriginal =
                withContext(Dispatchers.Default) {
                    renderSelectionLocalEdit(base, state, layer, nextRevision)
                }
            selectionTracker?.track(checkNotNull(renderedOriginal), "selectionEdit:original")
            renderedPreview =
                withContext(Dispatchers.Default) {
                    renderEditedPreview(
                        basePreview = renderedOriginal ?: error("missing selection render"),
                        params = EditParams(),
                        engines = state.engineSelection(),
                        revision = nextRevision,
                        look = state.presetLook,
                        quickEffects = state.activeQuickEffects,
                    )
                }
            selectionTracker?.track(checkNotNull(renderedPreview), "selectionEdit:preview")
            if (isManagedEditCurrent(operationToken, nextRevision)) {
                val adoptedOriginal = renderedOriginal ?: error("missing selection original")
                val adoptedPreview = renderedPreview ?: error("missing selection preview")
                updateUiStateAndRecycleReplaced {
                    it.copy(
                        // The selection composite is baked into the base bitmap; neutral params
                        // avoid export double-application.
                        params = EditParams(),
                        originalPreviewBitmap = adoptedOriginal,
                        previewBitmap = adoptedPreview,
                        baseBitmapDirty = true,
                        baseContentToken = newBaseContentToken(),
                        isBusy = false,
                        message = "선택한 마스크 보정을 적용했습니다.",
                    )
                }
                settleAdoptedEditHistory(undoSnapshot)
                undoSnapshot = null
                markParamsSuccessfullyRendered(EditParams())
                renderedOriginal = null
                renderedPreview = null
                persistDraftSnapshot()
            } else {
                renderedOriginal?.recycle()
                renderedOriginal = null
                renderedPreview?.recycle()
                renderedPreview = null
            }
        } catch (ce: CancellationException) {
            renderedOriginal?.recycle()
            renderedPreview?.recycle()
            throw ce
        } catch (t: Throwable) {
            renderedOriginal?.recycle()
            renderedPreview?.recycle()
            if (isManagedEditCurrent(operationToken, nextRevision)) {
                updateUiState {
                    it.copy(isBusy = false, message = "마스크 보정 적용에 실패했습니다: ${t.message}")
                }
            }
        } finally {
            selectionTracker?.end()
        }
    },
        PreparedResourceHandoff.create(
            "activeSelectionLocalEdit",
            {
                undoSnapshot?.let(::recycleHistorySnapshot)
                undoSnapshot = null
            },
            {
                val live = uiState.value
                if (live.revision == nextRevision &&
                    live.baseContentToken == state.baseContentToken &&
                    live.sourcePath == state.sourcePath) {
                    updateUiState { it.copy(isBusy = false) }
                }
            },
        ),
    )
}

private fun applyPaintStroke(
    bitmap: Bitmap,
    cx: Float,
    cy: Float,
    settings: SelectionPaintSettings,
): Boolean {
    val radius = settings.sizePx.coerceAtLeast(1f) * 0.5f
    val left = (cx - radius).toInt().coerceIn(0, bitmap.width - 1)
    val top = (cy - radius).toInt().coerceIn(0, bitmap.height - 1)
    val right = (cx + radius).toInt().coerceIn(0, bitmap.width - 1)
    val bottom = (cy + radius).toInt().coerceIn(0, bitmap.height - 1)
    val width = right - left + 1
    if (width <= 0 || bottom < top) return false

    val feather = settings.feather.coerceIn(0f, 0.98f)
    val hardRadius = radius * (1f - feather)
    val row = IntArray(width)
    var changed = false
    for (y in top..bottom) {
        bitmap.getPixels(row, 0, width, left, y, width, 1)
        for (i in 0 until width) {
            val x = left + i
            val dist = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
            if (dist > radius) continue
            val fade =
                if (dist <= hardRadius) 1f
                else {
                    val t =
                        ((dist - hardRadius) / (radius - hardRadius).coerceAtLeast(1f)).coerceIn(
                            0f,
                            1f,
                        )
                    1f - t * t * (3f - 2f * t)
                }
            val old = (row[i] ushr 16) and 0xff
            val delta = (255f * settings.strength.coerceIn(0f, 1f) * fade).roundToInt()
            val next =
                when (settings.mode) {
                    SelectionPaintMode.Add -> (old + delta).coerceIn(0, 255)
                    SelectionPaintMode.Remove -> (old - delta).coerceIn(0, 255)
                }
            if (next != old) changed = true
            row[i] = -0x1000000 or (next shl 16) or (next shl 8) or next
        }
        bitmap.setPixels(row, 0, width, left, y, width, 1)
    }
    return changed
}

private suspend fun renderSelectionLocalEdit(
    base: Bitmap,
    state: EditorUiState,
    layer: SelectionLayer,
    revision: Int,
): Bitmap {
    var global: Bitmap? = null
    var local: Bitmap? = null
    try {
        global = renderWithParams(base, state.params, state, revision)
        local =
            renderWithParams(base, mergeParams(state.params, layer.localParams), state, revision)
        return blendWithSelectionMask(local, global, layer)
    } catch (t: Throwable) {
        global?.recycle()
        local?.recycle()
        throw t
    }
}

private fun mergeParams(base: EditParams, local: EditParams): EditParams =
    EditParams(
        exposure = (base.exposure + local.exposure).coerceIn(-1f, 1f),
        contrast = (base.contrast + local.contrast).coerceIn(-1f, 1f),
        shadows = (base.shadows + local.shadows).coerceIn(-1f, 1f),
        highlights = (base.highlights + local.highlights).coerceIn(-1f, 1f),
        whites = (base.whites + local.whites).coerceIn(-1f, 1f),
        blacks = (base.blacks + local.blacks).coerceIn(-1f, 1f),
        temperature = (base.temperature + local.temperature).coerceIn(-1f, 1f),
        tint = (base.tint + local.tint).coerceIn(-1f, 1f),
        saturation = (base.saturation + local.saturation).coerceIn(-1f, 1f),
        vibrance = (base.vibrance + local.vibrance).coerceIn(-1f, 1f),
        clarity = (base.clarity + local.clarity).coerceIn(-1f, 1f),
        dehaze = (base.dehaze + local.dehaze).coerceIn(-1f, 1f),
        sharpness = (base.sharpness + local.sharpness).coerceIn(0f, 1f),
        noiseReduction = (base.noiseReduction + local.noiseReduction).coerceIn(0f, 1f),
        luminanceNoiseReduction =
            (base.luminanceNoiseReduction + local.luminanceNoiseReduction).coerceIn(0f, 1f),
        colorNoiseReduction =
            (base.colorNoiseReduction + local.colorNoiseReduction).coerceIn(0f, 1f),
        noiseDetailProtection =
            (base.noiseDetailProtection + local.noiseDetailProtection - 0.50f).coerceIn(0f, 1f),
    )

private suspend fun renderWithParams(
    base: Bitmap,
    params: EditParams,
    state: EditorUiState,
    revision: Int,
): Bitmap {
    val out = base.copyOrThrow(Bitmap.Config.ARGB_8888, true)
    val result =
        NativePhotoCore.nativeRenderPreviewInPlace(
            out,
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
            state.noiseEngine.nativeId,
            state.detailEngine.nativeId,
            state.toneEngine.nativeId,
            state.hazeEngine.nativeId,
            revision,
        )
    if (result < 0) {
        out.recycle()
        throw IllegalStateException("native selection render failed: code=$result")
    }
    return out
}

private fun blendWithSelectionMask(local: Bitmap, global: Bitmap, layer: SelectionLayer): Bitmap {
    val width = global.width
    val height = global.height
    val scaledMask =
        if (layer.bitmap.width == width && layer.bitmap.height == height) {
            layer.bitmap
        } else {
            createScaledBitmapOrThrow(layer.bitmap, width, height, true)
        }
    val localRow = IntArray(width)
    val globalRow = IntArray(width)
    val maskRow = IntArray(width)
    try {
        for (y in 0 until height) {
            local.getPixels(localRow, 0, width, 0, y, width, 1)
            global.getPixels(globalRow, 0, width, 0, y, width, 1)
            scaledMask.getPixels(maskRow, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val raw = ((maskRow[x] ushr 16) and 0xff) / 255f
                val a = (if (layer.inverted) 1f - raw else raw) * layer.opacity.coerceIn(0f, 1f)
                globalRow[x] = blendArgb(localRow[x], globalRow[x], a)
            }
            global.setPixels(globalRow, 0, width, 0, y, width, 1)
        }
    } finally {
        if (scaledMask !== layer.bitmap) scaledMask.recycle()
    }
    local.recycle()
    return global
}

private fun blendArgb(foreground: Int, background: Int, alpha: Float): Int {
    val inv = 1f - alpha.coerceIn(0f, 1f)
    val a = 0xff
    val r =
        (((foreground ushr 16) and 0xff) * alpha + ((background ushr 16) and 0xff) * inv)
            .roundToInt()
            .coerceIn(0, 255)
    val g =
        (((foreground ushr 8) and 0xff) * alpha + ((background ushr 8) and 0xff) * inv)
            .roundToInt()
            .coerceIn(0, 255)
    val b =
        ((foreground and 0xff) * alpha + (background and 0xff) * inv).roundToInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

private fun Bitmap.hasForegroundPixel(): Boolean {
    val threshold = 8
    val row = IntArray(width)
    for (y in 0 until height) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (pixel in row) {
            val red = (pixel ushr 16) and 0xff
            if (red > threshold) return true
        }
    }
    return false
}

private fun newSelectionId(): String = "sel_" + UUID.randomUUID().toString().take(8)
