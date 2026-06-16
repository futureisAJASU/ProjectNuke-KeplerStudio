package com.projectnuke.keplerstudio.editor

enum class ExportFormat(
    val label: String,
    val extension: String,
    val mimeType: String
) {
    Jpeg("JPEG", "jpg", "image/jpeg"),
    Png("PNG", "png", "image/png"),
    Webp("WebP", "webp", "image/webp"),
    Heif("HEIF", "heic", "image/heic")
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

enum class ExportHistoryRetention(
    val label: String,
    val days: Int?
) {
    Never("자동 정리 안 함", null),
    Days7("7일", 7),
    Days30("30일", 30),
    Days90("90일", 90)
}

data class SavedExport(
    val displayName: String,
    val uriString: String,
    val formatLabel: String,
    val resolutionLabel: String,
    val timestampMillis: Long
)
