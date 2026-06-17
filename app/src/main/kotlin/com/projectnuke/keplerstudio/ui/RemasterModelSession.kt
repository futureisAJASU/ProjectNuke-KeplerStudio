package com.projectnuke.keplerstudio.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter

object RemasterModelSession {
    var activeModel by mutableStateOf<RemasterModelCandidate?>(null)
        private set

    var statusText by mutableStateOf("로드된 항목이 없습니다")
        private set

    var isModelLoaded by mutableStateOf(false)
        private set

    private var closeableModel: AutoCloseable? = null

    fun load(context: Context, candidate: RemasterModelCandidate) {
        unload()
        activeModel = candidate
        val exists = hasAsset(context, candidate.assetPath)
        if (!exists) {
            isModelLoaded = false
            statusText = "${candidate.title} 슬롯을 선택했습니다. ${candidate.assetPath} 파일을 추가하면 활성화됩니다"
            return
        }

        runCatching {
            closeableModel = when (candidate.id) {
                "edge_masker" -> createImageSegmenter(context, candidate.assetPath)
                else -> null
            }
        }.onSuccess {
            isModelLoaded = true
            statusText = if (closeableModel != null) {
                "${candidate.title} 모델을 로드했습니다"
            } else {
                "${candidate.title} 파일을 확인했습니다. 실제 추론 연결은 준비 중입니다"
            }
        }.onFailure {
            closeableModel = null
            isModelLoaded = false
            statusText = "${candidate.title} 모델 로드에 실패했습니다: ${it.message}"
        }
    }

    fun unload() {
        runCatching { closeableModel?.close() }
        closeableModel = null
        activeModel = null
        isModelLoaded = false
        statusText = "로드된 항목을 해제했습니다"
    }

    private fun createImageSegmenter(context: Context, assetPath: String): ImageSegmenter {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(assetPath)
            .build()
        val options = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setOutputCategoryMask(true)
            .setOutputConfidenceMasks(false)
            .build()
        return ImageSegmenter.createFromOptions(context, options)
    }

    private fun hasAsset(context: Context, assetPath: String): Boolean = runCatching {
        context.assets.open(assetPath).use { true }
    }.getOrDefault(false)
}
