package com.projectnuke.keplerstudio.ui

import android.content.Context
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.projectnuke.keplerstudio.editor.EditParams
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val timestampMillis: Long
)

@Composable
fun PresetToolPanel(
    params: EditParams,
    onApplyPreset: (EditParams) -> Unit
) {
    val context = LocalContext.current
    var presetName by remember { mutableStateOf(defaultPresetName()) }
    var presets by remember { mutableStateOf(emptyList<StoredPreset>()) }

    LaunchedEffect(Unit) {
        presets = loadPresets(context)
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
                        timestampMillis = System.currentTimeMillis()
                    )
                    presets = (listOf(item) + presets.filterNot { it.name == item.name }).take(40)
                    savePresets(context, presets)
                    presetName = defaultPresetName()
                },
                colors = ButtonDefaults.buttonColors(containerColor = PresetAccent, contentColor = PresetButtonTextDark)
            ) {
                Text("현재 편집값 저장")
            }

            TextButton(
                onClick = { presetName = "Extracted_${SimpleDateFormat("HHmm", Locale.US).format(Date())}" }
            ) {
                Text("이름 자동")
            }
        }

        Text(
            text = "완성본 사진만 보고 프리셋을 역추출하는 기능은 별도 분석 엔진으로 추가 예정입니다. 현재는 편집 중인 보정값을 정확히 저장합니다",
            color = PresetTextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
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
                            Text(formatPresetSummary(preset.params), color = PresetTextSecondary, style = MaterialTheme.typography.bodySmall)
                            Text(formatPresetTime(preset.timestampMillis), color = PresetTextMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onApplyPreset(preset.params) }) {
                            Text("적용")
                        }
                        TextButton(
                            onClick = {
                                presets = presets.filterNot { it.id == preset.id }
                                savePresets(context, presets)
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

private fun formatPresetTime(timestampMillis: Long): String =
    SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(timestampMillis))

private fun formatPresetSummary(params: EditParams): String =
    "노출 ${params.exposure.toFixed2()} · 대비 ${params.contrast.toFixed2()} · 색온도 ${params.temperature.toFixed2()} · 샤픈 ${params.sharpness.toFixed2()}"

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
    return StoredPreset(
        id = p[0],
        name = p[1],
        timestampMillis = p[2].toLongOrNull() ?: return null,
        params = EditParams(
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
    )
}

private const val PRESET_PREF_NAME = "kepler_studio_presets"
private const val KEY_PRESETS = "presets"
