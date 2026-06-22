package com.projectnuke.keplerstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
                Column(modifier = Modifier.weight(1f)) {
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
                savedExports.chunked(3).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { item ->
                            SavedExportThumbnailTile(
                                item = item,
                                onRemoveSavedExport = onRemoveSavedExport,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(3 - rowItems.size) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedExportThumbnailTile(
    item: SavedExport,
    onRemoveSavedExport: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF242424))
            .padding(8.dp)
    ) {
        SavedExportThumbnail(
            uriString = item.uriString,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        )
        Text(
            text = item.displayName,
            color = Color(0xFFF2F2F2),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            text = item.formatLabel,
            color = Color(0xFFC8C8C8),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
        TextButton(onClick = { onRemoveSavedExport(item.uriString) }) {
            Text("삭제")
        }
    }
}

@Composable
private fun SavedExportThumbnail(uriString: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue = null, key1 = uriString) {
        value = withContext(Dispatchers.IO) {
            decodeSavedExportThumbnail(context, uriString)
        }
    }

    Box(
        modifier = modifier.background(Color(0xFF111111)),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = thumbnail
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text("미리보기 없음", color = Color(0xFF8E8E8E), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun decodeSavedExportThumbnail(context: Context, uriString: String, maxSide: Int = 512): Bitmap? {
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
    }
    val sampleSize = calculateThumbnailSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }.getOrNull()
}

private fun calculateThumbnailSampleSize(width: Int, height: Int, maxSide: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    var scaledWidth = width
    var scaledHeight = height
    while (scaledWidth / 2 >= maxSide || scaledHeight / 2 >= maxSide) {
        sample *= 2
        scaledWidth /= 2
        scaledHeight /= 2
    }
    return sample.coerceAtLeast(1)
}

private fun formatMainSavedTime(timestampMillis: Long): String =
    SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(timestampMillis))
