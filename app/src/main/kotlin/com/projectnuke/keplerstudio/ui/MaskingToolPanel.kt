package com.projectnuke.keplerstudio.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.projectnuke.keplerstudio.editor.EditParams
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.SelectionLayer
import com.projectnuke.keplerstudio.editor.SelectionPaintMode
import kotlin.math.roundToInt

private val MaskCardBackground = Color(0xFF242424)
private val MaskAccent = Color(0xFFE6E6E6)
private val MaskTextPrimary = Color(0xFFF2F2F2)
private val MaskTextSecondary = Color(0xFFC8C8C8)
private val MaskTextMuted = Color(0xFF8E8E8E)
private val MaskButtonTextDark = Color(0xFF111111)

@Composable
fun MaskingToolPanel() {
    val editorViewModel: EditorViewModel = viewModel()
    val state by editorViewModel.uiState.collectAsState()
    val activeLayer = state.selectionLayers.firstOrNull { it.id == state.activeSelectionLayerId }
    val settings = state.selectionPaintSettings

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "자동으로 만든 마스크를 저장하고, 브러시로 더하거나 뺀 뒤 선택 영역에만 보정을 적용할 수 있습니다",
            color = MaskTextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaskCardBackground)
                .padding(12.dp)
        ) {
            Text("마스크 레이어", color = MaskTextPrimary, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                TextButton(onClick = { editorViewModel.addSubjectSelectionFromEdgeModel() }) {
                    Text("피사체 가져오기")
                }
                TextButton(onClick = { editorViewModel.createBrushSelection() }) {
                    Text("브러시 마스크")
                }
                TextButton(onClick = { editorViewModel.toggleSelectionOverlay() }) {
                    Text(if (state.showSelectionOverlay) "표시 끄기" else "표시 켜기")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
                TextButton(onClick = { editorViewModel.createBackgroundSelectionFromActive() }, enabled = activeLayer != null) {
                    Text("배경 만들기")
                }
                TextButton(onClick = { editorViewModel.duplicateActiveSelectionLayer() }, enabled = activeLayer != null) {
                    Text("복제")
                }
                TextButton(onClick = { editorViewModel.invertActiveSelectionLayer() }, enabled = activeLayer != null) {
                    Text("반전")
                }
                TextButton(onClick = { editorViewModel.deleteActiveSelectionLayer() }, enabled = activeLayer != null) {
                    Text("삭제")
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 4.dp)
            ) {
                state.selectionLayers.forEach { layer ->
                    TextButton(onClick = { editorViewModel.selectSelectionLayer(layer.id) }) {
                        Text(
                            text = layer.name,
                            color = if (layer.id == state.activeSelectionLayerId) MaskAccent else MaskTextSecondary,
                            fontWeight = if (layer.id == state.activeSelectionLayerId) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
            if (state.selectionLayers.isEmpty()) {
                Text("아직 마스크가 없습니다. Edge Masker를 로드한 뒤 피사체를 가져오거나, 브러시 마스크를 만들어 주세요", color = MaskTextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }

        MaskPaintCard(activeLayer = activeLayer, editorViewModel = editorViewModel)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .background(MaskCardBackground)
                .padding(12.dp)
        ) {
            Text("브러시", color = MaskTextPrimary, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                TextButton(onClick = { editorViewModel.updateSelectionPaintSettings { it.copy(mode = SelectionPaintMode.Add) } }) {
                    Text("더하기", color = if (settings.mode == SelectionPaintMode.Add) MaskAccent else MaskTextSecondary)
                }
                TextButton(onClick = { editorViewModel.updateSelectionPaintSettings { it.copy(mode = SelectionPaintMode.Remove) } }) {
                    Text("빼기", color = if (settings.mode == SelectionPaintMode.Remove) MaskAccent else MaskTextSecondary)
                }
                TextButton(onClick = { editorViewModel.clearActiveSelectionLayer() }, enabled = activeLayer != null) {
                    Text("비우기")
                }
            }
            MaskSliderRow("크기", settings.sizePx, 16f, 360f) { value ->
                editorViewModel.updateSelectionPaintSettings { it.copy(sizePx = value) }
            }
            MaskSliderRow("부드러움", settings.feather, 0f, 0.95f) { value ->
                editorViewModel.updateSelectionPaintSettings { it.copy(feather = value) }
            }
            MaskSliderRow("강도", settings.strength, 0.05f, 1f) { value ->
                editorViewModel.updateSelectionPaintSettings { it.copy(strength = value) }
            }
        }

        LocalMaskEditCard(activeLayer = activeLayer, editorViewModel = editorViewModel)
    }
}

@Composable
private fun MaskPaintCard(activeLayer: SelectionLayer?, editorViewModel: EditorViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .background(MaskCardBackground)
            .padding(12.dp)
    ) {
        Text("마스크 미리보기", color = MaskTextPrimary, fontWeight = FontWeight.SemiBold)
        Text(
            "아래 영역을 드래그하면 선택된 마스크에 브러시가 적용됩니다",
            color = MaskTextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
        )
        var boxSize by remember { mutableStateOf(IntSize.Zero) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(Color(0xFF111111))
                .onSizeChanged { boxSize = it }
                .pointerInput(activeLayer?.id, boxSize) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            if (!editorViewModel.beginBrushStroke()) return@detectDragGestures
                            paintAtOffset(editorViewModel, activeLayer?.id, offset.x, offset.y, boxSize)
                        },
                        onDrag = { change, _ ->
                            paintAtOffset(editorViewModel, activeLayer?.id, change.position.x, change.position.y, boxSize)
                            change.consume()
                        },
                        onDragEnd = { editorViewModel.finishBrushStroke() },
                        onDragCancel = { editorViewModel.cancelBrushStroke() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (activeLayer == null) {
                Text("선택된 마스크가 없습니다", color = MaskTextMuted, style = MaterialTheme.typography.bodySmall)
            } else {
                Image(
                    bitmap = activeLayer.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxWidth().height(180.dp)
                )
            }
        }
    }
}

private fun paintAtOffset(editorViewModel: EditorViewModel, activeLayerId: String?, x: Float, y: Float, boxSize: IntSize) {
    val layer = editorViewModel.uiState.value.selectionLayers.firstOrNull { it.id == activeLayerId } ?: return
    if (boxSize.width <= 0 || boxSize.height <= 0) return
    val maskX = (x / boxSize.width.toFloat()).coerceIn(0f, 1f) * layer.bitmap.width
    val maskY = (y / boxSize.height.toFloat()).coerceIn(0f, 1f) * layer.bitmap.height
    editorViewModel.paintActiveSelectionAt(maskX, maskY)
}

@Composable
private fun LocalMaskEditCard(activeLayer: SelectionLayer?, editorViewModel: EditorViewModel) {
    val params = activeLayer?.localParams ?: EditParams()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .background(MaskCardBackground)
            .padding(12.dp)
    ) {
        Text("선택 마스크 보정", color = MaskTextPrimary, fontWeight = FontWeight.SemiBold)
        Text(
            activeLayer?.let { "현재 대상: ${it.name}" } ?: "마스크를 선택하면 이 영역에만 보정값을 줄 수 있습니다",
            color = MaskTextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
        )
        LocalParamSlider("노출", params.exposure, -0.8f, 0.8f, activeLayer != null, onValueChangeStarted = { editorViewModel.startSelectionParamGesture() }, onValueChangeFinished = { editorViewModel.finishActiveSelectionParamsGesture() }) { value ->
            editorViewModel.updateActiveSelectionParamsLive { it.copy(exposure = value) }
        }
        LocalParamSlider("대비", params.contrast, -0.6f, 0.6f, activeLayer != null, onValueChangeStarted = { editorViewModel.startSelectionParamGesture() }, onValueChangeFinished = { editorViewModel.finishActiveSelectionParamsGesture() }) { value ->
            editorViewModel.updateActiveSelectionParamsLive { it.copy(contrast = value) }
        }
        LocalParamSlider("채도", params.saturation, -0.6f, 0.6f, activeLayer != null, onValueChangeStarted = { editorViewModel.startSelectionParamGesture() }, onValueChangeFinished = { editorViewModel.finishActiveSelectionParamsGesture() }) { value ->
            editorViewModel.updateActiveSelectionParamsLive { it.copy(saturation = value) }
        }
        LocalParamSlider("명료도", params.clarity, -0.6f, 0.6f, activeLayer != null, onValueChangeStarted = { editorViewModel.startSelectionParamGesture() }, onValueChangeFinished = { editorViewModel.finishActiveSelectionParamsGesture() }) { value ->
            editorViewModel.updateActiveSelectionParamsLive { it.copy(clarity = value) }
        }
        LocalParamSlider("디헤이즈", params.dehaze, -0.6f, 0.6f, activeLayer != null, onValueChangeStarted = { editorViewModel.startSelectionParamGesture() }, onValueChangeFinished = { editorViewModel.finishActiveSelectionParamsGesture() }) { value ->
            editorViewModel.updateActiveSelectionParamsLive { it.copy(dehaze = value) }
        }
        Button(
            onClick = { editorViewModel.applyActiveSelectionLocalEditNativeBaked() },
            enabled = activeLayer != null,
            colors = ButtonDefaults.buttonColors(containerColor = MaskAccent, contentColor = MaskButtonTextDark),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("선택 마스크 보정 적용")
        }
    }
}

@Composable
private fun MaskSliderRow(label: String, value: Float, min: Float, max: Float, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = MaskTextSecondary, style = MaterialTheme.typography.bodySmall)
            Text(value.formatSliderValue(), color = MaskTextMuted, style = MaterialTheme.typography.bodySmall)
        }
        Slider(value = value.coerceIn(min, max), onValueChange = onValueChange, valueRange = min..max)
    }
}

@Composable
private fun LocalParamSlider(label: String, value: Float, min: Float, max: Float, enabled: Boolean, onValueChangeStarted: () -> Boolean, onValueChangeFinished: () -> Unit, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = if (enabled) MaskTextSecondary else MaskTextMuted, style = MaterialTheme.typography.bodySmall)
            Text(value.formatSliderValue(), color = MaskTextMuted, style = MaterialTheme.typography.bodySmall)
        }
        var gestureStarted by remember { mutableStateOf(false) }
        Slider(value = value.coerceIn(min, max), onValueChange = { next ->
            if (!gestureStarted) gestureStarted = onValueChangeStarted()
            if (gestureStarted) onValueChange(next)
        }, onValueChangeFinished = {
            if (gestureStarted) onValueChangeFinished()
            gestureStarted = false
        }, valueRange = min..max, enabled = enabled)
    }
}

private fun Float.formatSliderValue(): String = if (kotlin.math.abs(this) >= 10f) {
    roundToInt().toString()
} else {
    String.format(java.util.Locale.US, "%.2f", this)
}
