package com.projectnuke.keplerstudio.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.projectnuke.keplerstudio.editor.SelectionLayer

private val SelectionOverlayColor = Color(0xFFFF3D6E)
private val SelectionOverlayBadge = Color(0xAA000000)

@Composable
fun SelectionMaskOverlay(
    layer: SelectionLayer?,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    if (!visible || layer == null) return

    Box(modifier = modifier) {
        Image(
            bitmap = layer.bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(SelectionOverlayColor.copy(alpha = 0.48f), BlendMode.SrcIn),
            modifier = Modifier.matchParentSize()
        )
        Text(
            text = layer.name,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .background(SelectionOverlayBadge)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
