package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.heifwriter.HeifWriter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.projectnuke.keplerstudio.bridge.NativePhotoCore
import java.util.ArrayDeque
import java.util.Collections
import java.io.File
import java.io.FileOutputStream
import java.util.IdentityHashMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val _uiState = MutableStateFlow(
        EditorUiState(nativeVersion = runCatching { NativePhotoCore.nativeVersion() }.getOrElse { "native load failed: ${it.message}" })
    )
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var nativeSession: Long = 0L
    private var renderJob: Job? = null
    private var exportJob: Job? = null
    internal var selectionLivePreviewJob: Job? = null
    private var draftSaveJob: Job? = null
    private val draftSaveMutex = Mutex()
    private var paramUndoWindowJob: Job? = null
    private var paramUndoWindowOpen: Boolean = false
    private var pendingParamUndoSnapshot: EditorHistorySnapshot? = null
    private var paramUndoSnapshotCommitted: Boolean = false
    private var lastSuccessfullyRenderedParams: EditParams = EditParams()
    private var activeParamRenderRevision: Int? = null
    private var restoreDraftToken: Long = 0L
    private val undoHistory = ArrayDeque<EditorHistorySnapshot>()
    private val redoHistory = ArrayDeque<EditorHistorySnapshot>()

    fun updateUiState(transform: (EditorUiState) -> EditorUiState) {
        updateUiStateAndRecycleReplaced(transform)
    }

    private fun updateUiStateAndRecycleReplaced(transform: (EditorUiState) -> EditorUiState) {
        var previousState: EditorUiState? = null
        var nextState: EditorUiState? = null
        _uiState.update { current ->
            previousState = current
            transform(current).also { nextState = it }
        }
        val previous = previousState ?: return
        val next = nextState ?: return
        recycleReplacedLiveBitmaps(previous, next)
    }

    private fun recycleReplacedLiveBitmaps(previous: EditorUiState, next: EditorUiState) {
        val retained = identityBitmapSet()
        next.previewBitmap?.let(retained::add)
        next.originalPreviewBitmap?.let(retained::add)
        next.selectionLayers.forEach { retained.add(it.bitmap) }

        val candidates = identityBitmapSet()
        previous.previewBitmap?.let(candidates::add)
        previous.originalPreviewBitmap?.let(candidates::add)
        previous.selectionLayers.forEach { candidates.add(it.bitmap) }

        candidates
            .filterNot { it in retained }
            .forEach { recycleBitmapSoon(it) }
    }

    private fun recycleBitmapSoon(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        viewModelScope.launch {
            delay(150L)
            if (!bitmap.isRecycled && !isBitmapRetainedByCurrentState(bitmap)) bitmap.recycle()
        }
    }

    private fun isBitmapRetainedByCurrentState(bitmap: Bitmap): Boolean {
        val state = _uiState.value
        if (state.previewBitmap === bitmap) return true
        if (state.originalPreviewBitmap === bitmap) return true
        return state.selectionLayers.any { it.bitmap === bitmap }
    }

    private fun recycleLiveStateBitmapsNow(state: EditorUiState) {
        val bitmaps = identityBitmapSet()
        state.previewBitmap?.let(bitmaps::add)
        state.originalPreviewBitmap?.let(bitmaps::add)
        state.selectionLayers.forEach { bitmaps.add(it.bitmap) }
        bitmaps.forEach { if (!it.isRecycled) it.recycle() }
    }

    fun recordUserEditForUndo(clearRedo: Boolean = true) {
        pushUndoSnapshot(clearRedo = clearRedo)
    }

    fun persistDraftSnapshot() {
        forceDraftSaveAsync()
    }

    suspend fun persistDraftSnapshotNow(): Boolean {
        draftSaveJob?.cancelAndJoin()
        return persistDraftSnapshotInternal()
    }

    private fun scheduleDraftAutosave(delayMs: Long = 2000L) {
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(delayMs)
            persistDraftSnapshotInternal()
        }
    }

    private fun forceDraftSaveAsync() {
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            persistDraftSnapshotInternal()
        }
    }

    private suspend fun persistDraftSnapshotInternal(): Boolean {
        val context = getApplication<Application>()
        val draftState = _uiState.value
        val payload = try {
            withContext(Dispatchers.Default) {
                createDraftSavePayload(draftState)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            logDraftSaveFailure(t)
            updateUiStateAndRecycleReplaced {
                it.copy(message = "\uc784\uc2dc \uc800\uc7a5\uc5d0 \uc2e4\ud328\ud588\uc2b5\ub2c8\ub2e4. \ud3b8\uc9d1\uc740 \uacc4\uc18d\ud560 \uc218 \uc788\uc2b5\ub2c8\ub2e4.")
            }
            return false
        }
        val saved = try {
            withContext(Dispatchers.IO) {
                draftSaveMutex.withLock {
                    saveDraftSnapshot(context, payload)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            logDraftSaveFailure(t)
            updateUiStateAndRecycleReplaced {
                it.copy(message = "\uc784\uc2dc \uc800\uc7a5\uc5d0 \uc2e4\ud328\ud588\uc2b5\ub2c8\ub2e4. \ud3b8\uc9d1\uc740 \uacc4\uc18d\ud560 \uc218 \uc788\uc2b5\ub2c8\ub2e4.")
            }
            null
        } finally {
            payload.dirtyBitmapCopy?.takeIf { !it.isRecycled }?.recycle()
        } ?: return false
        updateUiStateAndRecycleReplaced {
            it.copy(
                draftSavedAtMillis = saved.savedAtMillis,
                draftSourcePath = saved.sourcePath
            )
        }
        return true
    }

    internal fun markParamsSuccessfullyRendered(params: EditParams) {
        lastSuccessfullyRenderedParams = params
    }

    fun appContext(): Context = getApplication<Application>().applicationContext

    fun appApplication(): Application = getApplication()

    init {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val retention = loadExportHistoryRetention(context)
            val engines = loadEngineSelection(context)
            val saved = withContext(Dispatchers.IO) {
                val savedFromPrefs = loadSavedExportsFromPrefs(context)
                val seed = if (savedFromPrefs.isEmpty()) rebuildSavedExportsFromMediaStore(context) else savedFromPrefs
                pruneSavedExportsIfNeeded(context, seed, retention)
            }
            updateUiStateAndRecycleReplaced {
                it.copy(
                    savedExports = saved,
                    exportHistoryRetention = retention,
                    noiseEngine = engines.noiseEngine,
                    detailEngine = engines.detailEngine,
                    toneEngine = engines.toneEngine,
                    hazeEngine = engines.hazeEngine
                )
            }
            restoreDraftIfAvailable(context)
        }
    }

    fun openImage(uri: Uri) {
        abortPendingParameterEdit()
        renderJob?.cancel()
        exportJob?.cancel()
        selectionLivePreviewJob?.cancel()
        closeParamUndoWindow()
        val openToken = restoreDraftToken + 1L
        restoreDraftToken = openToken
        val invalidateRevision = _uiState.value.revision + 1
        val openingMessage = "\uC774\uBBF8\uC9C0\uB97C \uC5EC\uB294 \uC911\uC785\uB2C8\uB2E4"
        updateUiStateAndRecycleReplaced { it.copy(isBusy = true, revision = invalidateRevision, message = openingMessage) }

        viewModelScope.launch {
            var preview: Bitmap? = null
            var createdSession = 0L
            var sourceFile: File? = null
            try {
                val context = getApplication<Application>()
                sourceFile = withContext(Dispatchers.IO) { copyUriToCache(context, uri) }
                preview = withContext(Dispatchers.IO) {
                    decodeSampledMutableBitmapWithExif(sourceFile.absolutePath, maxSide = 2048)
                }
                if (openToken != restoreDraftToken) {
                    preview?.recycle()
                    preview = null
                    sourceFile?.delete()
                    return@launch
                }
                val decodedPreview = preview!!
                Log.i(FLARE_GUARD_AI_TAG, "Opened image with EXIF orientation: ${sourceFile.name} preview=${decodedPreview.width}x${decodedPreview.height}")

                createdSession = NativePhotoCore.nativeCreateSession(sourceFile.absolutePath)
                if (openToken != restoreDraftToken || _uiState.value.revision != invalidateRevision) {
                    preview?.recycle()
                    preview = null
                    sourceFile?.delete()
                    releaseNativeSessionHandle(createdSession)
                    createdSession = 0L
                    return@launch
                }
                val previousSession = nativeSession
                nativeSession = createdSession
                createdSession = 0L
                releaseNativeSessionHandle(previousSession)
                lastSuccessfullyRenderedParams = EditParams()
                clearEditHistory()

                updateUiStateAndRecycleReplaced {
                    it.copy(
                        isBusy = false,
                        sourcePath = sourceFile.absolutePath,
                        baseBitmapDirty = false,
                        originalPreviewBitmap = decodedPreview,
                        previewBitmap = decodedPreview,
                        cropState = CropState(),
                        selectionLayers = emptyList(),
                        activeSelectionLayerId = null,
                        selectionPaintSettings = SelectionPaintSettings(),
                        showSelectionOverlay = true,
                        viewport = ViewportState(),
                        activeQuickEffects = emptyList(),
                        params = EditParams(),
                        presetLook = null,
                        canUndo = false,
                        canRedo = false,
                        flareGuardRuntimeStatus = null,
                        recoveryDebugInfo = null,
                        showRecoveryDebugCard = false,
                        revision = invalidateRevision + 1,
                        message = "\uC6D0\uBCF8 \uCE90\uC2DC\uAC00 \uC644\uB8CC\uB418\uC5C8\uC2B5\uB2C8\uB2E4: ${decodedPreview.width}x${decodedPreview.height} preview"
                    )
                }
                preview = null
                forceDraftSaveAsync()
            } catch (ce: CancellationException) {
                preview?.recycle()
                releaseNativeSessionHandle(createdSession)
                sourceFile?.delete()
                throw ce
            } catch (t: Throwable) {
                preview?.recycle()
                releaseNativeSessionHandle(createdSession)
                sourceFile?.delete()
                if (openToken == restoreDraftToken && _uiState.value.revision == invalidateRevision) {
                    updateUiStateAndRecycleReplaced { it.copy(isBusy = false, message = "\uC774\uBBF8\uC9C0\uB97C \uC5F4\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4: ${t.message}") }
                }
            }
        }
    }

    fun updateParams(transform: (EditParams) -> EditParams) {
        resolveOrAbortPreviousParamGroupIfNeeded()
        val current = _uiState.value
        val basePreview = current.originalPreviewBitmap ?: current.previewBitmap ?: return
        val nextParams = transform(current.params)
        if (nextParams == current.params) return
        beginParamUndoWindow()
        updateUiState { it.copy(params = nextParams) }
        val nextRevision = current.revision + 1

        // Commit the undo snapshot only after rendering succeeds.
        updateUiStateAndRecycleReplaced { it.copy(revision = nextRevision, isBusy = true, message = "미리보기를 렌더링하는 중입니다") }
        renderJob?.cancel()
        activeParamRenderRevision = nextRevision
        renderJob = viewModelScope.launch {
            var rendered: Bitmap? = null
            try {
                rendered = withContext(Dispatchers.Default) {
                    renderEditedPreview(basePreview, nextParams, current.engineSelection(), nextRevision, current.presetLook, current.activeQuickEffects)
                }
                if (_uiState.value.revision == nextRevision) {
                    val adopted = rendered!!
                    commitPendingParamUndoSnapshot()
                    lastSuccessfullyRenderedParams = nextParams
                    if (activeParamRenderRevision == nextRevision) activeParamRenderRevision = null
                    updateUiStateAndRecycleReplaced {
                        it.copy(params = nextParams, previewBitmap = adopted, isBusy = false, message = "미리보기 렌더링이 완료되었습니다")
                    }
                    rendered = null
                    scheduleDraftAutosave()
                } else {
                    if (activeParamRenderRevision == nextRevision) activeParamRenderRevision = null
                    rendered?.recycle()
                    rendered = null
                }
            } catch (ce: CancellationException) {
                rendered?.recycle()
                if (activeParamRenderRevision == nextRevision) activeParamRenderRevision = null
                throw ce
            } catch (t: Throwable) {
                rendered?.recycle()
                if (activeParamRenderRevision == nextRevision) activeParamRenderRevision = null
                if (_uiState.value.revision == nextRevision) {
                    discardPendingParamUndoSnapshot()
                    updateUiState { it.copy(params = lastSuccessfullyRenderedParams, revision = nextRevision + 1) }
                    updateUiStateAndRecycleReplaced { it.copy(isBusy = false, message = "미리보기 렌더링에 실패했습니다: ${t.message}") }
                }
            }
        }
    }

    fun applyAutoEnhance() {
        val current = prepareForExternalEdit()
        val basePreview = current.originalPreviewBitmap ?: current.previewBitmap
        if (basePreview == null) {
            updateUiStateAndRecycleReplaced { it.copy(message = "자동 보정을 적용할 이미지가 없습니다") }
            return
        }

        val nextRevision = current.revision + 1
        renderJob?.cancel()
        pushUndoSnapshot(clearRedo = true)
        updateUiStateAndRecycleReplaced { it.copy(isBusy = true, revision = nextRevision, message = "자동 보정값을 분석하는 중입니다") }

        renderJob = viewModelScope.launch {
            var rendered: Bitmap? = null
            try {
                val nextParams = withContext(Dispatchers.Default) { computeAutoEnhanceParams(basePreview) }
                rendered = withContext(Dispatchers.Default) {
                    renderEditedPreview(basePreview, nextParams, current.engineSelection(), nextRevision, current.presetLook, current.activeQuickEffects)
                }
                if (_uiState.value.revision == nextRevision) {
                    val adopted = rendered!!
                    lastSuccessfullyRenderedParams = nextParams
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            params = nextParams,
                            previewBitmap = adopted,
                            isBusy = false,
                            message = "자동 보정이 적용되었습니다"
                        )
                    }
                    rendered = null
                    scheduleDraftAutosave()
                } else {
                    rendered?.recycle()
                    rendered = null
                }
            } catch (ce: CancellationException) {
                rendered?.recycle()
                throw ce
            } catch (t: Throwable) {
                rendered?.recycle()
                if (_uiState.value.revision == nextRevision) {
                    updateUiStateAndRecycleReplaced { it.copy(isBusy = false, message = "자동 보정에 실패했습니다: ${t.message}") }
                }
            }
        }
    }

    fun setNoiseEngine(engine: NoiseEngine) {
        applyEngineChange(noiseEngine = engine, message = "노이즈 감소 엔진이 ${engine.label}으로 설정되었습니다")
    }

    fun setDetailEngine(engine: DetailEngine) {
        applyEngineChange(detailEngine = engine, message = "디테일 엔진이 ${engine.label}으로 설정되었습니다")
    }

    fun setToneEngine(engine: ToneEngine) {
        applyEngineChange(toneEngine = engine, message = "톤 엔진이 ${engine.label}으로 설정되었습니다")
    }

    fun setHazeEngine(engine: DehazeEngine) {
        applyEngineChange(hazeEngine = engine, message = "디헤이즈 엔진이 ${engine.label}으로 설정되었습니다")
    }

    private fun applyEngineChange(
        noiseEngine: NoiseEngine? = null,
        detailEngine: DetailEngine? = null,
        toneEngine: ToneEngine? = null,
        hazeEngine: DehazeEngine? = null,
        message: String
    ) {
        val current = prepareForExternalEdit()
        val nextEngines = EngineSelection(
            noiseEngine = noiseEngine ?: current.noiseEngine,
            detailEngine = detailEngine ?: current.detailEngine,
            toneEngine = toneEngine ?: current.toneEngine,
            hazeEngine = hazeEngine ?: current.hazeEngine
        ).coerceImplemented()
        val context = getApplication<Application>()

        val basePreview = current.originalPreviewBitmap ?: current.previewBitmap
        if (basePreview == null) {
            saveEngineSelection(context, nextEngines)
            updateUiStateAndRecycleReplaced {
                it.copy(
                    noiseEngine = nextEngines.noiseEngine,
                    detailEngine = nextEngines.detailEngine,
                    toneEngine = nextEngines.toneEngine,
                    hazeEngine = nextEngines.hazeEngine,
                    message = message
                )
            }
            return
        }

        val nextRevision = current.revision + 1
        updateUiStateAndRecycleReplaced {
                it.copy(
                    revision = nextRevision,
                isBusy = true,
                message = "$message. 미리보기를 다시 렌더링하는 중입니다"
            )
        }
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            var rendered: Bitmap? = null
            try {
                rendered = withContext(Dispatchers.Default) {
                    renderEditedPreview(basePreview, current.params, nextEngines, nextRevision, current.presetLook, current.activeQuickEffects)
                }
                if (_uiState.value.revision == nextRevision) {
                    val adopted = rendered!!
                    pushUndoSnapshot(clearRedo = true)
                    saveEngineSelection(context, nextEngines)
                    updateUiState { it.copy(noiseEngine = nextEngines.noiseEngine, detailEngine = nextEngines.detailEngine, toneEngine = nextEngines.toneEngine, hazeEngine = nextEngines.hazeEngine) }
                    updateUiStateAndRecycleReplaced { it.copy(previewBitmap = adopted, isBusy = false, message = message) }
                    rendered = null
                    scheduleDraftAutosave()
                } else {
                    rendered?.recycle()
                    rendered = null
                }
            } catch (ce: CancellationException) {
                rendered?.recycle()
                throw ce
            } catch (t: Throwable) {
                rendered?.recycle()
                if (_uiState.value.revision == nextRevision) {
                    updateUiStateAndRecycleReplaced { it.copy(isBusy = false, message = "미리보기 렌더링에 실패했습니다: ${t.message}") }
                }
            }
        }
    }

    fun resetAdjustments() {
        val current = prepareForExternalEdit()
        val path = current.sourcePath ?: return
        val nextRevision = current.revision + 1
        renderJob?.cancel()
        exportJob?.cancel()
        pushUndoSnapshot(clearRedo = true)
        updateUiStateAndRecycleReplaced { it.copy(isBusy = true, revision = nextRevision, message = "초기화하는 중입니다") }
        renderJob = viewModelScope.launch {
            var preview: Bitmap? = null
            try {
                preview = withContext(Dispatchers.IO) { decodeSampledMutableBitmapWithExif(path, maxSide = 2048) }
                if (_uiState.value.revision == nextRevision) {
                    val adopted = preview!!
                    lastSuccessfullyRenderedParams = EditParams()
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            originalPreviewBitmap = adopted,
                            previewBitmap = adopted,
                            baseBitmapDirty = false,
                            params = EditParams(),
                            presetLook = null,
                            activeQuickEffects = emptyList(),
                            cropState = CropState(),
                            selectionLayers = emptyList(),
                            activeSelectionLayerId = null,
                            showSelectionOverlay = true,
                            flareGuardRuntimeStatus = null,
                            isBusy = false,
                            message = "초기화가 완료되었습니다"
                        )
                    }
                    preview = null
                    forceDraftSaveAsync()
                } else {
                    preview?.recycle()
                    preview = null
                }
            } catch (ce: CancellationException) {
                preview?.recycle()
                throw ce
            } catch (t: Throwable) {
                preview?.recycle()
                if (_uiState.value.revision == nextRevision) {
                    updateUiStateAndRecycleReplaced { it.copy(isBusy = false, message = "초기화에 실패했습니다: ${t.message}") }
                }
            }
        }
    }

    fun applyPresetLook(params: EditParams, look: PresetColorLook?, message: String) {
        val current = prepareForExternalEdit()
        val basePreview = current.originalPreviewBitmap ?: current.previewBitmap
        if (basePreview == null) {
            updateUiStateAndRecycleReplaced { it.copy(message = "적용할 이미지가 없습니다.") }
            return
        }

        val nextRevision = current.revision + 1
        renderJob?.cancel()
        updateUiStateAndRecycleReplaced { it.copy(isBusy = true, revision = nextRevision, message = message) }

        renderJob = viewModelScope.launch {
            var rendered: Bitmap? = null
            try {
                rendered = withContext(Dispatchers.Default) {
                    renderEditedPreview(basePreview, params, current.engineSelection(), nextRevision, look, current.activeQuickEffects)
                }
                if (_uiState.value.revision == nextRevision) {
                    val adopted = rendered!!
                    lastSuccessfullyRenderedParams = params
                    pushUndoSnapshot(clearRedo = true)
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            params = params,
                            presetLook = look,
                            previewBitmap = adopted,
                            isBusy = false,
                            message = message
                        )
                    }
                    rendered = null
                    scheduleDraftAutosave()
                } else {
                    rendered?.recycle()
                    rendered = null
                }
            } catch (ce: CancellationException) {
                rendered?.recycle()
                throw ce
            } catch (t: Throwable) {
                rendered?.recycle()
                if (_uiState.value.revision == nextRevision) {
                    updateUiStateAndRecycleReplaced { it.copy(isBusy = false, message = "프로필 적용에 실패했습니다.") }
                }
            }
        }
    }

    fun setExportFormat(format: ExportFormat) {
        updateUiStateAndRecycleReplaced {
            it.copy(exportFormat = format, message = "파일 형식이 ${format.label}로 설정되었습니다")
        }
        scheduleDraftAutosave()
    }

    fun setExportResolution(resolution: ExportResolution) {
        updateUiStateAndRecycleReplaced {
            it.copy(exportResolution = resolution, message = "해상도가 ${resolution.label}로 설정되었습니다")
        }
        scheduleDraftAutosave()
    }

    fun setExportHistoryRetention(retention: ExportHistoryRetention) {
        val context = getApplication<Application>()
        saveExportHistoryRetention(context, retention)
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                pruneSavedExportsIfNeeded(context, _uiState.value.savedExports, retention)
            }
            updateUiStateAndRecycleReplaced {
                it.copy(
                    exportHistoryRetention = retention,
                    savedExports = saved,
                    message = "내보낸 사진 기록 자동 정리가 ${retention.label}으로 설정되었습니다"
                )
            }
        }
    }

    fun exportPreview() {
        val state = _uiState.value
        val sourcePath = state.sourcePath
        val exportBusyMessage = "${state.exportFormat.label} 형식, ${state.exportResolution.label} 목표 해상도로 내보내는 중입니다."
        if (sourcePath == null) {
            updateUiStateAndRecycleReplaced { it.copy(message = "\uB0B4\uBCF4\uB0BC \uC6D0\uBCF8 \uC774\uBBF8\uC9C0\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4") }
            return
        }

        exportJob?.cancel()
        updateUiStateAndRecycleReplaced { it.copy(isBusy = true, message = exportBusyMessage) }
        val launchedJob = viewModelScope.launch {
            val exportRevision = state.revision
            val exportSourcePath = sourcePath
            var ownedBaseBitmap: Bitmap? = null
            var exportedResolutionLabel = state.exportResolution.label
            try {
                val context = getApplication<Application>()
                val fileName = "KeplerStudio_${exportTimestamp()}.${state.exportFormat.extension}"
                val savedUri = withContext(Dispatchers.IO) {
                    val exportBitmap = if (!state.baseBitmapDirty) {
                        renderEditedExport(
                            sourcePath = sourcePath,
                            params = state.params,
                            resolution = state.exportResolution,
                            engines = state.engineSelection(),
                            revision = state.revision + 1,
                            look = state.presetLook,
                            quickEffects = state.activeQuickEffects
                        )
                    } else {
                        ownedBaseBitmap = withContext(Dispatchers.Default) {
                            (state.originalPreviewBitmap ?: state.previewBitmap)?.copy(Bitmap.Config.ARGB_8888, true)
                        } ?: error("export bitmap is missing")
                        renderEditedExportFromBitmap(
                            baseBitmap = ownedBaseBitmap ?: error("export bitmap is missing"),
                            params = state.params,
                            resolution = state.exportResolution,
                            engines = state.engineSelection(),
                            revision = state.revision + 1,
                            look = state.presetLook,
                            quickEffects = state.activeQuickEffects
                        )
                    }
                    try {
                        exportedResolutionLabel = "${exportBitmap.width}x${exportBitmap.height}"
                        saveBitmapToGallery(context, exportBitmap, fileName, state.exportFormat)
                    } finally {
                        exportBitmap.recycle()
                    }
                }
                val savedItem = SavedExport(
                    displayName = fileName,
                    uriString = savedUri.toString(),
                    formatLabel = state.exportFormat.label,
                    resolutionLabel = exportedResolutionLabel,
                    timestampMillis = System.currentTimeMillis()
                )
                val savedExports = withContext(Dispatchers.IO) {
                    rememberSavedExport(context, savedItem, state.exportHistoryRetention)
                }
                val currentState = _uiState.value
                if (currentState.sourcePath != exportSourcePath || currentState.revision != exportRevision) {
                    if (currentState.isBusy && currentState.message == exportBusyMessage) {
                        updateUiStateAndRecycleReplaced { it.copy(isBusy = false) }
                    }
                    return@launch
                }
                updateUiStateAndRecycleReplaced {
                    it.copy(
                        isBusy = false,
                        savedExports = savedExports,
                        message = "이미지가 Gallery > Pictures/KeplerStudio에 저장되었고, 앱 내 내보낸 사진 기록에도 추가되었습니다."
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val currentState = _uiState.value
                if (currentState.sourcePath == exportSourcePath && currentState.revision == exportRevision) {
                    updateUiStateAndRecycleReplaced { it.copy(isBusy = false, message = "\uB0B4\uBCF4\uB0B4\uAE30\uC5D0 \uC2E4\uD328\uD588\uC2B5\uB2C8\uB2E4: ${t.message}") }
                } else if (currentState.isBusy && currentState.message == exportBusyMessage) {
                    updateUiStateAndRecycleReplaced { it.copy(isBusy = false) }
                }
            } finally {
                ownedBaseBitmap?.takeIf { !it.isRecycled }?.recycle()
                if (exportJob === kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]) exportJob = null
            }
        }
        exportJob = launchedJob
    }

    fun clearDraft() {
        val context = getApplication<Application>()
        val activeSourcePath = _uiState.value.sourcePath
        val draftSourcePath = _uiState.value.draftSourcePath
        val preserveActiveSource = activeSourcePath != null && draftSourcePath != null &&
            File(activeSourcePath).absoluteFile == File(draftSourcePath).absoluteFile
        viewModelScope.launch {
            draftSaveJob?.cancelAndJoin()
            withContext(Dispatchers.IO) {
                draftSaveMutex.withLock {
                    clearDraftPrefs(context, preserveSourcePath = if (preserveActiveSource) activeSourcePath else null)
                }
            }
            updateUiStateAndRecycleReplaced {
                it.copy(
                    draftSavedAtMillis = null,
                    draftSourcePath = null,
                    recoveryDebugInfo = null,
                    showRecoveryDebugCard = false,
                    message = "자동복구용 임시저장 기록을 삭제했습니다. 현재 편집 화면은 유지됩니다"
                )
            }
        }
    }

    fun dismissRecoveryDebugCard() {
        updateUiStateAndRecycleReplaced { it.copy(showRecoveryDebugCard = false) }
    }

    fun cleanupOldTemporarySources() {
        val context = getApplication<Application>()
        val activeSourcePath = _uiState.value.sourcePath
        viewModelScope.launch {
            val removedCount = withContext(Dispatchers.IO) {
                cleanupTemporarySourceFiles(context, activeSourcePath = activeSourcePath)
            }
            updateUiStateAndRecycleReplaced {
                it.copy(message = "7일이 지난 임시 원본 캐시를 정리했습니다. 삭제된 파일: ${removedCount}개")
            }
        }
    }

    fun clearSavedExports() {
        val context = getApplication<Application>()
        clearSavedExportsPrefs(context)
        updateUiStateAndRecycleReplaced {
            it.copy(
                savedExports = emptyList(),
                message = "내보낸 사진 기록을 모두 비웠습니다. 갤러리 파일은 삭제되지 않습니다"
            )
        }
    }

    fun removeSavedExport(uriString: String) {
        val context = getApplication<Application>()
        val next = _uiState.value.savedExports.filterNot { it.uriString == uriString }
        saveSavedExportsToPrefs(context, next)
        updateUiStateAndRecycleReplaced {
            it.copy(
                savedExports = next,
                message = "선택한 내보낸 사진 기록을 삭제했습니다. 갤러리 파일은 삭제되지 않습니다"
            )
        }
    }

    fun updateViewport(viewport: ViewportState) {
        updateUiStateAndRecycleReplaced { it.copy(viewport = viewport) }
        // TODO v0.2: viewport가 scale 임계값 이상이면 ROI 타일 렌더 Job 발행.
    }

    fun undoEdit() {
        abortPendingParameterEdit()
        if (undoHistory.isEmpty()) {
            updateUiStateAndRecycleReplaced { it.copy(message = "되돌릴 편집 기록이 없습니다.") }
            return
        }
        renderJob?.cancel()
        exportJob?.cancel()
        val redoSnapshot = try {
            _uiState.value.toHistorySnapshot()
        } catch (t: Throwable) {
            Log.w(FLARE_GUARD_AI_TAG, "Failed to create redo snapshot", t)
            updateUiStateAndRecycleReplaced {
                it.copy(message = "되돌리기 기록을 저장하지 못했습니다. 편집은 계속할 수 있습니다.")
            }
            return
        }
        redoHistory.addLast(redoSnapshot)
        val snapshot = undoHistory.removeLast()
        val message = buildHistoryAppliedMessage(_uiState.value, snapshot, "이전 편집 상태를 적용했습니다")
        applyHistorySnapshot(snapshot, message)
        scheduleDraftAutosave()
        updateHistoryFlags()
        Log.i(FLARE_GUARD_AI_TAG, "Undo editor snapshot: undo=${undoHistory.size} redo=${redoHistory.size}")
    }

    fun redoEdit() {
        abortPendingParameterEdit()
        if (redoHistory.isEmpty()) {
            updateUiStateAndRecycleReplaced { it.copy(message = "다시 실행할 편집 기록이 없습니다.") }
            return
        }
        renderJob?.cancel()
        exportJob?.cancel()
        val undoSnapshot = try {
            _uiState.value.toHistorySnapshot()
        } catch (t: Throwable) {
            Log.w(FLARE_GUARD_AI_TAG, "Failed to create undo snapshot for redo", t)
            updateUiStateAndRecycleReplaced {
                it.copy(message = "되돌리기 기록을 저장하지 못했습니다. 편집은 계속할 수 있습니다.")
            }
            return
        }
        undoHistory.addLast(undoSnapshot)
        trimHistory(undoHistory)
        val snapshot = redoHistory.removeLast()
        val message = buildHistoryAppliedMessage(_uiState.value, snapshot, "다음 편집 상태를 적용했습니다")
        applyHistorySnapshot(snapshot, message)
        scheduleDraftAutosave()
        updateHistoryFlags()
        Log.i(FLARE_GUARD_AI_TAG, "Redo editor snapshot: undo=${undoHistory.size} redo=${redoHistory.size}")
    }

    fun rotatePreview90() {
        abortPendingParameterEdit()
        val current = _uiState.value
        val preview = current.previewBitmap
        if (preview == null) {
            updateUiStateAndRecycleReplaced { it.copy(message = "회전할 이미지가 없습니다.") }
            return
        }
        pushUndoSnapshot(clearRedo = true)
        val original = current.originalPreviewBitmap
        // TODO: model manual rotation as editor state instead of mutating preview bitmaps directly.
        val rotatedPreview = rotateBitmap90(preview)
        val rotatedOriginal = if (original != null && original !== preview) rotateBitmap90(original) else rotatedPreview
        updateUiStateAndRecycleReplaced {
            it.copy(
                previewBitmap = rotatedPreview,
                originalPreviewBitmap = rotatedOriginal,
                baseBitmapDirty = true,
                revision = it.revision + 1,
                message = "미리보기를 90도 회전했습니다."
            )
        }
        forceDraftSaveAsync()
        Log.i(FLARE_GUARD_AI_TAG, "Rotated preview manually: ${preview.width}x${preview.height} -> ${rotatedPreview.width}x${rotatedPreview.height}")
    }

    fun applySpotCleanup() {
        applyNativeSpecialEffects(
            title = "기본 정리",
            failureMessage = "기본 정리 적용에 실패했습니다.",
            effect = ActiveQuickEffect(QuickEffectKind.SpotCleanup)
        )
    }

    fun applyChromaticAberrationReduction() {
        applyNativeSpecialEffects(
            title = "색수차 완화",
            failureMessage = "색수차 완화 적용에 실패했습니다.",
            effect = ActiveQuickEffect(QuickEffectKind.ChromaticAberrationReduction)
        )
    }

    fun applyVignetteCorrection() {
        applyNativeSpecialEffects(
            title = "주변부 어두움 완화",
            failureMessage = "주변부 어두움 완화 적용에 실패했습니다.",
            effect = ActiveQuickEffect(QuickEffectKind.VignetteCorrection)
        )
    }

    fun applyOpticsCorrection() {
        applyNativeSpecialEffects(
            title = "통합 광학 보정",
            failureMessage = "통합 광학 보정 적용에 실패했습니다.",
            effect = ActiveQuickEffect(QuickEffectKind.OpticsCorrection)
        )
    }

    fun applySoftBlur(strength: Float = 0.32f) {
        applyNativeSpecialEffects(
            title = "부드러운 흐림",
            failureMessage = "부드러운 흐림 적용에 실패했습니다.",
            effect = ActiveQuickEffect(
                kind = QuickEffectKind.SoftBlur,
                strength = strength.toQuickEffectStrength()
            )
        )
    }

    private fun applyNativeSpecialEffects(
        title: String,
        failureMessage: String,
        effect: ActiveQuickEffect
    ) {
        val current = prepareForExternalEdit()
        val baseOriginal = current.originalPreviewBitmap ?: current.previewBitmap
        if (baseOriginal == null) {
            updateUiStateAndRecycleReplaced { it.copy(message = "적용할 이미지가 없습니다.") }
            return
        }

        val nextActiveQuickEffects = current.activeQuickEffects.toggle(effect)
        if (nextActiveQuickEffects == current.activeQuickEffects) return
        pushUndoSnapshot(clearRedo = true)
        val nextRevision = current.revision + 1
        renderJob?.cancel()
        updateUiStateAndRecycleReplaced {
            it.copy(
                isBusy = true,
                revision = nextRevision,
                message = "$title 적용 중입니다."
            )
        }

        renderJob = viewModelScope.launch {
            var renderedPreview: Bitmap? = null
            try {
                renderedPreview = withContext(Dispatchers.Default) {
                    renderEditedPreview(
                        baseOriginal,
                        current.params,
                        current.engineSelection(),
                        nextRevision,
                        current.presetLook,
                        nextActiveQuickEffects
                    )
                }
                if (_uiState.value.revision == nextRevision) {
                    val adoptedPreview = renderedPreview!!
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            previewBitmap = adoptedPreview,
                            activeQuickEffects = nextActiveQuickEffects,
                            isBusy = false,
                            message = if (nextActiveQuickEffects.any { active -> active.matches(effect) }) {
                                "$title 적용했습니다. 다시 누르면 해제할 수 있습니다."
                            } else {
                                "$title 적용을 해제했습니다."
                            }
                        )
                    }
                    renderedPreview = null
                    forceDraftSaveAsync()
                } else {
                    renderedPreview?.recycle()
                    renderedPreview = null
                }
            } catch (ce: CancellationException) {
                renderedPreview?.recycle()
                throw ce
            } catch (t: Throwable) {
                renderedPreview?.recycle()
                Log.e(FLARE_GUARD_AI_TAG, "$title native special effect failed", t)
                if (_uiState.value.revision == nextRevision) {
                    updateUiStateAndRecycleReplaced { it.copy(isBusy = false, message = failureMessage) }
                }
            }
        }
    }
    fun applyFlareGuardAiOrRulePreview(context: Context, mode: FlareGuardMode) {
        val current = prepareForExternalEdit()
        val baseOriginal = current.originalPreviewBitmap ?: current.previewBitmap
        if (baseOriginal == null) {
            updateUiStateAndRecycleReplaced { it.copy(message = "번짐 완화를 적용할 이미지가 없습니다.") }
            return
        }

        renderJob?.cancel()
        pushUndoSnapshot(clearRedo = true)
        val label = when (mode) {
            FlareGuardMode.NightLight -> "번짐 영역 감지"
            FlareGuardMode.DaySun -> "태양 번짐 영역 감지"
        }
        val nextRevision = current.revision + 1
        val appContext = context.applicationContext
        updateUiStateAndRecycleReplaced {
            it.copy(
                isBusy = true,
                revision = nextRevision,
                message = "$label 처리 중입니다.",
                flareGuardRuntimeStatus = "플레어 마스크 모델 상태를 확인하는 중입니다."
            )
        }
        Log.i(FLARE_GUARD_AI_TAG, "Starting FlareGuard preview: mode=$mode source=${baseOriginal.width}x${baseOriginal.height} revision=$nextRevision")

        renderJob = viewModelScope.launch {
            var resultBitmap: Bitmap? = null
            var renderedPreview: Bitmap? = null
            try {
                val result = withContext(Dispatchers.Default) {
                    applyFlareGuardModelOrRuleResultV0(appContext, baseOriginal, mode, allowRuleFallback = true)
                }
                resultBitmap = result.bitmap
                renderedPreview = withContext(Dispatchers.Default) {
                    renderEditedPreview(resultBitmap!!, current.params, current.engineSelection(), nextRevision, current.presetLook, current.activeQuickEffects)
                }
                if (_uiState.value.revision == nextRevision) {
                    val adoptedOriginal = resultBitmap!!
                    val adoptedPreview = renderedPreview!!
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            originalPreviewBitmap = adoptedOriginal,
                            previewBitmap = adoptedPreview,
                            baseBitmapDirty = true,
                            isBusy = false,
                            message = result.status.uiText,
                            flareGuardRuntimeStatus = result.status.uiText
                        )
                    }
                    resultBitmap = null
                    renderedPreview = null
                    forceDraftSaveAsync()
                    Log.i(FLARE_GUARD_AI_TAG, "Finished FlareGuard preview: mode=$mode status=${result.status} output=${result.bitmap.width}x${result.bitmap.height}")
                } else {
                    resultBitmap?.recycle()
                    renderedPreview?.recycle()
                    resultBitmap = null
                    renderedPreview = null
                    Log.w(FLARE_GUARD_AI_TAG, "Discarded stale FlareGuard preview: expected=$nextRevision actual=${_uiState.value.revision}")
                }
            } catch (ce: CancellationException) {
                resultBitmap?.recycle()
                renderedPreview?.recycle()
                throw ce
            } catch (t: Throwable) {
                resultBitmap?.recycle()
                renderedPreview?.recycle()
                Log.e(FLARE_GUARD_AI_TAG, "FlareGuard preview failed", t)
                if (_uiState.value.revision == nextRevision) {
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            isBusy = false,
                            message = "번짐 영역 감지에 실패했습니다.",
                            flareGuardRuntimeStatus = "번짐 영역 감지에 실패했습니다."
                        )
                    }
                }
            }
        }
    }


    private suspend fun restoreDraftIfAvailable(context: Context) {
        val restoreToken = ++restoreDraftToken
        val restoreStartRevision = _uiState.value.revision
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val storedSourcePath = prefs.getString(KEY_DRAFT_SOURCE, null) ?: return
        val draftSavedAt = prefs.getLong(KEY_DRAFT_SAVED_AT, 0L).takeIf { it > 0L }
        val recovery = try {
            withContext(Dispatchers.IO) {
                resolveDraftRecovery(context, storedSourcePath)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            logDraftSaveFailure(t)
            if (restoreToken == restoreDraftToken) {
                updateUiStateAndRecycleReplaced {
                    it.copy(
                        isBusy = false,
                        message = "\uC784\uC2DC\uC800\uC7A5 \uBCF5\uAD6C \uC815\uBCF4\uB97C \uD655\uC778\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4. \uD3B8\uC9D1\uC740 \uACC4\uC18D\uD560 \uC218 \uC788\uC2B5\uB2C8\uB2E4."
                    )
                }
            }
            return
        }
        if (restoreToken != restoreDraftToken) return
        updateUiStateAndRecycleReplaced {
            it.copy(
                draftSavedAtMillis = draftSavedAt,
                draftSourcePath = recovery.debugInfo.draftSourcePath,
                recoveryDebugInfo = recovery.debugInfo,
                showRecoveryDebugCard = true
            )
        }
        val sourceFile = recovery.sourceFile
        if (sourceFile == null) {
            val missingDraftMessage = "임시 저장 원본을 찾을 수 없습니다. 기존 임시 저장 파일이 삭제되어 복구할 수 없습니다."
            updateUiStateAndRecycleReplaced { it.copy(message = missingDraftMessage) }
            return
        }
        val sourcePath = sourceFile.absolutePath

        val legacyNoiseReduction = prefs.getFloat(KEY_DRAFT_NOISE_REDUCTION, 0f)
        val params = EditParams(
            exposure = prefs.getFloat(KEY_DRAFT_EXPOSURE, 0f),
            contrast = prefs.getFloat(KEY_DRAFT_CONTRAST, 0f),
            shadows = prefs.getFloat(KEY_DRAFT_SHADOWS, 0f),
            highlights = prefs.getFloat(KEY_DRAFT_HIGHLIGHTS, 0f),
            whites = prefs.getFloat(KEY_DRAFT_WHITES, 0f),
            blacks = prefs.getFloat(KEY_DRAFT_BLACKS, 0f),
            temperature = prefs.getFloat(KEY_DRAFT_TEMPERATURE, 0f),
            tint = prefs.getFloat(KEY_DRAFT_TINT, 0f),
            saturation = prefs.getFloat(KEY_DRAFT_SATURATION, 0f),
            vibrance = prefs.getFloat(KEY_DRAFT_VIBRANCE, 0f),
            clarity = prefs.getFloat(KEY_DRAFT_CLARITY, 0f),
            dehaze = prefs.getFloat(KEY_DRAFT_DEHAZE, 0f),
            sharpness = prefs.getFloat(KEY_DRAFT_SHARPNESS, 0f),
            noiseReduction = legacyNoiseReduction,
            luminanceNoiseReduction = prefs.getFloat(KEY_DRAFT_LUMINANCE_NOISE_REDUCTION, legacyNoiseReduction),
            colorNoiseReduction = prefs.getFloat(KEY_DRAFT_COLOR_NOISE_REDUCTION, legacyNoiseReduction),
            noiseDetailProtection = prefs.getFloat(KEY_DRAFT_NOISE_DETAIL_PROTECTION, 0.50f)
        )
        val exportFormat = enumValueOrDefault(prefs.getString(KEY_DRAFT_FORMAT, null), ExportFormat.Jpeg)
        val exportResolution = enumValueOrDefault(prefs.getString(KEY_DRAFT_RESOLUTION, null), ExportResolution.Full)
        val presetLook = runCatching {
            presetColorLookFromJson(prefs.getString(KEY_DRAFT_LOOK, null)?.let(::JSONObject))
        }.getOrNull()
        val engines = _uiState.value.engineSelection()
        updateUiStateAndRecycleReplaced {
            it.copy(
                draftSavedAtMillis = draftSavedAt,
                draftSourcePath = sourcePath
            )
        }

        updateUiStateAndRecycleReplaced { it.copy(isBusy = true, message = "\uC784\uC2DC\uC800\uC7A5\uB41C \uD3B8\uC9D1\uC744 \uBD88\uB7EC\uC624\uB294 \uC911\uC785\uB2C8\uB2E4") }
        var preview: Bitmap? = null
        var rendered: Bitmap? = null
        var createdSession = 0L
        var expectedRestoreRevision: Int? = null
        try {
            preview = withContext(Dispatchers.IO) { decodeSampledMutableBitmapWithExif(sourcePath, maxSide = 2048) }
            val nextRevision = _uiState.value.revision + 1
            expectedRestoreRevision = nextRevision
            val activeQuickEffects = prefs.getString(KEY_DRAFT_QUICK_EFFECTS, null).parseQuickEffects()
            rendered = withContext(Dispatchers.Default) {
                renderEditedPreview(preview!!, params, engines, nextRevision, presetLook, activeQuickEffects)
            }
            if (restoreToken != restoreDraftToken || _uiState.value.revision != restoreStartRevision) {
                preview?.recycle()
                rendered?.recycle()
                preview = null
                rendered = null
                return
            }
            createdSession = NativePhotoCore.nativeCreateSession(sourcePath)
            if (restoreToken != restoreDraftToken || _uiState.value.revision != restoreStartRevision) {
                preview?.recycle()
                rendered?.recycle()
                preview = null
                rendered = null
                releaseNativeSessionHandle(createdSession)
                createdSession = 0L
                return
            }
            val previousSession = nativeSession
            nativeSession = createdSession
            createdSession = 0L
            releaseNativeSessionHandle(previousSession)
            val adoptedPreview = preview!!
            val adoptedRendered = rendered!!
            lastSuccessfullyRenderedParams = params
            updateUiStateAndRecycleReplaced {
                it.copy(
                    isBusy = false,
                    sourcePath = sourcePath,
                    baseBitmapDirty = false,
                    originalPreviewBitmap = adoptedPreview,
                    previewBitmap = adoptedRendered,
                    activeQuickEffects = activeQuickEffects,
                    params = params,
                    presetLook = presetLook,
                    exportFormat = exportFormat,
                    exportResolution = exportResolution,
                    draftSavedAtMillis = draftSavedAt,
                    draftSourcePath = sourcePath,
                    revision = nextRevision,
                    message = "\uC784\uC2DC\uC800\uC7A5\uB41C \uD3B8\uC9D1\uC744 \uBD88\uB7EC\uC654\uC2B5\uB2C8\uB2E4"
                )
            }
            preview = null
            rendered = null
        } catch (ce: CancellationException) {
            preview?.recycle()
            rendered?.recycle()
            releaseNativeSessionHandle(createdSession)
            throw ce
        } catch (t: Throwable) {
            preview?.recycle()
            rendered?.recycle()
            releaseNativeSessionHandle(createdSession)
            val currentRevision = _uiState.value.revision
            val isRestoreStillCurrent = restoreToken == restoreDraftToken &&
                (currentRevision == restoreStartRevision || currentRevision == expectedRestoreRevision)
            if (isRestoreStillCurrent) {
                updateUiStateAndRecycleReplaced { it.copy(isBusy = false, message = "\uC784\uC2DC\uC800\uC7A5\uC744 \uBD88\uB7EC\uC624\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4: ${t.message}") }
            }
        }
    }

    private fun beginParamUndoWindow() {
        if (!paramUndoWindowOpen) {
            if (pendingParamUndoSnapshot != null) {
                pendingParamUndoSnapshot?.recycleBitmaps()
                pendingParamUndoSnapshot = null
                updateUiState { it.copy(params = lastSuccessfullyRenderedParams, revision = it.revision + 1, isBusy = false) }
            }
            pendingParamUndoSnapshot = runCatching { _uiState.value.toHistorySnapshot() }.getOrNull()
            paramUndoSnapshotCommitted = false
            paramUndoWindowOpen = true
        }
        paramUndoWindowJob?.cancel()
        paramUndoWindowJob = viewModelScope.launch {
            delay(900L)
            paramUndoWindowOpen = false
        }
    }

    private fun resolveOrAbortPreviousParamGroupIfNeeded() {
        if (!paramUndoWindowOpen && (activeParamRenderRevision != null || pendingParamUndoSnapshot != null)) {
            abortPendingParameterEdit()
        }
    }

    private fun commitPendingParamUndoSnapshot() {
        val snapshot = pendingParamUndoSnapshot ?: return
        if (!paramUndoSnapshotCommitted) {
            undoHistory.addLast(snapshot)
            trimHistory(undoHistory)
            recycleHistory(redoHistory)
            redoHistory.clear()
            updateHistoryFlags()
            paramUndoSnapshotCommitted = true
        }
        pendingParamUndoSnapshot = null
    }

    private fun discardPendingParamUndoSnapshot() {
        if (!paramUndoSnapshotCommitted) pendingParamUndoSnapshot?.recycleBitmaps()
        pendingParamUndoSnapshot = null
        closeParamUndoWindow()
    }

    internal fun abortPendingParameterEdit() {
        val unresolved = activeParamRenderRevision != null ||
            _uiState.value.params != lastSuccessfullyRenderedParams ||
            pendingParamUndoSnapshot != null || paramUndoWindowOpen
        if (!unresolved) return
        renderJob?.cancel()
        activeParamRenderRevision = null
        updateUiState { it.copy(params = lastSuccessfullyRenderedParams, revision = it.revision + 1, isBusy = false) }
        discardPendingParamUndoSnapshot()
    }

    internal fun prepareForExternalEdit(): EditorUiState {
        abortPendingParameterEdit()
        return uiState.value
    }

    private fun closeParamUndoWindow() {
        paramUndoWindowJob?.cancel()
        paramUndoWindowJob = null
        paramUndoWindowOpen = false
    }

    private fun pushUndoSnapshot(clearRedo: Boolean) {
        if (activeParamRenderRevision != null || _uiState.value.params != lastSuccessfullyRenderedParams) {
            abortPendingParameterEdit()
        }
        closeParamUndoWindow()
        val state = _uiState.value
        if (state.previewBitmap == null && state.originalPreviewBitmap == null) return
        val snapshot = try {
            state.toHistorySnapshot()
        } catch (t: Throwable) {
            Log.w(FLARE_GUARD_AI_TAG, "Failed to create undo snapshot", t)
            updateUiStateAndRecycleReplaced {
                it.copy(message = "되돌리기 기록을 저장하지 못했습니다. 편집은 계속할 수 있습니다.")
            }
            return
        }
        undoHistory.addLast(snapshot)
        trimHistory(undoHistory)
        if (clearRedo) {
            recycleHistory(redoHistory)
            redoHistory.clear()
        }
        updateHistoryFlags()
        Log.i(FLARE_GUARD_AI_TAG, "Pushed undo snapshot: undo=${undoHistory.size} redo=${redoHistory.size}")
    }

    private fun applyHistorySnapshot(snapshot: EditorHistorySnapshot, message: String) {
        lastSuccessfullyRenderedParams = snapshot.params
        updateUiStateAndRecycleReplaced {
            it.copy(
                params = snapshot.params,
                noiseEngine = snapshot.noiseEngine,
                detailEngine = snapshot.detailEngine,
                toneEngine = snapshot.toneEngine,
                hazeEngine = snapshot.hazeEngine,
                baseBitmapDirty = snapshot.baseBitmapDirty,
                previewBitmap = snapshot.previewBitmap,
                originalPreviewBitmap = snapshot.originalPreviewBitmap,
                presetLook = snapshot.presetLook,
                cropState = snapshot.cropState,
                selectionLayers = snapshot.selectionLayers,
                activeSelectionLayerId = snapshot.activeSelectionLayerId,
                selectionPaintSettings = snapshot.selectionPaintSettings,
                showSelectionOverlay = snapshot.showSelectionOverlay,
                activeQuickEffects = snapshot.activeQuickEffects,
                flareGuardRuntimeStatus = snapshot.flareGuardRuntimeStatus,
                isBusy = false,
                revision = it.revision + 1,
                message = message
            )
        }
        saveEngineSelection(getApplication<Application>(), EngineSelection(snapshot.noiseEngine, snapshot.detailEngine, snapshot.toneEngine, snapshot.hazeEngine))
    }

    private fun updateHistoryFlags() {
        updateUiStateAndRecycleReplaced { it.copy(canUndo = undoHistory.isNotEmpty(), canRedo = redoHistory.isNotEmpty()) }
    }

    private fun clearEditHistory() {
        discardPendingParamUndoSnapshot()
        recycleHistory(undoHistory)
        recycleHistory(redoHistory)
        undoHistory.clear()
        redoHistory.clear()
        updateHistoryFlags()
    }

    private fun releaseNativeSessionHandle(session: Long) {
        if (session != 0L) {
            runCatching { NativePhotoCore.nativeReleaseSession(session) }
        }
    }

    private fun releaseNativeSession() {
        if (nativeSession != 0L) {
            releaseNativeSessionHandle(nativeSession)
            nativeSession = 0L
        }
    }

    override fun onCleared() {
        renderJob?.cancel()
        selectionLivePreviewJob?.cancel()
        draftSaveJob?.cancel()
        paramUndoWindowJob?.cancel()
        releaseNativeSession()
        recycleLiveStateBitmapsNow(_uiState.value)
        clearEditHistory()
        super.onCleared()
    }
}

private data class EditorHistorySnapshot(
    val params: EditParams,
    val noiseEngine: NoiseEngine,
    val detailEngine: DetailEngine,
    val toneEngine: ToneEngine,
    val hazeEngine: DehazeEngine,
    val baseBitmapDirty: Boolean,
    val previewBitmap: Bitmap?,
    val originalPreviewBitmap: Bitmap?,
    val presetLook: PresetColorLook?,
    val cropState: CropState,
    val selectionLayers: List<SelectionLayer>,
    val activeSelectionLayerId: String?,
    val selectionPaintSettings: SelectionPaintSettings,
    val showSelectionOverlay: Boolean,
    val activeQuickEffects: List<ActiveQuickEffect>,
    val flareGuardRuntimeStatus: String?
)

private fun EditorUiState.toHistorySnapshot(): EditorHistorySnapshot {
    var previewCopy: Bitmap? = null
    var originalCopy: Bitmap? = null
    val selectionCopies = ArrayList<SelectionLayer>(selectionLayers.size)
    try {
        previewCopy = previewBitmap?.copy(Bitmap.Config.ARGB_8888, true)
        originalCopy = if (originalPreviewBitmap == null) {
            null
        } else if (originalPreviewBitmap === previewBitmap) {
            previewCopy
        } else {
            originalPreviewBitmap.copy(Bitmap.Config.ARGB_8888, true)
        }
        selectionLayers.forEach { layer ->
            selectionCopies.add(layer.copy(bitmap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true)))
        }
        return EditorHistorySnapshot(
            params = params,
            noiseEngine = noiseEngine,
            detailEngine = detailEngine,
            toneEngine = toneEngine,
            hazeEngine = hazeEngine,
            baseBitmapDirty = baseBitmapDirty,
            previewBitmap = previewCopy,
            originalPreviewBitmap = originalCopy,
            presetLook = presetLook,
            cropState = cropState,
            selectionLayers = selectionCopies,
            activeSelectionLayerId = activeSelectionLayerId,
            selectionPaintSettings = selectionPaintSettings,
            showSelectionOverlay = showSelectionOverlay,
            activeQuickEffects = activeQuickEffects,
            flareGuardRuntimeStatus = flareGuardRuntimeStatus
        )
    } catch (t: Throwable) {
        previewCopy?.recycle()
        if (originalCopy != null && originalCopy !== previewCopy) {
            originalCopy.recycle()
        }
        selectionCopies.forEach { it.bitmap.recycle() }
        throw t
    }
}

private fun buildHistoryAppliedMessage(
    current: EditorUiState,
    target: EditorHistorySnapshot,
    prefix: String
): String {
    val changedParams = historyParamSummaries(current.params, target.params)
    if (changedParams.isNotEmpty()) {
        return "$prefix: ${changedParams.take(3).joinToString(", ")}"
    }
    val changedImageState = current.presetLook != target.presetLook ||
        current.noiseEngine != target.noiseEngine ||
        current.detailEngine != target.detailEngine ||
        current.toneEngine != target.toneEngine ||
        current.hazeEngine != target.hazeEngine ||
        current.cropState != target.cropState ||
        current.selectionLayers != target.selectionLayers ||
        current.activeSelectionLayerId != target.activeSelectionLayerId ||
        current.selectionPaintSettings != target.selectionPaintSettings ||
        current.showSelectionOverlay != target.showSelectionOverlay ||
        current.activeQuickEffects != target.activeQuickEffects ||
        current.previewBitmap !== target.previewBitmap ||
        current.originalPreviewBitmap !== target.originalPreviewBitmap
    return if (changedImageState) {
        "$prefix: 이미지 상태 변경"
    } else {
        prefix
    }
}

private fun historyParamSummaries(current: EditParams, target: EditParams): List<String> = listOfNotNull(
    historyExposureSummary(current.exposure, target.exposure),
    historySliderSummary("대비", current.contrast, target.contrast),
    historySliderSummary("하이라이트", current.highlights, target.highlights),
    historySliderSummary("그림자", current.shadows, target.shadows),
    historySliderSummary("화이트", current.whites, target.whites),
    historySliderSummary("블랙", current.blacks, target.blacks),
    historySliderSummary("색온도", current.temperature, target.temperature),
    historySliderSummary("색조", current.tint, target.tint),
    historySliderSummary("채도", current.saturation, target.saturation),
    historySliderSummary("생동감", current.vibrance, target.vibrance),
    historySliderSummary("명료도", current.clarity, target.clarity),
    historySliderSummary("디헤이즈", current.dehaze, target.dehaze),
    historySliderSummary("선명도", current.sharpness, target.sharpness),
    historyAbsoluteSliderSummary("노이즈 감소", current.luminanceNoiseReduction, target.luminanceNoiseReduction),
    historyAbsoluteSliderSummary("색상 노이즈 감소", current.colorNoiseReduction, target.colorNoiseReduction),
    historyAbsoluteSliderSummary("디테일 보호", current.noiseDetailProtection, target.noiseDetailProtection)
)

private fun historyExposureSummary(current: Float, target: Float): String? {
    if (!historyValueChanged(current, target)) return null
    val value = historySignedValue(target)
    return "노출 $value"
}

private fun historySliderSummary(label: String, current: Float, target: Float): String? {
    if (!historyValueChanged(current, target)) return null
    return "$label ${historySignedValue(target)}"
}

private fun historyAbsoluteSliderSummary(label: String, current: Float, target: Float): String? {
    if (!historyValueChanged(current, target)) return null
    return "$label ${String.format(Locale.US, "%.2f", target)}"
}

private fun historyValueChanged(current: Float, target: Float): Boolean =
    kotlin.math.abs(current - target) >= 0.0005f

private fun historyIsZero(value: Float): Boolean = kotlin.math.abs(value) < 0.0005f

private fun historySignedValue(value: Float): String =
    if (historyIsZero(value)) "0.00" else String.format(Locale.US, "%+.2f", value)

private fun identityBitmapSet(): MutableSet<Bitmap> =
    Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())

private fun trimHistory(stack: ArrayDeque<EditorHistorySnapshot>) {
    while (stack.size > EDITOR_HISTORY_MAX) {
        stack.removeFirst().recycleBitmaps()
    }
}

private fun recycleHistory(stack: ArrayDeque<EditorHistorySnapshot>) {
    stack.forEach { it.recycleBitmaps() }
}

private fun EditorHistorySnapshot.recycleBitmaps() {
    previewBitmap?.recycle()
    if (originalPreviewBitmap !== previewBitmap) originalPreviewBitmap?.recycle()
    selectionLayers.forEach { it.bitmap.recycle() }
}

internal data class EngineSelection(
    val noiseEngine: NoiseEngine,
    val detailEngine: DetailEngine,
    val toneEngine: ToneEngine,
    val hazeEngine: DehazeEngine
)

internal fun EditorUiState.engineSelection(): EngineSelection = EngineSelection(
    noiseEngine = noiseEngine,
    detailEngine = detailEngine,
    toneEngine = toneEngine,
    hazeEngine = hazeEngine
)

private data class LumaStats(
    val p01: Float,
    val p05: Float,
    val p50: Float,
    val p95: Float,
    val p99: Float,
    val mean: Float,
    val chromaMean: Float
)

private fun computeAutoEnhanceParams(bitmap: Bitmap): EditParams {
    val stats = analyzeBitmap(bitmap)
    val safeMedian = stats.p50.coerceAtLeast(0.015f)
    val exposure = (ln(0.46f / safeMedian) / ln(2f)).toFloat().coerceIn(-0.70f, 0.70f)

    val range = (stats.p95 - stats.p05).coerceAtLeast(0.01f)
    val contrast = ((0.58f - range) * 0.70f).coerceIn(-0.22f, 0.38f)
    val shadows = ((0.24f - stats.p05) * 0.85f).coerceIn(-0.18f, 0.42f)
    val highlights = ((stats.p95 - 0.78f) * 0.95f).coerceIn(-0.18f, 0.42f)
    val whites = ((0.97f - stats.p99) * 0.65f).coerceIn(-0.20f, 0.30f)
    val blacks = ((0.025f - stats.p01) * 1.05f).coerceIn(-0.32f, 0.22f)
    val vibrance = ((0.30f - stats.chromaMean) * 0.72f).coerceIn(0.00f, 0.28f)
    val saturation = if (stats.chromaMean < 0.10f) 0.04f else 0.00f
    val clarity = if (range < 0.48f) 0.12f else 0.07f
    val dehaze = if (stats.p05 > 0.10f && stats.p95 < 0.86f) 0.10f else 0.02f
    val noiseReduction = if (stats.mean < 0.34f) 0.20f else 0.08f

    return EditParams(
        exposure = exposure,
        contrast = contrast,
        shadows = shadows,
        highlights = highlights,
        whites = whites,
        blacks = blacks,
        temperature = 0f,
        tint = 0f,
        saturation = saturation,
        vibrance = vibrance,
        clarity = clarity,
        dehaze = dehaze,
        sharpness = 0.16f,
        noiseReduction = noiseReduction
    )
}

private fun analyzeBitmap(bitmap: Bitmap): LumaStats {
    val histogram = IntArray(256)
    var count = 0
    var lumaSum = 0f
    var chromaSum = 0f
    val step = max(1, max(bitmap.width, bitmap.height) / 512)
    val row = IntArray(bitmap.width)

    var y = 0
    while (y < bitmap.height) {
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        var x = 0
        while (x < bitmap.width) {
            val pixel = row[x]
            val r = ((pixel shr 16) and 0xff) / 255f
            val g = ((pixel shr 8) and 0xff) / 255f
            val b = (pixel and 0xff) / 255f
            val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
            val maxC = max(r, max(g, b))
            val minC = kotlin.math.min(r, kotlin.math.min(g, b))
            histogram[(luma * 255f).roundToInt().coerceIn(0, 255)] += 1
            lumaSum += luma
            chromaSum += (maxC - minC).coerceIn(0f, 1f)
            count += 1
            x += step
        }
        y += step
    }

    if (count <= 0) {
        return LumaStats(0f, 0f, 0.5f, 1f, 1f, 0.5f, 0.1f)
    }

    return LumaStats(
        p01 = percentileFromHistogram(histogram, count, 0.01f),
        p05 = percentileFromHistogram(histogram, count, 0.05f),
        p50 = percentileFromHistogram(histogram, count, 0.50f),
        p95 = percentileFromHistogram(histogram, count, 0.95f),
        p99 = percentileFromHistogram(histogram, count, 0.99f),
        mean = lumaSum / count,
        chromaMean = chromaSum / count
    )
}

private fun percentileFromHistogram(histogram: IntArray, count: Int, percentile: Float): Float {
    val target = (count * percentile).roundToInt().coerceIn(1, count)
    var accum = 0
    for (i in histogram.indices) {
        accum += histogram[i]
        if (accum >= target) return i / 255f
    }
    return 1f
}

private fun copyUriToCache(context: Context, uri: Uri): File {
    val outFile = File(context.cacheDir, "source_${System.currentTimeMillis()}.img")
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "input stream is null" }
        FileOutputStream(outFile).use { output -> input.copyTo(output) }
    }
    return outFile
}

private fun migrateDraftSourceIfNeeded(context: Context, storedSourcePath: String): File? {
    val storedSource = File(storedSourcePath)
    if (!storedSource.isFile) return null
    val draftSource = persistDraftSourceFile(context, storedSource.absolutePath) ?: return null
    saveDraftThumbnailFile(context, draftSource)
    if (draftSource.absolutePath != storedSource.absolutePath) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_DRAFT_SOURCE, draftSource.absolutePath)
            .apply()
    }
    return draftSource
}

private data class DraftRecoveryResolution(
    val sourceFile: File?,
    val missingLegacyCacheDraft: Boolean,
    val debugInfo: RecoveryDebugInfo
)

private fun resolveDraftRecovery(context: Context, storedSourcePath: String): DraftRecoveryResolution {
    val storedSource = File(storedSourcePath)
    val persistentSource = persistentDraftSourceFile(context)
    val storedExists = storedSource.isFile
    val persistentExists = persistentSource.isFile
    val storedInCache = storedSource.absolutePath.startsWith(context.cacheDir.absolutePath)
    val sourceFile = when {
        storedExists -> migrateDraftSourceIfNeeded(context, storedSource.absolutePath)
        storedInCache && persistentExists -> {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_DRAFT_SOURCE, persistentSource.absolutePath)
                .apply()
            persistentSource
        }
        storedSource.absolutePath == persistentSource.absolutePath && persistentExists -> persistentSource
        else -> null
    }
    return DraftRecoveryResolution(
        sourceFile = sourceFile,
        missingLegacyCacheDraft = storedInCache && !storedExists,
        debugInfo = RecoveryDebugInfo(
            draftSourcePath = sourceFile?.absolutePath ?: storedSource.absolutePath,
            draftSourceExists = sourceFile?.isFile == true || storedExists,
            filesDirDraftPath = persistentSource.absolutePath,
            filesDirDraftExists = persistentExists
        )
    )
}

private fun persistDraftSourceFile(context: Context, sourcePath: String): File? {
    val source = File(sourcePath).takeIf { it.isFile } ?: return null
    val draftSource = persistentDraftSourceFile(context)
    if (source.absolutePath == draftSource.absolutePath) return draftSource

    draftSource.parentFile?.mkdirs()
    val temp = File(draftSource.parentFile, "${draftSource.name}.tmp")
    source.inputStream().use { input ->
        FileOutputStream(temp).use { output -> input.copyTo(output) }
    }
    if (draftSource.exists()) draftSource.delete()
    check(temp.renameTo(draftSource)) { "failed to persist draft source" }
    return draftSource
}

private fun persistDraftSourceFileIfNeeded(context: Context, sourcePath: String): DraftSourceResult? {
    val source = File(sourcePath).takeIf { it.isFile } ?: return null
    val draftSource = persistentDraftSourceFile(context)
    if (source.absolutePath == draftSource.absolutePath) {
        return DraftSourceResult(draftSource, changed = false)
    }
    val currentDraftIsFresh = draftSource.isFile &&
        draftSource.length() == source.length() &&
        draftSource.lastModified() >= source.lastModified()
    if (currentDraftIsFresh) {
        return DraftSourceResult(draftSource, changed = false)
    }
    return persistDraftSourceFile(context, source.absolutePath)?.let { DraftSourceResult(it, changed = true) }
}

private fun persistDraftBitmapFile(context: Context, bitmap: Bitmap): File? {
    val draftSource = persistentDraftSourceFile(context)
    draftSource.parentFile?.mkdirs()
    val temp = File(draftSource.parentFile, "${draftSource.name}.tmp")
    FileOutputStream(temp).use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "failed to encode draft bitmap" }
    }
    if (draftSource.exists()) draftSource.delete()
    check(temp.renameTo(draftSource)) { "failed to persist draft bitmap" }
    return draftSource
}

private fun saveDraftThumbnailFile(context: Context, source: File) {
    runCatching {
        val thumbnail = decodeSampledMutableBitmapWithExif(source.absolutePath, maxSide = 512)
        try {
            FileOutputStream(persistentDraftThumbnailFile(context)).use { output ->
                thumbnail.compress(Bitmap.CompressFormat.JPEG, 90, output)
            }
        } finally {
            thumbnail.recycle()
        }
    }
}

private fun logDraftSaveFailure(t: Throwable) {
    Log.w(FLARE_GUARD_AI_TAG, "Draft autosave failed", t)
}

private fun persistentDraftSourceFile(context: Context): File =
    File(persistentDraftDirectory(context), DRAFT_SOURCE_FILE_NAME)

private fun persistentDraftThumbnailFile(context: Context): File =
    File(persistentDraftDirectory(context), DRAFT_THUMBNAIL_FILE_NAME)

private fun persistentDraftDirectory(context: Context): File =
    File(context.filesDir, "drafts/current").apply { mkdirs() }

private fun decodeSampledMutableBitmap(path: String, maxSide: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "지원하지 않는 이미지이거나 디코딩에 실패했습니다" }

    var sample = 1
    val longest = max(bounds.outWidth, bounds.outHeight)
    while (longest / sample > maxSide) sample *= 2

    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inMutable = true
    }
    val decoded = requireNotNull(BitmapFactory.decodeFile(path, options)) { "미리보기 디코딩에 실패했습니다" }
    if (decoded.config == Bitmap.Config.ARGB_8888 && decoded.isMutable) return decoded
    val mutable = decoded.copy(Bitmap.Config.ARGB_8888, true)
    decoded.recycle()
    return mutable
}

private fun decodeSampledMutableBitmapWithExif(path: String, maxSide: Int): Bitmap {
    return applyExifOrientation(path, decodeSampledMutableBitmap(path, maxSide))
}

private fun applyExifOrientation(path: String, bitmap: Bitmap): Bitmap {
    val orientation = runCatching {
        ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_NORMAL -> return bitmap
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        else -> return bitmap
    }

    val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (transformed !== bitmap) bitmap.recycle()
    val mutable = transformed.copy(Bitmap.Config.ARGB_8888, true)
    if (mutable !== transformed) transformed.recycle()
    Log.i(FLARE_GUARD_AI_TAG, "Applied EXIF orientation=$orientation -> ${mutable.width}x${mutable.height}")
    return mutable
}

private fun rotateBitmap90(bitmap: Bitmap): Bitmap {
    val matrix = Matrix().apply { postRotate(90f) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

internal fun renderEditedPreview(
    basePreview: Bitmap,
    params: EditParams,
    engines: EngineSelection,
    revision: Int,
    look: PresetColorLook? = null,
    quickEffects: List<ActiveQuickEffect> = emptyList()
): Bitmap {
    val copy = basePreview.copy(Bitmap.Config.ARGB_8888, true)
    return try {
        renderBitmapInNative(copy, params, engines, revision, look)
        applyActiveQuickEffectsToBitmap(copy, quickEffects, revision)
        applySelectedToneEngine(copy, engines.toneEngine)
        copy
    } catch (t: Throwable) {
        copy.recycle()
        throw t
    }
}

private data class NativeSpecialEffectOp(
    val effect: Int,
    val strength: Float
)

internal fun applyActiveQuickEffectsToBitmap(
    bitmap: Bitmap,
    quickEffects: List<ActiveQuickEffect>,
    revision: Int
) {
    quickEffects.forEach { effect ->
        effect.toNativeOperations().forEach { operation ->
            val result = NativePhotoCore.nativeApplySpecialEffectInPlace(
                bitmap = bitmap,
                effect = operation.effect,
                strength = operation.strength.coerceIn(0f, 1f),
                revision = revision
            )
            if (result < 0) {
                throw IllegalStateException("native special effect failed: effect=${operation.effect} code=$result")
            }
        }
    }
}

private fun renderEditedExport(
    sourcePath: String,
    params: EditParams,
    resolution: ExportResolution,
    engines: EngineSelection,
    revision: Int,
    look: PresetColorLook? = null,
    quickEffects: List<ActiveQuickEffect> = emptyList()
): Bitmap {
    // TODO v0.2: replace whole-bitmap export with ROI/tile rendering to reduce peak memory use.
    var decoded: Bitmap? = null
    var scaled: Bitmap? = null
    try {
        decoded = decodeSampledMutableBitmapWithExif(sourcePath, maxSide = EXPORT_MAX_SIDE)
        val working = decoded!!
        renderBitmapInNative(working, params, engines, revision, look)
        applyActiveQuickEffectsToBitmap(working, quickEffects, revision)
        applySelectedToneEngine(working, engines.toneEngine)
        scaled = scaleBitmapForExport(working, resolution)
        if (scaled !== working) {
            working.recycle()
        }
        val result = scaled
        decoded = null
        scaled = null
        return result
    } catch (t: Throwable) {
        if (scaled != null && scaled !== decoded && !scaled.isRecycled) scaled.recycle()
        if (decoded != null && !decoded.isRecycled) decoded.recycle()
        throw t
    }
}

private fun renderEditedExportFromBitmap(
    baseBitmap: Bitmap,
    params: EditParams,
    resolution: ExportResolution,
    engines: EngineSelection,
    revision: Int,
    look: PresetColorLook? = null,
    quickEffects: List<ActiveQuickEffect> = emptyList()
): Bitmap {
    var decoded: Bitmap? = null
    var scaled: Bitmap? = null
    try {
        decoded = baseBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val working = decoded!!
        renderBitmapInNative(working, params, engines, revision, look)
        applyActiveQuickEffectsToBitmap(working, quickEffects, revision)
        applySelectedToneEngine(working, engines.toneEngine)
        scaled = scaleBitmapForExport(working, resolution)
        if (scaled !== working) {
            working.recycle()
        }
        val result = scaled
        decoded = null
        scaled = null
        return result
    } catch (t: Throwable) {
        if (scaled != null && scaled !== decoded && !scaled.isRecycled) scaled.recycle()
        if (decoded != null && !decoded.isRecycled) decoded.recycle()
        throw t
    }
}

private fun renderBitmapInNative(
    bitmap: Bitmap,
    params: EditParams,
    engines: EngineSelection,
    revision: Int,
    look: PresetColorLook? = null
): Int {
    val result = NativePhotoCore.nativeRenderPreviewInPlace(
        bitmap,
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
        revision,
        look
    )
    if (result < 0) {
        throw IllegalStateException("native render failed: code=$result")
    }
    return result
}

private fun applySelectedToneEngine(bitmap: Bitmap, toneEngine: ToneEngine) {
    if (toneEngine == ToneEngine.Clahe) {
        applyClaheToneInPlace(bitmap, strength = 0.34f)
    }
}

private fun applyClaheToneInPlace(bitmap: Bitmap, strength: Float) {
    val width = bitmap.width
    val height = bitmap.height
    val safeStrength = strength.coerceIn(0f, 1f)
    if (safeStrength <= 0.001f || width < 16 || height < 16) return

    val tilesX = kotlin.math.min(12, max(2, (width + 255) / 256))
    val tilesY = kotlin.math.min(12, max(2, (height + 255) / 256))
    val tileW = (width + tilesX - 1) / tilesX
    val tileH = (height + tilesY - 1) / tilesY
    val luts = Array(tilesX * tilesY) { IntArray(256) }

    for (ty in 0 until tilesY) {
        val y0 = ty * tileH
        val y1 = kotlin.math.min(height, y0 + tileH)
        for (tx in 0 until tilesX) {
            val x0 = tx * tileW
            val x1 = kotlin.math.min(width, x0 + tileW)
            val tileWidth = x1 - x0
            val pixelCount = max(1, tileWidth * (y1 - y0))
            val histogram = IntArray(256)
            val row = IntArray(tileWidth)

            for (y in y0 until y1) {
                bitmap.getPixels(row, 0, tileWidth, x0, y, tileWidth, 1)
                for (pixel in row) histogram[lumaBin(pixel)] += 1
            }

            val limit = max(2, ((pixelCount / 256f) * 2.25f).roundToInt())
            var overflow = 0
            for (i in histogram.indices) {
                if (histogram[i] > limit) {
                    overflow += histogram[i] - limit
                    histogram[i] = limit
                }
            }
            val bonus = overflow / 256
            val remainder = overflow % 256
            for (i in histogram.indices) histogram[i] += bonus + if (i < remainder) 1 else 0

            var cdf = 0
            var cdfMin = 0
            var found = false
            for (i in histogram.indices) {
                cdf += histogram[i]
                if (!found && cdf > 0) {
                    cdfMin = cdf
                    found = true
                }
            }

            cdf = 0
            val denom = max(1, pixelCount - cdfMin).toFloat()
            val lut = luts[ty * tilesX + tx]
            for (i in histogram.indices) {
                cdf += histogram[i]
                val equalized = ((cdf - cdfMin) / denom).coerceIn(0f, 1f)
                lut[i] = (equalized * 255f).roundToInt().coerceIn(0, 255)
            }
        }
    }

    val rowPixels = IntArray(width)
    for (y in 0 until height) {
        bitmap.getPixels(rowPixels, 0, width, 0, y, width, 1)
        val gy = y.toFloat() / max(1, tileH) - 0.5f
        val gyFloor = floor(gy)
        val ty0 = gyFloor.toInt()
        val ty1 = ty0 + 1
        val fy = (gy - gyFloor).coerceIn(0f, 1f)

        for (x in 0 until width) {
            val pixel = rowPixels[x]
            val luma = lumaFloat(pixel)
            val bin = (luma * 255f).roundToInt().coerceIn(0, 255)
            val gx = x.toFloat() / max(1, tileW) - 0.5f
            val gxFloor = floor(gx)
            val tx0 = gxFloor.toInt()
            val tx1 = tx0 + 1
            val fx = (gx - gxFloor).coerceIn(0f, 1f)

            val m00 = lutValue(luts, tilesX, tilesY, tx0, ty0, bin)
            val m10 = lutValue(luts, tilesX, tilesY, tx1, ty0, bin)
            val m01 = lutValue(luts, tilesX, tilesY, tx0, ty1, bin)
            val m11 = lutValue(luts, tilesX, tilesY, tx1, ty1, bin)
            val mappedTop = lerpFloat(m00, m10, fx)
            val mappedBottom = lerpFloat(m01, m11, fx)
            val mapped = lerpFloat(mappedTop, mappedBottom, fy)

            val highlightGuard = 1f - 0.65f * smoothstepFloat(0.86f, 1f, luma)
            val shadowGuard = 0.45f + 0.55f * smoothstepFloat(0.025f, 0.18f, luma)
            val localStrength = safeStrength * highlightGuard * shadowGuard
            val newLuma = lerpFloat(luma, mapped, localStrength)
            val scale = newLuma / max(0.015f, luma)
            rowPixels[x] = scalePixelLuma(pixel, scale)
        }
        bitmap.setPixels(rowPixels, 0, width, 0, y, width, 1)
    }
}

private fun lumaBin(pixel: Int): Int = (lumaFloat(pixel) * 255f).roundToInt().coerceIn(0, 255)

private fun lumaFloat(pixel: Int): Float {
    val r = ((pixel shr 16) and 0xff) / 255f
    val g = ((pixel shr 8) and 0xff) / 255f
    val b = (pixel and 0xff) / 255f
    return (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
}

private fun lutValue(luts: Array<IntArray>, tilesX: Int, tilesY: Int, tx: Int, ty: Int, bin: Int): Float {
    val safeTx = tx.coerceIn(0, tilesX - 1)
    val safeTy = ty.coerceIn(0, tilesY - 1)
    val safeBin = bin.coerceIn(0, 255)
    return luts[safeTy * tilesX + safeTx][safeBin] / 255f
}

private fun scalePixelLuma(pixel: Int, scale: Float): Int {
    val alpha = pixel and -0x1000000
    val r = ((((pixel shr 16) and 0xff) / 255f) * scale * 255f).roundToInt().coerceIn(0, 255)
    val g = ((((pixel shr 8) and 0xff) / 255f) * scale * 255f).roundToInt().coerceIn(0, 255)
    val b = (((pixel and 0xff) / 255f) * scale * 255f).roundToInt().coerceIn(0, 255)
    return alpha or (r shl 16) or (g shl 8) or b
}

private fun smoothstepFloat(edge0: Float, edge1: Float, value: Float): Float {
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun lerpFloat(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun scaleBitmapForExport(bitmap: Bitmap, resolution: ExportResolution): Bitmap {
    if (resolution.scalePercent >= 100) return bitmap
    val scale = resolution.scalePercent / 100f
    val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}

private fun saveBitmapToGallery(
    context: Context,
    bitmap: Bitmap,
    fileName: String,
    format: ExportFormat
): Uri {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/KeplerStudio")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }

    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("저장 위치를 만들 수 없습니다")

    try {
        if (format == ExportFormat.Heif) {
            writeHeifToUri(context, uri, bitmap)
        } else {
            writeCompressedBitmapToUri(context, uri, bitmap, format)
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        val updatedRows = resolver.update(uri, values, null, null)
        if (updatedRows <= 0) {
            resolver.delete(uri, null, null)
            error("failed to publish media store row")
        }
        return uri
    } catch (t: Throwable) {
        resolver.delete(uri, null, null)
        throw t
    }
}

private fun writeCompressedBitmapToUri(
    context: Context,
    uri: Uri,
    bitmap: Bitmap,
    format: ExportFormat
) {
    val compressFormat = when (format) {
        ExportFormat.Jpeg -> Bitmap.CompressFormat.JPEG
        ExportFormat.Png -> Bitmap.CompressFormat.PNG
        ExportFormat.Webp -> Bitmap.CompressFormat.WEBP
        ExportFormat.Heif -> error("HEIF는 별도 인코더를 사용합니다")
    }
    val quality = if (format == ExportFormat.Png) 100 else 95
    context.contentResolver.openOutputStream(uri)?.use { output ->
        check(bitmap.compress(compressFormat, quality, output)) { "이미지 압축에 실패했습니다" }
    } ?: error("저장 스트림을 열 수 없습니다")
}

private fun writeHeifToUri(context: Context, uri: Uri, bitmap: Bitmap) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        error("HEIF 저장은 Android 9 이상에서 지원됩니다")
    }

    val descriptor = context.contentResolver.openFileDescriptor(uri, "w")
        ?: error("HEIF 저장 스트림을 열 수 없습니다")

    descriptor.use { pfd ->
        val writer = HeifWriter.Builder(
            pfd.fileDescriptor,
            bitmap.width,
            bitmap.height,
            HeifWriter.INPUT_MODE_BITMAP
        )
            .setMaxImages(1)
            .setQuality(95)
            .build()

        try {
            writer.start()
            writer.addBitmap(bitmap)
            writer.stop(0)
        } finally {
            writer.close()
        }
    }
}

private fun EditorUiState.withSavedDraft(context: Context): EditorUiState {
    val saved = saveDraftSnapshotSafely(context, this) ?: return this
    return copy(
        draftSavedAtMillis = saved.savedAtMillis,
        draftSourcePath = saved.sourcePath
    )
}

private data class DraftSaveResult(
    val sourcePath: String,
    val savedAtMillis: Long
)

private data class DraftSavePayload(
    val sourcePath: String?,
    val baseBitmapDirty: Boolean,
    val dirtyBitmapCopy: Bitmap?,
    val params: EditParams,
    val exportFormat: ExportFormat,
    val exportResolution: ExportResolution,
    val presetLook: PresetColorLook?,
    val activeQuickEffects: List<ActiveQuickEffect>
)

private data class DraftSourceResult(
    val file: File,
    val changed: Boolean
)

private enum class QuickEffectGroup {
    Remove,
    Optics,
    Blur
}

private fun saveDraftSnapshotSafely(context: Context, state: EditorUiState): DraftSaveResult? {
    val payload = try {
        createDraftSavePayload(state)
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        logDraftSaveFailure(t)
        null
    } ?: return null
    return try {
        saveDraftSnapshot(context, payload)
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        logDraftSaveFailure(t)
        null
    } finally {
        payload.dirtyBitmapCopy?.takeIf { !it.isRecycled }?.recycle()
    }
}

private fun createDraftSavePayload(state: EditorUiState): DraftSavePayload {
    val dirtyBitmapCopy = when {
        !state.baseBitmapDirty && state.sourcePath != null -> null
        else -> (state.originalPreviewBitmap ?: state.previewBitmap)?.copy(Bitmap.Config.ARGB_8888, true)
    }
    if (state.baseBitmapDirty && dirtyBitmapCopy == null) {
        error("draft save bitmap is missing")
    }
    return DraftSavePayload(
        sourcePath = state.sourcePath,
        baseBitmapDirty = state.baseBitmapDirty,
        dirtyBitmapCopy = dirtyBitmapCopy,
        params = state.params,
        exportFormat = state.exportFormat,
        exportResolution = state.exportResolution,
        presetLook = state.presetLook,
        activeQuickEffects = state.activeQuickEffects
    )
}

private fun saveDraftSnapshot(context: Context, payload: DraftSavePayload): DraftSaveResult? {
    val draftSource = when {
        !payload.baseBitmapDirty && payload.sourcePath != null -> persistDraftSourceFileIfNeeded(context, payload.sourcePath)
        payload.dirtyBitmapCopy != null -> persistDraftBitmapFile(context, payload.dirtyBitmapCopy)?.let { DraftSourceResult(it, changed = true) }
        else -> null
    } ?: return null
    if (draftSource.changed) saveDraftThumbnailFile(context, draftSource.file)
    val savedAt = System.currentTimeMillis()
    val committed = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
        .putString(KEY_DRAFT_SOURCE, draftSource.file.absolutePath)
        .putFloat(KEY_DRAFT_EXPOSURE, payload.params.exposure)
        .putFloat(KEY_DRAFT_CONTRAST, payload.params.contrast)
        .putFloat(KEY_DRAFT_SHADOWS, payload.params.shadows)
        .putFloat(KEY_DRAFT_HIGHLIGHTS, payload.params.highlights)
        .putFloat(KEY_DRAFT_WHITES, payload.params.whites)
        .putFloat(KEY_DRAFT_BLACKS, payload.params.blacks)
        .putFloat(KEY_DRAFT_TEMPERATURE, payload.params.temperature)
        .putFloat(KEY_DRAFT_TINT, payload.params.tint)
        .putFloat(KEY_DRAFT_SATURATION, payload.params.saturation)
        .putFloat(KEY_DRAFT_VIBRANCE, payload.params.vibrance)
        .putFloat(KEY_DRAFT_CLARITY, payload.params.clarity)
        .putFloat(KEY_DRAFT_DEHAZE, payload.params.dehaze)
        .putFloat(KEY_DRAFT_SHARPNESS, payload.params.sharpness)
        .putFloat(KEY_DRAFT_NOISE_REDUCTION, payload.params.noiseReduction)
        .putFloat(KEY_DRAFT_LUMINANCE_NOISE_REDUCTION, payload.params.luminanceNoiseReduction)
        .putFloat(KEY_DRAFT_COLOR_NOISE_REDUCTION, payload.params.colorNoiseReduction)
        .putFloat(KEY_DRAFT_NOISE_DETAIL_PROTECTION, payload.params.noiseDetailProtection)
        .putString(KEY_DRAFT_FORMAT, payload.exportFormat.name)
        .putString(KEY_DRAFT_RESOLUTION, payload.exportResolution.name)
        .putString(KEY_DRAFT_LOOK, presetColorLookToJson(payload.presetLook)?.toString())
        .putString(KEY_DRAFT_QUICK_EFFECTS, payload.activeQuickEffects.toDraftString())
        .putLong(KEY_DRAFT_SAVED_AT, savedAt)
        .commit()
    if (!committed) return null
    return DraftSaveResult(draftSource.file.absolutePath, savedAt)
}

private fun clearDraftPrefs(context: Context, preserveSourcePath: String? = null) {
    val draftSource = persistentDraftSourceFile(context)
    if (preserveSourcePath == null || draftSource.absoluteFile != File(preserveSourcePath).absoluteFile) {
        draftSource.delete()
    }
    persistentDraftThumbnailFile(context).delete()
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
        .remove(KEY_DRAFT_SOURCE)
        .remove(KEY_DRAFT_EXPOSURE)
        .remove(KEY_DRAFT_CONTRAST)
        .remove(KEY_DRAFT_SHADOWS)
        .remove(KEY_DRAFT_HIGHLIGHTS)
        .remove(KEY_DRAFT_WHITES)
        .remove(KEY_DRAFT_BLACKS)
        .remove(KEY_DRAFT_TEMPERATURE)
        .remove(KEY_DRAFT_TINT)
        .remove(KEY_DRAFT_SATURATION)
        .remove(KEY_DRAFT_VIBRANCE)
        .remove(KEY_DRAFT_CLARITY)
        .remove(KEY_DRAFT_DEHAZE)
        .remove(KEY_DRAFT_SHARPNESS)
        .remove(KEY_DRAFT_NOISE_REDUCTION)
        .remove(KEY_DRAFT_LUMINANCE_NOISE_REDUCTION)
        .remove(KEY_DRAFT_COLOR_NOISE_REDUCTION)
        .remove(KEY_DRAFT_NOISE_DETAIL_PROTECTION)
        .remove(KEY_DRAFT_FORMAT)
        .remove(KEY_DRAFT_RESOLUTION)
        .remove(KEY_DRAFT_LOOK)
        .remove(KEY_DRAFT_QUICK_EFFECTS)
        .remove(KEY_DRAFT_SAVED_AT)
        .apply()
}

private fun List<ActiveQuickEffect>.toggle(effect: ActiveQuickEffect): List<ActiveQuickEffect> {
    val group = effect.kind.group()
    val current = firstOrNull { it.kind.group() == group }
    return if (current != null && current.matches(effect)) {
        filterNot { it.kind.group() == group }
    } else {
        filterNot { it.kind.group() == group } + effect
    }
}

private fun ActiveQuickEffect.matches(other: ActiveQuickEffect): Boolean =
    kind == other.kind && strength == other.strength

private fun QuickEffectKind.group(): QuickEffectGroup = when (this) {
    QuickEffectKind.SpotCleanup -> QuickEffectGroup.Remove
    QuickEffectKind.ChromaticAberrationReduction,
    QuickEffectKind.VignetteCorrection,
    QuickEffectKind.OpticsCorrection -> QuickEffectGroup.Optics
    QuickEffectKind.SoftBlur -> QuickEffectGroup.Blur
}

private fun ActiveQuickEffect.toNativeOperations(): List<NativeSpecialEffectOp> = when (kind) {
    QuickEffectKind.SpotCleanup -> listOf(NativeSpecialEffectOp(effect = 0, strength = 0.58f))
    QuickEffectKind.ChromaticAberrationReduction -> listOf(NativeSpecialEffectOp(effect = 1, strength = 0.62f))
    QuickEffectKind.VignetteCorrection -> listOf(NativeSpecialEffectOp(effect = 2, strength = 0.45f))
    QuickEffectKind.OpticsCorrection -> listOf(
        NativeSpecialEffectOp(effect = 1, strength = 0.62f),
        NativeSpecialEffectOp(effect = 2, strength = 0.45f)
    )
    QuickEffectKind.SoftBlur -> listOf(
        NativeSpecialEffectOp(effect = 3, strength = when (strength) {
            QuickEffectStrength.Weak -> 0.22f
            QuickEffectStrength.Medium -> 0.38f
            QuickEffectStrength.Strong -> 0.58f
        })
    )
}

private fun Float.toQuickEffectStrength(): QuickEffectStrength = when {
    this < 0.30f -> QuickEffectStrength.Weak
    this < 0.48f -> QuickEffectStrength.Medium
    else -> QuickEffectStrength.Strong
}

private fun List<ActiveQuickEffect>.toDraftString(): String =
    joinToString("|") { "${it.kind.name}:${it.strength.name}" }

private fun String?.parseQuickEffects(): List<ActiveQuickEffect> =
    this
        ?.split('|')
        ?.mapNotNull { token ->
            val parts = token.split(':')
            if (parts.size != 2) return@mapNotNull null
            val kind = runCatching { enumValueOf<QuickEffectKind>(parts[0]) }.getOrNull() ?: return@mapNotNull null
            val strength = runCatching { enumValueOf<QuickEffectStrength>(parts[1]) }.getOrDefault(QuickEffectStrength.Medium)
            ActiveQuickEffect(kind = kind, strength = strength)
        }
        .orEmpty()

private fun cleanupTemporarySourceFiles(context: Context, activeSourcePath: String?): Int {
    val now = System.currentTimeMillis()
    val maxAgeMs = 7L * 24L * 60L * 60L * 1000L
    val activePath = activeSourcePath?.let { File(it).absolutePath }
    val files = context.cacheDir.listFiles { file ->
        file.isFile && file.name.startsWith("source_") && file.name.endsWith(".img")
    }.orEmpty()

    var removed = 0
    files.forEach { file ->
        val expired = now - file.lastModified() > maxAgeMs
        val isActive = activePath != null && file.absolutePath == activePath
        if (expired && !isActive && file.delete()) removed += 1
    }
    return removed
}

private fun rememberSavedExport(
    context: Context,
    item: SavedExport,
    retention: ExportHistoryRetention
): List<SavedExport> {
    val next = (listOf(item) + loadSavedExportsFromPrefs(context).filter { it.uriString != item.uriString }).take(60)
    return pruneSavedExportsIfNeeded(context, next, retention)
}

private fun pruneSavedExportsIfNeeded(
    context: Context,
    items: List<SavedExport>,
    retention: ExportHistoryRetention
): List<SavedExport> {
    val days = retention.days
    val pruned = if (days == null) {
        items
    } else {
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        items.filter { it.timestampMillis >= cutoff }
    }
    saveSavedExportsToPrefs(context, pruned)
    return pruned
}

private fun saveSavedExportsToPrefs(context: Context, items: List<SavedExport>) {
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
        .putString(KEY_SAVED_EXPORTS, items.joinToString("\n") { encodeSavedExport(it) })
        .apply()
}

private fun clearSavedExportsPrefs(context: Context) {
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
        .remove(KEY_SAVED_EXPORTS)
        .apply()
}

private fun loadSavedExportsFromPrefs(context: Context): List<SavedExport> {
    val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_SAVED_EXPORTS, null)
        ?: return emptyList()
    return raw.lines().mapNotNull { decodeSavedExport(it) }
}

private fun rebuildSavedExportsFromMediaStore(context: Context): List<SavedExport> {
    val projection = buildList {
        add(MediaStore.Images.Media._ID)
        add(MediaStore.Images.Media.DISPLAY_NAME)
        add(MediaStore.Images.Media.MIME_TYPE)
        add(MediaStore.Images.Media.WIDTH)
        add(MediaStore.Images.Media.HEIGHT)
        add(MediaStore.Images.Media.DATE_ADDED)
        add(MediaStore.Images.Media.RELATIVE_PATH)
    }.toTypedArray()
    val items = mutableListOf<SavedExport>()
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        null,
        null,
        "${MediaStore.Images.Media.DATE_ADDED} DESC"
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndex(MediaStore.Images.Media._ID)
        val nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
        val mimeIndex = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
        val widthIndex = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
        val heightIndex = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
        val dateAddedIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
        val relativePathIndex = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
        if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0 || dateAddedIndex < 0) return@use
        while (cursor.moveToNext() && items.size < 60) {
            val displayName = cursor.getString(nameIndex).orEmpty()
            val inKeplerStudio = relativePathIndex >= 0 &&
                cursor.getString(relativePathIndex).orEmpty().startsWith("${Environment.DIRECTORY_PICTURES}/KeplerStudio")
            if (!inKeplerStudio) continue
            val id = cursor.getLong(idIndex)
            val safeDisplayName = displayName.ifBlank { "KeplerStudio_$id" }
            val mimeType = cursor.getString(mimeIndex).orEmpty()
            val width = if (widthIndex >= 0) cursor.getInt(widthIndex) else 0
            val height = if (heightIndex >= 0) cursor.getInt(heightIndex) else 0
            val dateAddedSeconds = cursor.getLong(dateAddedIndex)
            items += SavedExport(
                displayName = safeDisplayName,
                uriString = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()).toString(),
                formatLabel = mimeTypeToExportLabel(mimeType, safeDisplayName),
                resolutionLabel = if (width > 0 && height > 0) "${width}x${height}" else "원본",
                timestampMillis = if (dateAddedSeconds > 0L) dateAddedSeconds * 1000L else System.currentTimeMillis()
            )
        }
    }
    if (items.isNotEmpty()) saveSavedExportsToPrefs(context, items)
    return items
}

private fun mimeTypeToExportLabel(mimeType: String, displayName: String): String =
    when {
        mimeType.equals("image/jpeg", ignoreCase = true) -> "JPEG"
        mimeType.equals("image/png", ignoreCase = true) -> "PNG"
        mimeType.equals("image/webp", ignoreCase = true) -> "WebP"
        mimeType.equals("image/heic", ignoreCase = true) || mimeType.equals("image/heif", ignoreCase = true) -> "HEIF"
        displayName.endsWith(".jpg", ignoreCase = true) || displayName.endsWith(".jpeg", ignoreCase = true) -> "JPEG"
        displayName.endsWith(".png", ignoreCase = true) -> "PNG"
        displayName.endsWith(".webp", ignoreCase = true) -> "WebP"
        displayName.endsWith(".heic", ignoreCase = true) || displayName.endsWith(".heif", ignoreCase = true) -> "HEIF"
        else -> "사진"
    }

private fun saveExportHistoryRetention(context: Context, retention: ExportHistoryRetention) {
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
        .putString(KEY_EXPORT_HISTORY_RETENTION, retention.name)
        .apply()
}

private fun loadExportHistoryRetention(context: Context): ExportHistoryRetention =
    enumValueOrDefault(
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_EXPORT_HISTORY_RETENTION, null),
        ExportHistoryRetention.Never
    )

private fun saveEngineSelection(context: Context, engines: EngineSelection) {
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
        .putString(KEY_NOISE_ENGINE, engines.noiseEngine.name)
        .putString(KEY_DETAIL_ENGINE, engines.detailEngine.name)
        .putString(KEY_TONE_ENGINE, engines.toneEngine.name)
        .putString(KEY_HAZE_ENGINE, engines.hazeEngine.name)
        .apply()
}

private fun loadEngineSelection(context: Context): EngineSelection {
    val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    return EngineSelection(
        noiseEngine = enumValueOrDefault(prefs.getString(KEY_NOISE_ENGINE, null), NoiseEngine.FastEdgeAware),
        detailEngine = enumValueOrDefault(prefs.getString(KEY_DETAIL_ENGINE, null), DetailEngine.MaskedUnsharp),
        toneEngine = enumValueOrDefault(prefs.getString(KEY_TONE_ENGINE, null), ToneEngine.HistogramAuto),
        hazeEngine = enumValueOrDefault(prefs.getString(KEY_HAZE_ENGINE, null), DehazeEngine.FastContrast)
    ).coerceImplemented()
}

private fun EngineSelection.coerceImplemented(): EngineSelection = copy(
    noiseEngine = noiseEngine.takeIf { it in IMPLEMENTED_NOISE_ENGINES } ?: NoiseEngine.FastEdgeAware,
    detailEngine = detailEngine.takeIf { it in IMPLEMENTED_DETAIL_ENGINES } ?: DetailEngine.MaskedUnsharp,
    toneEngine = toneEngine.takeIf { it in IMPLEMENTED_TONE_ENGINES } ?: ToneEngine.HistogramAuto,
    hazeEngine = hazeEngine.takeIf { it in IMPLEMENTED_DEHAZE_ENGINES } ?: DehazeEngine.FastContrast
)

private fun encodeSavedExport(item: SavedExport): String = listOf(
    item.displayName,
    item.uriString,
    item.formatLabel,
    item.resolutionLabel,
    item.timestampMillis.toString()
).joinToString("|") { it.replace("|", " ").replace("\n", " ") }

private fun decodeSavedExport(raw: String): SavedExport? {
    val parts = raw.split("|")
    if (parts.size != 5) return null
    return SavedExport(
        displayName = parts[0],
        uriString = parts[1],
        formatLabel = parts[2],
        resolutionLabel = parts[3],
        timestampMillis = parts[4].toLongOrNull() ?: return null
    )
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T =
    runCatching { enumValueOf<T>(name ?: return default) }.getOrDefault(default)

private fun exportTimestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

private const val FLARE_GUARD_AI_TAG = "KeplerFlareAI"
private const val EDITOR_HISTORY_MAX = 5
private const val EXPORT_MAX_SIDE = 8192
private const val DRAFT_SOURCE_FILE_NAME = "source.img"
private const val DRAFT_THUMBNAIL_FILE_NAME = "thumbnail.jpg"
private const val PREF_NAME = "kepler_studio_editor"
private const val KEY_SAVED_EXPORTS = "saved_exports"
private const val KEY_EXPORT_HISTORY_RETENTION = "export_history_retention"
private const val KEY_NOISE_ENGINE = "noise_engine"
private const val KEY_DETAIL_ENGINE = "detail_engine"
private const val KEY_TONE_ENGINE = "tone_engine"
private const val KEY_HAZE_ENGINE = "haze_engine"
private const val KEY_DRAFT_SOURCE = "draft_source"
private const val KEY_DRAFT_EXPOSURE = "draft_exposure"
private const val KEY_DRAFT_CONTRAST = "draft_contrast"
private const val KEY_DRAFT_SHADOWS = "draft_shadows"
private const val KEY_DRAFT_HIGHLIGHTS = "draft_highlights"
private const val KEY_DRAFT_WHITES = "draft_whites"
private const val KEY_DRAFT_BLACKS = "draft_blacks"
private const val KEY_DRAFT_TEMPERATURE = "draft_temperature"
private const val KEY_DRAFT_TINT = "draft_tint"
private const val KEY_DRAFT_SATURATION = "draft_saturation"
private const val KEY_DRAFT_VIBRANCE = "draft_vibrance"
private const val KEY_DRAFT_CLARITY = "draft_clarity"
private const val KEY_DRAFT_DEHAZE = "draft_dehaze"
private const val KEY_DRAFT_SHARPNESS = "draft_sharpness"
private const val KEY_DRAFT_NOISE_REDUCTION = "draft_noise_reduction"
private const val KEY_DRAFT_LUMINANCE_NOISE_REDUCTION = "draft_luminance_noise_reduction"
private const val KEY_DRAFT_COLOR_NOISE_REDUCTION = "draft_color_noise_reduction"
private const val KEY_DRAFT_NOISE_DETAIL_PROTECTION = "draft_noise_detail_protection"
private const val KEY_DRAFT_FORMAT = "draft_format"
private const val KEY_DRAFT_RESOLUTION = "draft_resolution"
private const val KEY_DRAFT_LOOK = "draft_look"
private const val KEY_DRAFT_QUICK_EFFECTS = "draft_quick_effects"
private const val KEY_DRAFT_SAVED_AT = "draft_saved_at"
internal val IMPLEMENTED_NOISE_ENGINES = listOf(
    NoiseEngine.FastEdgeAware,
    NoiseEngine.GuidedFilter,
    NoiseEngine.NonLocalMeansLite
)
internal val IMPLEMENTED_DETAIL_ENGINES = listOf(DetailEngine.MaskedUnsharp)
internal val IMPLEMENTED_TONE_ENGINES = listOf(ToneEngine.HistogramAuto, ToneEngine.Clahe)
internal val IMPLEMENTED_DEHAZE_ENGINES = listOf(DehazeEngine.FastContrast)
