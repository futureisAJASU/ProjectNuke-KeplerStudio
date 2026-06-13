package com.projectnuke.keplerstudio.editor

import androidx.compose.ui.geometry.Offset

data class ViewportState(
    val scale: Float = 1f,
    val offset: Offset = Offset.Zero,
    val viewportWidth: Int = 0,
    val viewportHeight: Int = 0
)
