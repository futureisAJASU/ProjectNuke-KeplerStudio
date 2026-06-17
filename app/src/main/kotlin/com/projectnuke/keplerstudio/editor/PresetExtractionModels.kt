package com.projectnuke.keplerstudio.editor

data class PresetExtractionBundle(
    val params: EditParams,
    val look: PresetColorLook? = null,
    val origin: String = "manual",
    val confidence: Float = 0f,
    val note: String = ""
)
