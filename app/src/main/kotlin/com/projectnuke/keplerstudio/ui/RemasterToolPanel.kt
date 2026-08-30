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
import com.projectnuke.keplerstudio.editor.ModelRunnerLifecycle

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
    val modelCapability by com.projectnuke.keplerstudio.editor.ModelAvailabilityRegistry.state.collectAsState()
    val activeModel = RemasterModelSession.activeModel
    val flareMasker = OnDeviceRemasterModels.first { it.id == "flare_masker" }
    val flareRestorer = OnDeviceRemasterModels.first { it.id == "flare_restorer" }
    val edgeMasker = OnDeviceRemasterModels.first { it.id == "edge_masker" }
    val autoRouter = OnDeviceRemasterModels.first { it.id == "universal_auto_router" }
    val flareGuardCapability = modelCapability[com.projectnuke.keplerstudio.editor.ModelFeature.FlareGuard]
    val remasterCapability = modelCapability[com.projectnuke.keplerstudio.editor.ModelFeature.Remaster]
    val flareMaskerAvailable = flareMasker.canExecuteFromRegistry(flareGuardCapability)
    val flareRestorerAvailable = flareRestorer.canExecuteFromRegistry(null)
    val edgeAssetAvailable = edgeMasker.canExecuteFromRegistry(remasterCapability)
    val flareMaskerStatusLabel = flareMasker.registryStatus(flareGuardCapability)
    val flareRestorerStatusLabel = flareRestorer.registryStatus(null)
    val edgeMaskerStatusLabel = edgeMasker.registryStatus(remasterCapability)
    val edgeLoaded = remasterCapability?.sessionReady == true
    val edgeAttemptable = remasterCapability?.canAttemptModelUse == true
    val edgeLoadingOrInferring =
        RemasterModelSession.isModelLoading ||
            RemasterModelSession.isInferring ||
            RemasterModelSession.lifecycle in
                setOf(ModelRunnerLifecycle.Loading, ModelRunnerLifecycle.Closing)
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
            status = flareMaskerStatusLabel,
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
            status = flareRestorerStatusLabel,
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
            status = edgeMaskerStatusLabel,
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
                enabled = hasImage && edgeAttemptable && !editorState.isBusy && !edgeLoadingOrInferring,
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

        // N6 — AI 4배 고해상도 (Exynos NPU Super Resolution)
        val exynosCapability = modelCapability[com.projectnuke.keplerstudio.editor.ModelFeature.ExynosUpscale]
        val exynosStatus = when {
            exynosCapability == null -> "상태 확인 중"
            exynosCapability.canAttemptModelUse -> "사용 가능"
            exynosCapability.sessionReady -> "준비됨"
            else -> exynosCapability.phase.name
        }
        val srStatus by editorViewModel.superResolutionStatus.collectAsState()
        val srBusy = srStatus.isBusy
        val canStartSr = hasImage && !editorState.isBusy && !srBusy && exynosCapability?.canAttemptModelUse == true
        ModelHubCard(
            title = "AI 4배 고해상도",
            status = exynosStatus,
            explanation = "Exynos NPU에서 현재 편집 결과를 4배 확대해 PNG로 저장합니다. 4배 고해상도는 현재 PNG로 저장됩니다."
        ) {
            if (srBusy) {
                val p = srStatus.progress
                Text(
                    text = when (p.phase) {
                        com.projectnuke.keplerstudio.editor.SuperResolutionExportPhase.Preparing -> "준비 중…"
                        com.projectnuke.keplerstudio.editor.SuperResolutionExportPhase.Upscaling -> "AI 확대 중 · ${p.completedTiles} / ${p.totalTiles} 타일"
                        com.projectnuke.keplerstudio.editor.SuperResolutionExportPhase.Encoding -> "PNG 저장 중 · ${p.encodingRowsCompleted} / ${p.encodingRowsTotal}행"
                        com.projectnuke.keplerstudio.editor.SuperResolutionExportPhase.Publishing -> "저장소 게시 중…"
                        else -> p.message.ifBlank { "처리 중…" }
                    },
                    color = RemasterTextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                if (p.inputWidth > 0) {
                    Text(
                        text = "${p.inputWidth}×${p.inputHeight} → ${p.outputWidth}×${p.outputHeight}",
                        color = RemasterTextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                // Progress as overallFraction
                Text(
                    text = "진행률 ${(p.overallFraction * 100).toInt()}%",
                    color = RemasterTextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                TextButton(onClick = { editorViewModel.cancelSuperResolution() }, enabled = p.canCancel) {
                    Text("취소")
                }
            } else {
                Text(
                    text = when (srStatus.phase) {
                        com.projectnuke.keplerstudio.editor.SuperResolutionExportPhase.Succeeded -> srStatus.progress.message.ifBlank { "저장 완료" }
                        com.projectnuke.keplerstudio.editor.SuperResolutionExportPhase.Failed -> srStatus.failureMessage ?: "실패"
                        com.projectnuke.keplerstudio.editor.SuperResolutionExportPhase.Cancelled -> "취소됨"
                        else -> "현재 편집 결과를 4배로 확대합니다."
                    },
                    color = RemasterTextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Button(
                    onClick = { editorViewModel.exportSuperResolution() },
                    enabled = canStartSr,
                    colors = ButtonDefaults.buttonColors(containerColor = RemasterAccent, contentColor = RemasterButtonTextDark)
                ) {
                    Text("AI 4배 PNG 저장")
                }
                if (exynosCapability?.canAttemptModelUse != true) {
                    Text(
                        text = "모델을 사용할 수 없습니다. NNC 상태를 확인해 주세요.",
                        color = RemasterTextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
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
