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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.RecoveryDebugInfo
import com.projectnuke.keplerstudio.editor.SavedExport
import com.projectnuke.keplerstudio.ui.EditorScreenV2
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

private enum class AppMode { Home, Gallery, Editor }

private enum class GalleryDisplayMode(val label: String) {
    Draft("\uC784\uC2DC \uC800\uC7A5"),
    Exports("\uB0B4\uBCF4\uB0B8 \uC0AC\uC9C4")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: EditorViewModel = viewModel()
            val state by vm.uiState.collectAsState()
            val scope = rememberCoroutineScope()
            var appMode by remember { mutableStateOf(AppMode.Home) }
            var galleryMode by rememberSaveable { mutableStateOf(GalleryDisplayMode.Draft) }
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

            BackHandler(enabled = appMode == AppMode.Editor && hasEditableWork) { showLeaveDialog = true }
            BackHandler(enabled = appMode == AppMode.Editor && !hasEditableWork) { appMode = AppMode.Home }
            BackHandler(enabled = appMode == AppMode.Gallery) { appMode = AppMode.Home }
            BackHandler(enabled = appMode == AppMode.Home) { finish() }

            MaterialTheme(colorScheme = MainDarkColors) {
                when (appMode) {
                    AppMode.Editor -> EditorScreenV2(viewModel = vm)
                    AppMode.Home -> HomeScreen(
                        draftSavedAtMillis = state.draftSavedAtMillis,
                        draftSourcePath = state.draftSourcePath,
                        recoveryDebugInfo = state.recoveryDebugInfo,
                        activeSourcePath = state.sourcePath,
                        onOpenPhoto = { picker.launch("image/*") },
                        onOpenGallery = { appMode = AppMode.Gallery },
                        onContinueEditing = { appMode = AppMode.Editor },
                        onClearDraft = vm::clearDraft
                    )
                    AppMode.Gallery -> GalleryScreen(
                        mode = galleryMode,
                        onModeChange = { galleryMode = it },
                        savedExports = state.savedExports,
                        draftSavedAtMillis = state.draftSavedAtMillis,
                        draftSourcePath = state.draftSourcePath,
                        recoveryDebugInfo = state.recoveryDebugInfo,
                        onBack = { appMode = AppMode.Home },
                        onContinueEditing = { appMode = AppMode.Editor },
                        onClearDraft = vm::clearDraft,
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
                        title = { Text("\uD3B8\uC9D1\uC744 \uC885\uB8CC\uD560\uAE4C\uC694?") },
                        text = { Text("\uD604\uC7AC \uD3B8\uC9D1 \uB0B4\uC6A9\uC744 \uC790\uB3D9\uBCF5\uAD6C\uC6A9 \uC784\uC2DC \uC800\uC7A5\uC73C\uB85C \uD55C \uBC88 \uB354 \uC800\uC7A5\uD55C \uB4A4, \uD648 \uD654\uBA74\uC73C\uB85C \uC774\uB3D9\uD569\uB2C8\uB2E4.") },
                        confirmButton = {
                            TextButton(onClick = {
                                showLeaveDialog = false
                                showSavingDialog = true
                                scope.launch {
                                    withContext(Dispatchers.IO) { vm.persistDraftSnapshot() }
                                    showSavingDialog = false
                                    appMode = AppMode.Home
                                }
                            }) { Text("\uC800\uC7A5\uD558\uACE0 \uB098\uAC00\uAE30") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showLeaveDialog = false }) { Text("\uACC4\uC18D \uD3B8\uC9D1") }
                        }
                    )
                }

                if (showSavingDialog) {
                    AlertDialog(
                        onDismissRequest = { },
                        containerColor = Color(0xFF242424),
                        titleContentColor = Color(0xFFF2F2F2),
                        textContentColor = Color(0xFFC8C8C8),
                        title = { Text("\uC784\uC2DC \uC800\uC7A5 \uC911\uC785\uB2C8\uB2E4") },
                        text = {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator()
                                Text("\uD3B8\uC9D1 \uB0B4\uC6A9\uC744 \uC548\uC804\uD558\uAC8C \uC800\uC7A5\uD558\uACE0 \uC788\uC2B5\uB2C8\uB2E4.")
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
private fun HomeScreen(
    draftSavedAtMillis: Long?,
    draftSourcePath: String?,
    recoveryDebugInfo: RecoveryDebugInfo?,
    activeSourcePath: String?,
    onOpenPhoto: () -> Unit,
    onOpenGallery: () -> Unit,
    onContinueEditing: () -> Unit,
    onClearDraft: () -> Unit
) {
    val draftSourceExists = remember(draftSourcePath) { draftSourcePath?.let { File(it).isFile } == true }
    val canContinueDraft = draftSavedAtMillis != null && draftSourceExists
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF101010)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF101010))
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Kepler Studio", color = Color(0xFFF2F2F2), style = MaterialTheme.typography.titleLarge)
                    Text("\uD3B8\uC9D1\uC744 \uC2DC\uC791\uD558\uAC70\uB098 \uC790\uB3D9\uBCF5\uAD6C \uC0C1\uD0DC\uB97C \uD655\uC778\uD569\uB2C8\uB2E4.", color = Color(0xFFC8C8C8), style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = onOpenPhoto,
                    modifier = Modifier.widthIn(min = 108.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE6E6E6), contentColor = Color(0xFF111111))
                ) { Text("\uC0AC\uC9C4 \uCD94\uAC00", maxLines = 1) }
            }

            AutoRecoveryCard(
                draftSavedAtMillis = draftSavedAtMillis,
                draftSourcePath = draftSourcePath,
                draftSourceExists = draftSourceExists,
                recoveryDebugInfo = recoveryDebugInfo,
                canContinueDraft = canContinueDraft,
                onContinueEditing = onContinueEditing,
                onClearDraft = onClearDraft
            )

            Column(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF1B1B1B)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("\uC8FC\uC694 \uC791\uC5C5", color = Color(0xFFF2F2F2), fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onOpenPhoto,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE6E6E6), contentColor = Color(0xFF111111))
                    ) { Text("\uC0AC\uC9C4 \uCD94\uAC00") }
                    Button(
                        onClick = onOpenGallery,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF343434), contentColor = Color(0xFFF2F2F2))
                    ) { Text("\uAC24\uB7EC\uB9AC \uBCF4\uAE30") }
                }
            }

            GalleryCacheManagementCard(activeSourcePath = activeSourcePath, draftSourcePath = draftSourcePath)
        }
    }
}

@Composable
private fun AutoRecoveryCard(
    draftSavedAtMillis: Long?,
    draftSourcePath: String?,
    draftSourceExists: Boolean,
    recoveryDebugInfo: RecoveryDebugInfo?,
    canContinueDraft: Boolean,
    onContinueEditing: () -> Unit,
    onClearDraft: () -> Unit
) {
    var showDebugDetails by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF242424)).padding(12.dp)) {
        Text("\uC790\uB3D9\uBCF5\uAD6C", color = Color(0xFFF2F2F2), fontWeight = FontWeight.SemiBold)
        Text(
            draftStatusText(draftSavedAtMillis, draftSourceExists),
            color = Color(0xFFC8C8C8),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (draftSourceExists && draftSourcePath != null) {
            DraftSourceThumbnail(
                sourcePath = draftSourcePath,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().aspectRatio(16f / 9f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onContinueEditing, enabled = canContinueDraft) { Text("\uB9C8\uC9C0\uB9C9 \uD3B8\uC9D1 \uACC4\uC18D\uD558\uAE30") }
            TextButton(onClick = onClearDraft, enabled = draftSavedAtMillis != null || draftSourcePath != null) { Text("\uC784\uC2DC \uC800\uC7A5 \uAE30\uB85D \uC0AD\uC81C") }
        }
        if (recoveryDebugInfo != null) {
            TextButton(onClick = { showDebugDetails = !showDebugDetails }) {
                Text(if (showDebugDetails) "\uB514\uBC84\uADF8 \uC815\uBCF4 \uC228\uAE30\uAE30" else "\uB514\uBC84\uADF8 \uC815\uBCF4 \uBCF4\uAE30")
            }
            if (showDebugDetails) {
                RecoveryDebugDetails(recoveryDebugInfo)
            }
        }
    }
}

@Composable
private fun GalleryScreen(
    mode: GalleryDisplayMode,
    onModeChange: (GalleryDisplayMode) -> Unit,
    savedExports: List<SavedExport>,
    draftSavedAtMillis: Long?,
    draftSourcePath: String?,
    recoveryDebugInfo: RecoveryDebugInfo?,
    onBack: () -> Unit,
    onContinueEditing: () -> Unit,
    onClearDraft: () -> Unit,
    onClearSavedExports: () -> Unit,
    onRemoveSavedExport: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val draftSourceExists = remember(draftSourcePath) { draftSourcePath?.let { File(it).isFile } == true }
    val canContinueDraft = draftSavedAtMillis != null && draftSourceExists
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF101010)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF101010))
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("\uB4A4\uB85C") }
                Box {
                    TextButton(onClick = { expanded = true }) {
                        Text("${mode.label} \u25BE", color = Color(0xFFF2F2F2), style = MaterialTheme.typography.titleMedium)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        GalleryDisplayMode.entries.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.label) },
                                onClick = {
                                    onModeChange(item)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                if (mode == GalleryDisplayMode.Exports) {
                    TextButton(onClick = onClearSavedExports, enabled = savedExports.isNotEmpty()) { Text("\uAE30\uB85D \uBE44\uC6B0\uAE30") }
                } else {
                    Box(modifier = Modifier.widthIn(min = 96.dp))
                }
            }

            when (mode) {
                GalleryDisplayMode.Draft -> DraftGalleryContent(
                    draftSavedAtMillis = draftSavedAtMillis,
                    draftSourcePath = draftSourcePath,
                    draftSourceExists = draftSourceExists,
                    recoveryDebugInfo = recoveryDebugInfo,
                    canContinueDraft = canContinueDraft,
                    onContinueEditing = onContinueEditing,
                    onClearDraft = onClearDraft
                )
                GalleryDisplayMode.Exports -> ExportGalleryContent(savedExports, onRemoveSavedExport)
            }
        }
    }
}

@Composable
private fun DraftGalleryContent(
    draftSavedAtMillis: Long?,
    draftSourcePath: String?,
    draftSourceExists: Boolean,
    recoveryDebugInfo: RecoveryDebugInfo?,
    canContinueDraft: Boolean,
    onContinueEditing: () -> Unit,
    onClearDraft: () -> Unit
) {
    var showDebugDetails by remember { mutableStateOf(false) }
    val hasDraft = draftSavedAtMillis != null || draftSourcePath != null
    if (!hasDraft) {
        Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF1B1B1B)).padding(16.dp)) {
            Text("\uD604\uC7AC \uC784\uC2DC \uC800\uC7A5 \uAE30\uB85D\uC774 \uC5C6\uC2B5\uB2C8\uB2E4.", color = Color(0xFFF2F2F2), fontWeight = FontWeight.SemiBold)
            Text("\uC800\uC7A5\uD558\uACE0 \uB098\uAC00\uAE30\uB97C \uC0AC\uC6A9\uD558\uBA74 \uC790\uB3D9\uBCF5\uAD6C\uC6A9 \uC784\uC2DC \uC800\uC7A5\uC774 \uC5EC\uAE30\uC5D0 \uD45C\uC2DC\uB429\uB2C8\uB2E4.", color = Color(0xFFC8C8C8), style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF242424)).padding(12.dp)) {
        Text("\uC790\uB3D9\uBCF5\uAD6C \uC784\uC2DC \uC800\uC7A5", color = Color(0xFFF2F2F2), fontWeight = FontWeight.SemiBold)
        Text(draftStatusText(draftSavedAtMillis, draftSourceExists), color = Color(0xFFC8C8C8), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        if (draftSourceExists && draftSourcePath != null) {
            DraftSourceThumbnail(sourcePath = draftSourcePath, modifier = Modifier.padding(top = 8.dp).fillMaxWidth().aspectRatio(16f / 9f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onContinueEditing, enabled = canContinueDraft) { Text("\uB9C8\uC9C0\uB9C9 \uD3B8\uC9D1 \uACC4\uC18D\uD558\uAE30") }
            TextButton(onClick = onClearDraft) { Text("\uC784\uC2DC \uC800\uC7A5 \uAE30\uB85D \uC0AD\uC81C") }
        }
        if (recoveryDebugInfo != null) {
            TextButton(onClick = { showDebugDetails = !showDebugDetails }) {
                Text(if (showDebugDetails) "\uB514\uBC84\uADF8 \uC815\uBCF4 \uC228\uAE30\uAE30" else "\uB514\uBC84\uADF8 \uC815\uBCF4 \uBCF4\uAE30")
            }
            if (showDebugDetails) {
                RecoveryDebugDetails(recoveryDebugInfo)
            }
        }
    }
}

@Composable
private fun ExportGalleryContent(savedExports: List<SavedExport>, onRemoveSavedExport: (String) -> Unit) {
    if (savedExports.isEmpty()) {
        Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF1B1B1B)).padding(16.dp)) {
            Text("\uC544\uC9C1 \uB0B4\uBCF4\uB0B8 \uC0AC\uC9C4\uC774 \uC5C6\uC2B5\uB2C8\uB2E4.", color = Color(0xFFF2F2F2), fontWeight = FontWeight.SemiBold)
            Text("\uD3B8\uC9D1 \uD654\uBA74\uC5D0\uC11C \uB0B4\uBCF4\uB0B4\uAE30\uD558\uBA74 \uC774\uACF3\uC5D0 \uAE30\uB85D\uC774 \uD45C\uC2DC\uB429\uB2C8\uB2E4.", color = Color(0xFFC8C8C8), style = MaterialTheme.typography.bodySmall)
        }
    } else {
        savedExports.chunked(3).forEach { rowItems ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { item ->
                    SavedExportThumbnailTile(item = item, onRemoveSavedExport = onRemoveSavedExport, modifier = Modifier.weight(1f))
                }
                repeat(3 - rowItems.size) { Box(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun GalleryCacheManagementCard(activeSourcePath: String?, draftSourcePath: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableStateOf(0) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    val cacheStats by produceState(initialValue = TemporaryCacheStats(), key1 = refreshKey) {
        value = withContext(Dispatchers.IO) { calculateTemporaryCacheStats(context) }
    }
    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF242424)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("\uCE90\uC2DC \uAD00\uB9AC", color = Color(0xFFF2F2F2), fontWeight = FontWeight.SemiBold)
        Text("\uC784\uC2DC \uC6D0\uBCF8 \uCE90\uC2DC: ${formatCacheBytes(cacheStats.totalBytes)} \u00B7 ${cacheStats.fileCount}\uAC1C", color = Color(0xFFC8C8C8), style = MaterialTheme.typography.bodySmall)
        Text("7\uC77C \uC9C0\uB09C \uCE90\uC2DC: ${formatCacheBytes(cacheStats.oldBytes)} \u00B7 ${cacheStats.oldFileCount}\uAC1C", color = Color(0xFF8E8E8E), style = MaterialTheme.typography.bodySmall)
        actionMessage?.let { Text(it, color = Color(0xFFC8C8C8), style = MaterialTheme.typography.bodySmall) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LongPressCacheAction(
                label = "\uC624\uB798\uB41C \uD56D\uBAA9 \uC815\uB9AC",
                enabled = cacheStats.oldFileCount > 0,
                onTapHint = { actionMessage = "\uC0AD\uC81C\uD558\uB824\uBA74 \uC624\uB798\uB41C \uD56D\uBAA9 \uC815\uB9AC\uB97C \uAE38\uAC8C \uB20C\uB7EC\uC8FC\uC138\uC694." },
                onLongPress = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { cleanupTemporarySourceFiles(context, activeSourcePath, draftSourcePath, olderThan7DaysOnly = true) }
                        refreshKey += 1
                        actionMessage = "\uC624\uB798\uB41C \uC784\uC2DC \uC6D0\uBCF8 ${result.removedCount}\uAC1C\uB97C \uC815\uB9AC\uD588\uC2B5\uB2C8\uB2E4. \uD655\uBCF4 \uACF5\uAC04: ${formatCacheBytes(result.removedBytes)}"
                    }
                }
            )
            LongPressCacheAction(
                label = "\uD604\uC7AC \uD3B8\uC9D1 \uC81C\uC678 \uBAA8\uB450 \uC815\uB9AC",
                enabled = cacheStats.fileCount > listOfNotNull(activeSourcePath, draftSourcePath).distinct().size,
                onTapHint = { actionMessage = "\uC0AD\uC81C\uD558\uB824\uBA74 \uD604\uC7AC \uD3B8\uC9D1 \uC81C\uC678 \uBAA8\uB450 \uC815\uB9AC\uB97C \uAE38\uAC8C \uB20C\uB7EC\uC8FC\uC138\uC694." },
                onLongPress = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { cleanupTemporarySourceFiles(context, activeSourcePath, draftSourcePath, olderThan7DaysOnly = false) }
                        refreshKey += 1
                        actionMessage = "\uD604\uC7AC \uD3B8\uC9D1 \uC6D0\uBCF8\uACFC \uC784\uC2DC \uC800\uC7A5 \uC6D0\uBCF8\uC744 \uC81C\uC678\uD558\uACE0 \uC784\uC2DC \uC6D0\uBCF8 ${result.removedCount}\uAC1C\uB97C \uC815\uB9AC\uD588\uC2B5\uB2C8\uB2E4. \uD655\uBCF4 \uACF5\uAC04: ${formatCacheBytes(result.removedBytes)}"
                    }
                }
            )
        }
        Text("\uC0AD\uC81C \uC791\uC5C5\uC740 \uC2E4\uC218 \uBC29\uC9C0\uB97C \uC704\uD574 \uAE38\uAC8C \uB20C\uB7EC\uC57C \uC2E4\uD589\uB429\uB2C8\uB2E4. \uB0B4\uBCF4\uB0B8 \uC0AC\uC9C4 \uD30C\uC77C\uC740 \uC0AD\uC81C\uD558\uC9C0 \uC54A\uACE0, \uC571 \uB0B4\uBD80 \uC784\uC2DC \uC6D0\uBCF8\uB9CC \uC815\uB9AC\uD569\uB2C8\uB2E4.", color = Color(0xFF8E8E8E), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun LongPressCacheAction(label: String, enabled: Boolean, onTapHint: () -> Unit, onLongPress: () -> Unit) {
    val background = if (enabled) Color(0xFF343434) else Color(0xFF1B1B1B)
    val textColor = if (enabled) Color(0xFFE6E6E6) else Color(0xFF6E6E6E)
    Text(
        text = "$label \u00B7 \uAE38\uAC8C \uB204\uB974\uAE30",
        color = textColor,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(background)
            .pointerInput(enabled) {
                detectTapGestures(onTap = { if (enabled) onTapHint() }, onLongPress = { if (enabled) onLongPress() })
            }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
private fun SavedExportThumbnailTile(item: SavedExport, onRemoveSavedExport: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.background(Color(0xFF242424)).padding(8.dp)) {
        SavedExportThumbnail(uriString = item.uriString, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
        Text(text = item.displayName, color = Color(0xFFF2F2F2), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(text = "${item.formatLabel} \u00B7 ${item.resolutionLabel}", color = Color(0xFFC8C8C8), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        TextButton(onClick = { onRemoveSavedExport(item.uriString) }) { Text("\uAE30\uB85D \uC0AD\uC81C") }
    }
}

@Composable
private fun DraftSourceThumbnail(sourcePath: String, modifier: Modifier = Modifier) {
    val thumbnail by produceState<Bitmap?>(initialValue = null, key1 = sourcePath) {
        value = withContext(Dispatchers.IO) { decodeFileThumbnail(sourcePath) }
    }
    ThumbnailBox(thumbnail = thumbnail, emptyText = "\uC784\uC2DC \uC800\uC7A5 \uBBF8\uB9AC\uBCF4\uAE30\uB97C \uBD88\uB7EC\uC62C \uC218 \uC5C6\uC2B5\uB2C8\uB2E4.", modifier = modifier)
}

@Composable
private fun SavedExportThumbnail(uriString: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue = null, key1 = uriString) {
        value = withContext(Dispatchers.IO) { decodeSavedExportThumbnail(context, uriString) }
    }
    ThumbnailBox(thumbnail = thumbnail, emptyText = "\uBBF8\uB9AC\uBCF4\uAE30 \uC5C6\uC74C", modifier = modifier)
}

@Composable
private fun ThumbnailBox(thumbnail: Bitmap?, emptyText: String, modifier: Modifier) {
    Box(modifier = modifier.background(Color(0xFF111111)), contentAlignment = Alignment.Center) {
        if (thumbnail != null) {
            Image(bitmap = thumbnail.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(emptyText, color = Color(0xFF8E8E8E), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RecoveryDebugDetails(info: RecoveryDebugInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1B1B1B))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("draft_source 파일 존재: ${if (info.draftSourceExists) "\uC608" else "\uC544\uB2C8\uC624"}", color = Color(0xFFC8C8C8), style = MaterialTheme.typography.bodySmall)
        Text("filesDir draft 존재: ${if (info.filesDirDraftExists) "\uC608" else "\uC544\uB2C8\uC624"}", color = Color(0xFFC8C8C8), style = MaterialTheme.typography.bodySmall)
        Text("draft_source 경로: ${info.draftSourcePath ?: "\uC5C6\uC74C"}", color = Color(0xFF8E8E8E), style = MaterialTheme.typography.labelSmall)
        Text("filesDir 경로: ${info.filesDirDraftPath}", color = Color(0xFF8E8E8E), style = MaterialTheme.typography.labelSmall)
    }
}

private fun draftStatusText(draftSavedAtMillis: Long?, draftSourceExists: Boolean): String =
    when {
        draftSavedAtMillis != null && draftSourceExists -> "\uB9C8\uC9C0\uB9C9 \uC784\uC2DC \uC800\uC7A5: ${formatMainSavedTime(draftSavedAtMillis)}"
        draftSavedAtMillis != null -> "\uC784\uC2DC \uC800\uC7A5 \uC6D0\uBCF8\uC744 \uCC3E\uC744 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4. \uAE30\uC874 \uC784\uC2DC \uC800\uC7A5 \uD30C\uC77C\uC774 \uC0AD\uC81C\uB418\uC5B4 \uBCF5\uAD6C\uD560 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4."
        else -> "\uD604\uC7AC \uC784\uC2DC \uC800\uC7A5 \uAE30\uB85D\uC774 \uC5C6\uC2B5\uB2C8\uB2E4."
    }

private fun decodeSavedExportThumbnail(context: Context, uriString: String, maxSide: Int = 512): Bitmap? {
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateThumbnailSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } }.getOrNull()
}

private fun decodeFileThumbnail(sourcePath: String, maxSide: Int = 512): Bitmap? {
    val source = File(sourcePath).takeIf { it.isFile } ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateThumbnailSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return runCatching { BitmapFactory.decodeFile(source.absolutePath, options) }.getOrNull()
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

private data class TemporaryCacheStats(val fileCount: Int = 0, val totalBytes: Long = 0L, val oldFileCount: Int = 0, val oldBytes: Long = 0L)
private data class TemporaryCacheCleanupResult(val removedCount: Int, val removedBytes: Long)

private fun calculateTemporaryCacheStats(context: Context): TemporaryCacheStats {
    val now = System.currentTimeMillis()
    val files = listTemporarySourceFiles(context)
    val oldFiles = files.filter { now - it.lastModified() > TemporarySourceMaxAgeMs }
    return TemporaryCacheStats(files.size, files.sumOf { it.length() }, oldFiles.size, oldFiles.sumOf { it.length() })
}

private fun cleanupTemporarySourceFiles(context: Context, activeSourcePath: String?, draftSourcePath: String?, olderThan7DaysOnly: Boolean): TemporaryCacheCleanupResult {
    val now = System.currentTimeMillis()
    val protectedPaths = listOfNotNull(activeSourcePath, draftSourcePath).map { File(it).absolutePath }.toSet()
    var removedCount = 0
    var removedBytes = 0L
    listTemporarySourceFiles(context).forEach { file ->
        val shouldDelete = file.absolutePath !in protectedPaths && (!olderThan7DaysOnly || now - file.lastModified() > TemporarySourceMaxAgeMs)
        if (shouldDelete) {
            val size = file.length()
            if (file.delete()) {
                removedCount += 1
                removedBytes += size
            }
        }
    }
    return TemporaryCacheCleanupResult(removedCount, removedBytes)
}

private fun listTemporarySourceFiles(context: Context): List<File> =
    context.cacheDir.listFiles { file -> file.isFile && file.name.startsWith("source_") && file.name.endsWith(".img") }.orEmpty().toList()

private fun formatCacheBytes(bytes: Long): String {
    val formatter = DecimalFormat("0.#")
    return when {
        bytes >= 1024L * 1024L * 1024L -> "${formatter.format(bytes / 1024.0 / 1024.0 / 1024.0)} GB"
        bytes >= 1024L * 1024L -> "${formatter.format(bytes / 1024.0 / 1024.0)} MB"
        bytes >= 1024L -> "${formatter.format(bytes / 1024.0)} KB"
        else -> "$bytes B"
    }
}

private fun formatMainSavedTime(timestampMillis: Long): String =
    SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(timestampMillis))

private const val TemporarySourceMaxAgeMs = 7L * 24L * 60L * 60L * 1000L
