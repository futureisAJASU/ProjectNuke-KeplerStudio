package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap

/**
 * On-device preset extraction contract.
 *
 * This is intentionally model-agnostic. A LiteRT/TFLite implementation can later
 * fill this interface with a compact network that predicts EditParams and an
 * optional low-resolution 3D color look from reference or before/after images.
 */
interface NeuralPresetExtractor {
    val name: String
    val isAvailable: Boolean

    suspend fun extractFromReference(reference: Bitmap): NeuralPresetResult

    suspend fun extractFromPair(before: Bitmap, after: Bitmap): NeuralPresetResult
}

data class NeuralPresetResult(
    val params: EditParams,
    val look: PresetColorLook? = null,
    val confidence: Float = 0f,
    val message: String = ""
)

class DisabledNeuralPresetExtractor : NeuralPresetExtractor {
    override val name: String = "모델 기반 프리셋 추출 준비 중"
    override val isAvailable: Boolean = false

    override suspend fun extractFromReference(reference: Bitmap): NeuralPresetResult = unavailable()

    override suspend fun extractFromPair(before: Bitmap, after: Bitmap): NeuralPresetResult = unavailable()

    private fun unavailable(): NeuralPresetResult = NeuralPresetResult(
        params = EditParams(),
        look = null,
        confidence = 0f,
        message = "모델 파일이 아직 연결되지 않았습니다"
    )
}
