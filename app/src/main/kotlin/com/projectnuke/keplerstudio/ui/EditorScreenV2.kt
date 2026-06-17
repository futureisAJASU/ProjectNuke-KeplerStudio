package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlin.math.roundToInt

private val V2AppBackground = Color(0xFF101010)
private val V2TopBarBackground = Color(0xFF171717)
private val V2PanelBackground = Color(0xFF1B1B1B)
private val V2PreviewBackground = Color(0xFF000000)
private val V2CardBackground = Color(0xFF242424)
private val V2NeutralAccent = Color(0xFFE6E6E6)
private val V2TextPrimary = Color(0xFFF2F2F2)
private val V2TextSecondary = Color(0xFFC8C8C8)
private val V2TextMuted = Color(0xFF8E8E8E)
private val V2ButtonTextDark = Color(0xFF111111)

private val V2DarkColors = darkColorScheme(
    primary = V2NeutralAccent,
    onPrimary = V2ButtonTextDark,
    background = V2AppBackground,
    onBackground = V2TextPrimary,
    surface = V2PanelBackground,
    onSurface = V2TextPrimary
)

private enum class V2MainTab(val label: String) {
    Editor("편집"),
    Saved("저장본"),
    Settings("설정")
}

private enum class V2EditorTool(val label: String, val description: String) {
    Auto("자동", "원터치 보정"),
    Remaster("리마스터", "모델 기반 보정과 마스크 편집"),
    Masking("마스크", "마스크 레이어와 브러시"),
    Light("조명", "노출, 대비, 하이라이트, 섀도우"),
    Color("색상", "색온도, 색조, 생동감, 채도"),
    Effects("효과", "명료도와 디헤이즈"),
    Detail("디테일", "샤픈과 노이즈 감소")
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

    MaterialTheme(colorScheme = V2DarkColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = V2AppBackground) {
            Column(modifier = Modifier.fillMaxSize().background(V2AppBackground)) {
                V2TopBar(
                    nativeVersion = state.nativeVersion,
                    selectedTab = selectedTab,
                    canExport = state.previewBitmap != null && !state.isBusy,
                    onTabSelected = { selectedTab = it },
                    onOpen = { picker.launch("image/*") },
                    onReset = {
                        PresetLookHandoff.clear()
                        viewModel.resetAdjustments()
                    },
                    onSaveClicked = { showExportDialog = true }
                )

                when (selectedTab) {
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
                    V2MainTab.Editor -> {
                        V2PreviewArea(
                            bitmap = state.previewBitmap,
                            originalBitmap = state.originalPreviewBitmap,
                            isBusy = state.isBusy,
                            message = state.message,
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        )
                        V2AdjustmentPanel(
                            selectedTool = selectedTool,
                            params = state.params,
                            onToolSelected = { selectedTool = it },
                            onAutoEnhance = {
                                PresetLookHandoff.clear()
                                viewModel.applyAutoEnhance()
                            },
                            onChange = { transform -> viewModel.updateParams(transform) }
                        )
                    }
                }
            }

            if (showExportDialog) {
                ExportSettingsDialog(
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
    }
}

@Composable
private fun V2TopBar(
    nativeVersion: String,
    selectedTab: V2MainTab,
    canExport: Boolean,
    onTabSelected: (V2MainTab) -> Unit,
    onOpen: () -> Unit,
    onReset: () -> Unit,
    onSaveClicked: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(V2TopBarBackground)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Kepler Studio v0.1", style = MaterialTheme.typography.titleLarge, color = V2TextPrimary, maxLines = 1)
                Text(nativeVersion, style = MaterialTheme.typography.bodySmall, color = V2TextSecondary, maxLines = 1)
            }
            TextButton(onClick = onReset) { Text("초기화") }
            TextButton(onClick = onSaveClicked, enabled = canExport) { Text("저장") }
            Button(
                onClick = onOpen,
                colors = ButtonDefaults.buttonColors(containerColor = V2NeutralAccent, contentColor = V2ButtonTextDark)
            ) {
                Text("사진 선택")
            }
        }
        Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            V2MainTab.values().forEach { tab ->
                TextButton(onClick = { onTabSelected(tab) }) {
                    Text(
                        text = tab.label,
                        color = if (tab == selectedTab) V2NeutralAccent else V2TextSecondary,
                        fontWeight = if (tab == selectedTab) FontWeight.Bold else FontWeight.Normal
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
    isBusy: Boolean,
    message: String?,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.background(V2PreviewBackground), contentAlignment = Alignment.Center) {
        if (bitmap == null) {
            Text("사진을 선택해 주세요", color = V2TextPrimary, style = MaterialTheme.typography.bodyLarge)
        } else {
            var showOriginal by remember { mutableStateOf(false) }
            Image(
                bitmap = (if (showOriginal) originalBitmap ?: bitmap else bitmap).asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            )
            Row(modifier = Modifier.align(Alignment.TopStart).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showOriginal = !showOriginal }) {
                    Text(if (showOriginal) "편집본" else "원본 보기")
                }
            }
        }
        if (isBusy) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))
        }
        message?.let {
            Text(
                text = it,
                color = V2TextPrimary,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
            )
        }
    }
}

@Composable
private fun V2AdjustmentPanel(
    selectedTool: V2EditorTool,
    params: EditParams,
    onToolSelected: (V2EditorTool) -> Unit,
    onAutoEnhance: () -> Unit,
    onChange: ((EditParams) -> EditParams) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().background(V2PanelBackground).navigationBarsPadding()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 170.dp, max = 290.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(selectedTool.label, color = V2TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(selectedTool.description, color = V2TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            when (selectedTool) {
                V2EditorTool.Auto -> V2AutoPanel(onAutoEnhance)
                V2EditorTool.Remaster -> RemasterToolPanel(onQuickAutoEnhance = onAutoEnhance)
                V2EditorTool.Masking -> MaskingToolPanel()
                V2EditorTool.Light -> V2LightPanel(params, onChange)
                V2EditorTool.Color -> V2ColorPanel(params, onChange)
                V2EditorTool.Effects -> V2EffectsPanel(params, onChange)
                V2EditorTool.Detail -> V2DetailPanel(params, onChange)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(Color(0xFF141414))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            V2EditorTool.values().forEach { tool ->
                TextButton(onClick = { onToolSelected(tool) }) {
                    Text(
                        text = tool.label,
                        color = if (tool == selectedTool) V2NeutralAccent else V2TextSecondary,
                        fontWeight = if (tool == selectedTool) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportSettingsDialog(
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
            Column {
                Text("저장하기 전에 파일 형식과 해상도를 선택합니다", color = V2TextSecondary, style = MaterialTheme.typography.bodySmall)
                Text("파일 형식", color = V2TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                OptionRow2(values = ExportFormat.values().toList(), selected = exportFormat, label = { it.label }, onSelected = onFormatSelected)
                Text("해상도", color = V2TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                OptionRow2(values = ExportResolution.values().toList(), selected = exportResolution, label = { it.label }, onSelected = onResolutionSelected)
            }
        },
        confirmButton = {
            Button(onClick = onSave, colors = ButtonDefaults.buttonColors(containerColor = V2NeutralAccent, contentColor = V2ButtonTextDark)) {
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

@Composable
private fun V2AutoPanel(onAutoEnhance: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(V2CardBackground).padding(12.dp)) {
        Text("자동 분석", color = V2TextPrimary, fontWeight = FontWeight.SemiBold)
        Text("현재 사진의 밝기와 색상 통계를 분석해 기본 보정값을 적용합니다", color = V2TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        Button(onClick = onAutoEnhance, colors = ButtonDefaults.buttonColors(containerColor = V2NeutralAccent, contentColor = V2ButtonTextDark)) {
            Text("빠른 자동 보정 적용")
        }
    }
}

@Composable
private fun V2LightPanel(params: EditParams, onChange: ((EditParams) -> EditParams) -> Unit) {
    ParamSlider2("노출", params.exposure, -1f, 1f) { onChange { it.copy(exposure = it.exposure + (it.exposure - it.exposure) + 0f) } }
    ParamSlider2("노출", params.exposure, -1f, 1f) { value -> onChange { it.copy(exposure = value) } }
    ParamSlider2("대비", params.contrast, -1f, 1f) { value -> onChange { it.copy(contrast = value) } }
    ParamSlider2("하이라이트", params.highlights, -1f, 1f) { value -> onChange { it.copy(highlights = value) } }
    ParamSlider2("섀도우", params.shadows, -1f, 1f) { value -> onChange { it.copy(shadows = value) } }
    ParamSlider2("화이트", params.whites, -1f, 1f) { value -> onChange { it.copy(whites = value) } }
    ParamSlider2("블랙", params.blacks, -1f, 1f) { value -> onChange { it.copy(blacks = value) } }
}

@Composable
private fun V2ColorPanel(params: EditParams, onChange: ((EditParams) -> EditParams) -> Unit) {
    ParamSlider2("색온도", params.temperature, -1f, 1f) { value -> onChange { it.copy(temperature = value) } }
    ParamSlider2("색조", params.tint, -1f, 1f) { value -> onChange { it.copy(tint = value) } }
    ParamSlider2("생동감", params.vibrance, -1f, 1f) { value -> onChange { it.copy(vibrance = value) } }
    ParamSlider2("채도", params.saturation, -1f, 1f) { value -> onChange { it.copy(saturation = value) } }
}

@Composable
private fun V2EffectsPanel(params: EditParams, onChange: ((EditParams) -> EditParams) -> Unit) {
    ParamSlider2("명료도", params.clarity, -1f, 1f) { value -> onChange { it.copy(clarity = value) } }
    ParamSlider2("디헤이즈", params.dehaze, -1f, 1f) { value -> onChange { it.copy(dehaze = value) } }
}

@Composable
private fun V2DetailPanel(params: EditParams, onChange: ((EditParams) -> EditParams) -> Unit) {
    ParamSlider2("샤픈", params.sharpness, 0f, 1f) { value -> onChange { it.copy(sharpness = value) } }
    ParamSlider2("노이즈 감소", params.noiseReduction, 0f, 1f) { value -> onChange { it.copy(noiseReduction = value) } }
}

@Composable
private fun ParamSlider2(label: String, value: Float, min: Float, max: Float, onValue: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = V2TextSecondary, style = MaterialTheme.typography.bodySmall)
            Text(String.format(Locale.US, "%.2f", value), color = V2TextMuted, style = MaterialTheme.typography.bodySmall)
        }
        Slider(value = value.coerceIn(min, max), onValueChange = onValue, valueRange = min..max)
    }
}

@Composable
private fun <T> OptionRow2(values: List<T>, selected: T, label: (T) -> String, onSelected: (T) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        values.forEach { value ->
            TextButton(onClick = { onSelected(value) }) {
                Text(
                    text = label(value),
                    color = if (value == selected) V2NeutralAccent else V2TextSecondary,
                    fontWeight = if (value == selected) FontWeight.Bold else FontWeight.Normal
                )
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
    Column(modifier = modifier.background(V2AppBackground).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("내보낸 사진", color = V2TextPrimary, style = MaterialTheme.typography.titleLarge)
                Text("Kepler Studio에서 내보낸 기록만 표시됩니다", color = V2TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onClearSavedExports, enabled = savedExports.isNotEmpty()) { Text("기록 전체 비우기") }
        }
        if (savedExports.isEmpty()) {
            Text("아직 내보낸 사진 기록이 없습니다", color = V2TextMuted, modifier = Modifier.padding(top = 12.dp))
        } else {
            savedExports.forEach { item ->
                Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp).background(V2CardBackground).padding(14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.displayName, color = V2TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text("${item.formatLabel} · ${item.resolutionLabel}", color = V2TextSecondary, style = MaterialTheme.typography.bodySmall)
                            Text(formatSavedTime2(item.timestampMillis), color = V2TextMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onRemoveSavedExport(item.uriString) }) { Text("기록 삭제") }
                    }
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
    Column(modifier = modifier.background(V2AppBackground).verticalScroll(rememberScrollState()).padding(16.dp)) {
        V2SettingsCard("보정 엔진") {
            OptionRow2(NoiseEngine.values().toList(), noiseEngine, { it.label }, onNoiseEngineSelected)
            OptionRow2(DetailEngine.values().toList(), detailEngine, { it.label }, onDetailEngineSelected)
            OptionRow2(ToneEngine.values().toList(), toneEngine, { it.label }, onToneEngineSelected)
            OptionRow2(DehazeEngine.values().toList(), hazeEngine, { it.label }, onHazeEngineSelected)
        }
        V2SettingsCard("내보낸 사진 기록") {
            Text("현재 기록: ${savedExportCount}개", color = V2TextSecondary, style = MaterialTheme.typography.bodySmall)
            OptionRow2(ExportHistoryRetention.values().toList(), exportHistoryRetention, { it.label }, onRetentionSelected)
            TextButton(onClick = onClearSavedExports, enabled = savedExportCount > 0) { Text("기록 전체 비우기") }
        }
        V2SettingsCard("임시저장") {
            Text(draftSavedAtMillis?.let { "마지막 임시저장: ${formatSavedTime2(it)}" } ?: "현재 임시저장 기록이 없습니다", color = V2TextSecondary, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClearDraft, enabled = draftSavedAtMillis != null) { Text("임시저장 삭제") }
                TextButton(onClick = onCleanupOldTemporarySources) { Text("임시 원본 정리") }
            }
        }
    }
}

@Composable
private fun V2SettingsCard(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).background(V2CardBackground).padding(14.dp)) {
        Text(title, color = V2TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.padding(top = 6.dp))
        content()
    }
}

private fun formatSavedTime2(timestampMillis: Long): String =
    SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(timestampMillis))
