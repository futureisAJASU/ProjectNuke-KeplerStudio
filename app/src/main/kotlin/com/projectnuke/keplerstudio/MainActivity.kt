package com.projectnuke.keplerstudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.ui.EditorScreenV2

private val MainDarkColors = darkColorScheme(
    primary = Color(0xFFE6E6E6),
    onPrimary = Color(0xFF111111),
    background = Color(0xFF101010),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF242424),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF343434),
    onSurfaceVariant = Color(0xFFC8C8C8)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: EditorViewModel = viewModel()
            val state by vm.uiState.collectAsState()
            var showLeaveDialog by remember { mutableStateOf(false) }
            val hasEditableWork = state.previewBitmap != null ||
                state.originalPreviewBitmap != null ||
                state.canUndo ||
                state.canRedo ||
                state.selectionLayers.isNotEmpty()

            BackHandler(enabled = hasEditableWork) {
                showLeaveDialog = true
            }

            MaterialTheme(colorScheme = MainDarkColors) {
                EditorScreenV2(viewModel = vm)

                if (showLeaveDialog) {
                    AlertDialog(
                        onDismissRequest = { showLeaveDialog = false },
                        containerColor = Color(0xFF242424),
                        titleContentColor = Color(0xFFF2F2F2),
                        textContentColor = Color(0xFFC8C8C8),
                        title = { Text("편집을 종료할까요?") },
                        text = {
                            Text("저장하지 않은 편집 내용은 갤러리에 내보내지지 않습니다. 자동복구용 임시 저장이 있어도, 나가기 전에 갤러리 저장을 권장합니다.")
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showLeaveDialog = false
                                finish()
                            }) {
                                Text("나가기")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showLeaveDialog = false }) {
                                Text("계속 편집")
                            }
                        }
                    )
                }
            }
        }
    }
}
