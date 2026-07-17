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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.FlareGuardMode

private val RemasterCardBackground = Color(0xFF242424)
private val RemasterAccent = Color(0xFFE6E6E6)
private val RemasterTextPrimary = Color(0xFFF2F2F2)
private val RemasterTextSecondary = Color(0xFFC8C8C8)
private val RemasterTextMuted = Color(0xFF8E8E8E)
private val RemasterButtonTextDark = Color(0xFF111111)

@Composable
fun RemasterToolPanel(
    onQuickAutoEnhance: () -> Unit,
    editorViewModel: EditorViewModel = viewModel()
) {
    val context = LocalContext.current
    val editorState by editorViewModel.uiState.collectAsState()
    val activeModel = RemasterModelSession.activeModel
    val loaded = RemasterModelSession.isModelLoaded
    val flareMasker = OnDeviceRemasterModels.first { it.id == "flare_masker" }
    val flareRestorer = OnDeviceRemasterModels.first { it.id == "flare_restorer" }
    val edgeMasker = OnDeviceRemasterModels.first { it.id == "edge_masker" }
    val autoRouter = OnDeviceRemasterModels.first { it.id == "universal_auto_router" }
    val flareMaskerAvailable = RemasterModelSession.hasModelAsset(context, flareMasker.assetPath)
    val flareRestorerAvailable = RemasterModelSession.hasModelAsset(context, flareRestorer.assetPath)
    val edgeAssetAvailable = RemasterModelSession.hasModelAsset(context, edgeMasker.assetPath)
    val edgeLoaded = loaded && activeModel?.id == "edge_masker"
    val hasImage = editorState.previewBitmap != null || editorState.originalPreviewBitmap != null

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "모델 파일과 런타임 상태에 따라 사용할 수 있는 기능만 실행합니다.",
            color = RemasterTextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        ModelHubCard(
            title = "기본 자동 보정",
            status = "규칙 기반 보정",
            explanation = "히스토그램과 색상 통계를 사용해 기본 보정을 적용합니다."
        ) {
            Button(
                onClick = onQuickAutoEnhance,
                enabled = hasImage && !editorState.isBusy,
                colors = ButtonDefaults.buttonColors(containerColor = RemasterAccent, contentColor = RemasterButtonTextDark)
            ) {
                Text("기본 자동 보정 적용")
            }
        }

        Text(
            text = "모델 허브",
            color = RemasterTextPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
        )

        ModelHubCard(
            title = "플레어 자동 선택",
            status = if (flareMaskerAvailable) "사용 가능" else "모델 파일 없음",
            explanation = if (flareMaskerAvailable) {
                "현재 모델은 번짐 영역 감지에 사용됩니다. 자동 복원 모델은 아닙니다."
            } else {
                "모델 파일이 없어 규칙 기반 보정으로 대체했습니다."
            }
        ) {
            Text(
                text = editorState.flareGuardRuntimeStatus ?: "마지막 실행 상태가 없습니다.",
                color = RemasterTextMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { editorViewModel.applyFlareGuardAiOrRulePreview(context, FlareGuardMode.NightLight) },
                    enabled = hasImage && flareMaskerAvailable && !editorState.isBusy
                ) {
                    Text("마스크 기반 기본 보정")
                }
                TextButton(
                    onClick = { editorViewModel.applyFlareOriginalMvp() },
                    enabled = hasImage && !editorState.isBusy
                ) {
                    Text("규칙 기반 번짐 완화")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { editorViewModel.applyFlareGuardAiOrRulePreview(context, FlareGuardMode.DaySun) },
                    enabled = hasImage && flareMaskerAvailable && !editorState.isBusy
                ) {
                    Text("태양 번짐 마스크 보정")
                }
                TextButton(
                    onClick = { editorViewModel.applySunFlareOriginalMvp() },
                    enabled = hasImage && !editorState.isBusy
                ) {
                    Text("태양 번짐 규칙 보정")
                }
            }
        }

        ModelHubCard(
            title = "AI 번짐 보정",
            status = if (flareRestorerAvailable) "준비 중" else "모델 파일 없음",
            explanation = if (flareRestorerAvailable) {
                "플레어 복원 모델 파일이 감지되었습니다. 실행 경로 연결은 별도 단계에서 진행합니다."
            } else {
                "자동 복원 모델은 아직 연결되지 않았습니다."
            }
        ) {
            Text(
                text = "플레어 복원 모델은 향후 실제 복원 모델 자산이 있을 때만 활성화됩니다.",
                color = RemasterTextMuted,
                style = MaterialTheme.typography.bodySmall
            )
        }

        ModelHubCard(
            title = edgeMasker.title,
            status = when {
                edgeLoaded -> "사용 가능"
                edgeAssetAvailable -> "준비 중"
                else -> "모델 파일 없음"
            },
            explanation = if (edgeAssetAvailable) {
                "모델 마스크 보조를 사용할 수 있도록 런타임을 로드합니다."
            } else {
                "이 기능은 모델 파일이 있을 때만 사용할 수 있습니다."
            }
        ) {
            Text(
                text = RemasterModelSession.statusText,
                color = if (edgeLoaded) RemasterTextPrimary else RemasterTextMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { RemasterModelSession.load(context, edgeMasker) },
                    enabled = edgeAssetAvailable && !editorState.isBusy && !RemasterModelSession.isModelLoading && !RemasterModelSession.isInferring
                ) {
                    Text(if (edgeLoaded) "다시 로드" else "Edge Masker 로드")
                }
                TextButton(
                    onClick = { RemasterModelSession.unload() },
                    enabled = activeModel != null && !editorState.isBusy && !RemasterModelSession.isModelLoading && !RemasterModelSession.isInferring
                ) {
                    Text("모델 해제")
                }
            }
            Button(
                onClick = { editorViewModel.applyMaskAwareRemaster() },
                enabled = hasImage && edgeLoaded && !editorState.isBusy && !RemasterModelSession.isModelLoading && !RemasterModelSession.isInferring,
                colors = ButtonDefaults.buttonColors(containerColor = RemasterAccent, contentColor = RemasterButtonTextDark),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                Text("모델 마스크 보조 적용")
            }
        }

        ModelHubCard(
            title = autoRouter.title,
            status = "분석 전용",
            explanation = "자동 라우터는 현재 분석 전용입니다. 추천만 표시하고 자동 적용하지 않았습니다."
        ) {
            TextButton(onClick = { editorViewModel.runAutoRouterV0Analysis() }, enabled = hasImage && !editorState.isBusy) {
                Text("분석 실행")
            }
        }

        Text(
            text = "마스크 편집",
            color = RemasterTextPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
        )
        MaskingToolPanel(editorViewModel)
    }
}

@Composable
private fun ModelHubCard(
    title: String,
    status: String,
    explanation: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(RemasterCardBackground)
            .padding(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = RemasterTextPrimary, fontWeight = FontWeight.SemiBold)
            Text(status, color = RemasterTextMuted, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = explanation,
            color = RemasterTextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )
        content()
    }
}
