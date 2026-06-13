package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.unit.dp
import com.projectnuke.keplerstudio.editor.EditParams
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.ViewportState

private val AppBackground = Color(0xFF101014)
private val TopBarBackground = Color(0xFF17171D)
private val PanelBackground = Color(0xFF1A1A22)
private val PreviewBackground = Color(0xFF000000)
private val PrimaryPurple = Color(0xFF8E6CEF)
private val TextPrimary = Color(0xFFF7F2FF)
private val TextSecondary = Color(0xFFC9C0D8)

private val KeplerDarkColors = darkColorScheme(
    primary = PrimaryPurple,
    onPrimary = Color.White,
    background = AppBackground,
    onBackground = TextPrimary,
    surface = PanelBackground,
    onSurface = TextPrimary
)

@Composable
fun EditorScreen(viewModel: EditorViewModel) {
    val state by viewModel.uiState.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.openImage(uri)
    }

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
                    params = state.params,
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
    onViewportChanged: (ViewportState) -> Unit
) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "preview",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged {
                size = it
                onViewportChanged(ViewportState(scale, offset, it.width, it.height))
            }
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset += pan
                    if (scale <= 1.01f) offset = Offset.Zero
                    onViewportChanged(ViewportState(scale, offset, size.width, size.height))
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
    )
}

@Composable
private fun AdjustmentPanel(
    params: EditParams,
    onChange: ((EditParams) -> EditParams) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBackground)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        AdjustmentSlider("노출", params.exposure, -1f, 1f) { v -> onChange { it.copy(exposure = v) } }
        AdjustmentSlider("대비", params.contrast, -1f, 1f) { v -> onChange { it.copy(contrast = v) } }
        AdjustmentSlider("섀도우", params.shadows, -1f, 1f) { v -> onChange { it.copy(shadows = v) } }
        AdjustmentSlider("하이라이트", params.highlights, -1f, 1f) { v -> onChange { it.copy(highlights = v) } }
        AdjustmentSlider("샤픈", params.sharpness, 0f, 1f) { v -> onChange { it.copy(sharpness = v) } }
    }
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
            modifier = Modifier.width(76.dp),
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
