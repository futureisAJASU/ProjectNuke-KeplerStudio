package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.projectnuke.keplerstudio.editor.DehazeEngine
import com.projectnuke.keplerstudio.editor.DetailEngine
import com.projectnuke.keplerstudio.editor.EditParams
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.ExportFormat
import com.projectnuke.keplerstudio.editor.ExportHistoryRetention
import com.projectnuke.keplerstudio.editor.ExportResolution
import com.projectnuke.keplerstudio.editor.NoiseEngine
import com.projectnuke.keplerstudio.editor.PresetLookHandoff
import com.projectnuke.keplerstudio.editor.SavedExport
import com.projectnuke.keplerstudio.editor.ToneEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val V2AppBackground = Color(0xFF101010)
private val V2TopBarBackground = Color(0xFF171717)
private val V2PanelBackground = Color(0xFF1B1B1B)
private val V2PreviewBackground = Color(0xFF000000)
private val V2RailBackground = Color(0xFF141414)
private val V2CardBackground = Color(0xFF242424)
private val V2BadgeBackground = Color(0xCC000000)
private val V2Accent = Color(0xFFE6E6E6)
private val V2TextPrimary = Color(0xFFF2F2F2)
private val V2TextSecondary = Color(0xFFC8C8C8)
private val V2TextMuted = Color(0xFF8E8E8E)
private val V2ButtonTextDark = Color(0xFF111111)

private val V2DarkColors = darkColorScheme(
    primary = V2Accent,
    onPrimary = V2ButtonTextDark,
    background = V2AppBackground,
    onBackground = V2TextPrimary,
    surface = V2PanelBackground,
    onSurface = V2TextPrimary
)

private enum class V2MainTab(val label: String) {
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
            PresetLookHandoff.clear()
            viewModel.openImage(uri)
        }
    }
    var selectedTab by remember { mutableStateOf(V2MainTab.Editor) }
    var selectedTool by remember { mutableStateOf(V2EditorTool.Light) }
    var showExportDialog by remember { mutableStateOf(false) }
    var panelCollapsed by remember { mutableStateOf(false) }
    var fullScreenPreview by remember { mutableStateOf(false) }

    MaterialTheme(colorScheme = V2DarkColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = V2AppBackground) {
            Column(modifier = Modifier.fillMaxSize().background(V2AppBackground)) {
                if (!fullScreenPreview) {
                    V2TopBar(
                        nativeVersion = state.nativeVersion,
                        selectedTab = selectedTab,
                        hasImage = state.previewBitmap != null,
                        canExport = state.previewBitmap != null && !state.isBusy,
                        canUndo = state.canUndo,
                        canRedo = state.canRedo,
                        onTabSelected = { selectedTab = it },
                        onOpen = { picker.launch("image/*") },
                        onUndo = viewModel::undoEdit,
                        onRedo = viewModel::redoEdit,
                        onRotate = viewModel::rotatePreview90,
                        onReset = {
                            PresetLookHandoff.clear()
                            viewModel.resetAdjustments()
                        },
                        onSave = { showExportDialog = true }
                    )
                }

                when (selectedTab) {
                    V2MainTab.Editor -> {
                        V2PreviewArea(
                            bitmap = state.previewBitmap,
                            originalBitmap = state.originalPreviewBitmap,
                            isBusy = state.isBusy,
                            message = state.message,
                            isFullScreen = fullScreenPreview,
                            onTogglePanel = { panelCollapsed = !panelCollapsed },
                            onToggleFullScreen = { fullScreenPreview = !fullScreenPreview },
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        )
                        if (!fullScreenPreview) {
                            V2AdjustmentPanel(
                                editorViewModel = viewModel,
                                selectedTool = selectedTool,
                                params = state.params,
                                panelCollapsed = panelCollapsed,
                                onTogglePanel = { panelCollapsed = !panelCollapsed },
                                onFullScreen = { fullScreenPreview = true },
                                onToolSelected = { selectedTool = it },
                                onAutoEnhance = {
                                    PresetLookHandoff.clear()
                                    viewModel.applyAutoEnhance()
                                },
                                onChange = viewModel::updateParams
                            )
                        }
                    }
                    V2MainTab.Saved -> V2SavedScreen(
                        savedExports = state.savedExports,
                        onRemoveSavedExport = viewModel::removeSavedExport,
                        onClearSavedExports = viewModel::clearSavedExports,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                    V2MainTab.Settings -> V2SettingsScreen(
                        exportHistoryRetention = state.exportHistoryRetention,
                        savedExportCount = state.savedExports.size,
                        draftSavedAtMillis = state.draftSavedAtMillis,
                        noiseEngine = state.noiseEngine,
                        detailEngine = state.detailEngine,
                        toneEngine = state.toneEngine,
                        hazeEngine = state.hazeEngine,
                        onRetentionSelected = viewModel::setExportHistoryRetention,
                        onNoiseEngineSelected = viewModel::setNoiseEngine,
                        onDetailEngineSelected = viewModel::setDetailEngine,
                        onToneEngineSelected = viewModel::setToneEngine,
                        onHazeEngineSelected = viewModel::setHazeEngine,
                        onClearDraft = viewModel::clearDraft,
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
}

@Composable
private fun V2TopBar(
    nativeVersion: String,
    selectedTab: V2MainTab,
    hasImage: Boolean,
    canExport: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onTabSelected: (V2MainTab) -> Unit,
    onOpen: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onRotate: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().background(V2TopBarBackground).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Kepler Studio", color = V2TextPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(nativeVersion, color = V2TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            TextButton(onClick = onUndo, enabled = canUndo) { Text("Undo") }
            TextButton(onClick = onRedo, enabled = canRedo) { Text("Redo") }
            TextButton(onClick = onRotate, enabled = hasImage) { Text("회전") }
            TextButton(onClick = onReset, enabled = hasImage) { Text("초기화") }
            TextButton(onClick = onSave, enabled = canExport) { Text("저장") }
            Button(onClick = onOpen, colors = ButtonDefaults.buttonColors(containerColor = V2Accent, contentColor = V2ButtonTextDark)) {
                Text("사진")
            }
        }
        Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            V2MainTab.values().forEach { tab ->
                TextButton(onClick = { onTabSelected(tab) }) {
                    Text(tab.label, color = if (tab == selectedTab) V2Accent else V2TextSecondary, fontWeight = if (tab == selectedTab) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun V2PreviewArea(
    bitmap: Bitmap?,
    originalBitmap: Bitmap?,
    isBusy: Boolean,
    message: String?,
    isFullScreen: Boolean,
    onTogglePanel: () -> Unit,
    onToggleFullScreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.background(V2PreviewBackground), contentAlignment = Alignment.Center) {
        if (bitmap == null) {
            Text("사진을 선택해 주세요", color = V2TextPrimary)
        } else {
            V2ZoomablePreview(bitmap = bitmap, originalBitmap = originalBitmap)
        }
        if (isBusy) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))
        }
        Row(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onTogglePanel) { Text("편집창") }
            TextButton(onClick = onToggleFullScreen) { Text(if (isFullScreen) "전체화면 종료" else "전체화면") }
        }
        message?.let {
            Text(
                text = it,
                color = V2TextPrimary,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).background(V2BadgeBackground).padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun V2ZoomablePreview(bitmap: Bitmap, originalBitmap: Bitmap?) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var showOriginal by remember(bitmap, originalBitmap) { mutableStateOf(false) }
    val displayedBitmap = if (showOriginal && originalBitmap != null) originalBitmap else bitmap

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(
            bitmap = displayedBitmap.asImageBitmap(),
            contentDescription = "preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .pointerInput(bitmap) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val nextScale = (scale * zoom).coerceIn(1f, 8f)
                        scale = nextScale
                        offset = if (nextScale <= 1.01f) Offset.Zero else offset + pan
                        showOriginal = false
                    }
                }
                .pointerInput(bitmap, originalBitmap) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.01f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        },
                        onLongPress = { if (originalBitmap != null) showOriginal = true },
                        onPress = {
                            tryAwaitRelease()
                            showOriginal = false
                        }
                    )
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )

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
            TextButton(onClick = onFullScreen) { Text("전체화면") }
        }
        if (!panelCollapsed) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(selectedTool.description, color = V2TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                when (selectedTool) {
                    V2EditorTool.Auto -> V2AutoPanel(onAutoEnhance)
                    V2EditorTool.Remaster -> RemasterToolPanel(onQuickAutoEnhance = onAutoEnhance, editorViewModel = editorViewModel)
                    V2EditorTool.Profiles -> V2PlaceholderPanel("프로필 전용 LUT 자산이 아직 없어서 현재는 프리셋 기반 보정만 지원합니다")
                    V2EditorTool.Presets -> PresetToolPanel(params = params, onApplyPreset = { presetParams -> onChange { presetParams } })
                    V2EditorTool.Crop -> CropToolPanel()
                    V2EditorTool.Masking -> MaskingToolPanel()
                    V2EditorTool.Remove -> NativeRemoveToolPanel()
                    V2EditorTool.Light -> V2LightPanel(params, onChange)
                    V2EditorTool.Color -> V2ColorPanel(params, onChange)
                    V2EditorTool.Effects -> V2EffectsPanel(params, onChange)
                    V2EditorTool.Detail -> V2DetailPanel(params, onChange)
                    V2EditorTool.Optics -> NativeOpticsToolPanel()
                    V2EditorTool.Geometry -> V2PlaceholderPanel("원근 보정은 아직 연결하지 않았습니다. 수평 보정은 자르기 도구에서 사용할 수 있습니다")
                    V2EditorTool.Blur -> NativeBlurToolPanel()
                    V2EditorTool.Model -> NativeModelToolPanel()
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(V2RailBackground).padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            V2EditorTool.values().forEach { tool ->
                TextButton(onClick = { onToolSelected(tool) }, modifier = Modifier.width(84.dp)) {
                    Text(tool.label, color = if (tool == selectedTool) V2Accent else V2TextSecondary, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun V2AutoPanel(onAutoEnhance: () -> Unit) {
    Button(onClick = onAutoEnhance, colors = ButtonDefaults.buttonColors(containerColor = V2Accent, contentColor = V2ButtonTextDark)) {
        Text("빠른 자동 보정 적용")
    }
}

@Composable
private fun V2PlaceholderPanel(message: String) {
    Text(message, color = V2TextMuted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun V2LightPanel(params: EditParams, onChange: ((EditParams) -> EditParams) -> Unit) {
    V2AdjustmentSlider("노출", params.exposure, -1f, 1f) { v -> onChange { it.copy(exposure = v) } }
    V2AdjustmentSlider("대비", params.contrast, -1f, 1f) { v -> onChange { it.copy(contrast = v) } }
    V2AdjustmentSlider("하이라이트", params.highlights, -1f, 1f) { v -> onChange { it.copy(highlights = v) } }
    V2AdjustmentSlider("그림자", params.shadows, -1f, 1f) { v -> onChange { it.copy(shadows = v) } }
    V2AdjustmentSlider("화이트", params.whites, -1f, 1f) { v -> onChange { it.copy(whites = v) } }
    V2AdjustmentSlider("블랙", params.blacks, -1f, 1f) { v -> onChange { it.copy(blacks = v) } }
}

@Composable
private fun V2ColorPanel(params: EditParams, onChange: ((EditParams) -> EditParams) -> Unit) {
    V2AdjustmentSlider("색온도", params.temperature, -1f, 1f) { v -> onChange { it.copy(temperature = v) } }
    V2AdjustmentSlider("색조", params.tint, -1f, 1f) { v -> onChange { it.copy(tint = v) } }
    V2AdjustmentSlider("생동감", params.vibrance, -1f, 1f) { v -> onChange { it.copy(vibrance = v) } }
    V2AdjustmentSlider("채도", params.saturation, -1f, 1f) { v -> onChange { it.copy(saturation = v) } }
    V2PlaceholderPanel("HSL과 색상 혼합은 아직 연결하지 않았습니다")
}

@Composable
private fun V2EffectsPanel(params: EditParams, onChange: ((EditParams) -> EditParams) -> Unit) {
    V2AdjustmentSlider("선명 대비", params.clarity, -1f, 1f) { v -> onChange { it.copy(clarity = v) } }
    V2AdjustmentSlider("디헤이즈", params.dehaze, -1f, 1f) { v -> onChange { it.copy(dehaze = v) } }
    V2PlaceholderPanel("텍스처, 그레인, 고급 효과는 아직 연결하지 않았습니다")
}

@Composable
private fun V2DetailPanel(params: EditParams, onChange: ((EditParams) -> EditParams) -> Unit) {
    V2AdjustmentSlider("샤프닝", params.sharpness, 0f, 1f) { v -> onChange { it.copy(sharpness = v) } }
    V2AdjustmentSlider("노이즈 감소", params.noiseReduction, 0f, 1f) { v -> onChange { it.copy(noiseReduction = v) } }
    V2PlaceholderPanel("반경, 디테일 마스킹, 컬러 노이즈 감소는 아직 연결하지 않았습니다")
}

@Composable
private fun V2AdjustmentSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onValue: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, modifier = Modifier.width(86.dp), style = MaterialTheme.typography.bodyMedium, color = V2TextPrimary)
        Slider(value = value, onValueChange = onValue, valueRange = min..max, modifier = Modifier.weight(1f))
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
private fun <T> V2OptionRow(
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
                TextButton(onClick = { onSelected(value) }) {
                    Text(label(value), color = if (value == selected) V2Accent else V2TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun V2SavedScreen(
    savedExports: List<SavedExport>,
    onRemoveSavedExport: (String) -> Unit,
    onClearSavedExports: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("저장 기록", color = V2TextPrimary, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClearSavedExports, enabled = savedExports.isNotEmpty()) { Text("기록 비우기") }
        }
        if (savedExports.isEmpty()) {
            Text("저장 기록이 없습니다", color = V2TextMuted, modifier = Modifier.padding(top = 12.dp))
        } else {
            savedExports.forEach { item ->
                Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp).background(V2CardBackground).padding(12.dp)) {
                    Text(item.displayName, color = V2TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("${item.formatLabel} · ${item.resolutionLabel}", color = V2TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Text(formatSavedTime2(item.timestampMillis), color = V2TextMuted, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onRemoveSavedExport(item.uriString) }) { Text("기록 삭제") }
                }
            }
        }
    }
}

@Composable
private fun V2SettingsScreen(
    exportHistoryRetention: ExportHistoryRetention,
    savedExportCount: Int,
    draftSavedAtMillis: Long?,
    noiseEngine: NoiseEngine,
    detailEngine: DetailEngine,
    toneEngine: ToneEngine,
    hazeEngine: DehazeEngine,
    onRetentionSelected: (ExportHistoryRetention) -> Unit,
    onNoiseEngineSelected: (NoiseEngine) -> Unit,
    onDetailEngineSelected: (DetailEngine) -> Unit,
    onToneEngineSelected: (ToneEngine) -> Unit,
    onHazeEngineSelected: (DehazeEngine) -> Unit,
    onClearDraft: () -> Unit,
    onCleanupOldTemporarySources: () -> Unit,
    onClearSavedExports: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        V2SettingsCard("저장 기록") {
            Text("현재 기록 수: $savedExportCount", color = V2TextSecondary, style = MaterialTheme.typography.bodySmall)
            V2OptionRow("보관 정책", ExportHistoryRetention.values().toList(), exportHistoryRetention, { it.label }, onRetentionSelected)
            TextButton(onClick = onClearSavedExports, enabled = savedExportCount > 0) { Text("저장 기록 비우기") }
        }
        V2SettingsCard("임시 저장") {
            Text(draftSavedAtMillis?.let { "마지막 임시 저장: ${formatSavedTime2(it)}" } ?: "현재 임시 저장 기록이 없습니다", color = V2TextSecondary, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClearDraft, enabled = draftSavedAtMillis != null) { Text("임시 저장 삭제") }
                TextButton(onClick = onCleanupOldTemporarySources) { Text("임시 원본 정리") }
            }
        }
        V2SettingsCard("엔진 선택") {
            V2OptionRow("노이즈 감소", NoiseEngine.values().toList(), noiseEngine, { it.label }, onNoiseEngineSelected)
            V2OptionRow("디테일", DetailEngine.values().toList(), detailEngine, { it.label }, onDetailEngineSelected)
            V2OptionRow("톤", ToneEngine.values().toList(), toneEngine, { it.label }, onToneEngineSelected)
            V2OptionRow("디헤이즈", DehazeEngine.values().toList(), hazeEngine, { it.label }, onHazeEngineSelected)
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
