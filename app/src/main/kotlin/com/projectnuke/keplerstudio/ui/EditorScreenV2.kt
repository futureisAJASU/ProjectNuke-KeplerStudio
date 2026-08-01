package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.projectnuke.keplerstudio.BuildConfig
import com.projectnuke.keplerstudio.editor.DehazeEngine
import com.projectnuke.keplerstudio.editor.DetailEngine
import com.projectnuke.keplerstudio.editor.CropState
import com.projectnuke.keplerstudio.editor.CorrectionEngine
import com.projectnuke.keplerstudio.editor.CorrectionEngineState
import com.projectnuke.keplerstudio.editor.DebugFeatureOverrides
import com.projectnuke.keplerstudio.editor.PreviewResultClass
import com.projectnuke.keplerstudio.editor.EditParams
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.ExportFormat
import com.projectnuke.keplerstudio.editor.ExportHistoryRetention
import com.projectnuke.keplerstudio.editor.ExportResolution
import com.projectnuke.keplerstudio.editor.ExperimentalLabController
import com.projectnuke.keplerstudio.editor.ModelAvailabilityRegistry
import com.projectnuke.keplerstudio.editor.ModelFeature
import com.projectnuke.keplerstudio.editor.ExperimentalComparisonStore
import com.projectnuke.keplerstudio.editor.OwnedDebugComparisonArtifact
import com.projectnuke.keplerstudio.editor.FlareGuardRoute
import com.projectnuke.keplerstudio.editor.IMPLEMENTED_DEHAZE_ENGINES
import com.projectnuke.keplerstudio.editor.IMPLEMENTED_DETAIL_ENGINES
import com.projectnuke.keplerstudio.editor.IMPLEMENTED_NOISE_ENGINES
import com.projectnuke.keplerstudio.editor.IMPLEMENTED_TONE_ENGINES
import com.projectnuke.keplerstudio.editor.NoiseEngine
import com.projectnuke.keplerstudio.editor.NativeRenderRoute
import com.projectnuke.keplerstudio.editor.PresetColorLook
import com.projectnuke.keplerstudio.editor.PreviewGeometry
import com.projectnuke.keplerstudio.editor.RecoveryDebugInfo
import com.projectnuke.keplerstudio.editor.RemasterRoute
import com.projectnuke.keplerstudio.editor.RouteResolver
import com.projectnuke.keplerstudio.editor.SavedExport
import com.projectnuke.keplerstudio.editor.SelectionLayer
import com.projectnuke.keplerstudio.editor.SelectionPaintSettings
import com.projectnuke.keplerstudio.editor.ToneEngine
import com.projectnuke.keplerstudio.editor.SubjectSelectionRoute
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.max

private val V2AppBackground = Color(0xFF101010)
private val V2TopBarBackground = Color(0xFF171717)
private val V2PanelBackground = Color(0xFF1B1B1B)
private val V2PreviewBackground = Color(0xFF000000)
private val V2RailBackground = Color(0xFF141414)
private val V2CardBackground = Color(0xFF242424)
private val V2SelectedMenuBackground = Color(0xFF343434)
private val V2BadgeBackground = Color(0xCC000000)
private val V2Accent = Color(0xFFE6E6E6)
private val V2TextPrimary = Color(0xFFF2F2F2)
private val V2TextSecondary = Color(0xFFC8C8C8)
private val V2TextMuted = Color(0xFF8E8E8E)
private val V2ButtonTextDark = Color(0xFF111111)
private const val ChromeAnimationMillis = 320
private const val TransientMessageMillis = 2_000L

private val V2DarkColors = darkColorScheme(
    primary = V2Accent,
    onPrimary = V2ButtonTextDark,
    background = V2AppBackground,
    onBackground = V2TextPrimary,
    surface = V2PanelBackground,
    onSurface = V2TextPrimary
)

internal enum class EditorDestination(val label: String) {
    Editor("편집"),
    Saved("저장 기록"),
    Settings("설정")
}

private enum class V2EditorTool(val label: String, val description: String) {
    Auto("자동", "빠른 자동 보정을 적용합니다"),
    Remaster("리마스터", "모델 상태와 마스크 기반 보조 보정을 확인합니다"),
    Profiles("프로필", "전용 LUT 자산이 없어서 현재는 참고용 상태만 안내합니다"),
    Presets("프리셋", "저장한 보정값을 적용하거나 JSON으로 백업합니다"),
    Crop("자르기", "비율, 회전, 수평 기반 자르기를 적용합니다"),
    Masking("마스킹", "피사체 선택과 브러시 마스크를 편집합니다"),
    Remove("제거", "작은 결함 완화 같은 기본 정리 도구를 제공합니다"),
    Light("조명", "노출, 대비, 하이라이트, 그림자를 조정합니다"),
    Color("색상", "색온도, 색조, 생동감과 채도를 조정합니다"),
    Effects("효과", "효과 계열 파라미터를 조정합니다"),
    Detail("디테일", "선명도와 노이즈 감소를 조정합니다"),
    Optics("광학", "색수차 완화와 비네팅 보정을 적용합니다"),
    Geometry("기하", "원근 보정은 아직 제외하고 수평 관련 MVP 상태만 안내합니다"),
    Blur("블러", "기본 소프트 블러를 적용합니다"),
    Model("모델", "현재 연결된 모델 상태와 규칙 기반 보조 기능을 보여줍니다")
}

@Composable
fun EditorScreenV2(viewModel: EditorViewModel) {
    val state by viewModel.uiState.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            viewModel.openImage(uri)
        }
    }
    var selectedTab by rememberSaveable { mutableStateOf(EditorDestination.Editor) }
    var selectedTool by rememberSaveable { mutableStateOf(V2EditorTool.Light) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var panelCollapsed by remember { mutableStateOf(false) }
    var chromeHidden by remember { mutableStateOf(false) }
    var histogramVisible by rememberSaveable { mutableStateOf(false) }
    var histogramModeName by rememberSaveable {
        mutableStateOf(PreviewHistogramMode.Luminance.name)
    }
    var gridVisible by rememberSaveable { mutableStateOf(false) }
    val histogramMode =
        PreviewHistogramMode.entries.firstOrNull { it.name == histogramModeName }
            ?: PreviewHistogramMode.Luminance
    val hideChromeForPreview = selectedTab == EditorDestination.Editor && chromeHidden
    val chromeTween = tween<Int>(durationMillis = ChromeAnimationMillis, easing = FastOutSlowInEasing)
    val alphaTween = tween<Float>(durationMillis = 220, easing = FastOutSlowInEasing)

    BackHandler(enabled = selectedTab != EditorDestination.Editor || chromeHidden) {
        if (chromeHidden) {
            chromeHidden = false
        } else {
            selectedTab = EditorDestination.Editor
        }
    }

    MaterialTheme(colorScheme = V2DarkColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = V2AppBackground) {
            Column(modifier = Modifier.fillMaxSize().background(V2AppBackground)) {
                AnimatedVisibility(
                    visible = !hideChromeForPreview,
                    enter = slideInVertically(animationSpec = chromeTween, initialOffsetY = { -it }) + fadeIn(animationSpec = alphaTween),
                    exit = slideOutVertically(animationSpec = chromeTween, targetOffsetY = { -it }) + fadeOut(animationSpec = alphaTween)
                ) {
                    V2TopBar(
                        nativeVersion = state.nativeVersion,
                        correctionEngineState = state.correctionEngineState,
                        selectedTab = selectedTab,
                        hasImage = state.previewBitmap != null,
                        isBusy = state.isBusy,
                        canExport = state.previewBitmap != null && !state.isBusy,
                        canUndo = state.canUndo,
                        canRedo = state.canRedo,
                        onTabSelected = {
                            chromeHidden = false
                            selectedTab = it
                        },
                        onOpen = { picker.launch("image/*") },
                        onUndo = viewModel::undoEdit,
                        onRedo = viewModel::redoEdit,
                        onRotate = viewModel::rotatePreview90,
                        onReset = { showResetDialog = true },
                        onSave = { showExportDialog = true }
                    )
                }

                when (selectedTab) {
                    EditorDestination.Editor -> {
                        V2PreviewArea(
                            bitmap = state.previewBitmap,
                            originalBitmap = state.originalPreviewBitmap,
                            viewModel = viewModel,
                            selectedTool = selectedTool,
                            cropState = state.cropState,
                            isBusy = state.isBusy,
                            message = state.message,
                            chromeHidden = chromeHidden,
                            histogramVisible = histogramVisible,
                            histogramMode = histogramMode,
                            gridVisible = gridVisible,
                            selectionLayers = state.selectionLayers,
                            activeSelectionLayerId = state.activeSelectionLayerId,
                            selectionPaintSettings = state.selectionPaintSettings,
                            showSelectionOverlay = state.showSelectionOverlay,
                            onToggleHistogram = { histogramVisible = !histogramVisible },
                            onToggleHistogramMode = {
                                histogramModeName =
                                    if (histogramMode == PreviewHistogramMode.Luminance) {
                                        PreviewHistogramMode.RGB.name
                                    } else {
                                        PreviewHistogramMode.Luminance.name
                                    }
                            },
                            onToggleGrid = { gridVisible = !gridVisible },
                            onCropRectChanged = viewModel::updateCropRect,
                            onToggleChrome = {
                                if (state.previewBitmap != null) chromeHidden = !chromeHidden
                            },
                            onPaintAt = { mx, my -> viewModel.paintActiveSelectionAt(mx, my) },
                            onStrokeStart = { viewModel.beginBrushStroke() },
                            onStrokeEnd = { viewModel.finishBrushStroke() },
                            onStrokeCancel = { viewModel.cancelBrushStroke() },
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        )
                        AnimatedVisibility(
                            visible = !chromeHidden,
                            enter = slideInVertically(animationSpec = chromeTween, initialOffsetY = { it }) + fadeIn(animationSpec = alphaTween),
                            exit = slideOutVertically(animationSpec = chromeTween, targetOffsetY = { it }) + fadeOut(animationSpec = alphaTween)
                        ) {
                            V2AdjustmentPanel(
                                editorViewModel = viewModel,
                                selectedTool = selectedTool,
                                params = state.params,
                                activeLook = state.presetLook,
                                controlsEnabled = !state.isBusy,
                                panelCollapsed = panelCollapsed,
                                onTogglePanel = { panelCollapsed = !panelCollapsed },
                                onFullScreen = { chromeHidden = true },
                                onToolSelected = { selectedTool = it },
                                onAutoEnhance = { viewModel.applyAutoEnhance() },
                                onChange = viewModel::updateParams
                            )
                        }
                    }
                    EditorDestination.Saved -> V2SavedScreen(
                        savedExports = state.savedExports,
                        maintenanceBusy = state.maintenanceBusy,
                        onRemoveSavedExport = viewModel::removeSavedExport,
                        onClearSavedExports = viewModel::clearSavedExports,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                    EditorDestination.Settings -> V2SettingsScreen(
                        exportHistoryRetention = state.exportHistoryRetention,
                        savedExportCount = state.savedExports.size,
                        draftSavedAtMillis = state.draftSavedAtMillis,
                        recoveryDebugInfo = state.recoveryDebugInfo,
                        showRecoveryDebugCard = state.showRecoveryDebugCard,
                        noiseEngine = state.noiseEngine,
                        detailEngine = state.detailEngine,
                        toneEngine = state.toneEngine,
                        hazeEngine = state.hazeEngine,
                        correctionEngineState = state.correctionEngineState,
                        maintenanceBusy = state.maintenanceBusy,
                        comparisonBusy = state.comparisonBusy,
                        onRetentionSelected = viewModel::setExportHistoryRetention,
                        onNoiseEngineSelected = viewModel::setNoiseEngine,
                        onDetailEngineSelected = viewModel::setDetailEngine,
                        onToneEngineSelected = viewModel::setToneEngine,
                        onHazeEngineSelected = viewModel::setHazeEngine,
                        onDefaultCorrectionEngineSelected = viewModel::setDefaultCorrectionEngine,
                        onApplyCorrectionEngine = viewModel::applyCorrectionEngineToCurrentDocument,
                        onExperimentalLabChanged = viewModel::updateExperimentalLab,
                        onGenerateComparison = viewModel::generateDebugComparison,
                        onGenerateProcessingEngineComparison =
                            viewModel::generateProcessingEngineComparison,
                        onGenerateEditorResolutionComparison =
                            viewModel::generateEditorResolutionDebugComparison,
                        onCancelComparison = viewModel::cancelDebugComparison,
                        onClearDraft = viewModel::clearDraft,
                        onDismissRecoveryDebugCard = viewModel::dismissRecoveryDebugCard,
                        onCleanupOldTemporarySources = viewModel::cleanupOldTemporarySources,
                        onClearSavedExports = viewModel::clearSavedExports,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
            }
        }
    }

    if (showExportDialog) {
        V2ExportSettingsDialog(
            exportFormat = state.exportFormat,
            exportResolution = state.exportResolution,
            onFormatSelected = viewModel::setExportFormat,
            onResolutionSelected = viewModel::setExportResolution,
            onDismiss = { showExportDialog = false },
            onSave = {
                showExportDialog = false
                viewModel.exportPreview()
            }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("\uD3B8\uC9D1\uC744 \uCD08\uAE30\uD654\uD560\uAE4C\uC694?") },
            text = {
                Text(
                    "\uD604\uC7AC \uBCF4\uC815\uAC12\uACFC \uC801\uC6A9\uB41C \uD3B8\uC9D1 \uC0C1\uD0DC\uAC00 \uCD08\uAE30\uD654\uB429\uB2C8\uB2E4. \uB418\uB3CC\uB9AC\uAE30\uB85C \uBCF5\uAD6C\uD560 \uC218 \uC788\uC9C0\uB9CC, \uC2E4\uC218\uB97C \uBC29\uC9C0\uD558\uAE30 \uC704\uD574 \uD655\uC778\uC774 \uD544\uC694\uD569\uB2C8\uB2E4."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDialog = false
                        viewModel.resetAdjustments()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = V2Accent, contentColor = V2ButtonTextDark)
                ) {
                    Text("\uCD08\uAE30\uD654")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("\uCDE8\uC18C") }
            }
        )
    }

    state.memoryRecoveryRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelPendingMemoryRecovery(request.token) },
            title = { Text("더 많은 메모리가 필요합니다") },
            text = {
                Text(
                    if (request.mayMoveOldHistory) {
                        "현재 이미지와 적용된 편집은 안전하게 유지됩니다. 정리 과정에서 오래된 되돌리기 기록이 저장소로 이동되거나 제거될 수 있습니다."
                    } else {
                        "현재 이미지와 적용된 편집은 안전하게 유지됩니다."
                    }
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.retryPendingMemoryRecovery(request.token) }) {
                    Text("정리 후 다시 시도")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelPendingMemoryRecovery(request.token) }) { Text("취소") }
            }
        )
    }
}

@Composable
internal fun V2TopBar(
    nativeVersion: String,
    correctionEngineState: CorrectionEngineState,
    selectedTab: EditorDestination,
    hasImage: Boolean,
    isBusy: Boolean,
    canExport: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onTabSelected: (EditorDestination) -> Unit,
    onOpen: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onRotate: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
) {
    val editorActionsVisible = selectedTab == EditorDestination.Editor
    var overflowExpanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().background(V2TopBarBackground).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Kepler Studio", color = V2TextPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    "$nativeVersion · ${engineChipLabel(correctionEngineState)}",
                    color =
                        if (correctionEngineState.previewEngine?.experimental == true) Color(0xFFFFC857)
                        else V2TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (editorActionsVisible) {
                IconButton(onClick = onOpen, enabled = !isBusy) {
                    Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "사진 열기")
                }
                IconButton(onClick = onUndo, enabled = canUndo && !isBusy) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "실행 취소")
                }
                IconButton(onClick = onRedo, enabled = canRedo && !isBusy) {
                    Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = "다시 실행")
                }
                Box {
                    IconButton(
                        onClick = { overflowExpanded = true },
                        enabled = !isBusy,
                    ) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "편집 작업 더보기")
                    }
                    DropdownMenu(
                        expanded = overflowExpanded,
                        onDismissRequest = { overflowExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("회전") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.RotateRight, contentDescription = null) },
                            enabled = hasImage && !isBusy,
                            onClick = {
                                overflowExpanded = false
                                onRotate()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("초기화") },
                            leadingIcon = { Icon(Icons.Outlined.RestartAlt, contentDescription = null) },
                            enabled = hasImage && !isBusy,
                            onClick = {
                                overflowExpanded = false
                                onReset()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("내보내기") },
                            leadingIcon = { Icon(Icons.Outlined.Save, contentDescription = null) },
                            enabled = canExport && !isBusy,
                            onClick = {
                                overflowExpanded = false
                                onSave()
                            },
                        )
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            EditorDestination.values().forEach { tab ->
                val selected = tab == selectedTab
                TextButton(
                    onClick = { onTabSelected(tab) },
                    modifier =
                        Modifier
                            .weight(1f)
                            .background(if (selected) V2SelectedMenuBackground else Color.Transparent),
                ) {
                    Text(
                        tab.label,
                        color = if (selected) V2Accent else V2TextSecondary,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun V2PreviewArea(
    bitmap: Bitmap?,
    originalBitmap: Bitmap?,
    viewModel: EditorViewModel,
    selectedTool: V2EditorTool,
    cropState: CropState,
    isBusy: Boolean,
    message: String?,
    chromeHidden: Boolean,
    histogramVisible: Boolean,
    histogramMode: PreviewHistogramMode,
    gridVisible: Boolean,
    selectionLayers: List<SelectionLayer>,
    activeSelectionLayerId: String?,
    selectionPaintSettings: SelectionPaintSettings,
    showSelectionOverlay: Boolean,
    onToggleHistogram: () -> Unit,
    onToggleHistogramMode: () -> Unit,
    onToggleGrid: () -> Unit,
    onCropRectChanged: (Float, Float, Float, Float) -> Unit,
    onToggleChrome: () -> Unit,
    onPaintAt: (maskX: Float, maskY: Float) -> Unit,
    onStrokeStart: () -> Unit,
    onStrokeEnd: () -> Unit,
    onStrokeCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMessage by remember(message, isBusy) { mutableStateOf(!message.isNullOrBlank()) }
    LaunchedEffect(message, isBusy) {
        showMessage = !message.isNullOrBlank()
        if (!message.isNullOrBlank() && shouldAutoHidePreviewMessage(message, isBusy)) {
            delay(TransientMessageMillis)
            showMessage = false
        }
    }

    Box(
        modifier = modifier
            .background(V2PreviewBackground)
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap == null) {
            Text("사진을 선택해 주세요", color = V2TextPrimary)
        } else {
            if (selectedTool == V2EditorTool.Crop) {
                V2CropPreview(
                    bitmap = bitmap,
                    cropState = cropState,
                    showGrid = gridVisible,
                    enabled = !isBusy,
                    onCropRectChanged = onCropRectChanged
                )
            } else {
                V2ZoomablePreview(
                    bitmap = bitmap,
                    originalBitmap = originalBitmap,
                    viewModel = viewModel,
                    showGrid = gridVisible,
                    onToggleChrome = onToggleChrome,
                    selectionLayers = selectionLayers,
                    activeSelectionLayerId = activeSelectionLayerId,
                    selectionPaintSettings = selectionPaintSettings,
                    showSelectionOverlay = showSelectionOverlay,
                    paintMode = selectedTool == V2EditorTool.Masking && activeSelectionLayerId != null && showSelectionOverlay,
                    onPaintAt = onPaintAt,
                    onStrokeStart = onStrokeStart,
                    onStrokeEnd = onStrokeEnd,
                    onStrokeCancel = onStrokeCancel,
                )
            }
        }
        if (bitmap != null && histogramVisible) {
            PreviewHistogramOverlay(
                bitmap = bitmap,
                mode = histogramMode,
                viewModel = viewModel,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 52.dp),
            )
        }
        if (bitmap != null && !chromeHidden) {
            Row(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .background(V2BadgeBackground)
                        .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TextButton(onClick = onToggleHistogram) {
                    Text(if (histogramVisible) "히스토그램 끄기" else "히스토그램")
                }
                if (histogramVisible) {
                    TextButton(onClick = onToggleHistogramMode) {
                        Text(histogramMode.label)
                    }
                }
                TextButton(onClick = onToggleGrid) {
                    Text(if (gridVisible) "격자 끄기" else "격자")
                }
            }
        }
        if (isBusy) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))
        }
        if (!chromeHidden) {
            AnimatedVisibility(
                visible = showMessage && !message.isNullOrBlank(),
                enter = fadeIn(animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)),
                exit = fadeOut(animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)),
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
            ) {
                Text(
                    text = message.orEmpty(),
                    color = V2TextPrimary,
                    modifier = Modifier.background(V2BadgeBackground).padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

private fun shouldAutoHidePreviewMessage(message: String, isBusy: Boolean): Boolean {
    if (isBusy) return false
    return !isImportantPreviewMessage(message)
}

private fun isImportantPreviewMessage(message: String): Boolean {
    val importantTerms = listOf("실패", "복구", "찾을 수 없습니다", "없습니다", "못했습니다", "오류")
    return importantTerms.any { message.contains(it) }
}

@Composable
private fun V2CropPreview(
    bitmap: Bitmap,
    cropState: CropState,
    showGrid: Boolean,
    enabled: Boolean,
    onCropRectChanged: (Float, Float, Float, Float) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        )
        CropOverlayPreview(
            cropState = cropState,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            showGrid = showGrid,
            enabled = enabled,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            onCropRectChanged = onCropRectChanged
        )
        Text(
            text = "자르기",
            color = V2TextPrimary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(V2BadgeBackground)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun V2ZoomablePreview(
    bitmap: Bitmap,
    originalBitmap: Bitmap?,
    viewModel: EditorViewModel,
    showGrid: Boolean,
    onToggleChrome: () -> Unit,
    selectionLayers: List<SelectionLayer> = emptyList(),
    activeSelectionLayerId: String? = null,
    selectionPaintSettings: SelectionPaintSettings = SelectionPaintSettings(),
    showSelectionOverlay: Boolean = false,
    paintMode: Boolean = false,
    onPaintAt: (maskX: Float, maskY: Float) -> Unit = { _, _ -> },
    onStrokeStart: () -> Unit = {},
    onStrokeEnd: () -> Unit = {},
    onStrokeCancel: () -> Unit = {},
) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var showOriginal by remember(bitmap, originalBitmap) { mutableStateOf(false) }
    var containerSize by remember(bitmap) { mutableStateOf(IntSize.Zero) }
    var cursorPosition by remember { mutableStateOf<Offset?>(null) }
    val displayedBitmap = if (showOriginal && originalBitmap != null) originalBitmap else bitmap
    val activeMaskLayer = selectionLayers.firstOrNull { it.id == activeSelectionLayerId }
    val currentScale = rememberUpdatedState(scale)
    val currentOffset = rememberUpdatedState(offset)
    val currentMaskLayer = rememberUpdatedState(activeMaskLayer)
    val density = LocalDensity.current
    val paddingPx = with(density) { 8.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { containerSize = it },
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = displayedBitmap.asImageBitmap(),
            contentDescription = "preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .pointerInput(bitmap, paintMode) {
                    if (paintMode && currentMaskLayer.value != null) {
                        detectDragGestures(
                            onDragStart = { off ->
                                onStrokeStart()
                                val mask = currentMaskLayer.value?.bitmap ?: return@detectDragGestures
                                val mapped = PreviewGeometry(
                                    container = containerSize,
                                    imageWidth = mask.width,
                                    imageHeight = mask.height,
                                    padding = paddingPx,
                                    zoom = currentScale.value,
                                    pan = currentOffset.value,
                                ).viewToImage(off + Offset(paddingPx, paddingPx))
                                if (mapped != null) onPaintAt(mapped.first, mapped.second)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val innerCenter = Offset(
                                    (containerSize.width - paddingPx * 2f) / 2f,
                                    (containerSize.height - paddingPx * 2f) / 2f,
                                )
                                cursorPosition = innerCenter +
                                    (change.position - innerCenter - currentOffset.value) /
                                        currentScale.value.coerceAtLeast(0.001f)
                                val mask = currentMaskLayer.value?.bitmap ?: return@detectDragGestures
                                val mapped = PreviewGeometry(
                                    container = containerSize,
                                    imageWidth = mask.width,
                                    imageHeight = mask.height,
                                    padding = paddingPx,
                                    zoom = currentScale.value,
                                    pan = currentOffset.value,
                                ).viewToImage(change.position + Offset(paddingPx, paddingPx))
                                if (mapped != null) onPaintAt(mapped.first, mapped.second)
                            },
                            onDragEnd = { onStrokeEnd(); cursorPosition = null },
                            onDragCancel = { onStrokeCancel(); cursorPosition = null },
                        )
                    } else {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val oldScale = scale
                            val nextScale = (oldScale * zoom).coerceIn(1f, 8f)
                            scale = nextScale
                            offset = if (nextScale <= 1.01f) {
                                Offset.Zero
                            } else {
                                val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                val newOffset = ((offset + centroid - center) * (nextScale / oldScale)) - (centroid - center) + pan
                                clampZoomOffset(newOffset, nextScale, bitmap.width, bitmap.height, containerSize, paddingPx)
                            }
                            showOriginal = false
                        }
                        showOriginal = false
                    }
                }
                .pointerInput(bitmap, originalBitmap, containerSize, paintMode) {
                    if (!paintMode) {
                        detectTapGestures(
                            onTap = { onToggleChrome() },
                            onDoubleTap = { tap ->
                                if (scale > 1.01f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    val targetScale = 2.5f
                                    val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                    offset = (center - tap) * (targetScale - 1f)
                                    scale = targetScale
                                }
                                showOriginal = false
                            },
                            onLongPress = { if (originalBitmap != null) showOriginal = true },
                            onPress = {
                                tryAwaitRelease()
                                showOriginal = false
                            }
                        )
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
        if (showGrid) {
            PreviewGridOverlay(
                imageWidth = displayedBitmap.width,
                imageHeight = displayedBitmap.height,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
            )
        }
        if (showSelectionOverlay) {
            SelectionMaskOverlay(
                layer = activeMaskLayer,
                visible = showSelectionOverlay,
                viewModel = viewModel,
                scale = scale,
                offset = offset,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (paintMode && cursorPosition != null && activeMaskLayer != null) {
            BrushCursorOverlay(
                position = cursorPosition!!,
                radiusPx = activeMaskLayer?.let { mask ->
                    val fitted = PreviewGeometry(
                        container = containerSize,
                        imageWidth = mask.bitmap.width,
                        imageHeight = mask.bitmap.height,
                        padding = paddingPx,
                    ).imageRect
                    selectionPaintSettings.sizePx.coerceAtLeast(1f) *
                        (fitted.width / mask.bitmap.width.coerceAtLeast(1)) * 0.5f
                } ?: 0f,
                color = DefaultMaskTint,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }

        Text(
            text = if (showOriginal && originalBitmap != null) "원본" else "미리보기",
            color = V2TextPrimary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp).background(V2BadgeBackground).padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun V2AdjustmentPanel(
    editorViewModel: EditorViewModel,
    selectedTool: V2EditorTool,
    params: EditParams,
    activeLook: PresetColorLook?,
    controlsEnabled: Boolean,
    panelCollapsed: Boolean,
    onTogglePanel: () -> Unit,
    onFullScreen: () -> Unit,
    onToolSelected: (V2EditorTool) -> Unit,
    onAutoEnhance: () -> Unit,
    onChange: ((EditParams) -> EditParams) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().background(V2PanelBackground).navigationBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(selectedTool.label, color = V2TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onTogglePanel) { Text(if (panelCollapsed) "펼치기" else "접기") }
            TextButton(onClick = onFullScreen) { Text("미리보기") }
        }
        if (!panelCollapsed) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(selectedTool.description, color = V2TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                when (selectedTool) {
                    V2EditorTool.Auto -> V2AutoPanel(onAutoEnhance, controlsEnabled)
                    V2EditorTool.Remaster -> RemasterToolPanel(onQuickAutoEnhance = onAutoEnhance, editorViewModel = editorViewModel)
                    V2EditorTool.Profiles -> NativeProfilesToolPanel(editorViewModel)
                    V2EditorTool.Presets -> PresetToolPanel(editorViewModel = editorViewModel, params = params, activeLook = activeLook)
                    V2EditorTool.Crop -> CropToolPanel(editorViewModel)
                    V2EditorTool.Masking -> MaskingToolPanel(editorViewModel)
                    V2EditorTool.Remove -> NativeRemoveToolPanel(editorViewModel)
                    V2EditorTool.Light -> V2LightPanel(params, controlsEnabled, onChange)
                    V2EditorTool.Color -> V2ColorPanel(params, controlsEnabled, onChange)
                    V2EditorTool.Effects -> V2EffectsPanel(params, controlsEnabled, onChange)
                    V2EditorTool.Detail -> V2DetailPanel(params, controlsEnabled, onChange)
                    V2EditorTool.Optics -> NativeOpticsToolPanel(editorViewModel)
                    V2EditorTool.Geometry -> NativeGeometryToolPanel(editorViewModel)
                    V2EditorTool.Blur -> NativeBlurToolPanel(editorViewModel)
                    V2EditorTool.Model -> NativeModelToolPanel(editorViewModel)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(V2RailBackground).padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            V2EditorTool.values().forEach { tool ->
                val selected = tool == selectedTool
                TextButton(
                    onClick = { onToolSelected(tool) },
                    modifier = Modifier.width(84.dp).background(if (selected) V2SelectedMenuBackground else Color.Transparent)
                ) {
                    Text(tool.label, color = if (selected) V2Accent else V2TextSecondary, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun V2AutoPanel(onAutoEnhance: () -> Unit, enabled: Boolean) {
    Button(onClick = onAutoEnhance, enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = V2Accent, contentColor = V2ButtonTextDark)) {
        Text("빠른 자동 보정 적용")
    }
}

@Composable
private fun V2PlaceholderPanel(message: String) {
    Text(message, color = V2TextMuted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun V2LightPanel(params: EditParams, enabled: Boolean, onChange: ((EditParams) -> EditParams) -> Unit) {
    V2AdjustmentSlider("노출", params.exposure, -1f, 1f, enabled) { v -> onChange { it.copy(exposure = v) } }
    V2AdjustmentSlider("대비", params.contrast, -1f, 1f, enabled) { v -> onChange { it.copy(contrast = v) } }
    V2AdjustmentSlider("하이라이트", params.highlights, -1f, 1f, enabled) { v -> onChange { it.copy(highlights = v) } }
    V2AdjustmentSlider("그림자", params.shadows, -1f, 1f, enabled) { v -> onChange { it.copy(shadows = v) } }
    V2AdjustmentSlider("화이트", params.whites, -1f, 1f, enabled) { v -> onChange { it.copy(whites = v) } }
    V2AdjustmentSlider("블랙", params.blacks, -1f, 1f, enabled) { v -> onChange { it.copy(blacks = v) } }
}

@Composable
private fun V2ColorPanel(params: EditParams, enabled: Boolean, onChange: ((EditParams) -> EditParams) -> Unit) {
    V2AdjustmentSlider("색온도", params.temperature, -1f, 1f, enabled) { v -> onChange { it.copy(temperature = v) } }
    V2AdjustmentSlider("색조", params.tint, -1f, 1f, enabled) { v -> onChange { it.copy(tint = v) } }
    V2AdjustmentSlider("생동감", params.vibrance, -1f, 1f, enabled) { v -> onChange { it.copy(vibrance = v) } }
    V2AdjustmentSlider("채도", params.saturation, -1f, 1f, enabled) { v -> onChange { it.copy(saturation = v) } }
    V2PlaceholderPanel("HSL과 색상 혼합은 아직 연결하지 않았습니다")
}

@Composable
private fun V2EffectsPanel(params: EditParams, enabled: Boolean, onChange: ((EditParams) -> EditParams) -> Unit) {
    V2AdjustmentSlider("선명 대비", params.clarity, -1f, 1f, enabled) { v -> onChange { it.copy(clarity = v) } }
    V2AdjustmentSlider("디헤이즈", params.dehaze, -1f, 1f, enabled) { v -> onChange { it.copy(dehaze = v) } }
    V2PlaceholderPanel("텍스처, 그레인, 고급 효과는 아직 연결하지 않았습니다")
}

@Composable
private fun V2DetailPanel(params: EditParams, enabled: Boolean, onChange: ((EditParams) -> EditParams) -> Unit) {
    V2AdjustmentSlider("샤프닝", params.sharpness, 0f, 1f, enabled) { v -> onChange { it.copy(sharpness = v) } }
    V2AdjustmentSlider("노이즈 감소", params.luminanceNoiseReduction, 0f, 1f, enabled) { v ->
        onChange { it.copy(noiseReduction = v, luminanceNoiseReduction = v) }
    }
    V2AdjustmentSlider("색상 노이즈 감소", params.colorNoiseReduction, 0f, 1f, enabled) { v ->
        onChange { it.copy(colorNoiseReduction = v) }
    }
    V2AdjustmentSlider("디테일 보호", params.noiseDetailProtection, 0f, 1f, enabled) { v ->
        onChange { it.copy(noiseDetailProtection = v) }
    }
}

@Composable
private fun V2AdjustmentSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    enabled: Boolean,
    onValue: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, modifier = Modifier.width(86.dp), style = MaterialTheme.typography.bodyMedium, color = V2TextPrimary)
        Slider(value = value, onValueChange = onValue, valueRange = min..max, enabled = enabled, modifier = Modifier.weight(1f))
        Text(String.format(Locale.US, "%.2f", value), modifier = Modifier.width(52.dp), style = MaterialTheme.typography.bodyMedium, color = V2TextSecondary)
    }
}

@Composable
private fun V2ExportSettingsDialog(
    exportFormat: ExportFormat,
    exportResolution: ExportResolution,
    onFormatSelected: (ExportFormat) -> Unit,
    onResolutionSelected: (ExportResolution) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("내보내기 설정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                V2OptionRow("파일 형식", ExportFormat.values().toList(), exportFormat, { it.label }, onFormatSelected)
                V2OptionRow("해상도", ExportResolution.values().toList(), exportResolution, { it.label }, onResolutionSelected)
            }
        },
        confirmButton = {
            Button(onClick = onSave, colors = ButtonDefaults.buttonColors(containerColor = V2Accent, contentColor = V2ButtonTextDark)) {
                Text("저장")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
internal fun <T> V2OptionRow(
    title: String,
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit
) {
    Column {
        Text(title, color = V2TextSecondary, style = MaterialTheme.typography.bodySmall)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            values.forEach { value ->
                val isSelected = value == selected
                Surface(
                    modifier =
                        Modifier.selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onSelected(value) },
                        ),
                    color = if (isSelected) V2SelectedMenuBackground else Color.Transparent,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Text(label(value), color = if (isSelected) V2Accent else V2TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun V2SavedScreen(
    savedExports: List<SavedExport>,
    maintenanceBusy: Boolean,
    onRemoveSavedExport: (String) -> Unit,
    onClearSavedExports: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingRemoval by remember { mutableStateOf<SavedExport?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("저장 기록", color = V2TextPrimary, style = MaterialTheme.typography.titleMedium)
            TextButton(
                onClick = { confirmClear = true },
                enabled = savedExports.isNotEmpty() && !maintenanceBusy,
            ) { Text(if (maintenanceBusy) "처리 중…" else "기록 비우기") }
        }
        if (savedExports.isEmpty()) {
            Text("저장 기록이 없습니다", color = V2TextMuted, modifier = Modifier.padding(top = 12.dp))
        } else {
            savedExports.forEach { item ->
                Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp).background(V2CardBackground).padding(12.dp)) {
                    Text(item.displayName, color = V2TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("${item.formatLabel} · ${item.resolutionLabel}", color = V2TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Text(formatSavedTime2(item.timestampMillis), color = V2TextMuted, style = MaterialTheme.typography.bodySmall)
                    TextButton(
                        onClick = { pendingRemoval = item },
                        enabled = !maintenanceBusy,
                    ) { Text("기록 삭제") }
                }
            }
        }
    }
    pendingRemoval?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("저장 기록을 삭제할까요?") },
            text = {
                Text(
                    "‘${item.displayName}’ 기록만 목록에서 삭제합니다. 갤러리에 저장된 사진 파일은 유지됩니다.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingRemoval = null
                        onRemoveSavedExport(item.uriString)
                    },
                    enabled = !maintenanceBusy,
                ) { Text("기록 삭제") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text("취소") }
            },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("저장 기록을 모두 비울까요?") },
            text = {
                Text("목록의 ${savedExports.size}개 기록을 지웁니다. 갤러리에 저장된 사진 파일은 삭제하지 않습니다.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClear = false
                        onClearSavedExports()
                    },
                    enabled = !maintenanceBusy,
                ) { Text("모두 비우기") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("취소") }
            },
        )
    }
}

@Composable
internal fun V2SettingsScreen(
    exportHistoryRetention: ExportHistoryRetention,
    savedExportCount: Int,
    draftSavedAtMillis: Long?,
    recoveryDebugInfo: RecoveryDebugInfo?,
    showRecoveryDebugCard: Boolean,
    noiseEngine: NoiseEngine,
    detailEngine: DetailEngine,
    toneEngine: ToneEngine,
    hazeEngine: DehazeEngine,
    correctionEngineState: CorrectionEngineState,
    maintenanceBusy: Boolean,
    comparisonBusy: Boolean,
    onRetentionSelected: (ExportHistoryRetention) -> Unit,
    onNoiseEngineSelected: (NoiseEngine) -> Unit,
    onDetailEngineSelected: (DetailEngine) -> Unit,
    onToneEngineSelected: (ToneEngine) -> Unit,
    onHazeEngineSelected: (DehazeEngine) -> Unit,
    onDefaultCorrectionEngineSelected: (CorrectionEngine) -> Unit,
    onApplyCorrectionEngine: (CorrectionEngine) -> Unit,
    onExperimentalLabChanged: ((DebugFeatureOverrides) -> DebugFeatureOverrides) -> Unit,
    onGenerateComparison: () -> Unit,
    onGenerateProcessingEngineComparison: () -> Unit,
    onGenerateEditorResolutionComparison: () -> Unit,
    onCancelComparison: () -> Unit,
    onClearDraft: () -> Unit,
    onDismissRecoveryDebugCard: () -> Unit,
    onCleanupOldTemporarySources: () -> Unit,
    onClearSavedExports: () -> Unit,
    modifier: Modifier = Modifier
) {
    val overrides by ExperimentalLabController.overridesState.collectAsState()
    val comparison by ExperimentalComparisonStore.latest.collectAsState()
    var confirmDestructiveAction by remember { mutableStateOf<SettingsDestructiveAction?>(null) }
    var showRecoveryDetails by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        V2SettingsCard("보정 엔진") {
            V2OptionRow(
                title = "새 사진 기본 엔진",
                values = CorrectionEngine.entries,
                selected = correctionEngineState.defaultEngine,
                label = { if (it.experimental) "${it.displayName} · 실험적" else it.displayName },
                onSelected = onDefaultCorrectionEngineSelected,
            )
            Text(
                "기본 엔진을 바꿔도 현재 사진은 자동으로 변경되지 않습니다.",
                color = V2TextMuted,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                "현재 사진 상태",
                color = V2TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
            )
            val docEngine = correctionEngineState.documentEngine
            val prevEngine = correctionEngineState.previewEngine
            Text(
                "지정 엔진: ${docEngine.displayName}",
                color = V2TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                buildString {
                    append("보이는 결과: ")
                    append(prevEngine?.displayName ?: "원본")
                    correctionEngineState.previewRoute?.let { append(" · ${nativeRouteLabel(it)}") }
                    append(" · ${previewResultLabel(correctionEngineState.previewResultClass)}")
                },
                color = V2TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            val participation =
                (correctionEngineState.visiblePreview as? com.projectnuke.keplerstudio.editor.VisiblePreviewState.Rendered)
                    ?.participation
            participation?.let {
                val renderParticipation = it
                val indicators =
                    listOfNotNull(
                        "모델".takeIf { renderParticipation.model },
                        "규칙".takeIf { renderParticipation.rule },
                        "수동".takeIf { renderParticipation.manual },
                    )
                if (indicators.isNotEmpty()) {
                    Text(
                        "처리 참여: ${indicators.joinToString(" · ")}",
                        color = V2TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (correctionEngineState.usedFallback) {
                Surface(
                    color = Color(0xFFFFC857).copy(alpha = 0.17f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        "엔진 2 처리 실패로 이번 결과만 엔진 1을 사용했습니다. 다음 보정은 엔진 2를 다시 시도합니다.",
                        color = Color(0xFFFFC857),
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            val hasDocument = correctionEngineState.previewResultClass != PreviewResultClass.NoDocument
            val engineActionEnabled = hasDocument && !correctionEngineState.isSwitching
            if (docEngine == CorrectionEngine.Engine1) {
                Button(
                    onClick = { onApplyCorrectionEngine(CorrectionEngine.Engine2) },
                    enabled = engineActionEnabled,
                ) { Text("엔진 2로 전환") }
            } else if (correctionEngineState.usedFallback || correctionEngineState.lastRenderFailure != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onApplyCorrectionEngine(CorrectionEngine.Engine2) },
                        enabled = engineActionEnabled,
                    ) { Text("엔진 2 다시 시도") }
                    TextButton(
                        onClick = { onApplyCorrectionEngine(CorrectionEngine.Engine1) },
                        enabled = engineActionEnabled,
                    ) { Text("엔진 1로 되돌리기") }
                }
            } else {
                TextButton(
                    onClick = { onApplyCorrectionEngine(CorrectionEngine.Engine1) },
                    enabled = engineActionEnabled,
                ) { Text("엔진 1로 전환") }
            }
            Text(
                engineSettingsStatus(correctionEngineState),
                color = if (correctionEngineState.usedFallback) Color(0xFFFFC857) else V2TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (BuildConfig.DEBUG) {
            ExperimentalLabSettingsCard(
                assignedEngine = correctionEngineState.documentEngine,
                overrides = overrides,
                comparison = comparison,
                comparisonBusy = comparisonBusy,
                onOverridesChanged = onExperimentalLabChanged,
                onGenerateComparison = onGenerateComparison,
                onGenerateProcessingEngineComparison = onGenerateProcessingEngineComparison,
                onGenerateEditorResolutionComparison = onGenerateEditorResolutionComparison,
                onCancelComparison = onCancelComparison,
            )
        }
        if (showRecoveryDebugCard && recoveryDebugInfo != null) {
            V2SettingsCard("복구 상태") {
                Text(
                    if (recoveryDebugInfo.draftSourceExists) "임시 저장 원본을 확인했습니다." else "임시 저장 원본을 찾지 못했습니다.",
                    color = V2TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (BuildConfig.DEBUG) {
                    TextButton(onClick = { showRecoveryDetails = !showRecoveryDetails }) {
                        Text(if (showRecoveryDetails) "개발자 진단 숨기기" else "개발자 진단 보기")
                    }
                    if (showRecoveryDetails) {
                        Text("draft_source: ${recoveryDebugInfo.draftSourcePath ?: "없음"}", color = V2TextMuted, style = MaterialTheme.typography.labelSmall)
                        Text("draft_source 파일 존재: ${if (recoveryDebugInfo.draftSourceExists) "예" else "아니오"}", color = V2TextMuted, style = MaterialTheme.typography.labelSmall)
                        Text("filesDir draft 존재: ${if (recoveryDebugInfo.filesDirDraftExists) "예" else "아니오"}", color = V2TextMuted, style = MaterialTheme.typography.labelSmall)
                        Text("filesDir 경로: ${recoveryDebugInfo.filesDirDraftPath}", color = V2TextMuted, style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(onClick = onDismissRecoveryDebugCard) { Text("닫기") }
            }
        }
        V2SettingsCard("저장 기록") {
            Text("현재 기록 수: $savedExportCount", color = V2TextSecondary, style = MaterialTheme.typography.bodySmall)
            V2OptionRow("보관 정책", ExportHistoryRetention.values().toList(), exportHistoryRetention, { it.label }, onRetentionSelected)
            TextButton(
                onClick = { confirmDestructiveAction = SettingsDestructiveAction.ClearSavedHistory },
                enabled = savedExportCount > 0 && !maintenanceBusy,
            ) { Text(if (maintenanceBusy) "처리 중…" else "저장 기록 비우기") }
        }
        V2SettingsCard("임시 저장") {
            Text(draftSavedAtMillis?.let { "마지막 임시 저장: ${formatSavedTime2(it)}" } ?: "현재 임시 저장 기록이 없습니다", color = V2TextSecondary, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { confirmDestructiveAction = SettingsDestructiveAction.ClearDraft },
                    enabled = draftSavedAtMillis != null && !maintenanceBusy,
                ) { Text("임시 저장 삭제") }
                TextButton(
                    onClick = { confirmDestructiveAction = SettingsDestructiveAction.CleanupTemporarySources },
                    enabled = !maintenanceBusy,
                ) { Text("임시 원본 정리") }
            }
            if (maintenanceBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.width(20.dp))
                    Text(" 정리 작업을 처리하는 중입니다.", color = V2TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        V2SettingsCard("세부 보정 방식") {
            V2OptionRow("노이즈 감소", IMPLEMENTED_NOISE_ENGINES, noiseEngine, { it.label }, onNoiseEngineSelected)
            V2OptionRow("디테일", IMPLEMENTED_DETAIL_ENGINES, detailEngine, { it.label }, onDetailEngineSelected)
            V2OptionRow("톤", IMPLEMENTED_TONE_ENGINES, toneEngine, { it.label }, onToneEngineSelected)
            V2OptionRow("디헤이즈", IMPLEMENTED_DEHAZE_ENGINES, hazeEngine, { it.label }, onHazeEngineSelected)
        }
    }

    confirmDestructiveAction?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmDestructiveAction = null },
            title = { Text(action.title) },
            text = { Text(action.consequence) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDestructiveAction = null
                        when (action) {
                            SettingsDestructiveAction.ClearSavedHistory -> onClearSavedExports()
                            SettingsDestructiveAction.ClearDraft -> onClearDraft()
                            SettingsDestructiveAction.CleanupTemporarySources -> onCleanupOldTemporarySources()
                        }
                    },
                    enabled = !maintenanceBusy,
                ) { Text(action.confirmLabel) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDestructiveAction = null }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun ExperimentalLabSettingsCard(
    assignedEngine: CorrectionEngine,
    overrides: DebugFeatureOverrides,
    comparison: OwnedDebugComparisonArtifact?,
    comparisonBusy: Boolean,
    onOverridesChanged: ((DebugFeatureOverrides) -> DebugFeatureOverrides) -> Unit,
    onGenerateComparison: () -> Unit,
    onGenerateProcessingEngineComparison: () -> Unit,
    onGenerateEditorResolutionComparison: () -> Unit,
    onCancelComparison: () -> Unit,
) {
    val modelAvailability by ModelAvailabilityRegistry.state.collectAsState()
    val capabilitySnapshot =
        remember(modelAvailability) {
            com.projectnuke.keplerstudio.editor.ModelCapabilitySnapshot(modelAvailability)
        }
    val effective =
        RouteResolver.toLegacySelection(
            assignedEngine,
            overrides,
            capabilitySnapshot.routeAvailability(),
        )
    val flareModelReady = modelAvailability[ModelFeature.FlareGuard]?.executable == true
    val edgeModelReady = modelAvailability[ModelFeature.Remaster]?.executable == true
    V2SettingsCard("실험실") {
        Text(
            "개발 빌드의 현재 세션에서만 적용됩니다. ‘사진 엔진 따르기’는 현재 사진에 지정된 엔진을 사용합니다.",
            color = V2TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        LabRouteRow(
            title = "사진 렌더링",
            values =
                listOf(
                    LabRouteOption(null, "사진 엔진 따르기"),
                    LabRouteOption(NativeRenderRoute.V1, "엔진 1 강제"),
                    LabRouteOption(NativeRenderRoute.V2, "엔진 2 강제"),
                ),
            selectedValue = overrides.nativeRender,
        ) { value ->
            onOverridesChanged { it.copy(nativeRender = value) }
        }
        Text(
            "현재 사진 렌더링 경로: ${nativeRouteLabel(effective.nativeRender)}",
            color = V2TextMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        LabRouteRow(
            title = "플레어 가드",
            values =
                listOf(
                    LabRouteOption(null, "사진 엔진 따르기"),
                    LabRouteOption(FlareGuardRoute.V1, "레거시 V1 강제 · 모델 우선, 규칙 대체"),
                    LabRouteOption(FlareGuardRoute.V2Rule, "규칙 기반 V2"),
                    LabRouteOption(
                        FlareGuardRoute.V2ModelAssisted,
                        "모델 보조 V2",
                        enabled = flareModelReady,
                        unavailableReason =
                            modelAvailability[ModelFeature.FlareGuard]?.statusLabel,
                    ),
                ),
            selectedValue = overrides.flareGuard,
        ) { value ->
            onOverridesChanged { it.copy(flareGuard = value) }
        }
        LabRouteRow(
            title = "리마스터",
            values =
                listOf(
                    LabRouteOption(null, "사진 엔진 따르기"),
                    LabRouteOption(RemasterRoute.V1, "V1 강제"),
                    LabRouteOption(RemasterRoute.V2MaskAware, "수동 마스크 V2"),
                    LabRouteOption(
                        RemasterRoute.V2ModelAssisted,
                        "모델 보조 V2",
                        enabled = edgeModelReady,
                        unavailableReason =
                            modelAvailability[ModelFeature.Remaster]?.statusLabel,
                    ),
                ),
            selectedValue = overrides.remaster,
        ) { value ->
            onOverridesChanged { it.copy(remaster = value) }
        }
        LabRouteRow(
            title = "피사체 선택",
            values =
                listOf(
                    LabRouteOption(null, "사진 엔진 따르기"),
                    LabRouteOption(SubjectSelectionRoute.V1, "V1 강제"),
                    LabRouteOption(SubjectSelectionRoute.V2ManualOrSynthetic, "수동·합성 V2"),
                    LabRouteOption(
                        SubjectSelectionRoute.V2ModelAssisted,
                        "모델 보조 V2",
                        enabled = edgeModelReady,
                        unavailableReason =
                            modelAvailability[ModelFeature.SubjectSelection]?.statusLabel,
                    ),
                ),
            selectedValue = overrides.subjectSelection,
        ) { value ->
            onOverridesChanged { it.copy(subjectSelection = value) }
        }
        Text(
            "비교는 긴 변 720px 이하의 미리보기만 메모리에 유지합니다. 사용자 사진을 파일로 내보내지 않습니다.",
            color = V2TextMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        if (comparisonBusy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.width(20.dp))
                TextButton(onClick = onCancelComparison) { Text("비교 생성 취소") }
            }
        } else {
            Button(onClick = onGenerateComparison) { Text("V1·V2 비교 생성") }
            OutlinedButton(onClick = onGenerateProcessingEngineComparison) {
                Text("기본·선택 알고리즘 비교")
            }
            Text(
                "편집 해상도 비교는 현재 편집용 비트맵(보통 최대 2048px)을 사용합니다. 원본/내보내기 해상도가 아닙니다.",
                color = V2TextMuted,
                style = MaterialTheme.typography.labelSmall,
            )
            OutlinedButton(onClick = onGenerateEditorResolutionComparison) {
                Text("편집 해상도 비교 실행")
            }
        }
        comparison?.let { artifact ->
            DisposableEffect(artifact) {
                val lease = artifact.retain()
                onDispose { lease.close() }
            }
            val comparisonBitmaps =
                remember(artifact) {
                    artifact.labeledBitmaps().map { (label, bitmap) ->
                        LabeledComparisonBitmap(label = label, bitmap = bitmap)
                    }
                }
            Text(
                "${artifact.resolutionLevel.label} ${artifact.evaluatedWidth}×${artifact.evaluatedHeight} · " +
                    "${artifact.algorithmDecision ?: "비교"} · 변경 ${(artifact.metrics.changedPixelRatio * 100f).toInt()}% · " +
                    "${artifact.durationMillis ?: 0L} ms",
                color = V2TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                comparisonBitmaps.forEach { item ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(item.label, color = V2TextSecondary, style = MaterialTheme.typography.labelSmall)
                        Image(
                            bitmap = item.bitmap.asImageBitmap(),
                            contentDescription = "${item.label} 비교 미리보기",
                            modifier = Modifier.width(72.dp).heightIn(max = 88.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
            var toggledVersion by remember(artifact) { mutableStateOf(artifact.baselineLabel) }
            var viewerOpen by remember(artifact) { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { toggledVersion = artifact.baselineLabel }) {
                    Text("${artifact.baselineLabel} 보기")
                }
                TextButton(onClick = { toggledVersion = artifact.experimentalLabel }) {
                    Text("${artifact.experimentalLabel} 보기")
                }
                TextButton(onClick = { viewerOpen = true }) { Text("비교 뷰어 열기") }
                TextButton(onClick = ExperimentalComparisonStore::clear) { Text("비교 지우기") }
            }
            comparisonBitmaps.firstOrNull { it.label == toggledVersion }?.let { item ->
                Image(
                    bitmap = item.bitmap.asImageBitmap(),
                    contentDescription = "${item.label} 전환 비교",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            if (viewerOpen) {
                DebugComparisonViewerDialog(
                    artifact = artifact,
                    images = comparisonBitmaps,
                    onDismiss = { viewerOpen = false },
                    onClear = {
                        viewerOpen = false
                        ExperimentalComparisonStore.clear()
                    },
                )
            }
        }
    }
}

@Composable
private fun DebugComparisonViewerDialog(
    artifact: OwnedDebugComparisonArtifact,
    images: List<LabeledComparisonBitmap>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
    var selectedLabel by remember(artifact) { mutableStateOf(artifact.baselineLabel) }
    var scale by remember(artifact) { mutableFloatStateOf(1f) }
    var offset by remember(artifact) { mutableStateOf(Offset.Zero) }
    var splitPosition by remember(artifact) { mutableFloatStateOf(0.5f) }
    var viewportSize by remember(artifact) { mutableStateOf(IntSize.Zero) }
    val dividerTouchWidthPx = with(LocalDensity.current) { 36.dp.roundToPx() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "${artifact.resolutionLevel.label} · ${artifact.evaluatedWidth}×${artifact.evaluatedHeight}"
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    images.forEach { item ->
                        TextButton(
                            onClick = { selectedLabel = item.label }
                        ) {
                            Text(item.label)
                        }
                    }
                    TextButton(onClick = { selectedLabel = "분할" }) { Text("분할") }
                    TextButton(
                        onClick = {
                            scale = 1f
                            offset = Offset.Zero
                            splitPosition = 0.5f
                        }
                    ) {
                        Text("보기 초기화")
                    }
                }
                val selected = images.firstOrNull { it.label == selectedLabel }
                if (selected != null || selectedLabel == "분할") {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 280.dp, max = 480.dp)
                                .onSizeChanged { viewportSize = it }
                                .clipToBounds()
                                .background(Color.Black)
                                .pointerInput(artifact.artifactId) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        val nextScale = (scale * zoom).coerceIn(1f, 8f)
                                        scale = nextScale
                                        offset =
                                            clampComparisonOffset(
                                                offset + pan,
                                                viewportSize,
                                                nextScale,
                                            )
                                    }
                                }
                                .pointerInput(artifact.artifactId) {
                                    detectTapGestures(
                                        onDoubleTap = {
                                            if (scale > 1f) {
                                                scale = 1f
                                                offset = Offset.Zero
                                            } else {
                                                scale = 2f
                                            }
                                        },
                                    )
                                }
                    ) {
                        val transformed =
                            Modifier.fillMaxSize().graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            }
                        if (selectedLabel == "분할") {
                            Image(
                                bitmap = artifact.experimental.asImageBitmap(),
                                contentDescription = "${artifact.experimentalLabel} 분할 비교",
                                modifier = transformed,
                                contentScale = ContentScale.Fit,
                            )
                            Image(
                                bitmap = artifact.baseline.asImageBitmap(),
                                contentDescription = "${artifact.baselineLabel} 분할 비교",
                                // Keep the split clip in viewport coordinates and apply the
                                // shared image transform inside it. Reversing this modifier order
                                // would move/scale the clip with the image while the divider stays
                                // fixed, desynchronizing the comparison under pan/zoom.
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .drawWithContent {
                                            clipRect(right = size.width * splitPosition) {
                                                this@drawWithContent.drawContent()
                                            }
                                        }
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                            translationX = offset.x
                                            translationY = offset.y
                                        },
                                contentScale = ContentScale.Fit,
                            )
                            if (viewportSize.width > 0) {
                                val dividerX =
                                    (viewportSize.width * splitPosition).toInt()
                                Box(
                                    modifier =
                                        Modifier
                                            .offset {
                                                IntOffset(
                                                    x = dividerX - dividerTouchWidthPx / 2,
                                                    y = 0,
                                                )
                                            }
                                            .width(36.dp)
                                            .fillMaxHeight()
                                            .pointerInput(artifact.artifactId, viewportSize.width) {
                                                detectHorizontalDragGestures { change, dragAmount ->
                                                    change.consume()
                                                    splitPosition =
                                                        moveComparisonSplit(
                                                            splitPosition,
                                                            dragAmount,
                                                            viewportSize.width,
                                                        )
                                                }
                                            }
                                             .semantics {
                                                 contentDescription =
                                                    "${artifact.baselineLabel}과 ${artifact.experimentalLabel} 비교 분할선"
                                                stateDescription =
                                                    "${artifact.baselineLabel} ${(splitPosition * 100).toInt()}퍼센트"
                                                progressBarRangeInfo =
                                                    ProgressBarRangeInfo(
                                                        splitPosition,
                                                        0.05f..0.95f,
                                                    )
                                                setProgress { requested ->
                                                    splitPosition =
                                                        if (requested.isFinite()) requested.coerceIn(0.05f, 0.95f) else 0.5f
                                                    true
                                                }
                                            },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        modifier =
                                            Modifier
                                                .width(2.dp)
                                                .fillMaxHeight()
                                                .background(Color.White.copy(alpha = 0.9f))
                                    )
                                    Box(
                                        modifier =
                                            Modifier
                                                .width(24.dp)
                                                .height(40.dp)
                                                .background(V2BadgeBackground),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("↔", color = Color.White)
                                    }
                                }
                            }
                        } else if (selected != null) {
                            Image(
                                bitmap = selected.bitmap.asImageBitmap(),
                                contentDescription = "${selected.label} 확대 비교",
                                modifier = transformed,
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                    if (selectedLabel == "분할") {
                        Text(
                            "이미지 위 분할선을 드래그 · ${artifact.baselineLabel} ${(splitPosition * 100).toInt()}%",
                            color = V2TextMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Text(
                    artifact.compactMetricJson(),
                    color = V2TextMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "${artifact.baselineLabel} ${artifact.baselineContracts.nativeRenderContract ?: "계약 정보 없음"} · " +
                        "${artifact.experimentalLabel} ${artifact.experimentalContracts.nativeRenderContract ?: "계약 정보 없음"}",
                    color = V2TextMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
                artifact.baseProvenance.operations.lastOrNull()?.let { provenance ->
                    Text(
                        "기반 작업 ${provenance.feature.name} · ${provenance.stageContract} · " +
                            "${provenance.requestedRoute} → ${provenance.actualRoute}",
                        color = V2TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        dismissButton = { TextButton(onClick = onClear) { Text("비교 해제") } },
    )
}

private data class LabRouteOption<T>(
    val value: T?,
    val label: String,
    val enabled: Boolean = true,
    val unavailableReason: String? = null,
)

private data class LabeledComparisonBitmap(
    val label: String,
    val bitmap: Bitmap,
)

private enum class SettingsDestructiveAction(
    val title: String,
    val consequence: String,
    val confirmLabel: String,
) {
    ClearSavedHistory(
        title = "저장 기록을 비울까요?",
        consequence = "앱 안의 내보내기 기록만 삭제합니다. 갤러리에 저장된 사진 파일은 유지됩니다.",
        confirmLabel = "기록 비우기",
    ),
    ClearDraft(
        title = "임시 저장을 삭제할까요?",
        consequence = "자동 복구용 임시 저장을 삭제합니다. 현재 열려 있는 편집 화면은 유지됩니다.",
        confirmLabel = "임시 저장 삭제",
    ),
    CleanupTemporarySources(
        title = "오래된 임시 원본을 정리할까요?",
        consequence = "7일이 지난 앱 내부 임시 원본만 정리합니다. 현재 사진, 임시 저장 원본, 내보낸 사진은 유지됩니다.",
        confirmLabel = "임시 원본 정리",
    ),
}

@Composable
private fun <T> LabRouteRow(
    title: String,
    values: List<LabRouteOption<T>>,
    selectedValue: T?,
    onSelected: (T?) -> Unit,
) {
    val selected = values.first { it.value == selectedValue }
    Column {
        Text(title, color = V2TextSecondary, style = MaterialTheme.typography.bodySmall)
        values.forEach { option ->
            val isSelected = option == selected
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = isSelected,
                            enabled = option.enabled,
                            role = Role.RadioButton,
                            onClick = { onSelected(option.value) },
                        ),
                color = if (isSelected) V2SelectedMenuBackground else Color.Transparent,
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                        enabled = option.enabled,
                    )
                    Column {
                        Text(
                            option.label,
                            color =
                                if (!option.enabled) V2TextMuted
                                else if (isSelected) V2Accent
                                else V2TextSecondary,
                        )
                        if (!option.enabled && option.unavailableReason != null) {
                            Text(
                                option.unavailableReason,
                                color = V2TextMuted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun V2SettingsCard(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(V2CardBackground).padding(12.dp)) {
        Text(title, color = V2TextPrimary, fontWeight = FontWeight.SemiBold)
        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

private fun formatSavedTime2(timestampMillis: Long): String =
    SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(timestampMillis))

private fun engineChipLabel(state: CorrectionEngineState): String {
    val result = state.previewResultClass
    val engine = state.previewEngine ?: state.documentEngine
    return if (state.isSwitching) "전환 중"
    else when (result) {
        PreviewResultClass.NoDocument -> "E${state.defaultEngine.ordinal + 1}"
        PreviewResultClass.V2FallbackToV1 -> "E2 · E1 폴백"
        else -> "E${engine.ordinal + 1}"
    }
}

private fun engineSettingsStatus(state: CorrectionEngineState): String {
    if (state.isSwitching) return "현재 사진을 새 엔진으로 다시 렌더링하는 중입니다."
    val failure = state.lastRenderFailure
    if (failure != null && !state.isSwitching) {
        return "렌더링 실패: ${failure.reason} (이전 미리보기 유지)"
    }
    return when (state.previewResultClass) {
        PreviewResultClass.NoDocument -> "새 사진은 선택한 기본 엔진으로 열립니다."
        PreviewResultClass.Original -> "원본 이미지입니다."
        PreviewResultClass.V1 -> "현재 미리보기는 Correction Engine 1로 렌더링되었습니다."
        PreviewResultClass.V2 -> "현재 미리보기는 실험적 Correction Engine 2로 렌더링되었습니다."
        PreviewResultClass.V2FallbackToV1 ->
            "Engine 2가 실패하여 현재 미리보기에는 Engine 1 폴백이 적용되었습니다."
        PreviewResultClass.DebugForcedV1 ->
            "개발자 설정으로 현재 미리보기를 Engine 1로 렌더링했습니다."
        PreviewResultClass.DebugForcedV2 ->
            "개발자 설정으로 현재 미리보기를 Engine 2로 렌더링했습니다."
    }
}

private fun nativeRouteLabel(route: NativeRenderRoute): String =
    when (route) {
        NativeRenderRoute.V1 -> "V1"
        NativeRenderRoute.V2 -> "V2"
        NativeRenderRoute.Compare -> "비교"
    }

private fun previewResultLabel(result: PreviewResultClass): String =
    when (result) {
        PreviewResultClass.NoDocument -> "사진 없음"
        PreviewResultClass.Original -> "원본"
        PreviewResultClass.V1 -> "V1 결과"
        PreviewResultClass.V2 -> "V2 결과"
        PreviewResultClass.V2FallbackToV1 -> "실행 실패 후 V1 대체"
        PreviewResultClass.DebugForcedV1 -> "개발자 설정으로 V1 강제"
        PreviewResultClass.DebugForcedV2 -> "개발자 설정으로 V2 강제"
    }

/**
 * A brush cursor circle drawn on the Compose surface at the current pointer position,
 * scaled with the same graphicsLayer as the preview to reflect the true brush size on
 * the actual image. This is purely a UI indicator; no Bitmap is allocated.
 */
@Composable
private fun BrushCursorOverlay(
    position: Offset,
    radiusPx: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        drawCircle(
            color = color.copy(alpha = 0.85f),
            radius = radiusPx,
            center = position,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
        )
        drawCircle(
            color = color.copy(alpha = 0.18f),
            radius = radiusPx,
            center = position,
        )
    }
}

private val DefaultMaskTint = Color(0xFFE91E63)

/**
 * Clamps a zoomed-and-panned [offset] so the content never moves beyond half its extent
 * from the container center. After container resize, orientation change or scale change,
 * this keeps the entire image reachable while allowing intentional edge movement.
 */
internal fun clampZoomOffset(
    offset: Offset,
    scale: Float,
    contentWidth: Int,
    contentHeight: Int,
    containerSize: IntSize,
    paddingPx: Float,
): Offset {
    if (scale <= 1f || containerSize.width <= 0 || containerSize.height <= 0) return Offset.Zero
    val viewportWidth = (containerSize.width - 2 * paddingPx).coerceAtLeast(1f)
    val viewportHeight = (containerSize.height - 2 * paddingPx).coerceAtLeast(1f)
    val fittedWidth: Float
    val fittedHeight: Float
    if (contentHeight > 0 && viewportHeight > 0f) {
        val contentAspect = contentWidth.toFloat() / contentHeight.toFloat()
        val viewportAspect = viewportWidth / viewportHeight
        if (contentAspect > viewportAspect) {
            fittedWidth = viewportWidth
            fittedHeight = viewportWidth / contentAspect
        } else {
            fittedHeight = viewportHeight
            fittedWidth = viewportHeight * contentAspect
        }
    } else {
        fittedWidth = viewportWidth
        fittedHeight = viewportHeight
    }
    val maxX = maxOf(0f, (fittedWidth * scale - viewportWidth) / 2f)
    val maxY = maxOf(0f, (fittedHeight * scale - viewportHeight) / 2f)
    return Offset(
        offset.x.coerceIn(-maxX, maxX),
        offset.y.coerceIn(-maxY, maxY),
    )
}
