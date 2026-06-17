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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val RemasterCardBackground = Color(0xFF242424)
private val RemasterAccent = Color(0xFFE6E6E6)
private val RemasterTextPrimary = Color(0xFFF2F2F2)
private val RemasterTextSecondary = Color(0xFFC8C8C8)
private val RemasterTextMuted = Color(0xFF8E8E8E)
private val RemasterButtonTextDark = Color(0xFF111111)

@Composable
fun RemasterToolPanel(
    onQuickAutoEnhance: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "사진을 분석해 자동으로 보정합니다. 빠른 자동 보정은 즉시 사용할 수 있으며, 모델 기반 리마스터는 온디바이스 추론 엔진 연결 후 활성화됩니다",
            color = RemasterTextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(RemasterCardBackground)
                .padding(12.dp)
        ) {
            Text("빠른 자동 보정", color = RemasterTextPrimary, fontWeight = FontWeight.SemiBold)
            Text(
                "히스토그램과 색상 통계를 분석해 노출, 대비, 하이라이트, 섀도우, 색감, 디테일 값을 자동으로 조정합니다",
                color = RemasterTextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            Button(
                onClick = onQuickAutoEnhance,
                colors = ButtonDefaults.buttonColors(containerColor = RemasterAccent, contentColor = RemasterButtonTextDark)
            ) {
                Text("빠른 자동 보정 적용")
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .background(RemasterCardBackground)
                .padding(12.dp)
        ) {
            Text("모델 기반 리마스터", color = RemasterTextPrimary, fontWeight = FontWeight.SemiBold)
            Text(
                "마스크를 따고 영역별로 색감, 노이즈, 디테일을 다르게 보정하는 온디바이스 리마스터 모드입니다",
                color = RemasterTextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { }, enabled = false) {
                    Text("균형 리마스터")
                }
                TextButton(onClick = { }, enabled = false) {
                    Text("인물 보호")
                }
                TextButton(onClick = { }, enabled = false) {
                    Text("야간 복원")
                }
            }
            Text(
                text = "모델 파일과 LiteRT/TFLite 런타임이 연결되면 활성화됩니다",
                color = RemasterTextMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Text(
            text = "온디바이스 후보",
            color = RemasterTextPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
        )
        OnDeviceRemasterModels.forEach { model ->
            RemasterModelCard(model)
        }
    }
}

@Composable
private fun RemasterModelCard(model: RemasterModelCandidate) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(RemasterCardBackground)
            .padding(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(model.title, color = RemasterTextPrimary, fontWeight = FontWeight.SemiBold)
                Text(model.role, color = RemasterTextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Text(model.status, color = RemasterTextMuted, style = MaterialTheme.typography.bodySmall)
        }
        Text("성격: ${model.personality}", color = RemasterTextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        Text("특징: ${model.strengths}", color = RemasterTextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
        Text("비용: ${model.cost}", color = RemasterTextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
    }
}
