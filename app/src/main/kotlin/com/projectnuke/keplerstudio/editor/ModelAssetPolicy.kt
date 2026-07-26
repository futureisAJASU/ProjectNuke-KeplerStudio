package com.projectnuke.keplerstudio.editor

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Asset pinning policy.
 *
 * Release strict: every executable model whose asset path is non-blank must
 * carry a manifest SHA-256 that matches the on-disk bytes exactly.
 *
 * Debug-only experimental override:
 * - disabled by default and in release builds
 * - explicitly enabled through [DebugModelPolicy.enableUnpinnedExperimental] only
 * - when enabled, the validator reports [ModelAssetValidation.UnpinnedExperimental]
 *   instead of rejecting an unpinned-but-otherwise-valid asset; it NEVER reports
 *   the asset as production-ready
 * - hashes/sizes are logged, never image contents
 */
internal object ModelAssetPolicy {
    /**
     * Test/manual override of the in-process debug flag. Production wiring should
     * read from [DebugModelPolicy]; never active in release.
     */
    @Volatile
    private var manualUnpinnedExperimental: Boolean? = null

    internal fun allowUnpinnedExperimental(): Boolean {
        manualUnpinnedExperimental?.let { return it }
        return DebugModelPolicy.enableUnpinnedExperimental()
    }

    /** For tests/dev harness only. */
    internal fun setManualUnpinnedExperimental(value: Boolean?) {
        manualUnpinnedExperimental = value
    }
}

/**
 * Debug-only policy seam. Always returns false in release. In debug builds the
 * developer must explicitly enable the experimental override; it is OFF by
 * default. Only id/hash/size are inspected, never image contents.
 */
internal object DebugModelPolicy {
    private val debugBuild: Boolean by lazy {
        try {
            Class.forName("com.projectnuke.keplerstudio.BuildConfig")
                .getField("DEBUG")
                .getBoolean(null)
        } catch (_: Throwable) {
            false
        }
    }

    // Volatile singleton override so a dev session can flip this without recompiling.
    @Volatile
    private var devOverride: Boolean = false

    fun setDevOverride(enabled: Boolean) {
        devOverride = enabled
    }

    fun enableUnpinnedExperimental(): Boolean = debugBuild && devOverride
}

/** Asset summary printed by the dev pinning task. */
internal data class ModelAssetSummary(
    val id: String,
    val assetPath: String,
    val byteSize: Long,
    val sha256: String,
)

/**
 * Reproducible dev helper: open a packaged asset and report id/path/size/hash.
 *
 * Runtime callers MUST go through [ModelAssetValidator] (the single source of
 * truth) so the loader maps exactly the manifest path that was validated. This
 * object only computes the summary for pinning; it does not load the model.
 */
internal object ModelAssetReporter {
    fun summarize(
        entry: ModelAssetManifestEntry,
        open: (String) -> InputStream?,
    ): ModelAssetSummary? {
        if (entry.asset.assetPath.isBlank()) return null
        val stream = open(entry.asset.assetPath) ?: return null
        return stream.use {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                total = Math.addExact(total, read.toLong())
                digest.update(buffer, 0, read)
            }
            ModelAssetSummary(
                id = entry.id,
                assetPath = entry.asset.assetPath,
                byteSize = total,
                sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
            )
        }
    }

    /**
     * Print the manifest manifest in a stable, paste-friendly form for the dev task.
     * Output is hash/size/path/id only; never pixel contents.
     */
    fun reportLines(
        entries: List<ModelAssetManifestEntry>,
        open: (String) -> InputStream?,
    ): List<String> {
        val lines = mutableListOf<String>()
        lines.add("# model asset report — paste sha256 + path into ModelAssetManifest.entries")
        entries.forEach { entry ->
            val summary = summarize(entry, open)
            if (summary == null) {
                lines.add("# ${entry.id}: ${entry.asset.assetPath.ifBlank { "(rule statistics)" }} — no packaged asset")
            } else {
                lines.add(
                    "${entry.id}\tpath=${summary.assetPath}\tsize=${summary.byteSize}\tsha256=${summary.sha256}",
                )
            }
        }
        return lines
    }
}
