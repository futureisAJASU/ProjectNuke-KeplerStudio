package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.editor.BitmapAllocationRejectedException
import com.projectnuke.keplerstudio.editor.AlgorithmContracts
import com.projectnuke.keplerstudio.editor.BakedFeatureProvenance
import com.projectnuke.keplerstudio.editor.BakedFeatureType
import com.projectnuke.keplerstudio.editor.BitmapMemoryBudget
import com.projectnuke.keplerstudio.editor.MaskReservation
import com.projectnuke.keplerstudio.editor.EditParams
import com.projectnuke.keplerstudio.editor.EditorRenderer
import com.projectnuke.keplerstudio.editor.EditorHistorySnapshot
import com.projectnuke.keplerstudio.editor.PendingHistorySnapshot
import com.projectnuke.keplerstudio.editor.EditorUiState
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.ExperimentalLabController
import com.projectnuke.keplerstudio.editor.SubjectSelectionRoute
import com.projectnuke.keplerstudio.editor.HistorySnapshotStorage
import com.projectnuke.keplerstudio.editor.MemoryRetryAction
import com.projectnuke.keplerstudio.editor.FeatureExecutionOutcome
import com.projectnuke.keplerstudio.editor.FeatureMaskSummary
import com.projectnuke.keplerstudio.editor.ModelFeature
import com.projectnuke.keplerstudio.editor.RenderParticipation
import com.projectnuke.keplerstudio.editor.ModelOperationContext
import com.projectnuke.keplerstudio.editor.acquireEditorSnapshot
import com.projectnuke.keplerstudio.editor.ModelAvailabilityRegistry
import com.projectnuke.keplerstudio.editor.ModelRunResult
import com.projectnuke.keplerstudio.editor.PreparedResourceHandoff
import com.projectnuke.keplerstudio.editor.RenderFailedException
import com.projectnuke.keplerstudio.editor.RenderOperation
import com.projectnuke.keplerstudio.editor.RenderResult
import com.projectnuke.keplerstudio.editor.RouteResolver
import com.projectnuke.keplerstudio.editor.SelectionLayer
import com.projectnuke.keplerstudio.editor.SelectionLayerKind
import com.projectnuke.keplerstudio.editor.SelectionPaintMode
import com.projectnuke.keplerstudio.editor.SelectionPaintSettings
import com.projectnuke.keplerstudio.editor.beginMemoryTracking
import com.projectnuke.keplerstudio.editor.analyzeManualMask
import com.projectnuke.keplerstudio.editor.copyOrThrow
import com.projectnuke.keplerstudio.editor.reserveSelectionMaskCopy
import com.projectnuke.keplerstudio.editor.engineSelection
import com.projectnuke.keplerstudio.editor.newBaseContentToken
import com.projectnuke.keplerstudio.editor.refineTrackedSubjectSelectionV2
import com.projectnuke.keplerstudio.editor.successOrThrow
import com.projectnuke.keplerstudio.editor.toFeatureMaskSummary
import com.projectnuke.keplerstudio.editor.withFailedRender
import com.projectnuke.keplerstudio.editor.withSuccessfulRender
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
    prepareForExternalEdit()
    val startSnapshot = acquireEditorSnapshot("subjectSelection") ?: return
    val state = startSnapshot.state
    val base = state.originalPreviewBitmap ?: state.previewBitmap
    val sourcePath = state.sourcePath
    val sourceRevision = state.revision
    val documentEngine = state.correctionEngineState.documentEngine
    val subjectOverride = ExperimentalLabController.debugOverrides().subjectSelection
    val modelCapability =
        ModelAvailabilityRegistry.state.value[ModelFeature.SubjectSelection]
    val subjectResolution = RouteResolver.resolveSubjectRoute(
        documentEngine,
        subjectOverride,
        modelAvailable = modelCapability?.executable == true,
    )
    val subjectAlgorithm = subjectResolution.actualRoute
    val useModel =
        when (subjectAlgorithm) {
            SubjectSelectionRoute.V1,
            SubjectSelectionRoute.ForcedV1Fallback,
            SubjectSelectionRoute.V2ModelAssisted -> true
            SubjectSelectionRoute.V2ManualOrSynthetic -> false
            SubjectSelectionRoute.Compare -> true
        }
    val manualMaskAtEntry =
        if (!useModel) {
            state.selectionLayers
                .firstOrNull { it.id == state.activeSelectionLayerId && it.enabled }
                ?.bitmap
        } else {
            null
        }
    val busyMessage =
        "\uD53C\uC0AC\uCCB4 \uB9C8\uC2A4\uD06C\uB97C \uC0DD\uC131\uD558\uB294 \uC911\uC785\uB2C8\uB2E4."
    if (base == null) {
        startSnapshot.close()
        updateUiState {
            it.copy(
                message =
                    "\uB9C8\uC2A4\uD06C\uB97C \uB9CC\uB4E4 \uC774\uBBF8\uC9C0\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4."
            )
        }
        return
    }
    if (useModel && modelCapability?.executable != true) {
        startSnapshot.close()
        updateUiState {
            it.copy(
                message =
                    "\uBAA8\uB378 \uBCF4\uC870 V2\uB97C \uC0AC\uC6A9\uD560 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4. \uBAA8\uB378\uACFC \uB7F0\uD0C0\uC784 \uC0C1\uD0DC\uB97C \uD655\uC778\uD574 \uC8FC\uC138\uC694."
            )
        }
        return
    }
    if (!useModel && manualMaskAtEntry == null) {
        startSnapshot.close()
        updateUiState {
            it.copy(
                message =
                    "\uC218\uB3D9 \uC120\uD0DD V2\uB97C \uC0AC\uC6A9\uD558\uB824\uBA74 \uC120\uD0DD \uC601\uC5ED\uC744 \uB9CC\uB4E4\uAC70\uB098 \uD65C\uC131\uD654\uD574 \uC8FC\uC138\uC694."
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
    var pendingHistory: PendingHistorySnapshot? =
        prepareHistorySnapshot("subjectSelection", startSnapshot)
    val baseBaseConfig = base.config ?: Bitmap.Config.ARGB_8888
    val manualMaskConfig = manualMaskAtEntry?.config ?: Bitmap.Config.ARGB_8888
    val originalBaseRef = base
    val manualMaskRef = manualMaskAtEntry
    updateUiState { it.copy(isBusy = true, message = busyMessage) }
    launchManagedEditWithPreparedResources(
        { operationToken ->
            var ownedBaseOwned: Bitmap? = null
            var ownedManualMaskOwned: Bitmap? = null
            var pendingHistoryOwned = pendingHistory
            pendingHistory = null
            var undoSnapshotOwned: EditorHistorySnapshot? =
                withContext(Dispatchers.Default) { pendingHistoryOwned?.await() }
            pendingHistoryOwned = null
            var pendingLayerBitmap: Bitmap? = null
            var outputReservation: MaskReservation? = null
            var featureMaskSummary: FeatureMaskSummary? = null
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
                        if (!isManagedEditTokenCurrent(operationToken)) return@withContext null
                        val workerState = startSnapshot.state
                        if (workerState.sourcePath != sourcePath) return@withContext null
                        if (workerState.revision != sourceRevision) return@withContext null
                        val workerBase = workerState.originalPreviewBitmap ?: workerState.previewBitmap
                        if (workerBase == null) return@withContext null
                        ownedBaseOwned =
                            (if (workerBase === originalBaseRef && !originalBaseRef.isRecycled) originalBaseRef else workerBase)
                                .copyOrThrow(baseBaseConfig, false)
                                .also { selectionTracker?.track(it, "subjectSelection:base") }
                        if (manualMaskRef != null && useModel == false) {
                            val workerManualMask = workerState.selectionLayers
                                .firstOrNull { it.id == state.activeSelectionLayerId && it.enabled }
                                ?.bitmap
                            if (workerManualMask != null) {
                                val manualSource = if (workerManualMask === manualMaskRef && !manualMaskRef.isRecycled) manualMaskRef else workerManualMask
                                ownedManualMaskOwned =
                                    manualSource.copyOrThrow(manualMaskConfig, false)
                                        .also { selectionTracker?.track(it, "subjectSelection:manualInput") }
                            }
                        }
                        val rawTracked =
                            if (useModel) {
                                val loaded = RemasterModelSession.ensureEdgeLoaded(appContext())
                                if (
                                    loaded !is
                                        com.projectnuke.keplerstudio.editor.ModelLoadResult.Ready
                                ) {
                                    error(
                                        "Edge Masker load failed: ${loaded::class.java.simpleName}"
                                    )
                                }
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
                                    confidenceMetrics = analyzeManualMask(manual),
                                )
                            }
                        val tracked =
                            rawTracked
                                ?: error(
                                    "\uB9C8\uC2A4\uD06C\uB97C \uC0DD\uC131\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4."
                                )
                        val finalTracked =
                            if (subjectAlgorithm == SubjectSelectionRoute.V1 ||
                                subjectAlgorithm == SubjectSelectionRoute.ForcedV1Fallback
                            ) {
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
                        featureMaskSummary = finalTracked.toFeatureMaskSummary()
                        // Subject-selection adoption: copy the validated mask into an
                        // own bitmap, then release the model mask's diagnostic edge
                        // exactly once (and recycle the source bitmap) via TrackedMask.
                        val ownedMask =
                            try {
                                outputReservation = reserveSelectionMaskCopy(
                                    "subject:$sourceRevision",
                                    finalTracked.bitmap,
                                    Bitmap.Config.ARGB_8888,
                                ) ?: throw BitmapAllocationRejectedException(
                                    BitmapMemoryBudget.bytes(
                                        finalTracked.bitmap.width,
                                        finalTracked.bitmap.height,
                                        Bitmap.Config.ARGB_8888,
                                    )
                                )
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
                            algorithmContracts =
                                current.algorithmContracts.copy(
                                    subjectSelectionContract =
                                        if (
                                            subjectAlgorithm == SubjectSelectionRoute.V1 ||
                                                subjectAlgorithm ==
                                                    SubjectSelectionRoute.ForcedV1Fallback
                                        ) {
                                            AlgorithmContracts.SUBJECT_V1
                                        } else {
                                            AlgorithmContracts.SUBJECT_V2
                                        },
                                ),
                            baseProvenance =
                                current.baseProvenance.append(
                                    BakedFeatureProvenance(
                                        feature = BakedFeatureType.SubjectSelection,
                                        operationId = operationToken.toString(),
                                        sequence =
                                            (current.baseProvenance.operations.lastOrNull()
                                                ?.sequence ?: 0L) + 1L,
                                        requestedRoute = subjectResolution.requestedRoute.name,
                                        actualRoute = subjectAlgorithm.name,
                                        participation =
                                            RenderParticipation(
                                                model = useModel,
                                                manual = !useModel,
                                            ),
                                        capabilityPhase =
                                            ModelAvailabilityRegistry.state.value[
                                                    ModelFeature.SubjectSelection]
                                                ?.phase,
                                        outcome = FeatureExecutionOutcome.Applied,
                                        mask = featureMaskSummary,
                                        stageContract =
                                            if (
                                                subjectAlgorithm == SubjectSelectionRoute.V1 ||
                                                    subjectAlgorithm ==
                                                        SubjectSelectionRoute.ForcedV1Fallback
                                            ) {
                                                AlgorithmContracts.SUBJECT_V1
                                            } else {
                                                AlgorithmContracts.SUBJECT_V2
                                            },
                                        timestampMillis = System.currentTimeMillis(),
                                    )
                                ),
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
                outputReservation?.close()
                undoSnapshotOwned?.let(::recycleHistorySnapshot)
                pendingHistoryOwned?.close()
                selectionTracker?.end()
                startSnapshot.close()
            }
        },
        handoff =
            PreparedResourceHandoff.create(
                "subjectSelection",
                {
                    startSnapshot.close()
                    pendingHistory?.close()
                    pendingHistory = null
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
    applyAsyncSelectionLayerEdit(
        layerId = activeId,
        tag = "deleteSelectionLayer",
        message = "선택 마스크를 삭제했습니다.",
        delete = true,
    )
    return
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
    applyAsyncSelectionLayerEdit(
        layerId = activeId,
        tag = "invertSelectionLayer",
        message = "마스크를 반전했습니다.",
        invert = true,
    )
    return
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
    applyAsyncSelectionLayerEdit(
        layerId = activeId,
        tag = "clearSelectionLayer",
        message = "마스크를 비웠습니다.",
        clear = true,
    )
    return
    if (
        !applySynchronousEditWithHistory { current ->
            var changed = false
            current.copy(
                selectionLayers =
                    current.selectionLayers.map { layer ->
                        if (layer.id == activeId) {
                            // The legacy synchronous branch is unreachable; clear uses the
                            // copy-on-write worker path above.
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
    if (isShuttingDown()) return
    if (!hasActiveBrushStroke()) return
    if (isBrushPreparing()) {
        queueBrushPoint(maskX, maskY)
        return
    }
    val state = uiState.value
    val activeId = state.activeSelectionLayerId
    val layer = state.selectionLayers.firstOrNull { it.id == activeId }
    if (activeId == null || layer == null || !isBrushStrokeCurrent(activeId)) {
        cancelBrushStroke()
        return
    }
    val settings = state.selectionPaintSettings
    val startValid = !brushLastX.isNaN() && !brushLastY.isNaN()
    val painted =
        if (startValid) {
            applyPaintSegment(layer.bitmap, brushLastX, brushLastY, maskX, maskY, settings, brushScratch(layer.bitmap.width))
        } else {
            applyPaintStroke(layer.bitmap, maskX, maskY, settings, brushScratch(layer.bitmap.width))
        }
    setBrushLastPosition(maskX, maskY)
    if (painted) {
        markBrushChanged(true)
        nextBrushPreviewEpoch()
    }
}

/**
 * Paints a continuous interpolated segment between [startX,startY] and [endX,endY] by stamping
 * the brush disk at sub-radius intervals along the line, so fast pointer movement does not
 * leave holes.
 */
internal fun EditorViewModel.applyPaintSegment(
    bitmap: Bitmap,
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    settings: com.projectnuke.keplerstudio.editor.SelectionPaintSettings,
    scratch: IntArray,
): Boolean {
    val radius = settings.sizePx.coerceAtLeast(1f) * 0.5f
    val dx = endX - startX
    val dy = endY - startY
    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
    var changed = false
    // Step by a fraction of the brush radius to guarantee full coverage.
    val step = (radius * 0.5f).coerceAtLeast(1f)
    val steps = (dist / step).toInt().coerceIn(1, 4096)
    for (i in 0..steps) {
        val t = if (steps == 0) 0f else i.toFloat() / steps.toFloat()
        val px = startX + dx * t
        val py = startY + dy * t
        if (applyPaintStroke(bitmap, px, py, settings, scratch)) changed = true
    }
    return changed
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
    prepareForExternalEdit()
    val sourceSnapshot = acquireEditorSnapshot("activeSelectionLocalEdit") ?: return
    val state = sourceSnapshot.state
    val base = state.originalPreviewBitmap ?: state.previewBitmap
    val layer = state.selectionLayers.firstOrNull { it.id == state.activeSelectionLayerId }
    if (base == null || layer == null) {
        sourceSnapshot.close()
        updateUiState { it.copy(message = "적용할 마스크 또는 이미지가 없습니다.") }
        return
    }
    var pendingHistory: PendingHistorySnapshot? =
        prepareHistorySnapshot("activeSelectionLocalEdit", sourceSnapshot)
    val nextRevision = state.revision + 1
    updateUiState {
        it.copy(isBusy = true, revision = nextRevision, message = "마스크 보정을 적용하는 중입니다.")
    }
    launchManagedEditWithPreparedResources(
        { operationToken ->
        var pendingHistoryOwned = pendingHistory
        pendingHistory = null
        var undoSnapshot: EditorHistorySnapshot? =
            withContext(Dispatchers.Default) { pendingHistoryOwned?.await() }
        pendingHistoryOwned = null
        var renderedOriginal: Bitmap? = null
        var renderedPreview: Bitmap? = null
        var previewSuccess: RenderResult.Success? = null
        val selectionTracker =
            beginMemoryTracking(
                "applyActiveSelectionLocalEdit",
                snapshotState = "rendering",
                transientReserveBytes = BitmapMemoryBudget.operationReserveBytes(),
            )
        try {
            renderedOriginal =
                withContext(Dispatchers.Default) {
                    EditorRenderer.render(
                        createRenderRequest(
                            state = sourceSnapshot.state,
                            operation = RenderOperation.SelectionLocal,
                            basePreview = sourceSnapshot.originalPreviewBitmap ?: sourceSnapshot.previewBitmap
                                ?: error("missing selection base"),
                            revision = nextRevision,
                            look = null,
                            quickEffects = emptyList(),
                            selectionLayers = sourceSnapshot.selectionLayers.filter { it.id == layer.id },
                            diagnostics = selectionTracker,
                        )
                    ).successOrThrow().output
                }
            selectionTracker?.track(checkNotNull(renderedOriginal), "selectionEdit:original")
            renderedPreview =
                withContext(Dispatchers.Default) {
                    previewSuccess =
                        EditorRenderer.render(
                            createRenderRequest(
                                state = sourceSnapshot.state,
                                operation = RenderOperation.SelectionNativeBake,
                                basePreview =
                                    renderedOriginal ?: error("missing selection render"),
                                revision = nextRevision,
                                params = EditParams(),
                                selectionLayers = emptyList(),
                                diagnostics = selectionTracker,
                            )
                        ).successOrThrow()
                    checkNotNull(previewSuccess).output
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
                        correctionEngineState =
                            it.correctionEngineState.withSuccessfulRender(
                                state.correctionEngineState.documentEngine,
                                checkNotNull(previewSuccess),
                            ),
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
            val renderFailure = (t as? RenderFailedException)?.failure
            if (isManagedEditCurrent(operationToken, nextRevision)) {
                if (renderFailure != null) {
                    updateUiState {
                        it.copy(
                            correctionEngineState =
                                it.correctionEngineState.withFailedRender(
                                    state.correctionEngineState.documentEngine,
                                    renderFailure,
                                )
                        )
                    }
                }
                updateUiState {
                    it.copy(isBusy = false, message = "마스크 보정 적용에 실패했습니다: ${t.message}")
                }
            }
        } finally {
            pendingHistoryOwned?.close()
            sourceSnapshot.close()
            selectionTracker?.end()
        }
    },
        PreparedResourceHandoff.create(
            "activeSelectionLocalEdit",
            {
                pendingHistory?.close()
                pendingHistory = null
                sourceSnapshot.close()
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
    row: IntArray,
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
    if (row.size < width) return false
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
