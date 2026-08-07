package com.projectnuke.keplerstudio.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.projectnuke.keplerstudio.editor.EditParams
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.PresetApplyResult
import com.projectnuke.keplerstudio.editor.PresetColorLook
import com.projectnuke.keplerstudio.editor.Preset
import com.projectnuke.keplerstudio.editor.PresetImportException
import com.projectnuke.keplerstudio.editor.PresetImportFailure
import com.projectnuke.keplerstudio.editor.decodePresetDocument
import com.projectnuke.keplerstudio.editor.encodePresetDocument
import com.projectnuke.keplerstudio.editor.loadPresets
import com.projectnuke.keplerstudio.editor.mergePresets
import com.projectnuke.keplerstudio.editor.savePresets

import com.projectnuke.keplerstudio.editor.createPresetColorLookFromParams
import com.projectnuke.keplerstudio.editor.presetColorLookSummary
import java.nio.charset.StandardCharsets
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val PresetCardBackground = Color(0xFF242424)
private val PresetAccent = Color(0xFFE6E6E6)
private val PresetTextPrimary = Color(0xFFF2F2F2)
private val PresetTextSecondary = Color(0xFFC8C8C8)
private val PresetTextMuted = Color(0xFF8E8E8E)
private val PresetButtonTextDark = Color(0xFF111111)

private data class PresetDocumentIdentity(val sourcePath: String?, val baseToken: String, val revision: Int)

@Composable
fun PresetToolPanel(
    editorViewModel: EditorViewModel,
    params: EditParams,
    activeLook: PresetColorLook?
) {
    val context = LocalContext.current
    val editorState by editorViewModel.uiState.collectAsState()
    val mutatingEnabled = !editorState.isBusy
    val activeImageAvailable = editorState.sourcePath != null &&
        (editorState.originalPreviewBitmap != null || editorState.previewBitmap != null)
    val scope = rememberCoroutineScope()
    var presetName by remember { mutableStateOf(defaultPresetName()) }
    var presets by remember { mutableStateOf(emptyList<Preset>()) }
    var statusMessage by remember { mutableStateOf("프리셋 저장, JSON 백업, 통계 기반 추출을 사용할 수 있습니다.") }
    var pendingBeforeUri by remember { mutableStateOf<Uri?>(null) }
    var pendingIdentity by remember { mutableStateOf<PresetDocumentIdentity?>(null) }

fun applyStoredPreset(preset: Preset, message: String): PresetApplyResult {
        val current = editorViewModel.uiState.value
        if (current.sourcePath == null || current.originalPreviewBitmap == null && current.previewBitmap == null ||
            !editorViewModel.canEnterEditorAction()) {
            statusMessage = "활성 사진이 없어 프리셋을 적용하지 않았습니다."
            return PresetApplyResult.Rejected
        }
        val result = editorViewModel.applyPresetLook(preset.params, preset.look, message)
        when (result) {
            PresetApplyResult.Accepted -> statusMessage = "프리셋 적용 중입니다..."
            PresetApplyResult.AlreadyApplied -> statusMessage = "이미 적용된 프리셋입니다."
            PresetApplyResult.Rejected -> statusMessage = "프리셋을 적용할 수 없습니다."
        }
        return result
    }

    LaunchedEffect(Unit) {
        presets = loadPresets(context)
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { exportPresetsToJson(context, uri, presets) }
            }.onSuccess {
                statusMessage = "프리셋 JSON 내보내기를 완료했습니다."
            }.onFailure {
                statusMessage = "프리셋 JSON 내보내기에 실패했습니다: ${it.message}"
            }
        }
    }

    val importJsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { importPresetsFromJson(context, uri) }
            }.onSuccess { imported ->
                val merge = mergePresets(presets, imported)
                presets = merge.presets
                savePresets(context, presets)
                statusMessage = "프리셋 ${merge.importedCount}개를 JSON에서 불러왔습니다"
            }.onFailure { e ->
                statusMessage = when (e) {
                    is PresetImportException -> when (e.failure) {
                        PresetImportFailure.UnsupportedFormat,
                        PresetImportFailure.UnsupportedVersion ->
                            "선택한 파일은 지원하지 않는 프리셋 형식 또는 버전입니다."
                        PresetImportFailure.MalformedContent ->
                            "프리셋 파일 내용이 올바르지 않아 가져오지 못했습니다."
                    }
                    else -> "프리셋 JSON 불러오기에 실패했습니다: ${e.message}"
                }
            }
        }
    }

    val pairAfterPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { afterUri ->
        val beforeUri = pendingBeforeUri
        val identity = pendingIdentity
        pendingBeforeUri = null
        pendingIdentity = null
        if (beforeUri == null || afterUri == null) {
            statusMessage = "원본과 보정본을 모두 선택해 주세요"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            statusMessage = "전/후 이미지를 분석하여 프리셋을 추출하는 중입니다"
            runCatching {
                withContext(Dispatchers.IO) { estimatePresetFromBeforeAfter(context, beforeUri, afterUri) }
            }.onSuccess { extracted ->
                val item = Preset(
                    id = System.currentTimeMillis().toString(),
                    name = "Pair_${timeTag()}",
                    params = extracted,
                    timestampMillis = System.currentTimeMillis(),
                    look = createPresetColorLookFromParams(extracted, strength = 0.82f)
                )
                presets = mergePresets(presets, listOf(item)).presets
                savePresets(context, presets)
                val current = editorViewModel.uiState.value
                if (identity?.sourcePath != null && current.sourcePath == identity.sourcePath &&
                    (current.originalPreviewBitmap != null || current.previewBitmap != null) &&
                    current.baseContentToken == identity.baseToken && current.revision == identity.revision && editorViewModel.canEnterEditorAction()) {
                    val result = applyStoredPreset(item, "전/후 비교 기반 프리셋과 색감 룩을 추출하고 현재 사진에 적용했습니다.")
                    if (result == PresetApplyResult.Rejected) {
                        statusMessage = "전/후 비교 프리셋을 저장했지만 적용하지 않았습니다."
                    }
                } else {
                    statusMessage = "전/후 비교 프리셋을 저장했지만 변경된 사진에는 적용하지 않았습니다."
                }
            }.onFailure {
                statusMessage = "레퍼런스 프리셋 추출에 실패했습니다: ${it.message}"
            }
        }
    }

    val pairBeforePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { beforeUri ->
        if (beforeUri == null) {
            statusMessage = "원본 이미지 선택이 취소되었습니다"
            return@rememberLauncherForActivityResult
        }
        pendingBeforeUri = beforeUri
        statusMessage = "이제 보정본 이미지를 선택해 주세요"
        pairAfterPicker.launch("image/*")
    }

    val referencePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val identity = pendingIdentity
        pendingIdentity = null
        scope.launch {
            statusMessage = "레퍼런스 이미지를 분석하여 스타일 프리셋을 추출하는 중입니다"
            runCatching {
                withContext(Dispatchers.IO) { estimatePresetFromReference(context, uri) }
            }.onSuccess { extracted ->
                val item = Preset(
                    id = System.currentTimeMillis().toString(),
                    name = "Reference_${timeTag()}",
                    params = extracted,
                    timestampMillis = System.currentTimeMillis(),
                    look = createPresetColorLookFromParams(extracted, strength = 0.74f)
                )
                presets = mergePresets(presets, listOf(item)).presets
                savePresets(context, presets)
                val current = editorViewModel.uiState.value
                if (identity?.sourcePath != null && current.sourcePath == identity.sourcePath &&
                    (current.originalPreviewBitmap != null || current.previewBitmap != null) &&
                    current.baseContentToken == identity.baseToken && current.revision == identity.revision && editorViewModel.canEnterEditorAction()) {
                    val result = applyStoredPreset(item, "레퍼런스 기반 프리셋과 색감 룩을 추출하고 현재 사진에 적용했습니다.")
                    if (result == PresetApplyResult.Rejected) {
                        statusMessage = "레퍼런스 프리셋을 저장했지만 적용하지 않았습니다."
                    }
                } else {
                    statusMessage = "레퍼런스 프리셋을 저장했지만 변경된 사진에는 적용하지 않았습니다."
                }
            }.onFailure {
                statusMessage = "전/후 비교 프리셋 추출에 실패했습니다: ${it.message}"
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "현재 보정값을 프리셋으로 저장하거나 저장된 프리셋을 현재 사진에 적용할 수 있습니다.",
            color = PresetTextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        OutlinedTextField(
            value = presetName,
            onValueChange = { presetName = it.take(32) },
            label = { Text("프리셋 이름") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    val item = Preset(
                        id = System.currentTimeMillis().toString(),
                        name = presetName.ifBlank { defaultPresetName() },
                        params = params,
                        timestampMillis = System.currentTimeMillis(),
                        look = activeLook ?: createPresetColorLookFromParams(params, strength = 0.60f)
                    )
                    presets = mergePresets(presets, listOf(item)).presets
                    savePresets(context, presets)
                    presetName = defaultPresetName()
                    statusMessage = "현재 편집값과 색감 룩을 프리셋으로 저장했습니다."
                },
                colors = ButtonDefaults.buttonColors(containerColor = PresetAccent, contentColor = PresetButtonTextDark)
            ) {
                Text("현재 편집값 저장")
            }

            TextButton(onClick = { presetName = defaultPresetName() }) {
                Text("이름 자동")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { exportJsonLauncher.launch("keplerstudio_presets_${timeTag()}.json") },
                enabled = presets.isNotEmpty()
            ) {
                Text("JSON 내보내기")
            }
            TextButton(onClick = { importJsonLauncher.launch("application/json") }) {
                Text("JSON 불러오기")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = {
                pendingIdentity = PresetDocumentIdentity(editorState.sourcePath, editorState.baseContentToken, editorState.revision)
                pairBeforePicker.launch("image/*")
            }, enabled = mutatingEnabled) {
                Text("전/후 비교 추출")
            }
            TextButton(onClick = {
                pendingIdentity = PresetDocumentIdentity(editorState.sourcePath, editorState.baseContentToken, editorState.revision)
                referencePicker.launch("image/*")
            }, enabled = mutatingEnabled) {
                Text("레퍼런스 추출")
            }
        }

        Text(
            text = statusMessage,
            color = PresetTextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
        )

        Text(
            text = "전/후 비교 추출은 통계 기반 근사입니다. 추출된 프리셋은 슬라이더 값과 색감 룩을 함께 저장합니다.",
            color = PresetTextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (presets.isEmpty()) {
            Text(
                text = "저장된 프리셋이 없습니다",
                color = PresetTextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            presets.forEach { preset ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(PresetCardBackground)
                        .padding(12.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(preset.name, color = PresetTextPrimary, fontWeight = FontWeight.SemiBold)
                            Text(formatPresetSummary(preset), color = PresetTextSecondary, style = MaterialTheme.typography.bodySmall)
                            Text(formatPresetTime(preset.timestampMillis), color = PresetTextMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { applyStoredPreset(preset, "프리셋을 적용했습니다.") }, enabled = mutatingEnabled && activeImageAvailable) {
                            Text("적용")
                        }
                        TextButton(
                            onClick = {
                                presets = presets.filterNot { it.id == preset.id }
                                savePresets(context, presets)
                                statusMessage = "선택한 프리셋을 삭제했습니다."
                            }
                        ) {
                            Text("삭제")
                        }
                    }
                }
            }
        }
    }
}

private fun defaultPresetName(): String = "Preset_${SimpleDateFormat("MMdd_HHmm", Locale.US).format(Date())}"

private fun timeTag(): String = SimpleDateFormat("MMdd_HHmm", Locale.US).format(Date())

private fun formatPresetTime(timestampMillis: Long): String =
    SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(timestampMillis))

private fun formatPresetSummary(preset: Preset): String =
    "노출 ${preset.params.exposure.toFixed2()} · 대비 ${preset.params.contrast.toFixed2()} · 색온도 ${preset.params.temperature.toFixed2()} · ${presetColorLookSummary(preset.look)}"

private fun Float.toFixed2(): String = String.format(Locale.US, "%.2f", this)

private fun exportPresetsToJson(context: Context, uri: Uri, presets: List<Preset>) {
    val root = encodePresetDocument(presets)
    context.contentResolver.openOutputStream(uri)?.use { out ->
        OutputStreamWriter(out, StandardCharsets.UTF_8).use { writer -> writer.write(root.toString(2)) }
    } ?: error("JSON 파일 저장 스트림을 열 수 없습니다")
}

private fun importPresetsFromJson(context: Context, uri: Uri): List<Preset> {
    val raw = context.contentResolver.openInputStream(uri)?.use { input ->
        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readText()
    } ?: error("JSON 파일을 읽을 수 없습니다")
    return decodePresetDocument(JSONObject(raw))
}
