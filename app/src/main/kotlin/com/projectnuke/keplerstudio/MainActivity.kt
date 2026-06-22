package com.projectnuke.keplerstudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.SavedExport
import com.projectnuke.keplerstudio.ui.EditorScreenV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

private enum class AppMode {
    Gallery,
    Editor
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: EditorViewModel = viewModel()
            val state by vm.uiState.collectAsState()
            val scope = rememberCoroutineScope()
            var appMode by remember { mutableStateOf(AppMode.Editor) }
            var showLeaveDialog by remember { mutableStateOf(false) }
            var showSavingDialog by remember { mutableStateOf(false) }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) {
                    vm.openImage(uri)
                    appMode = AppMode.Editor
                }
            }
            val hasEditableWork = state.previewBitmap != null ||
                state.originalPreviewBitmap != null ||
                state.canUndo ||
                state.canRedo ||
                state.selectionLayers.isNotEmpty()

            BackHandler(enabled = appMode == AppMode.Editor && hasEditableWork) {
                showLeaveDialog = true
            }
            BackHandler(enabled = appMode == AppMode.Editor && !hasEditableWork) {
                appMode = AppMode.Gallery
            }
            BackHandler(enabled = appMode == AppMode.Gallery) {
                appMode = AppMode.Editor
            }

            MaterialTheme(colorScheme = MainDarkColors) {
                when (appMode) {
                    AppMode.Editor -> EditorScreenV2(viewModel = vm)
                    AppMode.Gallery -> EditedGalleryScreen(
                        savedExports = state.savedExports,
                        draftSavedAtMillis = state.draftSavedAtMillis,
                        onOpenPhoto = { picker.launch("image/*") },
                        onContinueEditing = { appMode = AppMode.Editor },
                        onClearSavedExports = vm::clearSavedExports,
                        onRemoveSavedExport = vm::removeSavedExport
                    )
                }

                if (showLeaveDialog) {
                    AlertDialog(
                        onDismissRequest = { showLeaveDialog = false },
                        containerColor = Color(0xFF242424),
                        titleContentColor = Color(0xFFF2F2F2),
                        textContentColor = Color(0xFFC8C8C8),
                        title = { Text("편집을 종료할까요?") },
                        text = {
                            Text("현재 편집 내용을 자동복구용 임시 저장으로 한 번 더 저장한 뒤, 편집 기록 화면으로 이동합니다.")
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showLeaveDialog = false
                                showSavingDialog = true
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        vm.persistDraftSnapshot()
                                    }
                                    showSavingDialog = false
                                    appMode = AppMode.Gallery
                                }
                            }) {
                                Text("저장하고 나가기")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showLeaveDialog = false }) {
                                Text("계속 편집")
                            }
                        }
                    )
                }

                if (showSavingDialog) {
                    AlertDialog(
                        onDismissRequest = { },
                        containerColor = Color(0xFF242424),
                        titleContentColor = Color(0xFFF2F2F2),
                        textContentColor = Color(0xFFC8C8C8),
                        title = { Text("임시 저장 중입니다") },
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator()
                                Text("편집 내용을 안전하게 저장하고 있습니다.")
                            }
                        },
                        confirmButton = { }
                    )
                }
            }
        }
    }
}

@Composable
private fun EditedGalleryScreen(
    savedExports: List<SavedExport>,
    draftSavedAtMillis: Long?,
    onOpenPhoto: () -> Unit,
    onContinueEditing: () -> Unit,
    onClearSavedExports: () -> Unit,
    onRemoveSavedExport: (String) -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF101010)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF101010))
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Kepler Gallery", color = Color(0xFFF2F2F2), style = MaterialTheme.typography.titleLarge)
                    Text("내보낸 사진과 자동복구 상태를 확인합니다", color = Color(0xFFC8C8C8), style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = onOpenPhoto,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE6E6E6), contentColor = Color(0xFF111111))
                ) {
                    Text("사진 열기")
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF242424))
                    .padding(12.dp)
            ) {
                Text("자동복구", color = Color(0xFFF2F2F2), fontWeight = FontWeight.SemiBold)
                Text(
                    draftSavedAtMillis?.let { "마지막 임시 저장: ${formatMainSavedTime(it)}" } ?: "현재 임시 저장 기록이 없습니다.",
                    color = Color(0xFFC8C8C8),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
                TextButton(onClick = onContinueEditing, enabled = draftSavedAtMillis != null) {
                    Text("마지막 편집 계속하기")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("내보낸 사진", color = Color(0xFFF2F2F2), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClearSavedExports, enabled = savedExports.isNotEmpty()) {
                    Text("기록 비우기")
                }
            }

            if (savedExports.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1B1B1B))
                        .padding(16.dp)
                ) {
                    Text("아직 내보낸 사진이 없습니다.", color = Color(0xFFF2F2F2), fontWeight = FontWeight.SemiBold)
                    Text("편집 화면에서 저장하면 이곳에 기록이 표시됩니다.", color = Color(0xFFC8C8C8), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                savedExports.forEach { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF242424))
                            .padding(12.dp)
                    ) {
                        Text(item.displayName, color = Color(0xFFF2F2F2), fontWeight = FontWeight.SemiBold)
                        Text("${item.formatLabel} · ${item.resolutionLabel}", color = Color(0xFFC8C8C8), style = MaterialTheme.typography.bodySmall)
                        Text(formatMainSavedTime(item.timestampMillis), color = Color(0xFF8E8E8E), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onRemoveSavedExport(item.uriString) }) {
                            Text("기록 삭제")
                        }
                    }
                }
            }
        }
    }
}

private fun formatMainSavedTime(timestampMillis: Long): String =
    SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(timestampMillis))
