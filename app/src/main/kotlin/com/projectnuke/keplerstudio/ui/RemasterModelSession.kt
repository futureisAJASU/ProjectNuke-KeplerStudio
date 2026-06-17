package com.projectnuke.keplerstudio.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object RemasterModelSession {
    var activeModel by mutableStateOf<RemasterModelCandidate?>(null)
        private set

    var statusText by mutableStateOf("로드된 항목이 없습니다")
        private set

    var isModelLoaded by mutableStateOf(false)
        private set

    fun load(context: Context, candidate: RemasterModelCandidate) {
        unload()
        activeModel = candidate
        val exists = hasAsset(context, candidate.assetPath)
        isModelLoaded = exists
        statusText = if (exists) {
            "${candidate.title} 항목을 로드했습니다"
        } else {
            "${candidate.title} 슬롯을 선택했습니다. ${candidate.assetPath} 파일을 추가하면 활성화됩니다"
        }
    }

    fun unload() {
        activeModel = null
        isModelLoaded = false
        statusText = "로드된 항목을 해제했습니다"
    }

    private fun hasAsset(context: Context, assetPath: String): Boolean = runCatching {
        context.assets.open(assetPath).use { true }
    }.getOrDefault(false)
}
