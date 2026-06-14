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
import androidx.compose.foundation.layout.Spacer
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
import com.projectnuke.keplerstudio.editor.EditParams
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.ViewportState

private val AppBackground = Color(0xFF101014)
private val TopBarBackground = Color(0xFF17171D)
private val PanelBackground = Color(0xFF1A1A22)
private val PreviewBackground = Color(0xFF000000)
private val RailBackground = Color(0xFF14141B)
private val CompareBadgeBackground = Color(0xCC000000)
private val PrimaryPurple = Color(0xFF8E6CEF)
private val TextPrimary = Color(0xFFF7F2FF)
private val TextSecondary = Color(0xFFC9C0D8)
private val TextMuted = Color(0xFF877D97)

private val KeplerDarkColors = darkColorScheme(
    primary = PrimaryPurple,
    onPrimary = Color.White,
    background = AppBackground,
    onBackground = TextPrimary,
    surface = PanelBackground,
    onSurface = TextPrimary
)

private enum class EditorTool(val label: String, val description: String) {
    Auto("자동", "원터치 보정과 흑백 전환"),
    Profiles("프로필", "기본, 필름, 모던, 빈티지 톤"),
    Presets("프리셋", "저장된 보정값과 추천 톤"),
    Crop("자르기", "비율, 회전, 수평 보정"),
    Masking("마스크", "피사체, 하늘, 배경 선택"),
    Remove("제거", "지우개, 반사, 먼지 제거"),
    Light("조명", "노출, 대비, 하이라이트, 섀도우"),
    Color("색상", "화이트밸런스, 생동감, 채도, HSL"),
    Effects("효과", "텍스처, 명료도, 디헤이즈, 비네팅, 그레인"),
    Detail("디테일", "샤픈, 노이즈 감소, 컬러 노이즈"),
    Optics("옵틱", "색수차 제거와 렌즈 보정"),
    Geometry("기하", "왜곡, 수직/수평, 원근 보정"),
    Blur("블러", "렌즈 블러와 초점 영역"),
    Ai("AI", "리마스터, 초점, 플레어 복원")
}

@Composable
fun EditorScreen(viewModel: EditorViewModel) {
    val state by viewModel.uiState.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.openImage(uri)
    }
    var selectedTool by remember { mutableStateOf(EditorTool.Light) }

    MaterialTheme(colorScheme = KeplerDarkColors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = AppBackground
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppBackground)
            ) {
                TopBar(
                    nativeVersion = state.nativeVersion,
                    onOpen = { picker.launch("image/*") },
                    onReset = { viewModel.resetAdjustments() }
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(PreviewBackground),
                    contentAlignment = Alignment.Center
                ) {
                    val bitmap = state.previewBitmap
                    if (bitmap == null) {
                        Text(
                            text = "사진을 선택해 주세요",
                            color = TextPrimary,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    } else {
                        ZoomablePreview(
                            bitmap = bitmap,
                            originalBitmap = state.originalPreviewBitmap,
                            onViewportChanged = viewModel::updateViewport
                        )
                    }

                    if (state.isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                        )
                    }

                    state.message?.let {
                        Text(
                            text = it,
                            color = TextPrimary,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(12.dp)
                        )
                    }
                }

                AdjustmentPanel(
                    selectedTool = selectedTool,
                    params = state.params,
                    onToolSelected = { selectedTool = it },
                    onChange = { transform -> viewModel.updateParams(transform) }
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    nativeVersion: String,
    onOpen: () -> Unit,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TopBarBackground)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Kepler Studio v0.1",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                maxLines = 1
            )
            Text(
                text = nativeVersion,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1
            )
        }

        TextButton(onClick = onReset) {
            Text("초기화")
        }
        Button(
            onClick = onOpen,
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple)
        ) {
            Text("사진 선택")
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
                            if (pressedCount != 1) {
                                showOriginal = false
                            }
                            if (pressedCount == 0) {
                                isTransforming = false
                            }
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
                            if (originalBitmap != null && !isMultiTouch && !isTransforming) {
                                showOriginal = true
                            }
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
    selectedTool: EditorTool,
    params: EditParams,
    onToolSelected: (EditorTool) -> Unit,
    onChange: ((EditParams) -> EditParams) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBackground)
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 150.dp, max = 260.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = selectedTool.label,
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = selectedTool.description,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )

            when (selectedTool) {
                EditorTool.Auto -> AutoPanel()
                EditorTool.Profiles -> PlaceholderPanel("프로필 브라우저와 강도 조절은 다음 단계에서 연결됩니다")
                EditorTool.Presets -> PlaceholderPanel("사용자 프리셋과 추천 프리셋 저장소를 준비 중입니다")
                EditorTool.Crop -> PlaceholderPanel("비율, 회전, 수평계 기반 자르기 도구를 준비 중입니다")
                EditorTool.Masking -> PlaceholderPanel("피사체, 하늘, 배경 마스크 모델을 연결할 예정입니다")
                EditorTool.Remove -> PlaceholderPanel("지우개, 반사 제거, 센서 먼지 제거 엔진을 연결할 예정입니다")
                EditorTool.Light -> LightPanel(params, onChange)
                EditorTool.Color -> ColorPanel()
                EditorTool.Effects -> EffectsPanel()
                EditorTool.Detail -> DetailPanel(params, onChange)
                EditorTool.Optics -> PlaceholderPanel("색수차 제거와 렌즈 프로필 보정은 네이티브 엔진에 추가할 예정입니다")
                EditorTool.Geometry -> PlaceholderPanel("왜곡, 수직, 수평, 원근 보정 UI를 준비 중입니다")
                EditorTool.Blur -> PlaceholderPanel("렌즈 블러와 초점 영역 편집은 AI 마스크 이후 연결됩니다")
                EditorTool.Ai -> PlaceholderPanel("리마스터, 초점 리마스터, 플레어 억제 기능을 준비 중입니다")
            }
        }

        ToolRail(
            selectedTool = selectedTool,
            onToolSelected = onToolSelected
        )
    }
}

@Composable
private fun ToolRail(
    selectedTool: EditorTool,
    onToolSelected: (EditorTool) -> Unit
) {
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
                    color = if (tool == selectedTool) PrimaryPurple else TextSecondary,
                    fontWeight = if (tool == selectedTool) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun AutoPanel() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = { },
            enabled = false,
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple)
        ) {
            Text("자동 보정")
        }
        Button(
            onClick = { },
            enabled = false,
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple)
        ) {
            Text("흑백")
        }
    }
    Text(
        text = "자동 보정 모델 연결 후 활성화됩니다",
        color = TextMuted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun LightPanel(
    params: EditParams,
    onChange: ((EditParams) -> EditParams) -> Unit
) {
    AdjustmentSlider("노출", params.exposure, -1f, 1f) { v -> onChange { it.copy(exposure = v) } }
    AdjustmentSlider("대비", params.contrast, -1f, 1f) { v -> onChange { it.copy(contrast = v) } }
    AdjustmentSlider("하이라이트", params.highlights, -1f, 1f) { v -> onChange { it.copy(highlights = v) } }
    AdjustmentSlider("섀도우", params.shadows, -1f, 1f) { v -> onChange { it.copy(shadows = v) } }
    DisabledSlider("화이트", "톤 커브/화이트 포인트 엔진 연결 예정")
    DisabledSlider("블랙", "톤 커브/블랙 포인트 엔진 연결 예정")
    DisabledSlider("커브", "RGB 톤 커브 UI 연결 예정")
}

@Composable
private fun ColorPanel() {
    DisabledSlider("색온도", "화이트밸런스 엔진 연결 예정")
    DisabledSlider("색조", "화이트밸런스 엔진 연결 예정")
    DisabledSlider("생동감", "선택적 채도 엔진 연결 예정")
    DisabledSlider("채도", "전역 채도 엔진 연결 예정")
    DisabledSlider("색상 믹스", "HSL 색상별 보정 UI 연결 예정")
    DisabledSlider("컬러 그레이딩", "섀도우/미드톤/하이라이트 휠 연결 예정")
}

@Composable
private fun EffectsPanel() {
    DisabledSlider("텍스처", "텍스처 보정 엔진 연결 예정")
    DisabledSlider("명료도", "로컬 콘트라스트 엔진 연결 예정")
    DisabledSlider("디헤이즈", "안개 제거 엔진 연결 예정")
    DisabledSlider("비네팅", "비네팅 엔진 연결 예정")
    DisabledSlider("그레인", "필름 그레인 엔진 연결 예정")
}

@Composable
private fun DetailPanel(
    params: EditParams,
    onChange: ((EditParams) -> EditParams) -> Unit
) {
    AdjustmentSlider("샤픈", params.sharpness, 0f, 1f) { v -> onChange { it.copy(sharpness = v) } }
    DisabledSlider("반경", "샤픈 반경 엔진 연결 예정")
    DisabledSlider("디테일", "고주파 디테일 보정 연결 예정")
    DisabledSlider("마스킹", "엣지 기반 샤픈 마스크 연결 예정")
    DisabledSlider("노이즈 감소", "휘도 노이즈 감소 연결 예정")
    DisabledSlider("컬러 노이즈", "색 노이즈 감소 연결 예정")
}

@Composable
private fun PlaceholderPanel(message: String) {
    Text(
        text = message,
        color = TextMuted,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 10.dp)
    )
}

@Composable
private fun DisabledSlider(
    label: String,
    hint: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.width(86.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted
            )
        }
        Slider(
            value = 0f,
            onValueChange = { },
            valueRange = -1f..1f,
            enabled = false,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "예정",
            modifier = Modifier.width(44.dp),
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )
    }
    Text(
        text = hint,
        color = TextMuted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 94.dp, bottom = 4.dp)
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
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.width(86.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary
        )
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = min..max,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = String.format("%.2f", value),
            modifier = Modifier.width(52.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}
