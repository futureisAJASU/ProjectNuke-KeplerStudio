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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import kotlin.math.max

@Composable
fun EditorScreen(viewModel: EditorViewModel) {
    val state by viewModel.uiState.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.openImage(uri)
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopBar(
                    nativeVersion = state.nativeVersion,
                    onOpen = { picker.launch("image/*") },
                    onReset = { viewModel.resetAdjustments() }
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    val bitmap = state.previewBitmap
                    if (bitmap == null) {
                        Text("사진을 열어줘", color = Color.White)
                    } else {
                        ZoomablePreview(
                            bitmap = bitmap,
                            onViewportChanged = viewModel::updateViewport
                        )
                    }

                    if (state.isBusy) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))
                    }

                    state.message?.let {
                        Text(
                            text = it,
                            color = Color.White,
                            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
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
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Kepler Studio v0.1", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(12.dp))
        Text(nativeVersion, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.weight(1f))
        Button(onClick = onReset) { Text("초기화") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onOpen) { Text("사진 열기") }
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
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
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
        modifier = Modifier.fillMaxWidth().height(38.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, modifier = Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = min..max,
            modifier = Modifier.weight(1f)
        )
        Text(String.format("%.2f", value), modifier = Modifier.width(48.dp), style = MaterialTheme.typography.bodySmall)
    }
}
