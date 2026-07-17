package com.projectnuke.keplerstudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import com.projectnuke.keplerstudio.editor.ActiveQuickEffect
import com.projectnuke.keplerstudio.editor.EditParams
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.FlareGuardMode
import com.projectnuke.keplerstudio.editor.PresetColorLook
import com.projectnuke.keplerstudio.editor.QuickEffectKind
import com.projectnuke.keplerstudio.editor.QuickEffectStrength
import com.projectnuke.keplerstudio.editor.createPresetColorLookFromParams

private val NativePanelBackground = Color(0xFF242424)
private val NativePanelAccent = Color(0xFFE6E6E6)
private val NativePanelTextPrimary = Color(0xFFF2F2F2)
private val NativePanelTextSecondary = Color(0xFFC8C8C8)
private val NativePanelTextMuted = Color(0xFF8E8E8E)

private data class BuiltInProfile(
    val title: String,
    val params: EditParams,
    val look: PresetColorLook? = null
)

private val BuiltInProfiles = listOf(
    BuiltInProfile("뉴트럴", EditParams()),
    BuiltInProfile(
        "부드러운 대비",
        EditParams(contrast = 0.12f, shadows = 0.10f, highlights = -0.08f, clarity = 0.05f),
        createPresetColorLookFromParams(EditParams(contrast = 0.08f, shadows = 0.06f, highlights = -0.05f), strength = 0.42f)
    ),
    BuiltInProfile(
        "따뜻한 필름",
        EditParams(temperature = 0.24f, contrast = 0.08f, saturation = 0.06f, vibrance = 0.10f, highlights = -0.10f, blacks = -0.04f),
        createPresetColorLookFromParams(EditParams(temperature = 0.28f, contrast = 0.10f, saturation = 0.10f, vibrance = 0.12f), strength = 0.58f)
    ),
    BuiltInProfile(
        "차가운 클린",
        EditParams(temperature = -0.18f, tint = -0.04f, contrast = 0.06f, clarity = 0.08f, dehaze = 0.05f),
        createPresetColorLookFromParams(EditParams(temperature = -0.20f, tint = -0.04f, contrast = 0.08f, clarity = 0.08f), strength = 0.46f)
    ),
    BuiltInProfile(
        "선명하게",
        EditParams(contrast = 0.14f, vibrance = 0.16f, saturation = 0.08f, sharpness = 0.18f, clarity = 0.10f),
        createPresetColorLookFromParams(EditParams(contrast = 0.12f, vibrance = 0.18f, saturation = 0.10f), strength = 0.52f)
    ),
    BuiltInProfile(
        "매트",
        EditParams(contrast = -0.08f, shadows = 0.16f, highlights = -0.14f, blacks = 0.08f, saturation = -0.04f),
        createPresetColorLookFromParams(EditParams(contrast = -0.08f, shadows = 0.12f, highlights = -0.12f, saturation = -0.04f), strength = 0.50f)
    ),
    BuiltInProfile(
        "야간 클린",
        EditParams(exposure = 0.10f, shadows = 0.14f, highlights = -0.18f, noiseReduction = 0.18f, dehaze = 0.08f, temperature = -0.06f),
        createPresetColorLookFromParams(EditParams(shadows = 0.12f, highlights = -0.12f, temperature = -0.08f, vibrance = 0.06f), strength = 0.44f)
    )
)

@Composable
fun NativeRemoveToolPanel(editorViewModel: EditorViewModel = viewModel()) {
    val state by editorViewModel.uiState.collectAsState()
    val selected = state.activeQuickEffects.any { it.kind == QuickEffectKind.SpotCleanup }
    NativeToolCard(
        title = "기본 정리",
        description = "작은 얼룩을 완화합니다. AI 객체 제거가 아닌 기본 정리 기능입니다."
    ) {
        NativeQuickEffectButton(
            label = "작은 얼룩 완화",
            selected = selected,
            enabled = !state.isBusy,
            onClick = { editorViewModel.applySpotCleanup() }
        )
    }
}

@Composable
fun NativeOpticsToolPanel(editorViewModel: EditorViewModel = viewModel()) {
    val state by editorViewModel.uiState.collectAsState()
    NativeToolCard(
        title = "광학 보정",
        description = "색수차와 주변부 어두움을 기본 보정으로 완화합니다."
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NativeQuickEffectButton(
                label = "색수차 완화",
                selected = state.activeQuickEffects.any { it.kind == QuickEffectKind.ChromaticAberrationReduction },
                enabled = !state.isBusy,
                onClick = { editorViewModel.applyChromaticAberrationReduction() }
            )
            NativeQuickEffectButton(
                label = "주변부 어두움 완화",
                selected = state.activeQuickEffects.any { it.kind == QuickEffectKind.VignetteCorrection },
                enabled = !state.isBusy,
                onClick = { editorViewModel.applyVignetteCorrection() }
            )
        }
        NativeQuickEffectButton(
            label = "통합 광학 보정",
            selected = state.activeQuickEffects.any { it.kind == QuickEffectKind.OpticsCorrection },
            enabled = !state.isBusy,
            onClick = { editorViewModel.applyOpticsCorrection() }
        )
    }
}

@Composable
fun NativeGeometryToolPanel(editorViewModel: EditorViewModel = viewModel()) {
    val state by editorViewModel.uiState.collectAsState()
    NativeToolCard(
        title = "기하 보정",
        description = "자르기와 기울기 보정만 지원합니다. 원근 보정은 아직 지원되지 않습니다."
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { editorViewModel.autoStraightenCrop() }, enabled = !state.isBusy) { Text("기울기 보정") }
            TextButton(onClick = { editorViewModel.setStraightenDegrees(0f) }, enabled = !state.isBusy) { Text("기울기 초기화") }
        }
    }
}

@Composable
fun NativeBlurToolPanel(editorViewModel: EditorViewModel = viewModel()) {
    val state by editorViewModel.uiState.collectAsState()
    NativeToolCard(
        title = "부드러운 흐림",
        description = "이미지를 부드럽게 흐립니다."
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NativeQuickEffectButton(
                label = "약하게",
                selected = state.activeQuickEffects.contains(ActiveQuickEffect(QuickEffectKind.SoftBlur, QuickEffectStrength.Weak)),
                enabled = !state.isBusy,
                onClick = { editorViewModel.applySoftBlur(0.22f) }
            )
            NativeQuickEffectButton(
                label = "보통",
                selected = state.activeQuickEffects.contains(ActiveQuickEffect(QuickEffectKind.SoftBlur, QuickEffectStrength.Medium)),
                enabled = !state.isBusy,
                onClick = { editorViewModel.applySoftBlur(0.38f) }
            )
            NativeQuickEffectButton(
                label = "강하게",
                selected = state.activeQuickEffects.contains(ActiveQuickEffect(QuickEffectKind.SoftBlur, QuickEffectStrength.Strong)),
                enabled = !state.isBusy,
                onClick = { editorViewModel.applySoftBlur(0.58f) }
            )
        }
    }
}
@Composable
private fun NativeQuickEffectButton(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (selected) NativePanelTextMuted.copy(alpha = 0.28f) else Color.Transparent,
            contentColor = if (selected) NativePanelAccent else NativePanelTextSecondary
        )
    ) {
        Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
fun NativeModelToolPanel(editorViewModel: EditorViewModel = viewModel()) {
    val context = LocalContext.current
    val state by editorViewModel.uiState.collectAsState()
    val flareMasker = OnDeviceRemasterModels.first { it.id == "flare_masker" }
    val flareRestorer = OnDeviceRemasterModels.first { it.id == "flare_restorer" }
    val edgeMasker = OnDeviceRemasterModels.first { it.id == "edge_masker" }
    val flareMaskerAvailable = RemasterModelSession.hasModelAsset(context, flareMasker.assetPath)
    val flareRestorerAvailable = RemasterModelSession.hasModelAsset(context, flareRestorer.assetPath)
    val edgeAssetAvailable = RemasterModelSession.hasModelAsset(context, edgeMasker.assetPath)
    NativeToolCard(
        title = "모델 허브",
        description = "모델 파일과 런타임 상태에 따라 사용할 수 있는 기능만 실행합니다."
    ) {
        Text(
            text = "플레어 자동 선택: ${if (flareMaskerAvailable) "사용 가능" else "모델 파일 없음"}",
            color = NativePanelTextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = "AI 번짐 보정: ${if (flareRestorerAvailable) "준비 중" else "모델 파일 없음"}",
            color = NativePanelTextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = "Edge Masker: ${if (edgeAssetAvailable) "사용 가능" else "모델 파일 없음"}",
            color = NativePanelTextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = "Auto Router v0: 분석 전용",
            color = NativePanelTextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "현재 모델은 번짐 영역 감지에 사용됩니다. 자동 복원 모델은 아직 연결되지 않았습니다.",
            color = NativePanelTextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { editorViewModel.runAutoRouterV0Analysis() }, enabled = !state.isBusy) { Text("분석 실행") }
            TextButton(
                onClick = { editorViewModel.applyFlareGuardAiOrRulePreview(context, FlareGuardMode.NightLight) },
                enabled = flareMaskerAvailable && !state.isBusy
            ) { Text("마스크 기반 기본 보정") }
            TextButton(onClick = { editorViewModel.applyFlareOriginalMvp() }, enabled = !state.isBusy) { Text("규칙 기반 번짐 완화") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            TextButton(
                onClick = { editorViewModel.applyFlareGuardAiOrRulePreview(context, FlareGuardMode.DaySun) },
                enabled = flareMaskerAvailable && !state.isBusy
            ) { Text("태양 번짐 마스크 보정") }
            TextButton(onClick = { editorViewModel.applySunFlareOriginalMvp() }, enabled = !state.isBusy) { Text("태양 번짐 규칙 보정") }
        }
    }
}

@Composable
fun NativeProfilesToolPanel(editorViewModel: EditorViewModel = viewModel()) {
    val state by editorViewModel.uiState.collectAsState()
    NativeToolCard(
        title = "룩 엔진",
        description = "내장 프로필입니다. 전용 카메라 프로필은 아직 지원되지 않습니다."
    ) {
        Text(
            "이 프로필은 내장 색감 프리셋입니다.",
            color = NativePanelTextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        BuiltInProfiles.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { profile ->
                    TextButton(
                        onClick = {
                            editorViewModel.applyPresetLook(
                                params = profile.params,
                                look = profile.look,
                                message = "프로필을 적용했습니다."
                            )
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !state.isBusy
                    ) {
                        Text(profile.title)
                    }
                }
                if (rowItems.size == 1) {
                    Text("", modifier = Modifier.weight(1f))
                }
            }
        }
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
        Text(
            description,
            color = NativePanelTextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )
        content()
    }
}
