package com.projectnuke.keplerstudio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.projectnuke.keplerstudio.editor.CropAspectRatio
import com.projectnuke.keplerstudio.editor.CropState
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.normalized
import kotlin.math.abs
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

private const val CropMinSize = 0.08f
private const val CropCornerRadiusPx = 10f
private const val CropCornerHitPx = 48f
private const val CropEdgeHitPx = 36f

private enum class CropDragMode {
    Move,
    Left,
    Right,
    Top,
    Bottom,
    TopLeft,
    TopRight,
    BottomLeft,
    BottomRight,
    None
}

@Composable
fun CropToolPanel() {
    val editorViewModel: EditorViewModel = viewModel()
    val state by editorViewModel.uiState.collectAsState()
    val bitmap = state.previewBitmap ?: state.originalPreviewBitmap
    val cropState = state.cropState
    var showResetDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "\uC0AC\uC9C4 \uC704\uC758 \uC790\uB974\uAE30 \uC601\uC5ED\uC744 \uB4DC\uB798\uADF8\uD558\uC5EC \uC870\uC815\uD560 \uC218 \uC788\uC2B5\uB2C8\uB2E4.",
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
            Text("\uBE44\uC728", color = CropTextPrimary, style = MaterialTheme.typography.titleSmall)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 4.dp)
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
            Text("\uD68C\uC804 \uBC0F \uC218\uD3C9", color = CropTextPrimary, style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                TextButton(onClick = { editorViewModel.rotateCropLeft() }) { Text("\uC67C\uCABD 90\u00B0") }
                TextButton(onClick = { editorViewModel.rotateCropRight() }) { Text("\uC624\uB978\uCABD 90\u00B0") }
                TextButton(onClick = { editorViewModel.toggleCropFlipHorizontal() }) {
                    Text(
                        if (cropState.flipHorizontal) {
                            "\uC88C\uC6B0 \uBC18\uC804 \uD574\uC81C"
                        } else {
                            "\uC88C\uC6B0 \uBC18\uC804"
                        }
                    )
                }
                TextButton(onClick = { editorViewModel.autoStraightenCrop() }) { Text("\uC790\uB3D9 \uC218\uD3C9") }
            }
            Text(
                "\uC218\uB3D9 \uC218\uD3C9: ${String.format(java.util.Locale.US, "%.1f", cropState.straightenDegrees)}\u00B0",
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

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
        ) {
            Button(
                onClick = { editorViewModel.applyCropTransform() },
                enabled = bitmap != null && !state.isBusy,
                colors = ButtonDefaults.buttonColors(containerColor = CropAccent, contentColor = CropButtonTextDark)
            ) { Text("\uC790\uB974\uAE30 \uC801\uC6A9") }
            TextButton(onClick = { showResetDialog = true }) { Text("\uCD08\uAE30\uD654") }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("\uC790\uB974\uAE30 \uC601\uC5ED\uC744 \uCD08\uAE30\uD654\uD560\uAE4C\uC694?") },
            text = { Text("\uD604\uC7AC \uC790\uB974\uAE30 \uC601\uC5ED\uACFC \uD68C\uC804/\uC218\uD3C9 \uC870\uC815\uAC12\uC774 \uCD08\uAE30\uD654\uB429\uB2C8\uB2E4.") },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDialog = false
                        editorViewModel.resetCropState()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CropAccent, contentColor = CropButtonTextDark)
                ) { Text("\uCD08\uAE30\uD654") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("\uCDE8\uC18C") }
            }
        )
    }
}

@Composable
fun CropOverlayPreview(
    cropState: CropState,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier,
    onCropRectChanged: (Float, Float, Float, Float) -> Unit
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var dragMode by remember { mutableStateOf(CropDragMode.None) }
    val latestCropState = rememberUpdatedState(cropState)
    val imageRect = remember(boxSize, imageWidth, imageHeight) { fittedImageRect(boxSize, imageWidth, imageHeight) }

    Box(
        modifier = modifier
            .onSizeChanged { boxSize = it }
            .pointerInput(imageRect, imageWidth, imageHeight) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragMode = hitCropHandle(offset, latestCropState.value, imageRect)
                    },
                    onDragEnd = { dragMode = CropDragMode.None },
                    onDragCancel = { dragMode = CropDragMode.None },
                    onDrag = { change, dragAmount ->
                        if (dragMode == CropDragMode.None) return@detectDragGestures
                        if (imageRect.width <= 0f || imageRect.height <= 0f) return@detectDragGestures
                        val dx = dragAmount.x / imageRect.width
                        val dy = dragAmount.y / imageRect.height
                        val next = updateCropByDrag(latestCropState.value, dragMode, dx, dy, imageWidth, imageHeight)
                        onCropRectChanged(next.cropLeft, next.cropTop, next.cropRight, next.cropBottom)
                        change.consume()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (imageRect == Rect.Zero) return@Canvas
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

            drawEdgeHandle(Offset(rect.left, rect.center.y), vertical = true)
            drawEdgeHandle(Offset(rect.right, rect.center.y), vertical = true)
            drawEdgeHandle(Offset(rect.center.x, rect.top), vertical = false)
            drawEdgeHandle(Offset(rect.center.x, rect.bottom), vertical = false)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCorner(point: Offset) {
    drawCircle(CropGuide, radius = CropCornerRadiusPx, center = point)
    drawCircle(Color.Black, radius = 5f, center = point)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEdgeHandle(point: Offset, vertical: Boolean) {
    val size = if (vertical) Size(8f, 28f) else Size(28f, 8f)
    drawRoundRect(
        color = CropGuide,
        topLeft = Offset(point.x - size.width / 2f, point.y - size.height / 2f),
        size = size,
        cornerRadius = CornerRadius(4f, 4f)
    )
}

private fun updateCropByDrag(
    state: CropState,
    mode: CropDragMode,
    dx: Float,
    dy: Float,
    imageWidth: Int,
    imageHeight: Int
): CropState {
    return when (mode) {
        CropDragMode.Move -> {
            val width = state.cropWidth
            val height = state.cropHeight
            val left = (state.cropLeft + dx).coerceIn(0f, 1f - width)
            val top = (state.cropTop + dy).coerceIn(0f, 1f - height)
            state.copy(cropLeft = left, cropTop = top, cropRight = left + width, cropBottom = top + height)
        }
        CropDragMode.Left -> resizeCropEdge(state, left = state.cropLeft + dx, top = state.cropTop, right = state.cropRight, bottom = state.cropBottom, imageWidth = imageWidth, imageHeight = imageHeight, edge = CropDragMode.Left)
        CropDragMode.Right -> resizeCropEdge(state, left = state.cropLeft, top = state.cropTop, right = state.cropRight + dx, bottom = state.cropBottom, imageWidth = imageWidth, imageHeight = imageHeight, edge = CropDragMode.Right)
        CropDragMode.Top -> resizeCropEdge(state, left = state.cropLeft, top = state.cropTop + dy, right = state.cropRight, bottom = state.cropBottom, imageWidth = imageWidth, imageHeight = imageHeight, edge = CropDragMode.Top)
        CropDragMode.Bottom -> resizeCropEdge(state, left = state.cropLeft, top = state.cropTop, right = state.cropRight, bottom = state.cropBottom + dy, imageWidth = imageWidth, imageHeight = imageHeight, edge = CropDragMode.Bottom)
        CropDragMode.TopLeft -> resizeCropCorner(state, left = state.cropLeft + dx, top = state.cropTop + dy, right = state.cropRight, bottom = state.cropBottom, imageWidth = imageWidth, imageHeight = imageHeight, anchor = CropDragMode.BottomRight)
        CropDragMode.TopRight -> resizeCropCorner(state, left = state.cropLeft, top = state.cropTop + dy, right = state.cropRight + dx, bottom = state.cropBottom, imageWidth = imageWidth, imageHeight = imageHeight, anchor = CropDragMode.BottomLeft)
        CropDragMode.BottomLeft -> resizeCropCorner(state, left = state.cropLeft + dx, top = state.cropTop, right = state.cropRight, bottom = state.cropBottom + dy, imageWidth = imageWidth, imageHeight = imageHeight, anchor = CropDragMode.TopRight)
        CropDragMode.BottomRight -> resizeCropCorner(state, left = state.cropLeft, top = state.cropTop, right = state.cropRight + dx, bottom = state.cropBottom + dy, imageWidth = imageWidth, imageHeight = imageHeight, anchor = CropDragMode.TopLeft)
        CropDragMode.None -> state
    }.normalized(CropMinSize)
}

private fun resizeCropCorner(
    state: CropState,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    imageWidth: Int,
    imageHeight: Int,
    anchor: CropDragMode
): CropState {
    val imageRatio = if (imageHeight > 0) imageWidth.toFloat() / imageHeight.toFloat() else 1f
    val targetRatio = when (state.aspectRatio) {
        CropAspectRatio.Free -> null
        CropAspectRatio.Original -> imageRatio
        else -> state.aspectRatio.ratio
    }
    val l = min(left, right)
    val r = max(left, right)
    val t = min(top, bottom)
    val b = max(top, bottom)
    if (targetRatio == null || targetRatio <= 0f) {
        return state.copy(
            cropLeft = l.coerceIn(0f, 1f),
            cropTop = t.coerceIn(0f, 1f),
            cropRight = r.coerceIn(0f, 1f),
            cropBottom = b.coerceIn(0f, 1f)
        )
    }

    val normalizedRatio = targetRatio / imageRatio
    val anchorX = when (anchor) {
        CropDragMode.TopLeft, CropDragMode.BottomLeft -> l
        CropDragMode.TopRight, CropDragMode.BottomRight -> r
        else -> state.cropRight
    }
    val anchorY = when (anchor) {
        CropDragMode.TopLeft, CropDragMode.TopRight -> t
        CropDragMode.BottomLeft, CropDragMode.BottomRight -> b
        else -> state.cropBottom
    }
    val movingX = when (anchor) {
        CropDragMode.TopLeft, CropDragMode.BottomLeft -> r
        CropDragMode.TopRight, CropDragMode.BottomRight -> l
        else -> left
    }
    val movingY = when (anchor) {
        CropDragMode.TopLeft, CropDragMode.TopRight -> b
        CropDragMode.BottomLeft, CropDragMode.BottomRight -> t
        else -> top
    }

    val proposedWidth = abs(movingX - anchorX).coerceAtLeast(CropMinSize)
    val proposedHeight = abs(movingY - anchorY).coerceAtLeast(CropMinSize)
    val width = max(proposedWidth, proposedHeight * normalizedRatio).coerceAtLeast(CropMinSize)
    val height = (width / normalizedRatio).coerceAtLeast(CropMinSize)
    return buildAnchoredCrop(state, anchorX, anchorY, width, height, anchor)
}

private fun resizeCropEdge(
    state: CropState,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    imageWidth: Int,
    imageHeight: Int,
    edge: CropDragMode
): CropState {
    val imageRatio = if (imageHeight > 0) imageWidth.toFloat() / imageHeight.toFloat() else 1f
    val targetRatio = when (state.aspectRatio) {
        CropAspectRatio.Free -> null
        CropAspectRatio.Original -> imageRatio
        else -> state.aspectRatio.ratio
    }
    if (targetRatio == null || targetRatio <= 0f) {
        return state.copy(
            cropLeft = min(left, right).coerceIn(0f, 1f),
            cropTop = min(top, bottom).coerceIn(0f, 1f),
            cropRight = max(left, right).coerceIn(0f, 1f),
            cropBottom = max(top, bottom).coerceIn(0f, 1f)
        )
    }

    val normalizedRatio = targetRatio / imageRatio
    return when (edge) {
        CropDragMode.Left, CropDragMode.Right -> {
            val fixed = if (edge == CropDragMode.Left) state.cropRight else state.cropLeft
            val moving = if (edge == CropDragMode.Left) left else right
            val width = abs(fixed - moving).coerceAtLeast(CropMinSize)
            val height = (width / normalizedRatio).coerceAtLeast(CropMinSize)
            val centerY = (state.cropTop + state.cropBottom) / 2f
            val nextLeft = if (edge == CropDragMode.Left) fixed - width else fixed
            buildRectCrop(state, nextLeft, centerY - height / 2f, width, height)
        }
        CropDragMode.Top, CropDragMode.Bottom -> {
            val fixed = if (edge == CropDragMode.Top) state.cropBottom else state.cropTop
            val moving = if (edge == CropDragMode.Top) top else bottom
            val height = abs(fixed - moving).coerceAtLeast(CropMinSize)
            val width = (height * normalizedRatio).coerceAtLeast(CropMinSize)
            val centerX = (state.cropLeft + state.cropRight) / 2f
            val nextTop = if (edge == CropDragMode.Top) fixed - height else fixed
            buildRectCrop(state, centerX - width / 2f, nextTop, width, height)
        }
        else -> state
    }
}

private fun buildAnchoredCrop(
    state: CropState,
    anchorX: Float,
    anchorY: Float,
    width: Float,
    height: Float,
    anchor: CropDragMode
): CropState {
    return when (anchor) {
        CropDragMode.TopLeft -> buildRectCrop(state, anchorX, anchorY, width, height)
        CropDragMode.TopRight -> buildRectCrop(state, anchorX - width, anchorY, width, height)
        CropDragMode.BottomLeft -> buildRectCrop(state, anchorX, anchorY - height, width, height)
        CropDragMode.BottomRight -> buildRectCrop(state, anchorX - width, anchorY - height, width, height)
        else -> state
    }
}

private fun buildRectCrop(state: CropState, left: Float, top: Float, width: Float, height: Float): CropState {
    val actualWidth = width.coerceIn(CropMinSize, 1f)
    val actualHeight = height.coerceIn(CropMinSize, 1f)
    val clampedLeft = left.coerceIn(0f, 1f - actualWidth)
    val clampedTop = top.coerceIn(0f, 1f - actualHeight)
    return state.copy(
        cropLeft = clampedLeft,
        cropTop = clampedTop,
        cropRight = clampedLeft + actualWidth,
        cropBottom = clampedTop + actualHeight
    )
}

private fun hitCropHandle(offset: Offset, state: CropState, imageRect: Rect): CropDragMode {
    val rect = cropRectPx(state, imageRect)
    fun near(point: Offset): Boolean = (offset - point).getDistance() < CropCornerHitPx
    fun withinVerticalEdge(x: Float, top: Float, bottom: Float): Boolean =
        abs(offset.x - x) <= CropEdgeHitPx && offset.y in (top - CropEdgeHitPx)..(bottom + CropEdgeHitPx)
    fun withinHorizontalEdge(y: Float, left: Float, right: Float): Boolean =
        abs(offset.y - y) <= CropEdgeHitPx && offset.x in (left - CropEdgeHitPx)..(right + CropEdgeHitPx)

    return when {
        near(rect.topLeft) -> CropDragMode.TopLeft
        near(Offset(rect.right, rect.top)) -> CropDragMode.TopRight
        near(Offset(rect.left, rect.bottom)) -> CropDragMode.BottomLeft
        near(Offset(rect.right, rect.bottom)) -> CropDragMode.BottomRight
        withinVerticalEdge(rect.left, rect.top, rect.bottom) -> CropDragMode.Left
        withinVerticalEdge(rect.right, rect.top, rect.bottom) -> CropDragMode.Right
        withinHorizontalEdge(rect.top, rect.left, rect.right) -> CropDragMode.Top
        withinHorizontalEdge(rect.bottom, rect.left, rect.right) -> CropDragMode.Bottom
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
