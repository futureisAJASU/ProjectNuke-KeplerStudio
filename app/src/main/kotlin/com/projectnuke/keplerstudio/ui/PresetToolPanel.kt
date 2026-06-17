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
import com.projectnuke.keplerstudio.editor.PresetColorLook
import com.projectnuke.keplerstudio.editor.PresetLookHandoff
import com.projectnuke.keplerstudio.editor.createPresetColorLookFromParams
import com.projectnuke.keplerstudio.editor.presetColorLookFromJson
import com.projectnuke.keplerstudio.editor.presetColorLookSummary
import com.projectnuke.keplerstudio.editor.presetColorLookToJson
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val PresetCardBackground = Color(0xFF242424)
private val PresetAccent = Color(0xFFE6E6E6)
private val PresetTextPrimary = Color(0xFFF2F2F2)
private val PresetTextSecondary = Color(0xFFC8C8C8)
private val PresetTextMuted = Color(0xFF8E8E8E)
private val PresetButtonTextDark = Color(0xFF111111)

private data class StoredPreset(
    val id: String,
    val name: String,
    val params: EditParams,
    val timestampMillis: Long,
    val look: PresetColorLook? = null
)

@Composable
fun PresetToolPanel(
    params: EditParams,
    onApplyPreset: (EditParams) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var presetName by remember { mutableStateOf(defaultPresetName()) }
    var presets by remember { mutableStateOf(emptyList<StoredPreset>()) }
    var statusMessage by remember { mutableStateOf("현재 편집값 저장, JSON 백업/복원, 이미지 분석 추출을 사용할 수 있습니다") }
    var pendingBeforeUri by remember { mutableStateOf<Uri?>(null) }

    fun applyStoredPreset(preset: StoredPreset, message: String) {
        PresetLookHandoff.offer(preset.look)
        onApplyPreset(preset.params)
        statusMessage = message
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
                statusMessage = "프리셋 JSON 내보내기가 완료되었습니다"
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
                presets = mergePresets(presets, imported).take(40)
                savePresets(context, presets)
                statusMessage = "프리셋 ${imported.size}개를 JSON에서 불러왔습니다"
            }.onFailure {
                statusMessage = "프리셋 JSON 불러오기에 실패했습니다: ${it.message}"
            }
        }
    }

    val pairAfterPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { afterUri ->
        val beforeUri = pendingBeforeUri
        pendingBeforeUri = null
        if (beforeUri == null || afterUri == null) {
            statusMessage = "원본과 보정본을 모두 선택해 주세요"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            statusMessage = "전/후 이미지를 분석하여 프리셋을 추출하는 중입니다"
            runCatching {
                withContext(Dispatchers.IO) { estimatePresetFromBeforeAfter(context, beforeUri, afterUri) }
            }.onSuccess { extracted ->
                val item = StoredPreset(
                    id = System.currentTimeMillis().toString(),
                    name = "Pair_${timeTag()}",
                    params = extracted,
                    timestampMillis = System.currentTimeMillis(),
                    look = createPresetColorLookFromParams(extracted, strength = 0.82f)
                )
                presets = mergePresets(presets, listOf(item)).take(40)
                savePresets(context, presets)
                applyStoredPreset(item, "전/후 비교 기반 프리셋과 LUT를 추출하고 현재 사진에 적용했습니다")
            }.onFailure {
                statusMessage = "전/후 비교 프리셋 추출에 실패했습니다: ${it.message}"
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
        scope.launch {
            statusMessage = "레퍼런스 이미지를 분석하여 스타일 프리셋을 추출하는 중입니다"
            runCatching {
                withContext(Dispatchers.IO) { estimatePresetFromReference(context, uri) }
            }.onSuccess { extracted ->
                val item = StoredPreset(
                    id = System.currentTimeMillis().toString(),
                    name = "Reference_${timeTag()}",
                    params = extracted,
                    timestampMillis = System.currentTimeMillis(),
                    look = createPresetColorLookFromParams(extracted, strength = 0.74f)
                )
                presets = mergePresets(presets, listOf(item)).take(40)
                savePresets(context, presets)
                applyStoredPreset(item, "레퍼런스 기반 프리셋과 LUT를 추출하고 현재 사진에 적용했습니다")
            }.onFailure {
                statusMessage = "레퍼런스 프리셋 추출에 실패했습니다: ${it.message}"
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "현재 보정값을 프리셋으로 저장하거나, 저장된 프리셋을 현재 사진에 적용할 수 있습니다",
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
                    val item = StoredPreset(
                        id = System.currentTimeMillis().toString(),
                        name = presetName.ifBlank { defaultPresetName() },
                        params = params,
                        timestampMillis = System.currentTimeMillis(),
                        look = createPresetColorLookFromParams(params, strength = 0.60f)
                    )
                    presets = mergePresets(presets, listOf(item)).take(40)
                    savePresets(context, presets)
                    presetName = defaultPresetName()
                    statusMessage = "현재 편집값과 LUT를 프리셋으로 저장했습니다"
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
            TextButton(onClick = { pairBeforePicker.launch("image/*") }) {
                Text("전/후 비교 추출")
            }
            TextButton(onClick = { referencePicker.launch("image/*") }) {
                Text("레퍼런스 한 장 추출")
            }
        }

        Text(
            text = statusMessage,
            color = PresetTextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
        )

        Text(
            text = "추출 기능은 1차 통계 기반 근사입니다. 추출된 프리셋은 슬라이더값과 LUT를 함께 저장해 색 변환을 더 강하게 재사용합니다",
            color = PresetTextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (presets.isEmpty()) {
            Text(
                text = "아직 저장된 프리셋이 없습니다",
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
                        TextButton(onClick = { applyStoredPreset(preset, "선택한 프리셋을 현재 사진에 적용했습니다") }) {
                            Text("적용")
                        }
                        TextButton(
                            onClick = {
                                presets = presets.filterNot { it.id == preset.id }
                                savePresets(context, presets)
                                statusMessage = "선택한 프리셋을 삭제했습니다"
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

private fun formatPresetSummary(preset: StoredPreset): String =
    "노출 ${preset.params.exposure.toFixed2()} · 대비 ${preset.params.contrast.toFixed2()} · 색온도 ${preset.params.temperature.toFixed2()} · ${presetColorLookSummary(preset.look)}"

private fun Float.toFixed2(): String = String.format(Locale.US, "%.2f", this)

private fun loadPresets(context: Context): List<StoredPreset> {
    val raw = context.getSharedPreferences(PRESET_PREF_NAME, Context.MODE_PRIVATE).getString(KEY_PRESETS, null)
        ?: return emptyList()
    return raw.lines().mapNotNull { decodePreset(it) }
}

private fun savePresets(context: Context, presets: List<StoredPreset>) {
    context.getSharedPreferences(PRESET_PREF_NAME, Context.MODE_PRIVATE).edit()
        .putString(KEY_PRESETS, presets.joinToString("\n") { encodePreset(it) })
        .apply()
}

private fun mergePresets(current: List<StoredPreset>, incoming: List<StoredPreset>): List<StoredPreset> {
    val merged = LinkedHashMap<String, StoredPreset>()
    (incoming + current).forEach { preset ->
        merged[preset.name.lowercase(Locale.ROOT)] = preset
    }
    return merged.values.sortedByDescending { it.timestampMillis }
}

private fun encodePreset(item: StoredPreset): String = listOf(
    item.id,
    item.name,
    item.timestampMillis.toString(),
    item.params.exposure.toString(),
    item.params.contrast.toString(),
    item.params.shadows.toString(),
    item.params.highlights.toString(),
    item.params.whites.toString(),
    item.params.blacks.toString(),
    item.params.temperature.toString(),
    item.params.tint.toString(),
    item.params.saturation.toString(),
    item.params.vibrance.toString(),
    item.params.clarity.toString(),
    item.params.dehaze.toString(),
    item.params.sharpness.toString(),
    item.params.noiseReduction.toString()
).joinToString("|") { it.replace("|", " ").replace("\n", " ") }

private fun decodePreset(raw: String): StoredPreset? {
    val p = raw.split("|")
    if (p.size != 17) return null
    val params = EditParams(
        exposure = p[3].toFloatOrNull() ?: 0f,
        contrast = p[4].toFloatOrNull() ?: 0f,
        shadows = p[5].toFloatOrNull() ?: 0f,
        highlights = p[6].toFloatOrNull() ?: 0f,
        whites = p[7].toFloatOrNull() ?: 0f,
        blacks = p[8].toFloatOrNull() ?: 0f,
        temperature = p[9].toFloatOrNull() ?: 0f,
        tint = p[10].toFloatOrNull() ?: 0f,
        saturation = p[11].toFloatOrNull() ?: 0f,
        vibrance = p[12].toFloatOrNull() ?: 0f,
        clarity = p[13].toFloatOrNull() ?: 0f,
        dehaze = p[14].toFloatOrNull() ?: 0f,
        sharpness = p[15].toFloatOrNull() ?: 0f,
        noiseReduction = p[16].toFloatOrNull() ?: 0f
    )
    return StoredPreset(
        id = p[0],
        name = p[1],
        timestampMillis = p[2].toLongOrNull() ?: return null,
        params = params,
        look = createPresetColorLookFromParams(params, strength = 0.60f)
    )
}

private fun exportPresetsToJson(context: Context, uri: Uri, presets: List<StoredPreset>) {
    val root = JSONObject().apply {
        put("format", "keplerstudio-presets")
        put("version", 2)
        put("exportedAt", System.currentTimeMillis())
        put("presets", JSONArray().apply {
            presets.forEach { preset ->
                put(JSONObject().apply {
                    put("id", preset.id)
                    put("name", preset.name)
                    put("timestampMillis", preset.timestampMillis)
                    put("params", editParamsToJson(preset.params))
                    presetColorLookToJson(preset.look)?.let { put("look", it) }
                })
            }
        })
    }

    context.contentResolver.openOutputStream(uri)?.use { out ->
        OutputStreamWriter(out).use { writer -> writer.write(root.toString(2)) }
    } ?: error("JSON 파일 저장 스트림을 열 수 없습니다")
}

private fun importPresetsFromJson(context: Context, uri: Uri): List<StoredPreset> {
    val raw = context.contentResolver.openInputStream(uri)?.use { input ->
        BufferedReader(InputStreamReader(input)).readText()
    } ?: error("JSON 파일을 읽을 수 없습니다")

    val root = JSONObject(raw)
    val array = root.optJSONArray("presets") ?: JSONArray()
    val items = mutableListOf<StoredPreset>()
    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        val params = editParamsFromJson(obj.optJSONObject("params") ?: JSONObject())
        items += StoredPreset(
            id = obj.optString("id", System.currentTimeMillis().toString()),
            name = obj.optString("name", "Imported_${i + 1}"),
            timestampMillis = obj.optLong("timestampMillis", System.currentTimeMillis()),
            params = params,
            look = presetColorLookFromJson(obj.optJSONObject("look")) ?: createPresetColorLookFromParams(params, strength = 0.60f)
        )
    }
    return items
}

private fun editParamsToJson(params: EditParams): JSONObject = JSONObject().apply {
    put("exposure", params.exposure)
    put("contrast", params.contrast)
    put("shadows", params.shadows)
    put("highlights", params.highlights)
    put("whites", params.whites)
    put("blacks", params.blacks)
    put("temperature", params.temperature)
    put("tint", params.tint)
    put("saturation", params.saturation)
    put("vibrance", params.vibrance)
    put("clarity", params.clarity)
    put("dehaze", params.dehaze)
    put("sharpness", params.sharpness)
    put("noiseReduction", params.noiseReduction)
}

private fun editParamsFromJson(obj: JSONObject): EditParams = EditParams(
    exposure = obj.optDouble("exposure", 0.0).toFloat(),
    contrast = obj.optDouble("contrast", 0.0).toFloat(),
    shadows = obj.optDouble("shadows", 0.0).toFloat(),
    highlights = obj.optDouble("highlights", 0.0).toFloat(),
    whites = obj.optDouble("whites", 0.0).toFloat(),
    blacks = obj.optDouble("blacks", 0.0).toFloat(),
    temperature = obj.optDouble("temperature", 0.0).toFloat(),
    tint = obj.optDouble("tint", 0.0).toFloat(),
    saturation = obj.optDouble("saturation", 0.0).toFloat(),
    vibrance = obj.optDouble("vibrance", 0.0).toFloat(),
    clarity = obj.optDouble("clarity", 0.0).toFloat(),
    dehaze = obj.optDouble("dehaze", 0.0).toFloat(),
    sharpness = obj.optDouble("sharpness", 0.0).toFloat(),
    noiseReduction = obj.optDouble("noiseReduction", 0.0).toFloat()
)

private const val PRESET_PREF_NAME = "kepler_studio_presets"
private const val KEY_PRESETS = "presets"
