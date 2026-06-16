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
    val scalePercent: Int
) {
    Full("100%", 100),
    Percent90("90%", 90),
    Percent75("75%", 75),
    Percent50("50%", 50),
    Percent25("25%", 25)
}

data class SavedExport(
    val displayName: String,
    val uriString: String,
    val formatLabel: String,
    val resolutionLabel: String,
    val timestampMillis: Long
)
