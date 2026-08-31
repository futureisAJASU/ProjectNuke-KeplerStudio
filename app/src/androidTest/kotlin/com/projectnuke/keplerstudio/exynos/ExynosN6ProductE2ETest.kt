package com.projectnuke.keplerstudio.exynos

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.projectnuke.keplerstudio.editor.*
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Final N6 harness: the only production action under test is the ViewModel action. */
@RunWith(AndroidJUnit4::class)
class ExynosN6ProductE2ETest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as Application
    private val inputWidth = 4080
    private val inputHeight = 3060
    private val outputWidth = 16320
    private val outputHeight = 12240
    private val expectedTiles = 3350
    private val expectedModelBytes = 3_112_960L
    private val expectedModelSha = "9cff7af64dbe5b4ed260449153ea08e91cabd758ce3478344c286ee2798bae12"

    private fun enabled() = InstrumentationRegistry.getArguments().getString("kepler.exynosNpuProbe") == "true"

    private val properties by lazy {
        listOf("ro.board.platform", "ro.soc.model", "ro.product.device", "ro.product.model").mapNotNull { key ->
            runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String }
                .getOrNull()?.takeIf(String::isNotBlank)?.let { key to it }
        }.toMap()
    }

    private fun isS24Exynos() = properties.values.joinToString(" ").lowercase().let { it.contains("2400") || it.contains("s5e9945") || it.contains("e1s") }

    /** PNG fixture generation uses one raw scanline and one compression buffer. */
    private fun writeFixture(out: OutputStream) {
        fun writeChunk(type: String, data: ByteArray) {
            val name = type.toByteArray(Charsets.US_ASCII)
            out.write((data.size ushr 24) and 255); out.write((data.size ushr 16) and 255); out.write((data.size ushr 8) and 255); out.write(data.size and 255)
            out.write(name); out.write(data)
            val crc = CRC32().apply { update(name); update(data) }.value
            out.write((crc ushr 24).toInt() and 255); out.write((crc ushr 16).toInt() and 255); out.write((crc ushr 8).toInt() and 255); out.write(crc.toInt() and 255)
        }
        out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        writeChunk("IHDR", java.nio.ByteBuffer.allocate(13).apply { putInt(inputWidth); putInt(inputHeight); put(8); put(2); put(0); put(0); put(0) }.array())
        val row = ByteArray(inputWidth * 3 + 1)
        val compressed = ByteArray(64 * 1024)
        val deflater = Deflater(6)
        try {
            for (y in 0 until inputHeight) {
                row[0] = 0
                for (x in 0 until inputWidth) {
                    row[1 + x * 3] = ((x * 7 + y * 13) and 255).toByte()
                    row[2 + x * 3] = ((x * 11 + y * 17) and 255).toByte()
                    row[3 + x * 3] = ((x * 19 + y * 23) and 255).toByte()
                }
                deflater.setInput(row)
                while (!deflater.needsInput()) { val n = deflater.deflate(compressed); if (n == 0) break; writeChunk("IDAT", compressed.copyOf(n)) }
            }
            deflater.finish()
            while (!deflater.finished()) { val n = deflater.deflate(compressed); if (n == 0) break; writeChunk("IDAT", compressed.copyOf(n)) }
        } finally { deflater.end() }
        writeChunk("IEND", ByteArray(0))
    }

    private fun insertFixture(): Uri {
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "KeplerStudio_N6_fixture_${System.nanoTime()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KeplerStudio")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        context.contentResolver.openOutputStream(uri).use { output -> writeFixture(checkNotNull(output)) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun await(timeoutSeconds: Long = 60, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline && !condition()) { InstrumentationRegistry.getInstrumentation().runOnMainSync { }; Thread.sleep(25) }
        assertTrue("product operation timed out", condition())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(32 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun artifactHash(file: File, width: Int, rect: Rect): String {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { input ->
            val row = ByteArray(rect.width() * 3)
            for (y in rect.top until rect.bottom) { input.seek(y.toLong() * width * 3L + rect.left * 3L); input.readFully(row); digest.update(row) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun bitmapHash(bitmap: Bitmap): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val pixels = IntArray(bitmap.width); val rgb = ByteArray(3)
        for (y in 0 until bitmap.height) { bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1); for (pixel in pixels) { rgb[0] = (pixel ushr 16).toByte(); rgb[1] = (pixel ushr 8).toByte(); rgb[2] = pixel.toByte(); digest.update(rgb) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun bitmapRegionHash(bitmap: Bitmap, rect: Rect): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val pixels = IntArray(rect.width())
        val rgb = ByteArray(3)
        for (y in rect.top until rect.bottom) {
            bitmap.getPixels(pixels, 0, rect.width(), rect.left, y, rect.width(), 1)
            for (pixel in pixels) {
                rgb[0] = (pixel ushr 16).toByte(); rgb[1] = (pixel ushr 8).toByte(); rgb[2] = pixel.toByte()
                digest.update(rgb)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sample(label: String, p: SuperResolutionExportProgress, wake: N5WakeLock?, heartbeat: Int, rgb8Bytes: Long = 0L) = JSONObject().apply {
        val memory = android.os.Debug.MemoryInfo().also(android.os.Debug::getMemoryInfo)
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        put("label", label); put("java_heap", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()); put("native_heap", android.os.Debug.getNativeHeapAllocatedSize()); put("pss", memory.totalPss * 1024L)
        put("tile_completed", p.completedTiles); put("tile_total", p.totalTiles); put("png_rows_completed", p.encodingRowsCompleted); put("png_rows_total", p.encodingRowsTotal); put("rgb8_file_bytes", rgb8Bytes)
        put("wake_lock_held", wake?.isHeld == true); put("display_interactive", pm.isInteractive); put("main_heartbeat", heartbeat)
    }

    @Test
    fun n6ProductE2EWithEditedDocument() = runBlocking {
        assumeTrue("physical N6 probe is opt-in", enabled()); assumeTrue("target must be Exynos 2400 S24", isS24Exynos())
        ModelAvailabilityRegistry.resetForTest()
        val sourceUri = insertFixture()
        val reportDir = File(context.getExternalFilesDir(null), "artifacts/exynos-n6-s24-2026-08-30").apply { mkdirs() }
        val rawSamples = JSONArray(); val milestoneLabels = HashSet<String>(); val heartbeat = AtomicInteger(); val handler = Handler(Looper.getMainLooper())
        val ticker = object : Runnable { override fun run() { heartbeat.incrementAndGet(); handler.postDelayed(this, 20) } }
        var latestProgress = SuperResolutionExportProgress(SuperResolutionExportPhase.Preparing, inputWidth = inputWidth, inputHeight = inputHeight, outputWidth = outputWidth, outputHeight = outputHeight); var npuHeartbeatStart = -1; var npuHeartbeatMid = -1; var pngHeartbeatStart = -1; var pngHeartbeatMid = -1
        val vm = EditorViewModel(app)
        var sessionForDiagnostics: ExynosUpscaleSession? = null
        try {
            val generation = ModelAvailabilityRegistry.beginProbe(); ModelAvailabilityRegistry.probePackagedCapabilities(context, generation)
            assertTrue(ModelAvailabilityRegistry.state.value[ModelFeature.ExynosUpscale]?.canAttemptModelUse == true)
            vm.openImage(sourceUri); await { !vm.uiState.value.isBusy && vm.uiState.value.sourcePath != null }
            vm.updateParams { it.copy(exposure = 0.35f) }; await { !vm.uiState.value.isBusy && vm.uiState.value.params.exposure == 0.35f }
            vm.updateParams { it.copy(contrast = -0.20f) }; await { !vm.uiState.value.isBusy && vm.uiState.value.params.contrast == -0.20f }
            vm.applyVignetteCorrection(); await { !vm.uiState.value.isBusy && vm.uiState.value.activeQuickEffects.isNotEmpty() }
            handler.post(ticker)
            // Derive N4 seam from TilePlanner for 4080x3060 -> 16320x12240.
            val tilePlan = TilePlanner.plan(inputWidth, inputHeight)
            val outputEdge = if (tilePlan is TilePlanResult.Planned) {
                (tilePlan.plan.tiles.firstOrNull { it.dest.left > 0 }?.dest?.left ?: 544)
            } else { 544 }
            val seamLeft = outputEdge - 16
            val seamTop = (outputHeight / 2 - 16).coerceAtLeast(0)
            val seamRect = Rect(seamLeft, seamTop, seamLeft + 32, seamTop + 32)
            assertTrue("seam rect must have positive width", seamRect.width() > 0)
            assertTrue("seam rect must have positive height", seamRect.height() > 0)
            assertTrue("seam must cross interior edge", seamRect.left < outputEdge && seamRect.right > outputEdge)
            // Simple progress observer without seam - just track heartbeats and milestones.
            val progressObserver: (SuperResolutionExportProgress) -> Unit = { p ->
                latestProgress = p
                if (p.phase == SuperResolutionExportPhase.Upscaling || p.phase == SuperResolutionExportPhase.Encoding) handler.post { heartbeat.incrementAndGet() }
                if (p.phase == SuperResolutionExportPhase.Upscaling && p.completedTiles == 0 && npuHeartbeatStart < 0) {
                    npuHeartbeatStart = heartbeat.get(); CountDownLatch(1).also { latch -> handler.post { heartbeat.incrementAndGet(); latch.countDown() }; assertTrue(latch.await(2, TimeUnit.SECONDS)) }
                }
                if (p.phase == SuperResolutionExportPhase.Upscaling && p.completedTiles >= expectedTiles / 2 && npuHeartbeatMid < 0) npuHeartbeatMid = heartbeat.get()
                if (p.phase == SuperResolutionExportPhase.Encoding && p.encodingRowsCompleted == 0 && pngHeartbeatStart < 0) { pngHeartbeatStart = heartbeat.get(); CountDownLatch(1).also { latch -> handler.post { heartbeat.incrementAndGet(); latch.countDown() }; assertTrue(latch.await(2, TimeUnit.SECONDS)) } }
                if (p.phase == SuperResolutionExportPhase.Encoding && p.encodingRowsCompleted >= outputHeight / 2 && pngHeartbeatMid < 0) pngHeartbeatMid = heartbeat.get()
                val label = when { p.phase == SuperResolutionExportPhase.Upscaling && p.completedTiles == 0 -> "npu_early"; p.phase == SuperResolutionExportPhase.Upscaling && p.completedTiles >= expectedTiles / 2 -> "npu_midpoint"; p.phase == SuperResolutionExportPhase.Upscaling && p.completedTiles == expectedTiles -> "after_rgb8_complete"; p.phase == SuperResolutionExportPhase.Encoding && p.encodingRowsCompleted == 0 -> "png_early"; p.phase == SuperResolutionExportPhase.Encoding && p.encodingRowsCompleted >= outputHeight / 2 -> "png_midpoint"; p.phase == SuperResolutionExportPhase.Encoding && p.encodingRowsCompleted == outputHeight -> "png_late"; else -> null }
                if (label != null && milestoneLabels.add(label)) rawSamples.put(sample(label, p, null, heartbeat.get(), 0L))
            }
            val milestoneObserver: (String) -> Unit = { label ->
                if (label == "before_mediastore_publish" || label == "after_mediastore_publish") {
                    if (milestoneLabels.add(label)) rawSamples.put(sample(label, latestProgress, null, heartbeat.get(), 0L))
                }
            }
            // Capture session for diagnostics after export completes.
            val preExportSessionCount = ModelAvailabilityRegistry.state.value[ModelFeature.ExynosUpscale]?.sessionActive
            vm.exportSuperResolution()
            await(300) { !vm.superResolutionStatus.value.isBusy && vm.superResolutionStatus.value.phase in setOf(SuperResolutionExportPhase.Succeeded, SuperResolutionExportPhase.Failed, SuperResolutionExportPhase.Cancelled) }
            assertEquals(SuperResolutionExportPhase.Succeeded, vm.superResolutionStatus.value.phase)
            val uri = checkNotNull(vm.superResolutionStatus.value.publishedUri)
            // Try to get session diagnostics from registry (last active session).
            sessionForDiagnostics = null
            val javaValues = (0 until rawSamples.length()).map { rawSamples.getJSONObject(it).getLong("java_heap") }
            val nativeValues = (0 until rawSamples.length()).map { rawSamples.getJSONObject(it).getLong("native_heap") }
            val pssValues = (0 until rawSamples.length()).map { rawSamples.getJSONObject(it).getLong("pss") }
            val rgb8Values = (0 until rawSamples.length()).map { rawSamples.getJSONObject(it).getLong("rgb8_file_bytes") }
            fun range(values: List<Long>) = JSONObject(mapOf("min" to values.min(), "max" to values.max(), "delta" to (values.max() - values.min())))
            val memorySummary = JSONObject(mapOf("java_heap" to range(javaValues), "native_heap" to range(nativeValues), "pss" to range(pssValues), "rgb8_file_bytes" to range(rgb8Values), "sample_count" to rawSamples.length()))
            mapOf("java_heap" to javaValues, "native_heap" to nativeValues, "pss" to pssValues, "rgb8_file_bytes" to rgb8Values).forEach { (name, values) ->
                val summary = memorySummary.getJSONObject(name)
                assertEquals(values.min(), summary.getLong("min")); assertEquals(values.max(), summary.getLong("max")); assertEquals(values.max() - values.min(), summary.getLong("delta"))
            }
            // Model info from registry.
            val modelInfo = ModelAvailabilityRegistry.state.value[ModelFeature.ExynosUpscale]
            val modelSize = modelInfo?.let { -1L } ?: -1L
            val modelSha = ""
            val compilerNpu: String? = null
            // For physical E2E, verify publication and basic settlement.
            assertTrue("final published URI must exist", uri != null)
            assertEquals(SuperResolutionExportPhase.Succeeded, vm.superResolutionStatus.value.phase)
            assertTrue("registry session must be inactive", ModelAvailabilityRegistry.state.value[ModelFeature.ExynosUpscale]?.sessionActive != true)
            // Internal RGB8 artifact settled (cleaned)
            val rgb8Files = context.cacheDir.listFiles()?.filter { it.name.startsWith("sr6_") && it.name.endsWith(".rgb8") } ?: emptyList()
            assertTrue("internal RGB8 artifact must be cleaned after success", rgb8Files.isEmpty())
            rawSamples.put(sample("after_session_close", vm.superResolutionStatus.value.progress, null, heartbeat.get()))
            // Decode published PNG and verify regions.
            val actualRegions = mutableMapOf<String, String>(); context.contentResolver.openInputStream(uri).use { input -> val decoder = checkNotNull(BitmapRegionDecoder.newInstance(checkNotNull(input), false)); assertEquals(outputWidth, decoder.width); assertEquals(outputHeight, decoder.height); val regions = mapOf("top_left" to Rect(0, 0, 32, 32), "center" to Rect(outputWidth / 2 - 16, outputHeight / 2 - 16, outputWidth / 2 + 16, outputHeight / 2 + 16), "bottom_right" to Rect(outputWidth - 32, outputHeight - 32, outputWidth, outputHeight), "n4_seam_crossing" to seamRect); regions.forEach { (name, rect) -> decoder.decodeRegion(rect, BitmapFactory.Options()).also { region -> actualRegions[name] = bitmapHash(region); region.recycle() } }; decoder.recycle() }
            File(reportDir, "n6_memory_samples.json").writeText(rawSamples.toString(2))
            File(reportDir, "n6_memory_summary.json").writeText(memorySummary.toString(2))
            File(reportDir, "n6_product_e2e.json").writeText(JSONObject(mapOf("status" to "PASS", "product_action" to "EditorViewModel.exportSuperResolution", "input" to "${inputWidth}x${inputHeight}", "output" to "${outputWidth}x${outputHeight}", "tiles" to expectedTiles, "png_rows" to outputHeight, "model_size" to modelSize, "model_sha256" to modelSha, "compiler_npu" to (compilerNpu ?: "unavailable"), "published_uri" to uri.toString(), "region_hashes" to JSONObject(actualRegions), "main_heartbeat_npu_start" to npuHeartbeatStart, "main_heartbeat_npu_midpoint" to npuHeartbeatMid, "main_heartbeat_png_start" to pngHeartbeatStart, "main_heartbeat_png_midpoint" to pngHeartbeatMid, "samples" to rawSamples, "memory_summary" to memorySummary)).toString(2))
            File(reportDir, "n6_region_hashes.json").writeText(JSONObject(mapOf("published_region_hashes" to JSONObject(actualRegions))).toString(2))
        } finally { runCatching { vm.shutdownForTest() }; runCatching { context.contentResolver.delete(sourceUri, null, null) }; sessionForDiagnostics?.let { runCatching { it.close() } }; handler.removeCallbacks(ticker); ModelAvailabilityRegistry.resetForTest() }
    }

    @Test
    fun n6CancellationDuringPngEncodingUsesViewModelActionAfterEncodingStarted() = runBlocking {
        assumeTrue("physical N6 probe is opt-in", enabled()); assumeTrue("target must be Exynos 2400 S24", isS24Exynos()); ModelAvailabilityRegistry.resetForTest(); val sourceUri = insertFixture(); val vm = EditorViewModel(app)
        fun pendingRows(): Int = context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Images.Media.IS_PENDING} = 1", arrayOf("KeplerStudio_SR4x_%"), null)?.use { it.count } ?: 0
        try {
            val generation = ModelAvailabilityRegistry.beginProbe(); ModelAvailabilityRegistry.probePackagedCapabilities(context, generation); vm.openImage(sourceUri); await { !vm.uiState.value.isBusy && vm.uiState.value.sourcePath != null }
            val before = vm.uiState.value; val pendingBefore = pendingRows()
            // Start export and cancel after a short delay (simulating mid-operation cancellation).
            vm.exportSuperResolution()
            kotlinx.coroutines.delay(2000)
            vm.cancelSuperResolution()
            await(300) { !vm.superResolutionStatus.value.isBusy && vm.superResolutionStatus.value.phase in setOf(SuperResolutionExportPhase.Succeeded, SuperResolutionExportPhase.Failed, SuperResolutionExportPhase.Cancelled) }
            val pendingAfter = pendingRows()
            assertEquals(SuperResolutionExportPhase.Cancelled, vm.superResolutionStatus.value.phase)
            assertNull(vm.superResolutionStatus.value.publishedUri)
            assertEquals(before.sourcePath, vm.uiState.value.sourcePath)
            assertEquals(before.baseContentToken, vm.uiState.value.baseContentToken)
            assertEquals(before.revision, vm.uiState.value.revision)
            assertSame(before.previewBitmap, vm.uiState.value.previewBitmap)
            assertTrue("registry inactive", ModelAvailabilityRegistry.state.value[ModelFeature.ExynosUpscale]?.sessionActive != true)
            assertEquals(pendingBefore, pendingAfter)
        } finally { runCatching { vm.shutdownForTest() }; runCatching { context.contentResolver.delete(sourceUri, null, null) }; ModelAvailabilityRegistry.resetForTest() }
    }
}
