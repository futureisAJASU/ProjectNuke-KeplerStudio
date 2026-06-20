package com.projectnuke.keplerstudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.projectnuke.keplerstudio.editor.EditorViewModel

private val NativePanelBackground = Color(0xFF242424)
private val NativePanelAccent = Color(0xFFE6E6E6)
private val NativePanelTextDark = Color(0xFF111111)
private val NativePanelTextPrimary = Color(0xFFF2F2F2)
private val NativePanelTextSecondary = Color(0xFFC8C8C8)
private val NativePanelTextMuted = Color(0xFF8E8E8E)

@Composable
fun NativeRemoveToolPanel() {
    val vm: EditorViewModel = viewModel()
    NativeToolCard(
        title = "기본 제거",
        description = "작은 얼룩을 완화합니다. 이 기능은 AI 기반 객체 제거가 아닙니다."
    ) {
        NativePrimaryButton("작은 얼룩 완화") { vm.applySpotCleanupMvp() }
    }
}

@Composable
fun NativeOpticsToolPanel() {
    val vm: EditorViewModel = viewModel()
    NativeToolCard(
        title = "광학 보정",
        description = "색수차와 주변부 어두움을 완화합니다. 렌즈 프로필 보정은 아직 지원되지 않습니다."
    ) {
        NativePrimaryButton("색수차 및 주변부 완화") { vm.applyOpticsCorrectionMvp() }
    }
}

@Composable
fun NativeGeometryToolPanel() {
    val vm: EditorViewModel = viewModel()
    NativeToolCard(
        title = "기하 보정",
        description = "자르기와 기울기 보정만 지원합니다. 원근 보정은 아직 지원되지 않습니다."
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.autoStraightenCrop() }) { Text("기울기 보정") }
            TextButton(onClick = { vm.setStraightenDegrees(0f) }) { Text("기울기 초기화") }
        }
    }
}

@Composable
fun NativeBlurToolPanel() {
    val vm: EditorViewModel = viewModel()
    NativeToolCard(
        title = "부드러운 흐림",
        description = "이미지를 부드럽게 흐립니다."
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.applySoftBlurMvp(0.28f) }) { Text("약하게") }
            TextButton(onClick = { vm.applySoftBlurMvp(0.55f) }) { Text("강하게") }
        }
    }
}

@Composable
fun NativeModelToolPanel() {
    val vm: EditorViewModel = viewModel()
    NativeToolCard(
        title = "모델 및 기본 보조",
        description = "연결된 모델이 없을 때는 기본 보정과 규칙 기반 분석으로 동작합니다."
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.runAutoRouterV0Analysis() }) { Text("화면 분석") }
            TextButton(onClick = { vm.applyFlareOriginalMvp() }) { Text("번짐 완화") }
            TextButton(onClick = { vm.applySunFlareOriginalMvp() }) { Text("태양 번짐 완화") }
        }
    }
}

@Composable
fun NativeProfilesToolPanel() {
    NativeToolCard(
        title = "프로필",
        description = "전용 LUT 또는 카메라 매트릭스 프로필은 아직 연결되지 않았습니다."
    ) {
        Text("현재는 프리셋과 동일한 보정값 경로만 사용할 수 있습니다.", color = NativePanelTextMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NativeToolCard(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NativePanelBackground)
            .padding(12.dp)
    ) {
        Text(title, color = NativePanelTextPrimary, fontWeight = FontWeight.SemiBold)
        Text(description, color = NativePanelTextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        content()
    }
}

@Composable
private fun NativePrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = NativePanelAccent, contentColor = NativePanelTextDark)
    ) {
        Text(text)
    }
}
