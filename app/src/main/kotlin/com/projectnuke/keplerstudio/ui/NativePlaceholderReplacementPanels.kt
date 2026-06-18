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
        title = "네이티브 제거",
        description = "작은 점, 먼지, 고립된 결함을 C++ 픽셀 커널로 완화합니다. 큰 물체 제거는 이후 마스크 기반으로 연결합니다."
    ) {
        NativePrimaryButton("작은 결함 완화") { vm.applyNativeSpecialEffect(NativeSpecialEffect.SmallSpotCleanup, 0.58f) }
    }
}

@Composable
fun NativeOpticsToolPanel() {
    val vm: EditorViewModel = viewModel()
    NativeToolCard(
        title = "네이티브 옵틱 보정",
        description = "렌즈 주변부와 강한 엣지에서 생기는 색수차·비네팅을 C++ 커널로 보정합니다."
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.applyNativeSpecialEffect(NativeSpecialEffect.ChromaFringeReduce, 0.62f) }) { Text("색수차 완화") }
            TextButton(onClick = { vm.applyNativeSpecialEffect(NativeSpecialEffect.VignetteCorrection, 0.45f) }) { Text("비네팅 보정") }
        }
    }
}

@Composable
fun NativeGeometryToolPanel() {
    val vm: EditorViewModel = viewModel()
    NativeToolCard(
        title = "기하 보정",
        description = "수평 보정은 자르기 도구의 수동/자동 수평 엔진과 연결되어 있습니다. 원근 보정은 다음 단계에서 네이티브 워프 커널로 확장합니다."
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.autoStraightenCrop() }) { Text("자동 수평 계산") }
            TextButton(onClick = { vm.setStraightenDegrees(0f) }) { Text("수평 초기화") }
        }
    }
}

@Composable
fun NativeBlurToolPanel() {
    val vm: EditorViewModel = viewModel()
    NativeToolCard(
        title = "네이티브 블러",
        description = "현재 사진에 C++ 3x3 소프트 블러를 적용합니다. 이후 마스크/피사체 기반 렌즈 블러로 확장합니다."
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.applyNativeSpecialEffect(NativeSpecialEffect.SoftBlur, 0.28f) }) { Text("약하게") }
            TextButton(onClick = { vm.applyNativeSpecialEffect(NativeSpecialEffect.SoftBlur, 0.55f) }) { Text("강하게") }
        }
    }
}

@Composable
fun NativeModelToolPanel() {
    val vm: EditorViewModel = viewModel()
    NativeToolCard(
        title = "모델 및 네이티브 보조",
        description = "AI 모델이 없을 때도 네이티브 C++ 보조 기능과 규칙 기반 분석으로 동작합니다."
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.runAutoRouterV0Analysis() }) { Text("장면 분석") }
            TextButton(onClick = { vm.applyFlareGuardV0Preview() }) { Text("번짐 완화") }
            TextButton(onClick = { vm.applyDaySunFlareGuardV0Preview() }) { Text("태양 번짐 완화") }
        }
    }
}

@Composable
fun NativeProfilesToolPanel() {
    NativeToolCard(
        title = "프로필",
        description = "현재는 프리셋/룩 엔진과 같은 네이티브 렌더 파라미터를 사용합니다. 프로필별 카메라 매트릭스와 LUT는 다음 단계에서 별도 asset으로 연결합니다."
    ) {
        Text("프로필은 프리셋 탭의 보정값 적용 경로와 공유됩니다.", color = NativePanelTextMuted, style = MaterialTheme.typography.bodySmall)
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
