package com.projectnuke.keplerstudio.editor

enum class ExportFormat(
    val label: String,
    val extension: String,
    val mimeType: String
) {
    Jpeg("JPEG", "jpg", "image/jpeg"),
    Png("PNG", "png", "image/png"),
    Webp("WebP", "webp", "image/webp")
}

enum class ExportResolution(
    val label: String,
    val maxLongEdge: Int?
) {
    Preview("현재 미리보기", null),
    LongEdge1080("긴 변 1080px", 1080),
    LongEdge2048("긴 변 2048px", 2048),
    LongEdge4096("긴 변 4096px", 4096)
}

data class SavedExport(
    val displayName: String,
    val uriString: String,
    val formatLabel: String,
    val resolutionLabel: String,
    val timestampMillis: Long
)
