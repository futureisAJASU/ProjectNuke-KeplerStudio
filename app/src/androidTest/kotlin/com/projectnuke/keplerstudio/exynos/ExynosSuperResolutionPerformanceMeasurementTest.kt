package com.projectnuke.keplerstudio.exynos

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.EnnMetaIds
import com.projectnuke.keplerstudio.editor.EnnStatus
import com.projectnuke.keplerstudio.editor.ExynosUpscaleSession
import com.projectnuke.keplerstudio.editor.ModelAvailabilityRegistry
import com.projectnuke.keplerstudio.editor.ModelFeature
import com.projectnuke.keplerstudio.editor.ModelRunnerLifecycle
import com.projectnuke.keplerstudio.editor.N5WakeLock
import com.projectnuke.keplerstudio.editor.NpuProofStatus
import com.projectnuke.keplerstudio.editor.RealN5WakeLock
import com.projectnuke.keplerstudio.editor.SuperResolutionExportPhase
import com.projectnuke.keplerstudio.editor.SuperResolutionExportProgress
import com.projectnuke.keplerstudio.editor.SuperResolutionTestSeam
import com.projectnuke.keplerstudio.editor.decideNpuProof
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Measurement-only full export: no tuning, no artifact decoding, and no N6 evidence rewrite. */
@RunWith(AndroidJUnit4::class)
class ExynosSuperResolutionPerformanceMeasurementTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as Application
    private val inputWidth = 4080
    private val inputHeight = 3060

    private fun enabled() =
        InstrumentationRegistry.getArguments().getString("kepler.exynosNpuProbe") == "true"

    private fun isS24Exynos(): Boolean {
        val values = listOf("ro.board.platform", "ro.soc.model", "ro.product.device", "ro.product.model").mapNotNull { key ->
            runCatching {
                Class.forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java)
                    .invoke(null, key) as String
            }.getOrNull()
        }.joinToString(" ").lowercase()
        return values.contains("2400") || values.contains("s5e9945") || values.contains("e1s")
    }

    private fun writeFixture(output: OutputStream) {
        fun writeChunk(type: String, data: ByteArray) {
            val name = type.toByteArray(Charsets.US_ASCII)
            output.write((data.size ushr 24) and 255)
            output.write((data.size ushr 16) and 255)
            output.write((data.size ushr 8) and 255)
            output.write(data.size and 255)
            output.write(name)
            output.write(data)
            val crc = CRC32().apply { update(name); update(data) }.value
            output.write((crc ushr 24).toInt() and 255)
            output.write((crc ushr 16).toInt() and 255)
            output.write((crc ushr 8).toInt() and 255)
            output.write(crc.toInt() and 255)
        }
        output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        writeChunk(
            "IHDR",
            ByteBuffer.allocate(13).apply {
                putInt(inputWidth)
                putInt(inputHeight)
                put(8)
                put(2)
                put(0)
                put(0)
                put(0)
            }.array(),
        )
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
                while (!deflater.needsInput()) {
                    val count = deflater.deflate(compressed)
                    if (count == 0) break
                    writeChunk("IDAT", compressed.copyOf(count))
                }
            }
            deflater.finish()
            while (!deflater.finished()) {
                val count = deflater.deflate(compressed)
                if (count == 0) break
                writeChunk("IDAT", compressed.copyOf(count))
            }
        } finally {
            deflater.end()
        }
        writeChunk("IEND", ByteArray(0))
    }

    private fun insertFixture(): android.net.Uri {
        val uri = checkNotNull(
            context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "KeplerStudio_perf_${System.nanoTime()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KeplerStudio")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        )
        context.contentResolver.openOutputStream(uri).use { writeFixture(checkNotNull(it)) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun await(timeoutSeconds: Long, label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline && !condition()) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { }
            Thread.sleep(25)
        }
        assertTrue("timed out waiting for $label", condition())
    }

    private fun elapsed(start: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

    private fun sample(label: String, start: Long, progress: SuperResolutionExportProgress, heartbeat: Int) =
        JSONObject().apply {
            val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            put("label", label)
            put("elapsed_ms", elapsed(start))
            put("phase", progress.phase.name)
            put("tiles", "${progress.completedTiles}/${progress.totalTiles}")
            put("rows", "${progress.encodingRowsCompleted}/${progress.encodingRowsTotal}")
            put("java_heap", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
            put("native_heap", Debug.getNativeHeapAllocatedSize())
            put("pss", memory.totalPss * 1024L)
            put("thermal_status", power.currentThermalStatus)
            put("battery_level", battery?.getIntExtra("level", -1) ?: -1)
            put("main_heartbeat", heartbeat)
        }

    @Test
    fun cleanFullExportRecordsActualStageDurations() = runBlocking {
        assumeTrue("physical performance probe is opt-in", enabled())
        assumeTrue("target must be Exynos 2400 S24", isS24Exynos())
        ModelAvailabilityRegistry.resetForTest()
        val source = insertFixture()
        val vm = EditorViewModel(app)
        val sessionRef = AtomicReference<ExynosUpscaleSession?>()
        val wakeRef = AtomicReference<N5WakeLock?>()
        val compilerRef = AtomicReference<String?>()
        val marks = ConcurrentHashMap<String, Long>()
        val samples = JSONArray()
        val heartbeat = AtomicInteger()
        val handler = Handler(Looper.getMainLooper())
        val ticker = object : Runnable {
            override fun run() {
                heartbeat.incrementAndGet()
                handler.postDelayed(this, 20)
            }
        }
        var seamHandle: AutoCloseable? = null
        var latest = SuperResolutionExportProgress(SuperResolutionExportPhase.Preparing)
        val start = System.nanoTime()
        try {
            val generation = ModelAvailabilityRegistry.beginProbe()
            ModelAvailabilityRegistry.probePackagedCapabilities(context, generation)
            assertTrue(ModelAvailabilityRegistry.state.value[ModelFeature.ExynosUpscale]?.canAttemptModelUse == true)
            vm.openImage(source)
            await(120, "clean source and Draft settlement") {
                val state = vm.uiState.value
                !state.isBusy && !state.historyBusy && !vm.historyActivityBusyForTest() &&
                    !vm.hasActiveDraftSaveJobForTest() && state.draftGenerationId != null &&
                    state.draftGenerationId == vm.draftPointerBaseline && state.sourcePath != null
            }
            handler.post(ticker)
            val seam = SuperResolutionTestSeam(
                progressObserver = { progress ->
                    latest = progress
                    if (progress.phase == SuperResolutionExportPhase.Upscaling && progress.completedTiles > 0) {
                        marks.putIfAbsent("npu_start", elapsed(start))
                    }
                    if (progress.phase == SuperResolutionExportPhase.Encoding && progress.encodingRowsCompleted > 0) {
                        marks.putIfAbsent("png_start", elapsed(start))
                    }
                },
                milestoneObserver = { label ->
                    marks.putIfAbsent(label, elapsed(start))
                    if (label == "after_model_load") {
                        compilerRef.set(
                            runCatching {
                                sessionRef.get()?.getEnnMetaInfo(EnnMetaIds.MODEL_COMPILER_NPU)
                            }.getOrNull(),
                        )
                    }
                    if (label in setOf(
                            "before_full_source_preparation",
                            "after_full_source_preparation",
                            "after_model_load",
                            "before_mediastore_publish",
                            "after_mediastore_publish",
                            "after_rgb8_cleanup",
                            "after_session_close",
                        )
                    ) {
                        samples.put(sample(label, start, latest, heartbeat.get()))
                    }
                },
                rgb8ArtifactObserver = {
                    marks.putIfAbsent("after_rgb8_complete", elapsed(start))
                    samples.put(sample("after_rgb8_complete", start, latest, heartbeat.get()))
                },
                sessionProvider = { ExynosUpscaleSession(context).also(sessionRef::set) },
                wakeLockFactory = { owner, tag -> RealN5WakeLock(owner, tag).also(wakeRef::set) },
            )
            seamHandle = SuperResolutionTestSeam.install(seam)
            vm.exportSuperResolution()
            await(360, "full export terminal settlement") {
                !vm.superResolutionStatus.value.isBusy &&
                    vm.superResolutionStatus.value.phase in setOf(
                        SuperResolutionExportPhase.Succeeded,
                        SuperResolutionExportPhase.Failed,
                        SuperResolutionExportPhase.Cancelled,
                    )
            }
            assertEquals(SuperResolutionExportPhase.Succeeded, vm.superResolutionStatus.value.phase)
            val end = elapsed(start)
            marks["terminal"] = end
            samples.put(sample("terminal", start, vm.superResolutionStatus.value.progress, heartbeat.get()))
            val required = setOf(
                "before_full_source_preparation",
                "after_full_source_preparation",
                "after_model_load",
                "npu_start",
                "after_rgb8_complete",
                "png_start",
                "before_mediastore_publish",
                "after_mediastore_publish",
                "after_rgb8_cleanup",
                "after_session_close",
            )
            required.forEach { assertNotNull("missing actual timing mark $it", marks[it]) }
            val session = checkNotNull(sessionRef.get())
            val diagnostics = session.lastRunDiagnostics
            val compiler = compilerRef.get()
            assertEquals(EnnStatus.SUCCESS, diagnostics.h2dStatus)
            assertTrue(diagnostics.executeReached)
            assertEquals(EnnStatus.SUCCESS, diagnostics.executeStatus)
            assertEquals(EnnStatus.SUCCESS, diagnostics.d2hStatus)
            assertEquals(NpuProofStatus.OBSERVED, decideNpuProof(diagnostics.executeReached, diagnostics.executeStatus, compiler).status)
            assertEquals(ModelRunnerLifecycle.Unloaded, session.lifecycle)
            assertFalse(checkNotNull(wakeRef.get()).isHeld)
            assertTrue(heartbeat.get() > 5)
            fun duration(from: String, to: String) = checkNotNull(marks[to]) - checkNotNull(marks[from])
            val report = JSONObject().apply {
                put("status", "PASS")
                put("source", "clean 4080x3060")
                put("output", "16320x12240")
                put("elapsed_ms", end)
                put("stage_durations_ms", JSONObject().apply {
                    put("source_preparation", duration("before_full_source_preparation", "after_full_source_preparation"))
                    put("model_load", duration("after_full_source_preparation", "after_model_load"))
                    put("npu", duration("npu_start", "after_rgb8_complete"))
                    put("png", duration("png_start", "before_mediastore_publish"))
                    put("mediastore", duration("before_mediastore_publish", "after_mediastore_publish"))
                    put("cleanup_and_close", duration("after_mediastore_publish", "after_session_close"))
                })
                put("marks_ms", JSONObject(marks as Map<*, *>))
                put("samples", samples)
                put("main_heartbeat_final", heartbeat.get())
                put("h2d", diagnostics.h2dStatus)
                put("execute_reached", diagnostics.executeReached)
                put("execute", diagnostics.executeStatus)
                put("d2h", diagnostics.d2hStatus)
                put("compiler_npu", compiler)
                put("npu_proof", NpuProofStatus.OBSERVED.name)
            }
            val reportDir = File(context.getExternalFilesDir(null), "artifacts/exynos-sr-productization-20260901").apply { mkdirs() }
            File(reportDir, "full_export_timing.json").writeText(report.toString(2))
        } finally {
            runCatching { seamHandle?.close() }
            runCatching { vm.shutdownForTest() }
            runCatching { context.contentResolver.delete(source, null, null) }
            handler.removeCallbacks(ticker)
            ModelAvailabilityRegistry.resetForTest()
        }
    }
}
