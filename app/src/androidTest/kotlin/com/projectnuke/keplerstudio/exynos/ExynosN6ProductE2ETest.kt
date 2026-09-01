package com.projectnuke.keplerstudio.exynos

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.util.Log
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
import java.util.concurrent.atomic.AtomicReference
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

    private fun sourceBitmapHashBounded(bitmap: Bitmap): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val w = bitmap.width; val h = bitmap.height
        val row = IntArray(w)
        val rgb = ByteArray(3)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (px in row) {
                rgb[0] = (px ushr 16).toByte(); rgb[1] = (px ushr 8).toByte(); rgb[2] = px.toByte()
                digest.update(rgb)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sample(label: String, p: SuperResolutionExportProgress, wake: N5WakeLock?, heartbeat: Int, rgb8Bytes: Long, rgb8Exists: Boolean = rgb8Bytes > 0L) = JSONObject().apply {
        val memory = android.os.Debug.MemoryInfo().also(android.os.Debug::getMemoryInfo)
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        put("label", label); put("java_heap", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()); put("native_heap", android.os.Debug.getNativeHeapAllocatedSize()); put("pss", memory.totalPss * 1024L)
        put("tile_completed", p.completedTiles); put("tile_total", p.totalTiles); put("png_rows_completed", p.encodingRowsCompleted); put("png_rows_total", p.encodingRowsTotal); put("rgb8_file_bytes", rgb8Bytes); put("rgb8_file_exists", rgb8Exists)
        put("overall_fraction", p.overallFraction); put("phase", p.phase.name)
        put("input_width", p.inputWidth); put("input_height", p.inputHeight); put("output_width", p.outputWidth); put("output_height", p.outputHeight)
        put("wake_lock_held", wake?.isHeld == true); put("display_interactive", pm.isInteractive); put("main_heartbeat", heartbeat)
    }

    private fun diagnosticSnapshot(elapsedMs: Long, lastMilestone: String?, vm: EditorViewModel, session: ExynosUpscaleSession?, heartbeat: Int, rgb8File: File?): String {
        val status = vm.superResolutionStatus.value
        val progress = status.progress
        val ui = vm.uiState.value
        val cap = ModelAvailabilityRegistry.state.value[ModelFeature.ExynosUpscale]
        val runDiag = session?.lastRunDiagnostics
        val loadDiag = session?.lastLoadDiagnostics
        val rgb8Exists = rgb8File?.exists() == true
        val rgb8Size = if (rgb8Exists) rgb8File!!.length() else -1L
        return "elapsed=${elapsedMs}ms lastMilestone=${lastMilestone ?: "none"} vmPhase=${status.phase} vmIsBusy=${status.isBusy} failureKind=${status.failureKind} failureMsg=${status.failureMessage} " +
            "input=${progress.inputWidth}x${progress.inputHeight} output=${progress.outputWidth}x${progress.outputHeight} tiles=${progress.completedTiles}/${progress.totalTiles} " +
            "rows=${progress.encodingRowsCompleted}/${progress.encodingRowsTotal} overall=${progress.overallFraction} " +
            "uiIsBusy=${ui.isBusy} uiMsg=${ui.message} sessionActive=${cap?.sessionActive} sessionLifecycle=${session?.lifecycle} " +
            "h2d=${runDiag?.h2dStatus} executeReached=${runDiag?.executeReached} execute=${runDiag?.executeStatus} d2h=${runDiag?.d2hStatus} " +
            "compiler_npu=${session?.getEnnMetaInfo(EnnMetaIds.MODEL_COMPILER_NPU)} loadInit=${loadDiag?.initializeStatus} loadOpen=${loadDiag?.openModelStatus} loadAlloc=${loadDiag?.allocationStatus} " +
            "heartbeat=${heartbeat} rgb8Exists=${rgb8Exists} rgb8Bytes=${rgb8Size}"
    }

    private fun awaitWithDiagnostics(
        vm: EditorViewModel,
        sessionRef: AtomicReference<ExynosUpscaleSession?>,
        lastMilestoneRef: AtomicReference<String?>,
        heartbeat: AtomicInteger,
        rgb8FileRef: AtomicReference<File?>,
        timeoutSeconds: Long,
        condition: () -> Boolean
    ) {
        val start = System.nanoTime()
        val deadline = start + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        var lastLog = start
        while (System.nanoTime() < deadline && !condition()) {
            if (System.nanoTime() - lastLog > TimeUnit.SECONDS.toNanos(5)) {
                lastLog = System.nanoTime()
                val snap = diagnosticSnapshot(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), lastMilestoneRef.get(), vm, sessionRef.get(), heartbeat.get(), rgb8FileRef.get())
                Log.i("KeplerN6Diag", snap)
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { }
            Thread.sleep(25)
        }
        if (!condition()) {
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            val snap = diagnosticSnapshot(elapsed, lastMilestoneRef.get(), vm, sessionRef.get(), heartbeat.get(), rgb8FileRef.get())
            Log.e("KeplerN6Diag", "WATCHDOG TIMEOUT: $snap")
            fail("product operation timed out after ${elapsed}ms | $snap")
        }
    }

    private fun cancellationReportDir(): File =
        File(context.getExternalFilesDir(null), "artifacts/exynos-n6-s24-20260901").apply { mkdirs() }

    private fun documentIdentity(state: EditorUiState) = JSONObject().apply {
        put("source_path", state.sourcePath ?: JSONObject.NULL)
        put("base_content_token", state.baseContentToken ?: JSONObject.NULL)
        put("revision", state.revision)
        put("params", state.params.toString())
        put("crop_state", state.cropState.toString())
        put("selection_layer_count", state.selectionLayers.size)
    }

    private fun writeCancellationEvidence(
        fileName: String,
        triggerPhase: SuperResolutionExportPhase,
        triggerProgress: SuperResolutionExportProgress,
        terminal: SuperResolutionExportStatus,
        before: EditorUiState,
        after: EditorUiState,
        pendingBefore: Int,
        pendingAfter: Int,
        rgb8ExistsAfterSettlement: Boolean,
        session: ExynosUpscaleSession,
        wake: N5WakeLock,
        elapsedMs: Long,
    ) {
        val registryActive = ModelAvailabilityRegistry.state.value[ModelFeature.ExynosUpscale]?.sessionActive == true
        val documentUnchanged = before.sourcePath == after.sourcePath &&
            before.baseContentToken == after.baseContentToken &&
            before.revision == after.revision &&
            before.params == after.params &&
            before.cropState == after.cropState &&
            before.selectionLayers == after.selectionLayers &&
            before.previewBitmap === after.previewBitmap &&
            before.originalPreviewBitmap === after.originalPreviewBitmap
        File(cancellationReportDir(), fileName).writeText(JSONObject().apply {
            put("status", "PASS")
            put("trigger_phase", triggerPhase.name)
            put("rows_completed_at_cancellation", if (triggerPhase == SuperResolutionExportPhase.Encoding) triggerProgress.encodingRowsCompleted else JSONObject.NULL)
            put("rows_total", if (triggerPhase == SuperResolutionExportPhase.Encoding) triggerProgress.encodingRowsTotal else JSONObject.NULL)
            put("completed_tiles_at_cancellation", if (triggerPhase == SuperResolutionExportPhase.Upscaling) triggerProgress.completedTiles else JSONObject.NULL)
            put("total_tiles", if (triggerPhase == SuperResolutionExportPhase.Upscaling) triggerProgress.totalTiles else JSONObject.NULL)
            put("terminal_phase", terminal.phase.name)
            put("is_busy", terminal.isBusy)
            put("published_uri", terminal.publishedUri?.toString() ?: JSONObject.NULL)
            put("pending_rows_before", pendingBefore)
            put("pending_rows_after", pendingAfter)
            put("rgb8_exists_after_settlement", rgb8ExistsAfterSettlement)
            put("session_lifecycle", session.lifecycle.name)
            put("registry_session_active", registryActive)
            put("wake_lock_held", wake.isHeld)
            put("document_identity_before", documentIdentity(before))
            put("document_identity_after", documentIdentity(after))
            put("document_unchanged", documentUnchanged)
            put("elapsed_ms", elapsedMs)
        }.toString(2))
    }

    @Test
    fun n6ProductE2EWithEditedDocument() = runBlocking {
        assumeTrue("physical N6 probe is opt-in", enabled()); assumeTrue("target must be Exynos 2400 S24", isS24Exynos())
        ModelAvailabilityRegistry.resetForTest()
        val sourceUri = insertFixture()
        val reportDir = File(context.getExternalFilesDir(null), "artifacts/exynos-n6-s24-20260901").apply { mkdirs() }
        val rawSamples = JSONArray(); val milestoneLabels = HashSet<String>(); val heartbeat = AtomicInteger(); val handler = Handler(Looper.getMainLooper())
        val ticker = object : Runnable { override fun run() { heartbeat.incrementAndGet(); handler.postDelayed(this, 20) } }
        var latestProgress = SuperResolutionExportProgress(SuperResolutionExportPhase.Preparing, inputWidth = inputWidth, inputHeight = inputHeight, outputWidth = outputWidth, outputHeight = outputHeight)
        val lastMilestone = AtomicReference<String?>(null)
        val sessionRef = AtomicReference<ExynosUpscaleSession?>(null)
        val rgb8FileRef = AtomicReference<File?>(null)
        val observedWake = AtomicReference<N5WakeLock?>(null)
        val sourceHashRef = AtomicReference<String?>(null)
        val expectedRegionHashes = mutableMapOf<String, String>()
        var rgb8ArtifactForHash: FileBackedRgb8Artifact? = null
        val preparedFileCaptured = AtomicReference<File?>(null)
        val preparedFileShaCaptured = AtomicReference<String?>(null)
        val preparedFileSizeCaptured = AtomicReference<Long?>(null)
        val vm = EditorViewModel(app)
        var seamHandle: AutoCloseable? = null
        try {
            val generation = ModelAvailabilityRegistry.beginProbe(); ModelAvailabilityRegistry.probePackagedCapabilities(context, generation)
            assertTrue(ModelAvailabilityRegistry.state.value[ModelFeature.ExynosUpscale]?.canAttemptModelUse == true)
            vm.openImage(sourceUri); awaitWithDiagnostics(vm, sessionRef, lastMilestone, heartbeat, rgb8FileRef, 180) { !vm.uiState.value.isBusy && vm.uiState.value.sourcePath != null }
            Log.i("KeplerN6Diag", "openImage done sourcePath=${vm.uiState.value.sourcePath} isBusy=${vm.uiState.value.isBusy}")
            vm.updateParams { it.copy(exposure = 0.35f) }; awaitWithDiagnostics(vm, sessionRef, lastMilestone, heartbeat, rgb8FileRef, 90) { !vm.uiState.value.isBusy && vm.uiState.value.params.exposure == 0.35f }
            Log.i("KeplerN6Diag", "updateParams done")
            handler.post(ticker)
            val tilePlan = TilePlanner.plan(inputWidth, inputHeight)
            assertTrue("N4 seam requires the real planner-derived tiled plan", tilePlan is TilePlanResult.Planned)
            val outputEdge = checkNotNull((tilePlan as TilePlanResult.Planned).plan.tiles.firstOrNull { it.dest.left > 0 }?.dest?.left) {
                "N4 seam requires an interior planner tile"
            }
            val seamLeft = outputEdge - 16
            val seamTop = (outputHeight / 2 - 16).coerceAtLeast(0)
            val seamRect = Rect(seamLeft, seamTop, seamLeft + 32, seamTop + 32)
            assertTrue("seam rect must have positive width", seamRect.width() > 0)
            assertTrue("seam rect must have positive height", seamRect.height() > 0)
            assertTrue("seam must cross interior edge", seamRect.left < outputEdge && seamRect.right > outputEdge)
            val progressRef = AtomicReference<SuperResolutionExportProgress>(latestProgress)
            val seam = SuperResolutionTestSeam(
                sourceBitmapObserver = { bmp ->
                    val h = sourceBitmapHashBounded(bmp)
                    sourceHashRef.set(h)
                },
                progressObserver = { p ->
                    latestProgress = p; progressRef.set(p)
                    lastMilestone.set(p.phase.name)
                    val wake = observedWake.get(); val rgb8Bytes = if (rgb8FileRef.get()?.exists() == true) rgb8FileRef.get()!!.length() else 0L
                    if (p.phase == SuperResolutionExportPhase.Upscaling) {
                        if (p.completedTiles > 0 && milestoneLabels.add("npu_early")) rawSamples.put(sample("npu_early", p, wake, heartbeat.get(), rgb8Bytes))
                        if (p.completedTiles >= expectedTiles / 2 && milestoneLabels.add("npu_midpoint")) rawSamples.put(sample("npu_midpoint", p, wake, heartbeat.get(), rgb8Bytes))
                        if (p.completedTiles == p.totalTiles && milestoneLabels.add("npu_late")) rawSamples.put(sample("npu_late", p, wake, heartbeat.get(), rgb8Bytes))
                    }
                    if (p.phase == SuperResolutionExportPhase.Encoding) {
                        if (p.encodingRowsCompleted > 0 && milestoneLabels.add("png_early")) rawSamples.put(sample("png_early", p, wake, heartbeat.get(), rgb8Bytes))
                        if (p.encodingRowsCompleted >= outputHeight / 2 && milestoneLabels.add("png_midpoint")) rawSamples.put(sample("png_midpoint", p, wake, heartbeat.get(), rgb8Bytes))
                        if (p.encodingRowsCompleted == p.encodingRowsTotal && p.encodingRowsTotal > 0 && milestoneLabels.add("png_late")) rawSamples.put(sample("png_late", p, wake, heartbeat.get(), rgb8Bytes))
                    }
                },
                rgb8ArtifactObserver = { artifact ->
                    rgb8ArtifactForHash = artifact; rgb8FileRef.set(artifact.file)
                    val wake = observedWake.get()
                    val regions = mapOf(
                        "top_left" to Rect(0, 0, 32, 32),
                        "center" to Rect(outputWidth / 2 - 16, outputHeight / 2 - 16, outputWidth / 2 + 16, outputHeight / 2 + 16),
                        "bottom_right" to Rect(outputWidth - 32, outputHeight - 32, outputWidth, outputHeight),
                        "n4_seam_crossing" to seamRect
                    )
                    for ((name, rect) in regions) expectedRegionHashes[name] = artifactHash(artifact.file, artifact.width, rect)
                    if (milestoneLabels.add("after_rgb8_complete")) rawSamples.put(sample("after_rgb8_complete", progressRef.get(), wake, heartbeat.get(), artifact.file.length()))
                },
                milestoneObserver = { label ->
                    lastMilestone.set(label)
                    val wake = observedWake.get(); val p = progressRef.get(); val rgb8File = rgb8FileRef.get()
                    val rgb8Bytes = if (rgb8File?.exists() == true) rgb8File.length() else 0L
                    when (label) {
                        "before_full_source_preparation", "after_full_source_preparation", "after_model_load",
                        "before_mediastore_publish", "after_mediastore_publish", "after_rgb8_cleanup", "after_session_close" -> {
                            if (milestoneLabels.add(label)) {
                                val observed = sample(label, p, wake, heartbeat.get(), rgb8Bytes, rgb8File?.exists() == true)
                                if (label == "after_rgb8_cleanup") {
                                    observed.put("rgb8_cleanup_status", if (rgb8File?.exists() == true) "file_present_cleanup_debt" else "file_absent_cleanup_success")
                                }
                                rawSamples.put(observed)
                            }
                        }
                    }
                    if (label == "after_model_load") {
                        val s = sessionRef.get(); val f = s?.preparedModelFileForDiagnostics()
                        if (f != null && f.exists()) { preparedFileCaptured.set(File(f.absolutePath)); try { preparedFileShaCaptured.set(sha256(f)) } catch (_: Throwable) {}; preparedFileSizeCaptured.set(f.length()) }
                    }
                },
                sessionProvider = { ExynosUpscaleSession(context).also { s -> sessionRef.set(s) } },
                wakeLockFactory = { c, t -> RealN5WakeLock(c, t).also { w -> observedWake.set(w) } }
            )
            seamHandle = SuperResolutionTestSeam.install(seam)
            val startTime = System.nanoTime()
            vm.exportSuperResolution()
            Log.i("KeplerN6Diag", "BEFORE export canStart=${vm.canStartSuperResolution()} phase=${vm.superResolutionStatus.value.phase}")
            awaitWithDiagnostics(vm, sessionRef, lastMilestone, heartbeat, rgb8FileRef, 360) { !vm.superResolutionStatus.value.isBusy && vm.superResolutionStatus.value.phase in setOf(SuperResolutionExportPhase.Succeeded, SuperResolutionExportPhase.Failed, SuperResolutionExportPhase.Cancelled) }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)
            Log.i("KeplerN6Diag", "N6 elapsed ${elapsedMs}ms phase=${vm.superResolutionStatus.value.phase}")
            if (elapsedMs > 300_000) Log.w("KeplerN6Diag", "N6 elapsed >300s; measured=${elapsedMs}ms (justified margin)")
            assertEquals(SuperResolutionExportPhase.Succeeded, vm.superResolutionStatus.value.phase)
            val uri = checkNotNull(vm.superResolutionStatus.value.publishedUri)
            val observedProgress = vm.superResolutionStatus.value.progress
            val observedInputW = observedProgress.inputWidth; val observedInputH = observedProgress.inputHeight
            val observedOutputW = observedProgress.outputWidth; val observedOutputH = observedProgress.outputHeight
            val observedTiles = observedProgress.totalTiles; val observedRows = observedProgress.encodingRowsTotal
            val finalTiles = if (observedTiles != 0) observedTiles else latestProgress.totalTiles
            val finalRows = if (observedRows != 0) observedRows else latestProgress.encodingRowsTotal
            assertEquals(inputWidth, observedInputW); assertEquals(inputHeight, observedInputH)
            assertEquals(outputWidth, observedOutputW); assertEquals(outputHeight, observedOutputH)
            assertEquals(expectedTiles, finalTiles); assertEquals(outputHeight, finalRows)
            assertTrue("rawSamples must be non-empty after successful export", rawSamples.length() > 0)
            val requiredLabels = setOf(
                "before_full_source_preparation", "after_full_source_preparation", "after_model_load",
                "npu_early", "npu_midpoint", "npu_late", "after_rgb8_complete",
                "png_early", "png_midpoint", "png_late", "before_mediastore_publish",
                "after_mediastore_publish", "after_rgb8_cleanup", "after_session_close",
            )
            for (lbl in requiredLabels) assertTrue("sample $lbl must be present", (0 until rawSamples.length()).any { rawSamples.getJSONObject(it).getString("label") == lbl })
            assertEquals("N6 product evidence must contain exactly the 14 intended samples", 14, rawSamples.length())
            assertEquals(requiredLabels, (0 until rawSamples.length()).map { rawSamples.getJSONObject(it).getString("label") }.toSet())
            val rgb8CompleteSample = (0 until rawSamples.length()).map { rawSamples.getJSONObject(it) }.firstOrNull { it.getString("label") == "after_rgb8_complete" }
            assertNotNull("after_rgb8_complete must be an actual artifact callback", rgb8CompleteSample)
            assertTrue("after_rgb8_complete must have non-zero rgb8_file_bytes", checkNotNull(rgb8CompleteSample).getLong("rgb8_file_bytes") > 0L)
            val rgb8CleanupSample = (0 until rawSamples.length()).map { rawSamples.getJSONObject(it) }.first { it.getString("label") == "after_rgb8_cleanup" }
            assertFalse("cleanup sample must observe the RGB8 file absent", rgb8CleanupSample.getBoolean("rgb8_file_exists"))
            assertEquals("file_absent_cleanup_success", rgb8CleanupSample.getString("rgb8_cleanup_status"))
            assertTrue(rawSamples.length() > 0)
            val javaValues = (0 until rawSamples.length()).map { rawSamples.getJSONObject(it).getLong("java_heap") }
            val nativeValues = (0 until rawSamples.length()).map { rawSamples.getJSONObject(it).getLong("native_heap") }
            val pssValues = (0 until rawSamples.length()).map { rawSamples.getJSONObject(it).getLong("pss") }
            val rgb8Values = (0 until rawSamples.length()).map { rawSamples.getJSONObject(it).getLong("rgb8_file_bytes") }
            fun range(values: List<Long>) = JSONObject(mapOf("min" to values.min(), "max" to values.max(), "delta" to (values.max() - values.min())))
            val memorySummary = JSONObject(mapOf("java_heap" to range(javaValues), "native_heap" to range(nativeValues), "pss" to range(pssValues), "rgb8_file_bytes" to range(rgb8Values), "sample_count" to rawSamples.length()))
            val session = checkNotNull(sessionRef.get()) { "session must have been created via seam" }
            val capturedSize = preparedFileSizeCaptured.get(); val capturedSha = preparedFileShaCaptured.get(); val capturedFile = preparedFileCaptured.get()
            assertNotNull("prepared NNC file must have been captured at after_model_load", capturedFile)
            assertNotNull("captured NNC SHA must exist", capturedSha)
            assertNotNull("captured NNC size must exist", capturedSize)
            assertEquals(expectedModelBytes, capturedSize)
            assertEquals(expectedModelSha, capturedSha)
            val diag = session.lastRunDiagnostics
            val compilerNpu = runCatching { session.getEnnMetaInfo(EnnMetaIds.MODEL_COMPILER_NPU) }.getOrNull()
            Log.i("KeplerN6Diag", "NPU proof compiler_npu=$compilerNpu")
            val decision = decideNpuProof(diag.executeReached, diag.executeStatus, compilerNpu)
            assertEquals("NPU proof must be OBSERVED (compiler=$compilerNpu)", NpuProofStatus.OBSERVED, decision.status)
            assertEquals(EnnStatus.SUCCESS, diag.h2dStatus)
            assertTrue(diag.executeReached)
            assertEquals(EnnStatus.SUCCESS, diag.executeStatus)
            assertEquals(EnnStatus.SUCCESS, diag.d2hStatus)
            assertTrue(compilerNpu!!.isNotBlank() && !compilerNpu.equals("unavailable", ignoreCase = true))
            assertTrue("registry session must be inactive", ModelAvailabilityRegistry.state.value[ModelFeature.ExynosUpscale]?.sessionActive != true)
            assertEquals(ModelRunnerLifecycle.Unloaded, session.lifecycle)
            assertEquals(false, observedWake.get()?.isHeld)
            val rgb8File = rgb8ArtifactForHash?.file ?: rgb8FileRef.get()
            if (rgb8File != null) assertFalse("internal RGB8 artifact must be cleaned after success", rgb8File.exists())
            assertTrue("sourceBitmapObserver must have been invoked", sourceHashRef.get() != null)
            val actualRegionHashes = mutableMapOf<String, String>()
            context.contentResolver.openInputStream(uri).use { input ->
                val decoder = checkNotNull(BitmapRegionDecoder.newInstance(checkNotNull(input), false))
                assertEquals(outputWidth, decoder.width); assertEquals(outputHeight, decoder.height)
                val regions = mapOf("top_left" to Rect(0, 0, 32, 32), "center" to Rect(outputWidth / 2 - 16, outputHeight / 2 - 16, outputWidth / 2 + 16, outputHeight / 2 + 16), "bottom_right" to Rect(outputWidth - 32, outputHeight - 32, outputWidth, outputHeight), "n4_seam_crossing" to seamRect)
                for ((name, rect) in regions) {
                    val bmp = decoder.decodeRegion(rect, BitmapFactory.Options()); actualRegionHashes[name] = bitmapHash(bmp); bmp.recycle()
                    val expected = expectedRegionHashes[name]; assertNotNull("expected hash for $name must exist", expected)
                    assertEquals("RGB8 artifact region $name must equal published PNG region", expected, actualRegionHashes[name])
                }
                decoder.recycle()
            }
            File(reportDir, "n6_memory_samples.json").writeText(rawSamples.toString(2))
            File(reportDir, "n6_memory_summary.json").writeText(memorySummary.toString(2))
            File(reportDir, "n6_product_e2e.json").writeText(JSONObject(mapOf(
                "status" to "PASS", "product_action" to "EditorViewModel.exportSuperResolution",
                "input_observed" to "${observedInputW}x${observedInputH}", "input_expected" to "${inputWidth}x${inputHeight}",
                "output_observed" to "${observedOutputW}x${observedOutputH}", "output_expected" to "${outputWidth}x${outputHeight}",
                "tiles_observed" to finalTiles, "tiles_expected" to expectedTiles,
                "png_rows_observed" to finalRows, "png_rows_expected" to outputHeight,
                "model_size_observed" to (capturedSize ?: -1L), "model_size_expected" to expectedModelBytes,
                "model_sha256_observed" to capturedSha, "model_sha256_expected" to expectedModelSha,
                "h2d_status" to (session.lastRunDiagnostics.h2dStatus ?: -1), "execute_reached" to session.lastRunDiagnostics.executeReached, "execute_status" to (session.lastRunDiagnostics.executeStatus ?: -1), "d2h_status" to (session.lastRunDiagnostics.d2hStatus ?: -1),
                "compiler_npu" to (compilerNpu ?: "unavailable"), "npu_proof" to decision.status.name,
                "session_lifecycle" to session.lifecycle.name, "session_active" to (ModelAvailabilityRegistry.state.value[ModelFeature.ExynosUpscale]?.sessionActive ?: false),
                "elapsed_ms" to elapsedMs, "published_uri" to uri.toString(), "source_hash" to (sourceHashRef.get() ?: ""),
                "region_hashes_expected" to JSONObject(expectedRegionHashes), "region_hashes_actual" to JSONObject(actualRegionHashes)
            )).toString(2))
            File(reportDir, "n6_region_hashes.json").writeText(JSONObject(mapOf("expected_region_hashes" to JSONObject(expectedRegionHashes), "actual_region_hashes" to JSONObject(actualRegionHashes))).toString(2))
        } finally {
            try { seamHandle?.close() } catch (_: Throwable) {}
            runCatching { vm.shutdownForTest() }
            runCatching { context.contentResolver.delete(sourceUri, null, null) }
            sessionRef.get()?.let { s -> runCatching { s.close() } }
            handler.removeCallbacks(ticker)
            ModelAvailabilityRegistry.resetForTest()
        }
    }

    @Test
    fun n6CancellationDuringPngEncodingUsesViewModelActionAfterEncodingStarted() = runBlocking {
        assumeTrue("physical N6 probe is opt-in", enabled()); assumeTrue("target must be Exynos 2400 S24", isS24Exynos())
        ModelAvailabilityRegistry.resetForTest()
        val sourceUri = insertFixture(); val vm = EditorViewModel(app)
        fun pendingRows(): Int = context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Images.Media.IS_PENDING} = 1", arrayOf("KeplerStudio_SR4x_%"), null)?.use { it.count } ?: 0
        val lastMilestone = AtomicReference<String?>(null); val sessionRef = AtomicReference<ExynosUpscaleSession?>(null); val rgb8Ref = AtomicReference<File?>(null)
        val wakeRef = AtomicReference<N5WakeLock?>(null); val heartbeat = AtomicInteger(0); val handler = Handler(Looper.getMainLooper()); val ticker = object : Runnable { override fun run() { heartbeat.incrementAndGet(); handler.postDelayed(this, 20) } }
        var seamHandle: AutoCloseable? = null
        try {
            val generation = ModelAvailabilityRegistry.beginProbe(); ModelAvailabilityRegistry.probePackagedCapabilities(context, generation)
            vm.openImage(sourceUri); awaitWithDiagnostics(vm, sessionRef, lastMilestone, heartbeat, rgb8Ref, 90) { !vm.uiState.value.isBusy && vm.uiState.value.sourcePath != null }
            val before = vm.uiState.value; val pendingBefore = pendingRows(); handler.post(ticker)
            val startTime = System.nanoTime()
            val encodingStarted = CountDownLatch(1); val triggerProgress = AtomicReference<SuperResolutionExportProgress?>()
            val seam = SuperResolutionTestSeam(
                progressObserver = { p ->
                    if (p.phase == SuperResolutionExportPhase.Encoding && p.encodingRowsCompleted > 0 && triggerProgress.compareAndSet(null, p)) encodingStarted.countDown()
                },
                rgb8ArtifactObserver = { artifact -> rgb8Ref.set(artifact.file) },
                milestoneObserver = { label -> lastMilestone.set(label) },
                sessionProvider = { ExynosUpscaleSession(context).also { s -> sessionRef.set(s) } },
                wakeLockFactory = { c, t -> RealN5WakeLock(c, t).also { w -> wakeRef.set(w) } }
            )
            seamHandle = SuperResolutionTestSeam.install(seam)
            vm.exportSuperResolution()
            awaitWithDiagnostics(vm, sessionRef, lastMilestone, heartbeat, rgb8Ref, 360) {
                vm.superResolutionStatus.value.phase == SuperResolutionExportPhase.Encoding && vm.superResolutionStatus.value.progress.encodingRowsCompleted > 0 || encodingStarted.count == 0L
            }
            assertTrue("PNG encoding must have started before cancellation", encodingStarted.await(0, TimeUnit.SECONDS))
            val trigger = checkNotNull(triggerProgress.get())
            assertTrue("must record exact observed row count >0, got ${trigger.encodingRowsCompleted}", trigger.encodingRowsCompleted > 0)
            val rowsAtCancel = trigger.encodingRowsCompleted
            Log.i("KeplerN6Diag", "Cancelling during PNG encoding at rows=$rowsAtCancel")
            vm.cancelSuperResolution()
            awaitWithDiagnostics(vm, sessionRef, lastMilestone, heartbeat, rgb8Ref, 60) { !vm.superResolutionStatus.value.isBusy && vm.superResolutionStatus.value.phase in setOf(SuperResolutionExportPhase.Succeeded, SuperResolutionExportPhase.Failed, SuperResolutionExportPhase.Cancelled) }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)
            val pendingAfter = pendingRows()
            val terminal = vm.superResolutionStatus.value
            val after = vm.uiState.value
            assertEquals(SuperResolutionExportPhase.Cancelled, terminal.phase)
            assertEquals(false, terminal.isBusy)
            assertNull(terminal.publishedUri)
            assertEquals(before.sourcePath, after.sourcePath); assertEquals(before.baseContentToken, after.baseContentToken); assertEquals(before.revision, after.revision)
            assertSame(before.previewBitmap, after.previewBitmap)
            assertEquals(pendingBefore, pendingAfter)
            val rgb8File = rgb8Ref.get()
            val rgb8ExistsAfter = rgb8File?.exists() == true
            val leftovers = context.cacheDir.listFiles()?.filter { it.name.startsWith("sr6_") && it.name.endsWith(".rgb8") } ?: emptyList()
            assertFalse("RGB8 artifact must be settled after cancellation", rgb8ExistsAfter)
            assertTrue("no RGB8 leftover after cancellation", leftovers.isEmpty())
            val session = checkNotNull(sessionRef.get())
            val wake = checkNotNull(wakeRef.get())
            assertEquals(ModelRunnerLifecycle.Unloaded, session.lifecycle)
            assertFalse(wake.isHeld)
            assertTrue("registry inactive after cancellation", ModelAvailabilityRegistry.state.value[ModelFeature.ExynosUpscale]?.sessionActive != true)
            assertTrue("document identity must remain unchanged", before.sourcePath == after.sourcePath && before.baseContentToken == after.baseContentToken && before.revision == after.revision && before.params == after.params && before.cropState == after.cropState && before.selectionLayers == after.selectionLayers && before.previewBitmap === after.previewBitmap && before.originalPreviewBitmap === after.originalPreviewBitmap)
            writeCancellationEvidence("n6_cancel_png_e2e.json", SuperResolutionExportPhase.Encoding, trigger, terminal, before, after, pendingBefore, pendingAfter, rgb8ExistsAfter, session, wake, elapsedMs)
        } finally {
            try { seamHandle?.close() } catch (_: Throwable) {}
            runCatching { vm.shutdownForTest() }
            runCatching { context.contentResolver.delete(sourceUri, null, null) }
            sessionRef.get()?.let { runCatching { it.close() } }
            handler.removeCallbacks(ticker)
            ModelAvailabilityRegistry.resetForTest()
        }
    }

    @Test
    fun n6CancellationDuringNpuUpscalingUsesViewModelActionAfterTilesStarted() = runBlocking {
        assumeTrue("physical N6 probe is opt-in", enabled()); assumeTrue("target must be Exynos 2400 S24", isS24Exynos())
        ModelAvailabilityRegistry.resetForTest()
        val sourceUri = insertFixture(); val vm = EditorViewModel(app)
        fun pendingRows(): Int = context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Images.Media.IS_PENDING} = 1", arrayOf("KeplerStudio_SR4x_%"), null)?.use { it.count } ?: 0
        val lastMilestone = AtomicReference<String?>(null); val sessionRef = AtomicReference<ExynosUpscaleSession?>(null); val rgb8Ref = AtomicReference<File?>(null)
        val wakeRef = AtomicReference<N5WakeLock?>(null); val heartbeat = AtomicInteger(0); val handler = Handler(Looper.getMainLooper()); val ticker = object : Runnable { override fun run() { heartbeat.incrementAndGet(); handler.postDelayed(this, 20) } }
        var seamHandle: AutoCloseable? = null
        try {
            val generation = ModelAvailabilityRegistry.beginProbe(); ModelAvailabilityRegistry.probePackagedCapabilities(context, generation)
            vm.openImage(sourceUri); awaitWithDiagnostics(vm, sessionRef, lastMilestone, heartbeat, rgb8Ref, 90) { !vm.uiState.value.isBusy && vm.uiState.value.sourcePath != null }
            val before = vm.uiState.value; val pendingBefore = pendingRows(); handler.post(ticker)
            val startTime = System.nanoTime()
            val tilesStarted = CountDownLatch(1); val triggerProgress = AtomicReference<SuperResolutionExportProgress?>()
            val seam = SuperResolutionTestSeam(
                progressObserver = { p ->
                    if (p.phase == SuperResolutionExportPhase.Upscaling && p.completedTiles >= 2 && triggerProgress.compareAndSet(null, p)) tilesStarted.countDown()
                },
                rgb8ArtifactObserver = { artifact -> rgb8Ref.set(artifact.file) },
                milestoneObserver = { label -> lastMilestone.set(label) },
                sessionProvider = { ExynosUpscaleSession(context).also { s -> sessionRef.set(s) } },
                wakeLockFactory = { c, t -> RealN5WakeLock(c, t).also { w -> wakeRef.set(w) } }
            )
            seamHandle = SuperResolutionTestSeam.install(seam)
            vm.exportSuperResolution()
            awaitWithDiagnostics(vm, sessionRef, lastMilestone, heartbeat, rgb8Ref, 360) {
                vm.superResolutionStatus.value.phase == SuperResolutionExportPhase.Upscaling && vm.superResolutionStatus.value.progress.completedTiles >= 2 || tilesStarted.count == 0L
            }
            assertTrue("NPU upscaling must have reached >=2 tiles before cancellation", tilesStarted.await(0, TimeUnit.SECONDS))
            val trigger = checkNotNull(triggerProgress.get())
            val tilesAtCancel = trigger.completedTiles
            assertTrue("must record exact tile count >=2, got $tilesAtCancel", tilesAtCancel >= 2)
            Log.i("KeplerN6Diag", "Cancelling during NPU upscaling at tiles=$tilesAtCancel")
            vm.cancelSuperResolution()
            awaitWithDiagnostics(vm, sessionRef, lastMilestone, heartbeat, rgb8Ref, 60) { !vm.superResolutionStatus.value.isBusy && vm.superResolutionStatus.value.phase in setOf(SuperResolutionExportPhase.Succeeded, SuperResolutionExportPhase.Failed, SuperResolutionExportPhase.Cancelled) }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)
            val pendingAfter = pendingRows()
            val terminal = vm.superResolutionStatus.value
            val after = vm.uiState.value
            assertEquals(SuperResolutionExportPhase.Cancelled, terminal.phase)
            assertEquals(false, terminal.isBusy)
            assertNull(terminal.publishedUri)
            assertEquals(before.sourcePath, after.sourcePath); assertEquals(before.baseContentToken, after.baseContentToken); assertEquals(before.revision, after.revision)
            assertSame(before.previewBitmap, after.previewBitmap)
            assertEquals(pendingBefore, pendingAfter)
            val rgb8File = rgb8Ref.get()
            val rgb8ExistsAfter = rgb8File?.exists() == true
            val leftovers = context.cacheDir.listFiles()?.filter { it.name.startsWith("sr6_") && it.name.endsWith(".rgb8") } ?: emptyList()
            assertFalse("RGB8 artifact must be settled after NPU cancellation", rgb8ExistsAfter)
            assertTrue("no RGB8 leftover after NPU cancellation", leftovers.isEmpty())
            val session = checkNotNull(sessionRef.get())
            val wake = checkNotNull(wakeRef.get())
            assertEquals(ModelRunnerLifecycle.Unloaded, session.lifecycle)
            assertFalse(wake.isHeld)
            assertTrue("registry inactive after NPU cancellation", ModelAvailabilityRegistry.state.value[ModelFeature.ExynosUpscale]?.sessionActive != true)
            assertTrue("document identity must remain unchanged", before.sourcePath == after.sourcePath && before.baseContentToken == after.baseContentToken && before.revision == after.revision && before.params == after.params && before.cropState == after.cropState && before.selectionLayers == after.selectionLayers && before.previewBitmap === after.previewBitmap && before.originalPreviewBitmap === after.originalPreviewBitmap)
            writeCancellationEvidence("n6_cancel_npu_e2e.json", SuperResolutionExportPhase.Upscaling, trigger, terminal, before, after, pendingBefore, pendingAfter, rgb8ExistsAfter, session, wake, elapsedMs)
        } finally {
            try { seamHandle?.close() } catch (_: Throwable) {}
            runCatching { vm.shutdownForTest() }
            runCatching { context.contentResolver.delete(sourceUri, null, null) }
            sessionRef.get()?.let { runCatching { it.close() } }
            handler.removeCallbacks(ticker)
            ModelAvailabilityRegistry.resetForTest()
        }
    }
}
