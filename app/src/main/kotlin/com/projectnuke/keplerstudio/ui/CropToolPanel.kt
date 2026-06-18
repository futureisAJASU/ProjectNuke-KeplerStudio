package com.projectnuke.keplerstudio.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.projectnuke.keplerstudio.editor.CropAspectRatio
import com.projectnuke.keplerstudio.editor.CropState
import com.projectnuke.keplerstudio.editor.EditorViewModel
import kotlin.math.max
import kotlin.math.min

private val CropCardBackground = Color(0xFF242424)
private val CropTextPrimary = Color(0xFFF2F2F2)
private val CropTextSecondary = Color(0xFFC8C8C8)
private val CropTextMuted = Color(0xFF8E8E8E)
private val CropAccent = Color(0xFFE6E6E6)
private val CropButtonTextDark = Color(0xFF111111)
private val CropOverlay = Color(0x99000000)
private val CropGuide = Color(0xDDFFFFFF)
private val CropGuideSoft = Color(0x88FFFFFF)

private enum class CropDragMode { Move, TopLeft, TopRight, BottomLeft, BottomRight, None }

@Composable
fun CropToolPanel() {
    val editorViewModel: EditorViewModel = viewModel()
    val state by editorViewModel.uiState.collectAsState()
    val bitmap = state.previewBitmap ?: state.originalPreviewBitmap
    val cropState = state.cropState

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "드래그 박스로 자를 영역을 지정하고, 비율·90도 회전·좌우 반전·수평 보정을 적용할 수 있습니다",
            color = CropTextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CropCardBackground)
                .padding(12.dp)
        ) {
            Text("자르기 영역", color = CropTextPrimary, style = MaterialTheme.typography.titleSmall)
            if (bitmap == null) {
                Box(modifier = Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    Text("사진을 선택해 주세요", color = CropTextMuted, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                CropDragPreview(
                    cropState = cropState,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    image = { Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(240.dp)) },
                    onCropRectChanged = editorViewModel::updateCropRect
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .background(CropCardBackground)
                .padding(12.dp)
        ) {
            Text("비율", color = CropTextPrimary, style = MaterialTheme.typography.titleSmall)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp)
            ) {
                CropAspectRatio.values().forEach { ratio ->
                    TextButton(onClick = { editorViewModel.setCropAspectRatio(ratio) }) {
                        Text(
                            ratio.label,
                            color = if (cropState.aspectRatio == ratio) CropAccent else CropTextSecondary
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .background(CropCardBackground)
                .padding(12.dp)
        ) {
            Text("회전 및 수평", color = CropTextPrimary, style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                TextButton(onClick = { editorViewModel.rotateCropLeft() }) { Text("왼쪽 90°") }
                TextButton(onClick = { editorViewModel.rotateCropRight() }) { Text("오른쪽 90°") }
                TextButton(onClick = { editorViewModel.toggleCropFlipHorizontal() }) {
                    Text(if (cropState.flipHorizontal) "좌우 반전 켜짐" else "좌우 반전")
                }
                TextButton(onClick = { editorViewModel.autoStraightenCrop() }) { Text("자동 수평") }
            }
            Text(
                "수동 수평: ${String.format(java.util.Locale.US, "%.1f", cropState.straightenDegrees)}°",
                color = CropTextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            Slider(
                value = cropState.straightenDegrees.coerceIn(-45f, 45f),
                onValueChange = editorViewModel::setStraightenDegrees,
                valueRange = -45f..45f
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Button(
                onClick = { editorViewModel.applyCropTransform() },
                enabled = bitmap != null,
                colors = ButtonDefaults.buttonColors(containerColor = CropAccent, contentColor = CropButtonTextDark)
            ) { Text("자르기 적용") }
            TextButton(onClick = { editorViewModel.resetCropState() }) { Text("초기화") }
        }
    }
}

@Composable
private fun CropDragPreview(
    cropState: CropState,
    imageWidth: Int,
    imageHeight: Int,
    image: @Composable () -> Unit,
    onCropRectChanged: (Float, Float, Float, Float) -> Unit
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var dragMode by remember { mutableStateOf(CropDragMode.None) }
    val imageRect = remember(boxSize, imageWidth, imageHeight) { fittedImageRect(boxSize, imageWidth, imageHeight) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(Color.Black)
            .onSizeChanged { boxSize = it }
            .pointerInput(cropState, imageRect) {
                detectDragGestures(
                    onDragStart = { offset -> dragMode = hitCropHandle(offset, cropState, imageRect) },
                    onDragEnd = { dragMode = CropDragMode.None },
                    onDragCancel = { dragMode = CropDragMode.None },
                    onDrag = { change, dragAmount ->
                        if (imageRect.width <= 0f || imageRect.height <= 0f) return@detectDragGestures
                        val dx = dragAmount.x / imageRect.width
                        val dy = dragAmount.y / imageRect.height
                        val next = updateCropByDrag(cropState, dragMode, dx, dy, imageWidth, imageHeight)
                        onCropRectChanged(next.cropLeft, next.cropTop, next.cropRight, next.cropBottom)
                        change.consume()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        image()
        Canvas(modifier = Modifier.fillMaxWidth().height(240.dp)) {
            val rect = cropRectPx(cropState, imageRect)
            drawRect(CropOverlay, topLeft = imageRect.topLeft, size = Size(imageRect.width, rect.top - imageRect.top))
            drawRect(CropOverlay, topLeft = Offset(imageRect.left, rect.bottom), size = Size(imageRect.width, imageRect.bottom - rect.bottom))
            drawRect(CropOverlay, topLeft = Offset(imageRect.left, rect.top), size = Size(rect.left - imageRect.left, rect.height))
            drawRect(CropOverlay, topLeft = Offset(rect.right, rect.top), size = Size(imageRect.right - rect.right, rect.height))
            drawRect(CropGuide, topLeft = rect.topLeft, size = rect.size, style = Stroke(width = 3f))
            val x1 = rect.left + rect.width / 3f
            val x2 = rect.left + rect.width * 2f / 3f
            val y1 = rect.top + rect.height / 3f
            val y2 = rect.top + rect.height * 2f / 3f
            drawLine(CropGuideSoft, Offset(x1, rect.top), Offset(x1, rect.bottom), strokeWidth = 1.5f)
            drawLine(CropGuideSoft, Offset(x2, rect.top), Offset(x2, rect.bottom), strokeWidth = 1.5f)
            drawLine(CropGuideSoft, Offset(rect.left, y1), Offset(rect.right, y1), strokeWidth = 1.5f)
            drawLine(CropGuideSoft, Offset(rect.left, y2), Offset(rect.right, y2), strokeWidth = 1.5f)
            drawCorner(rect.topLeft)
            drawCorner(Offset(rect.right, rect.top))
            drawCorner(Offset(rect.left, rect.bottom))
            drawCorner(Offset(rect.right, rect.bottom))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCorner(point: Offset) {
    drawCircle(CropGuide, radius = 10f, center = point)
    drawCircle(Color.Black, radius = 5f, center = point)
}

private fun updateCropByDrag(state: CropState, mode: CropDragMode, dx: Float, dy: Float, imageWidth: Int, imageHeight: Int): CropState {
    val minSize = 0.08f
    return when (mode) {
        CropDragMode.Move -> {
            val width = state.cropWidth
            val height = state.cropHeight
            var left = state.cropLeft + dx
            var top = state.cropTop + dy
            left = left.coerceIn(0f, 1f - width)
            top = top.coerceIn(0f, 1f - height)
            state.copy(cropLeft = left, cropTop = top, cropRight = left + width, cropBottom = top + height)
        }
        CropDragMode.TopLeft -> resizeCrop(state, left = state.cropLeft + dx, top = state.cropTop + dy, right = state.cropRight, bottom = state.cropBottom, imageWidth, imageHeight, anchor = CropDragMode.BottomRight)
        CropDragMode.TopRight -> resizeCrop(state, left = state.cropLeft, top = state.cropTop + dy, right = state.cropRight + dx, bottom = state.cropBottom, imageWidth, imageHeight, anchor = CropDragMode.BottomLeft)
        CropDragMode.BottomLeft -> resizeCrop(state, left = state.cropLeft + dx, top = state.cropTop, right = state.cropRight, bottom = state.cropBottom + dy, imageWidth, imageHeight, anchor = CropDragMode.TopRight)
        CropDragMode.BottomRight -> resizeCrop(state, left = state.cropLeft, top = state.cropTop, right = state.cropRight + dx, bottom = state.cropBottom + dy, imageWidth, imageHeight, anchor = CropDragMode.TopLeft)
        CropDragMode.None -> state
    }.normalized(minSize)
}

private fun resizeCrop(state: CropState, left: Float, top: Float, right: Float, bottom: Float, imageWidth: Int, imageHeight: Int, anchor: CropDragMode): CropState {
    val imageRatio = if (imageHeight > 0) imageWidth.toFloat() / imageHeight.toFloat() else 1f
    val targetRatio = when (state.aspectRatio) {
        CropAspectRatio.Free -> null
        CropAspectRatio.Original -> imageRatio
        else -> state.aspectRatio.ratio
    }
    var l = min(left, right).coerceIn(0f, 1f)
    var r = max(left, right).coerceIn(0f, 1f)
    var t = min(top, bottom).coerceIn(0f, 1f)
    var b = max(top, bottom).coerceIn(0f, 1f)
    if (targetRatio != null && targetRatio > 0f) {
        val normalizedRatio = targetRatio / imageRatio
        val width = (r - l).coerceAtLeast(0.08f)
        val height = (width / normalizedRatio).coerceAtLeast(0.08f)
        when (anchor) {
            CropDragMode.TopLeft -> { r = l + width; b = t + height }
            CropDragMode.TopRight -> { l = r - width; b = t + height }
            CropDragMode.BottomLeft -> { r = l + width; t = b - height }
            CropDragMode.BottomRight -> { l = r - width; t = b - height }
            else -> Unit
        }
    }
    return state.copy(cropLeft = l, cropTop = t, cropRight = r, cropBottom = b)
}

private fun hitCropHandle(offset: Offset, state: CropState, imageRect: Rect): CropDragMode {
    val rect = cropRectPx(state, imageRect)
    val handle = 34f
    fun near(point: Offset): Boolean = (offset - point).getDistance() < handle
    return when {
        near(rect.topLeft) -> CropDragMode.TopLeft
        near(Offset(rect.right, rect.top)) -> CropDragMode.TopRight
        near(Offset(rect.left, rect.bottom)) -> CropDragMode.BottomLeft
        near(Offset(rect.right, rect.bottom)) -> CropDragMode.BottomRight
        rect.contains(offset) -> CropDragMode.Move
        else -> CropDragMode.None
    }
}

private fun cropRectPx(state: CropState, imageRect: Rect): Rect = Rect(
    left = imageRect.left + state.cropLeft * imageRect.width,
    top = imageRect.top + state.cropTop * imageRect.height,
    right = imageRect.left + state.cropRight * imageRect.width,
    bottom = imageRect.top + state.cropBottom * imageRect.height
)

private fun fittedImageRect(boxSize: IntSize, imageWidth: Int, imageHeight: Int): Rect {
    if (boxSize.width <= 0 || boxSize.height <= 0 || imageWidth <= 0 || imageHeight <= 0) return Rect.Zero
    val boxRatio = boxSize.width.toFloat() / boxSize.height.toFloat()
    val imageRatio = imageWidth.toFloat() / imageHeight.toFloat()
    val width: Float
    val height: Float
    if (imageRatio > boxRatio) {
        width = boxSize.width.toFloat()
        height = width / imageRatio
    } else {
        height = boxSize.height.toFloat()
        width = height * imageRatio
    }
    val left = (boxSize.width - width) / 2f
    val top = (boxSize.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}
