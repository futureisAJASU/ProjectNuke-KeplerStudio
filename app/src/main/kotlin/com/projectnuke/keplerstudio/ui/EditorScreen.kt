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
import androidx.compose.ui.layout.onSizeChanged
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
import com.projectnuke.keplerstudio.editor.SavedExport
import com.projectnuke.keplerstudio.editor.ToneEngine
import com.projectnuke.keplerstudio.editor.ViewportState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AppBackground = Color(0xFF101010)
private val TopBarBackground = Color(0xFF171717)
private val PanelBackground = Color(0xFF1B1B1B)
private val PreviewBackground = Color(0xFF000000)
private val RailBackground = Color(0xFF141414)
private val CardBackground = Color(0xFF242424)
private val CompareBadgeBackground = Color(0xCC000000)
private val NeutralAccent = Color(0xFFE6E6E6)
private val TextPrimary = Color(0xFFF2F2F2)
private val TextSecondary = Color(0xFFC8C8C8)
private val TextMuted = Color(0xFF8E8E8E)
private val ButtonTextDark = Color(0xFF111111)

private val KeplerDarkColors = darkColorScheme(
    primary = NeutralAccent,
    onPrimary = ButtonTextDark,
    background = AppBackground,
    onBackground = TextPrimary,
    surface = PanelBackground,
    onSurface = TextPrimary
)

private enum class MainTab(val label: String) {
    Editor("편집"),
    Saved("저장본"),
    Settings("설정")
}

private enum class EditorTool(val label: String, val description: String) {
    Auto("자동", "원터치 보정과 흑백 전환"),
    Remaster("리마스터", "빠른 자동 보정과 온디바이스 모델 기반 보정"),
    Profiles("프로필", "기본, 필름, 모던, 빈티지 톤"),
    Presets("프리셋", "저장된 보정값과 추천 톤"),
    Crop("자르기", "비율, 회전, 수평 보정"),
    Masking("마스크", "피사체, 하늘, 배경 선택"),
    Remove("제거", "지우개, 반사 제거, 먼지 제거"),
    Light("조명", "노출, 대비, 하이라이트, 섀도우"),
    Color("색상", "화이트밸런스, 생동감, 채도, HSL"),
    Effects("효과", "명료도, 디헤이즈, 비네팅, 그레인"),
    Detail("디테일", "샤픈, 노이즈 감소, 컬러 노이즈"),
    Optics("옵틱", "색수차 제거와 렌즈 보정"),
    Geometry("기하", "왜곡, 수직/수평, 원근 보정"),
    Blur("블러", "렌즈 블러와 초점 영역"),
    Model("모델", "자동 마스크, 디테일 복원, 노이즈 억제")
}

// Legacy reference screen kept buildable while EditorScreenV2 remains the active entry.
@Composable
fun EditorScreen(viewModel: EditorViewModel) {
    val state by viewModel.uiState.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            viewModel.openImage(uri)
        }
    }
    var selectedTool by remember { mutableStateOf(EditorTool.Light) }
    var selectedTab by remember { mutableStateOf(MainTab.Editor) }

    MaterialTheme(colorScheme = KeplerDarkColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = AppBackground) {
            Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
                TopBar(
                    nativeVersion = state.nativeVersion,
                    selectedTab = selectedTab,
                    canExport = state.previewBitmap != null && !state.isBusy,
                    onTabSelected = { selectedTab = it },
                    onOpen = { picker.launch("image/*") },
                    onReset = viewModel::resetAdjustments,
                    onExport = { viewModel.exportPreview() }
                )

                when (selectedTab) {
                    MainTab.Saved -> SavedExportsScreen(
                        savedExports = state.savedExports,
                        onRemoveSavedExport = viewModel::removeSavedExport,
                        onClearSavedExports = viewModel::clearSavedExports,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )

                    MainTab.Settings -> SettingsScreen(
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

                    MainTab.Editor -> {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth().background(PreviewBackground),
                            contentAlignment = Alignment.Center
                        ) {
                            val bitmap = state.previewBitmap
                            if (bitmap == null) {
                                Text("사진을 선택해 주세요", color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                            } else {
                                ZoomablePreview(
                                    bitmap = bitmap,
                                    originalBitmap = state.originalPreviewBitmap,
                                    onViewportChanged = viewModel::updateViewport
                                )
                            }

                            if (state.isBusy) {
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))
                            }

                            state.message?.let {
                                Text(
                                    text = it,
                                    color = TextPrimary,
                                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                                )
                            }
                        }

                        AdjustmentPanel(
                            viewModel = viewModel,
                            selectedTool = selectedTool,
                            params = state.params,
                            activeLook = state.presetLook,
                            exportFormat = state.exportFormat,
                            exportResolution = state.exportResolution,
                            draftSavedAtMillis = state.draftSavedAtMillis,
                            onToolSelected = { selectedTool = it },
                            onFormatSelected = viewModel::setExportFormat,
                            onResolutionSelected = viewModel::setExportResolution,
                            onAutoEnhance = viewModel::applyAutoEnhance,
                            onChange = { transform -> viewModel.updateParams(transform) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    nativeVersion: String,
    selectedTab: MainTab,
    canExport: Boolean,
    onTabSelected: (MainTab) -> Unit,
    onOpen: () -> Unit,
    onReset: () -> Unit,
    onExport: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TopBarBackground)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Kepler Studio v0.1", style = MaterialTheme.typography.titleLarge, color = TextPrimary, maxLines = 1)
                Text(nativeVersion, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1)
            }

            TextButton(onClick = onReset) { Text("초기화") }
            TextButton(onClick = onExport, enabled = canExport) { Text("저장") }
            Button(
                onClick = onOpen,
                colors = ButtonDefaults.buttonColors(containerColor = NeutralAccent, contentColor = ButtonTextDark)
            ) {
                Text("사진 선택")
            }
        }

        Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MainTab.values().forEach { tab ->
                TextButton(onClick = { onTabSelected(tab) }) {
                    Text(
                        text = tab.label,
                        color = if (tab == selectedTab) NeutralAccent else TextSecondary,
                        fontWeight = if (tab == selectedTab) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedExportsScreen(
    savedExports: List<SavedExport>,
    onRemoveSavedExport: (String) -> Unit,
    onClearSavedExports: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(AppBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("내보낸 사진", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Kepler Studio에서 내보낸 기록만 표시됩니다. 갤러리 파일은 직접 삭제하지 않습니다",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            TextButton(onClick = onClearSavedExports, enabled = savedExports.isNotEmpty()) {
                Text("기록 전체 비우기")
            }
        }

        if (savedExports.isEmpty()) {
            Text(
                "아직 내보낸 사진 기록이 없습니다",
                color = TextMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp)
            )
        } else {
            savedExports.forEach { item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .background(CardBackground)
                        .padding(14.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.displayName, color = TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("${item.formatLabel} · ${item.resolutionLabel}", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                            Text(formatSavedTime(item.timestampMillis), color = TextMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onRemoveSavedExport(item.uriString) }) {
                            Text("기록 삭제")
                        }
                    }
                    Text(item.uriString, color = TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
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
    Column(
        modifier = modifier
            .background(AppBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("정리 설정", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        Text(
            "자동 정리는 Kepler Studio가 관리하는 기록만 대상으로 합니다. 갤러리에 저장된 실제 사진은 자동으로 삭제하지 않습니다",
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp, bottom = 14.dp)
        )

        SettingsCard(title = "보정 엔진") {
            Text(
                "사진 특성에 맞게 알고리즘을 바꿔 테스트할 수 있습니다. 지원 준비 중인 엔진은 선택값을 저장해 두고, 구현되는 순서대로 활성화됩니다",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            OptionRow(title = "노이즈", values = NoiseEngine.values().toList(), selected = noiseEngine, label = { it.label }, onSelected = onNoiseEngineSelected)
            OptionRow(title = "디테일", values = DetailEngine.values().toList(), selected = detailEngine, label = { it.label }, onSelected = onDetailEngineSelected)
            OptionRow(title = "톤", values = ToneEngine.values().toList(), selected = toneEngine, label = { it.label }, onSelected = onToneEngineSelected)
            OptionRow(title = "안개", values = DehazeEngine.values().toList(), selected = hazeEngine, label = { it.label }, onSelected = onHazeEngineSelected)
        }

        SettingsCard(title = "내보낸 사진 기록") {
            Text("현재 기록: ${savedExportCount}개", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            OptionRow(
                title = "자동",
                values = ExportHistoryRetention.values().toList(),
                selected = exportHistoryRetention,
                label = { it.label },
                onSelected = onRetentionSelected
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClearSavedExports, enabled = savedExportCount > 0) {
                    Text("기록 전체 비우기")
                }
            }
        }

        SettingsCard(title = "임시저장") {
            Text(
                draftSavedAtMillis?.let { "마지막 임시저장: ${formatSavedTime(it)}" } ?: "현재 자동복구용 임시저장 기록이 없습니다",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClearDraft, enabled = draftSavedAtMillis != null) {
                    Text("임시저장 기록 삭제")
                }
                TextButton(onClick = onCleanupOldTemporarySources) {
                    Text("오래된 임시 원본 정리")
                }
            }
            Text(
                "오래된 임시 원본 정리는 7일이 지난 앱 캐시 파일만 삭제하며, 현재 편집 중인 원본은 제외합니다",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(CardBackground)
            .padding(14.dp)
    ) {
        Text(title, color = TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Column(modifier = Modifier.padding(top = 8.dp)) {
            content()
        }
    }
}

@Composable
private fun ZoomablePreview(
    bitmap: Bitmap,
    originalBitmap: Bitmap?,
    onViewportChanged: (ViewportState) -> Unit
) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var showOriginal by remember(bitmap, originalBitmap) { mutableStateOf(false) }
    var isMultiTouch by remember(bitmap) { mutableStateOf(false) }
    var isTransforming by remember(bitmap) { mutableStateOf(false) }
    val displayedBitmap = if (showOriginal && originalBitmap != null) originalBitmap else bitmap

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(
            bitmap = displayedBitmap.asImageBitmap(),
            contentDescription = "preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged {
                    size = it
                    onViewportChanged(ViewportState(scale, offset, it.width, it.height))
                }
                .pointerInput(bitmap) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressedCount = event.changes.count { it.pressed }
                            isMultiTouch = pressedCount > 1
                            if (pressedCount != 1) showOriginal = false
                            if (pressedCount == 0) isTransforming = false
                        }
                    }
                }
                .pointerInput(bitmap) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        isTransforming = true
                        showOriginal = false
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        offset += pan
                        if (scale <= 1.01f) offset = Offset.Zero
                        onViewportChanged(ViewportState(scale, offset, size.width, size.height))
                    }
                }
                .pointerInput(bitmap, originalBitmap) {
                    detectTapGestures(
                        onLongPress = {
                            if (originalBitmap != null && !isMultiTouch && !isTransforming) showOriginal = true
                        },
                        onPress = {
                            tryAwaitRelease()
                            showOriginal = false
                            isTransforming = false
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

        if (showOriginal && originalBitmap != null) {
            Text(
                text = "원본",
                color = TextPrimary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp)
                    .background(CompareBadgeBackground)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun AdjustmentPanel(
    viewModel: EditorViewModel,
    selectedTool: EditorTool,
    params: EditParams,
    activeLook: com.projectnuke.keplerstudio.editor.PresetColorLook?,
    exportFormat: ExportFormat,
    exportResolution: ExportResolution,
    draftSavedAtMillis: Long?,
    onToolSelected: (EditorTool) -> Unit,
    onFormatSelected: (ExportFormat) -> Unit,
    onResolutionSelected: (ExportResolution) -> Unit,
    onAutoEnhance: () -> Unit,
    onChange: ((EditParams) -> EditParams) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().background(PanelBackground).navigationBarsPadding()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 170.dp, max = 290.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            ExportOptionsPanel(
                exportFormat = exportFormat,
                exportResolution = exportResolution,
                draftSavedAtMillis = draftSavedAtMillis,
                onFormatSelected = onFormatSelected,
                onResolutionSelected = onResolutionSelected
            )

            Text(selectedTool.label, color = TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                selectedTool.description,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )

            when (selectedTool) {
                EditorTool.Auto -> AutoPanel(onAutoEnhance)
                EditorTool.Remaster -> RemasterToolPanel(onQuickAutoEnhance = onAutoEnhance)
                EditorTool.Profiles -> UnavailablePanel("내장 색감 룩은 EditorScreenV2에서 지원됩니다. 전용 카메라 프로필은 아직 지원되지 않습니다")
                EditorTool.Presets -> PresetToolPanel(
                    editorViewModel = viewModel,
                    params = params,
                    activeLook = activeLook
                )
                EditorTool.Light -> LightPanel(params, onChange)
                EditorTool.Color -> ColorPanel(params, onChange)
                EditorTool.Effects -> EffectsPanel(params, onChange)
                EditorTool.Detail -> DetailPanel(params, onChange)
                EditorTool.Crop -> UnavailablePanel("자르기 도구는 EditorScreenV2에서 지원됩니다")
                EditorTool.Masking -> UnavailablePanel("마스킹 모델은 아직 지원되지 않습니다")
                EditorTool.Remove -> UnavailablePanel("기본 정리 도구는 EditorScreenV2에서 지원됩니다")
                EditorTool.Optics -> UnavailablePanel("기본 광학 보정은 EditorScreenV2에서 지원됩니다. 렌즈 프로필 보정은 아직 지원되지 않습니다")
                EditorTool.Geometry -> UnavailablePanel("자르기와 기울기 보정은 EditorScreenV2에서 지원됩니다. 원근 보정은 아직 지원되지 않습니다")
                EditorTool.Blur -> UnavailablePanel("부드러운 흐림은 EditorScreenV2에서 지원됩니다")
                EditorTool.Model -> UnavailablePanel("AI 스타일 전환과 자동 마스크는 아직 지원되지 않습니다")
            }
        }

        ToolRail(selectedTool = selectedTool, onToolSelected = onToolSelected)
    }
}

@Composable
private fun ExportOptionsPanel(
    exportFormat: ExportFormat,
    exportResolution: ExportResolution,
    draftSavedAtMillis: Long?,
    onFormatSelected: (ExportFormat) -> Unit,
    onResolutionSelected: (ExportResolution) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text("내보내기 설정", color = TextPrimary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            text = draftSavedAtMillis?.let { "임시저장됨 · ${formatSavedTime(it)}" } ?: "편집값은 자동으로 임시저장됩니다",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp)
        )

        OptionRow(title = "파일", values = ExportFormat.values().toList(), selected = exportFormat, label = { it.label }, onSelected = onFormatSelected)
        OptionRow(title = "크기", values = ExportResolution.values().toList(), selected = exportResolution, label = { it.label }, onSelected = onResolutionSelected)
    }
}

@Composable
private fun <T> OptionRow(
    title: String,
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(46.dp))
        values.forEach { value ->
            TextButton(onClick = { onSelected(value) }) {
                Text(
                    text = label(value),
                    color = if (value == selected) NeutralAccent else TextSecondary,
                    fontWeight = if (value == selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun ToolRail(selectedTool: EditorTool, onToolSelected: (EditorTool) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RailBackground)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EditorTool.values().forEach { tool ->
            TextButton(onClick = { onToolSelected(tool) }) {
                Text(
                    text = tool.label,
                    color = if (tool == selectedTool) NeutralAccent else TextSecondary,
                    fontWeight = if (tool == selectedTool) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun AutoPanel(onAutoEnhance: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onAutoEnhance,
            colors = ButtonDefaults.buttonColors(containerColor = NeutralAccent, contentColor = ButtonTextDark)
        ) {
            Text("자동 보정")
        }
        Button(
            onClick = { },
            enabled = false,
            colors = ButtonDefaults.buttonColors(containerColor = NeutralAccent, contentColor = ButtonTextDark)
        ) {
            Text("흑백")
        }
    }
    Text(
        text = "히스토그램을 분석해 노출, 대비, 하이라이트, 섀도우, 색감, 디테일 값을 자동으로 적용합니다",
        color = TextMuted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun LightPanel(params: EditParams, onChange: ((EditParams) -> EditParams) -> Unit) {
    AdjustmentSlider("노출", params.exposure, -1f, 1f) { v -> onChange { it.copy(exposure = v) } }
    AdjustmentSlider("대비", params.contrast, -1f, 1f) { v -> onChange { it.copy(contrast = v) } }
    AdjustmentSlider("하이라이트", params.highlights, -1f, 1f) { v -> onChange { it.copy(highlights = v) } }
    AdjustmentSlider("섀도우", params.shadows, -1f, 1f) { v -> onChange { it.copy(shadows = v) } }
    AdjustmentSlider("화이트", params.whites, -1f, 1f) { v -> onChange { it.copy(whites = v) } }
    AdjustmentSlider("블랙", params.blacks, -1f, 1f) { v -> onChange { it.copy(blacks = v) } }
}

@Composable
private fun ColorPanel(params: EditParams, onChange: ((EditParams) -> EditParams) -> Unit) {
    AdjustmentSlider("색온도", params.temperature, -1f, 1f) { v -> onChange { it.copy(temperature = v) } }
    AdjustmentSlider("색조", params.tint, -1f, 1f) { v -> onChange { it.copy(tint = v) } }
    AdjustmentSlider("생동감", params.vibrance, -1f, 1f) { v -> onChange { it.copy(vibrance = v) } }
    AdjustmentSlider("채도", params.saturation, -1f, 1f) { v -> onChange { it.copy(saturation = v) } }
    UnavailablePanel("HSL과 색상 혼합은 아직 지원되지 않습니다")
}

@Composable
private fun EffectsPanel(params: EditParams, onChange: ((EditParams) -> EditParams) -> Unit) {
    AdjustmentSlider("명료도", params.clarity, -1f, 1f) { v -> onChange { it.copy(clarity = v) } }
    AdjustmentSlider("디헤이즈", params.dehaze, -1f, 1f) { v -> onChange { it.copy(dehaze = v) } }
    UnavailablePanel("텍스처, 비네팅, 그레인은 아직 지원되지 않습니다")
}

@Composable
private fun DetailPanel(params: EditParams, onChange: ((EditParams) -> EditParams) -> Unit) {
    AdjustmentSlider("샤프닝", params.sharpness, 0f, 1f) { v -> onChange { it.copy(sharpness = v) } }
    AdjustmentSlider("노이즈 감소", params.luminanceNoiseReduction, 0f, 1f) { v ->
        onChange { it.copy(noiseReduction = v, luminanceNoiseReduction = v) }
    }
    AdjustmentSlider("색상 노이즈 감소", params.colorNoiseReduction, 0f, 1f) { v ->
        onChange { it.copy(colorNoiseReduction = v) }
    }
    AdjustmentSlider("디테일 보호", params.noiseDetailProtection, 0f, 1f) { v ->
        onChange { it.copy(noiseDetailProtection = v) }
    }
}

@Composable
private fun UnavailablePanel(message: String) {
    Text(
        text = message,
        color = TextMuted,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 10.dp)
    )
}

@Composable
private fun AdjustmentSlider(
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
        Text(label, modifier = Modifier.width(86.dp), style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
        Slider(value = value, onValueChange = onValue, valueRange = min..max, modifier = Modifier.weight(1f))
        Text(String.format(Locale.US, "%.2f", value), modifier = Modifier.width(52.dp), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
    }
}

private fun formatSavedTime(timestampMillis: Long): String =
    SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(timestampMillis))
