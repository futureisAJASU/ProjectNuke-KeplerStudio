package com.projectnuke.keplerstudio.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.projectnuke.keplerstudio.editor.SelectionLayer

@Composable
fun SelectionMaskOverlay(
    layer: SelectionLayer?,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    // Preview overlay rendering will be wired after EditorScreenV2 receives the active layer.
    // Keep this composable lightweight so the project builds while the UI integration is staged.
}
