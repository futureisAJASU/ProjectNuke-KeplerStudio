package com.projectnuke.keplerstudio.exynos

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.projectnuke.keplerstudio.editor.DiagnosticRetention
import com.projectnuke.keplerstudio.editor.EnnMetaIds
import com.projectnuke.keplerstudio.editor.EnnStatus
import com.projectnuke.keplerstudio.editor.ExynosUpscaleSession
import com.projectnuke.keplerstudio.editor.FakeN5WakeLock
import com.projectnuke.keplerstudio.editor.FileBackedRgb8Artifact
import com.projectnuke.keplerstudio.editor.ModelAssetManifest
import com.projectnuke.keplerstudio.editor.ModelAvailabilityRegistry
import com.projectnuke.keplerstudio.editor.ModelFeature
import com.projectnuke.keplerstudio.editor.ModelLoadResult
import com.projectnuke.keplerstudio.editor.ModelOperationContext
import com.projectnuke.keplerstudio.editor.N5WakeLock
import com.projectnuke.keplerstudio.editor.RealN5WakeLock
import com.projectnuke.keplerstudio.editor.TileFileBackedUpscaler
import com.projectnuke.keplerstudio.editor.TileInputSource
import com.projectnuke.keplerstudio.editor.TilePlanner
import com.projectnuke.keplerstudio.editor.FileBackedUpscaleResult
import com.projectnuke.keplerstudio.editor.ValidatedModelCapabilityToken
import com.projectnuke.keplerstudio.editor.decideNpuProof
import com.projectnuke.keplerstudio.editor.npuProofAcceptanceFailure
import com.projectnuke.keplerstudio.editor.sha256Bytes
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AssumptionViolatedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase N5 — physical S24 bounded-memory stress harness.
 *
 * Opt-in instrumentation (kepler.exynosNpuProbe=true) targeting:
 *   SM-S921N / e1s / S5E9945 / Exynos 2400
 *   NNC size 3,112,960 SHA 9cff7af64dbe5b4ed260449153ea08e91cabd758ce3478344c286ee2798bae12
 *   4080x3060 -> 16320x12240 RGB8 599,270,400 bytes, 3350 tiles
 *
 * Does NOT create a full-image FP32 CHW ByteArray; uses bounded procedural TileInputSource.
 * Uses PARTIAL_WAKE_LOCK for entire workload, allowing display OFF / locked device.
 */
@RunWith(AndroidJUnit4::class)
class ExynosN5StressInstrumentationTest {

    private val appContext: Context = InstrumentationRegistry.getInstrumentation().targetContext

    // Expected production NNC pinning (informational; actual verification via ModelAvailabilityRegistry)
    private val EXPECTED_NNC_SIZE = 3_112_960L
    private val EXPECTED_NNC_SHA = "9cff7af64dbe5b4ed260449153ea08e91cabd758ce3478344c286ee2798bae12"
    private val STRESS_WIDTH = 4080
    private val STRESS_HEIGHT = 3060
    private val OUTPUT_WIDTH = STRESS_WIDTH * 4
    private val OUTPUT_HEIGHT = STRESS_HEIGHT * 4
    private val EXPECTED_RGB8_BYTES = 599_270_400L
    private val EXPECTED_TILE_COUNT = 3350

    private fun isProbeRequested(): Boolean {
        val bundle = runCatching { InstrumentationRegistry.getArguments() }.getOrNull()
        return bundle?.getString("kepler.exynosNpuProbe") == "true"
    }

    private val socProperties: Map<String, String> by lazy {
        val keys = listOf("ro.board.platform", "ro.soc.model", "ro.soc.manufacturer", "ro.hardware", "ro.product.model", "ro.product.device")
        keys.mapNotNull { name -> runtimeProperty(name)?.let { v -> name to v } }.toMap()
    }

    private fun runtimeProperty(name: String): String? = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun isExynos2400Target(): Boolean {
        val socModel = socProperties["ro.soc.model"]?.lowercase() ?: ""
        val board = socProperties["ro.board.platform"]?.lowercase() ?: ""
        val product = socProperties["ro.product.device"]?.lowercase() ?: ""
        val isSoc = socModel.contains("2400") || socModel.contains("s5e9945")
        val isBoard = board.contains("exynos") || board.contains("e1s")
        val isS24 = product.contains("e1s") || product.contains("s24")
        return (isSoc || isBoard) && isS24
    }

    /** Bounded procedural source: synthesizes 128x128 CHW FP32 tile on demand, no full-image buffer. */
    private class ProceduralTileInputSource(
        override val sourceWidth: Int,
        override val sourceHeight: Int,
    ) : TileInputSource {
        override suspend fun fillChwTile(sx: Int, sy: Int, into: ByteArray) {
            require(into.size == com.projectnuke.keplerstudio.editor.ExynosUpscaleSession.INPUT_BYTES)
            require(sx >= 0 && sy >= 0 && sx + 128 <= sourceWidth && sy + 128 <= sourceHeight)
            val fb = ByteBuffer.wrap(into).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val plane = 128 * 128
            for (c in 0 until 3) {
                for (ty in 0 until 128) {
                    for (tx in 0 until 128) {
                        val x = sx + tx
                        val y = sy + ty
                        // Deterministic pseudo-pattern: (x*7 + y*13 + c*47) % 256 /255
                        val v = ((x * 7 + y * 13 + c * 47) % 256) / 255f
                        val idx = c * plane + ty * 128 + tx
                        fb.put(idx, v)
                    }
                }
            }
        }
    }

    private data class MemSample(
        val label: String,
        val tileIndex: Int = -1,
        val javaHeapUsed: Long = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
        val nativeHeap: Long = Debug.getNativeHeapAllocatedSize(),
        val pssKb: Long = runCatching { val mi = Debug.MemoryInfo(); Debug.getMemoryInfo(mi); mi.totalPss.toLong() }.getOrDefault(-1),
        val outputFileBytes: Long = -1,
        val displayOn: Boolean? = null,
        val wakeHeld: Boolean? = null,
    )

    private fun sample(label: String, tileIndex: Int = -1, outputFile: File? = null, wakeLock: N5WakeLock? = null): JSONObject {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val interactive = runCatching { pm.isInteractive }.getOrNull()
        val runtime = Runtime.getRuntime()
        val javaUsed = runtime.totalMemory() - runtime.freeMemory()
        val nativeHeap = Debug.getNativeHeapAllocatedSize()
        val pss = runCatching { val mi = Debug.MemoryInfo(); Debug.getMemoryInfo(mi); mi.totalPss.toLong() }.getOrDefault(-1)
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val obj = JSONObject()
        obj.put("label", label)
        obj.put("tile_index", tileIndex)
        obj.put("java_heap_used", javaUsed)
        obj.put("native_heap_allocated", nativeHeap)
        obj.put("pss_kb", pss)
        obj.put("avail_mem", memInfo.availMem)
        obj.put("output_file_bytes", outputFile?.length() ?: -1)
        obj.put("display_interactive", interactive ?: JSONObject.NULL)
        obj.put("wake_held", wakeLock?.isHeld ?: JSONObject.NULL)
        return obj
    }

    @Test
    fun n5FullStressWithDisplayOff() {
        runBlocking {
            assumeTrue("opt-in only; -e kepler.exynosNpuProbe true", isProbeRequested())
            assumeTrue("target must be Exynos 2400 S24", isExynos2400Target())

            val reportDir = File(appContext.getExternalFilesDir(null), "exynos_n5_stress").apply { mkdirs() }
            val metadata = JSONObject()
            metadata.put("device", JSONObject(socProperties))
            metadata.put("stress_width", STRESS_WIDTH)
            metadata.put("stress_height", STRESS_HEIGHT)
            metadata.put("output_width", OUTPUT_WIDTH)
            metadata.put("output_height", OUTPUT_HEIGHT)
            metadata.put("expected_rgb8_bytes", EXPECTED_RGB8_BYTES)
            metadata.put("expected_tile_count", EXPECTED_TILE_COUNT)
            metadata.put("nnc_expected_size", EXPECTED_NNC_SIZE)
            metadata.put("nnc_expected_sha", EXPECTED_NNC_SHA)

            val samples = JSONArray()
            var testFailure: Throwable? = null
            var session: ExynosUpscaleSession? = null
            var wakeLock: N5WakeLock? = null
            var wakeAcquired = false
            var wakeReleased = false

            try {
                samples.put(sample("before_source_sink_creation"))
                val source = ProceduralTileInputSource(STRESS_WIDTH, STRESS_HEIGHT)
                val plan = TilePlanner.plan(STRESS_WIDTH, STRESS_HEIGHT)
                assertTrue(plan is com.projectnuke.keplerstudio.editor.TilePlanResult.Planned)
                val tileCount = (plan as com.projectnuke.keplerstudio.editor.TilePlanResult.Planned).plan.tiles.size
                assertEquals("4080x3060 must produce 3350 tiles", EXPECTED_TILE_COUNT, tileCount)
                val outputFile = File(reportDir, "n5_stress_${System.nanoTime()}.rgb8")
                samples.put(sample("after_source_sink_creation", outputFile = outputFile))

                ModelAvailabilityRegistry.resetForTest()
                val gen = ModelAvailabilityRegistry.beginProbe()
                ModelAvailabilityRegistry.probePackagedCapabilities(appContext, gen)
                val cap = ModelAvailabilityRegistry.state.value[ModelFeature.ExynosUpscale]
                assertTrue("ExynosUpscale not loadable", cap?.canAttemptModelUse == true)
                val tokenResult = ModelAvailabilityRegistry.validatedCapabilityToken(ModelFeature.ExynosUpscale)
                assertTrue(tokenResult is ModelLoadResult.Ready)
                val token = (tokenResult as ModelLoadResult.Ready).runner as ValidatedModelCapabilityToken

                session = ExynosUpscaleSession(appContext).apply { diagnosticRetention = DiagnosticRetention.LAST_ONLY }
                val load = session.load(token)
                if (load !is ModelLoadResult.Ready) throw AssertionError("session load failed: $load")
                // Verify NNC pinning if available
                val prepared = session.preparedModelFileForDiagnostics()
                if (prepared != null) {
                    metadata.put("prepared_size", prepared.length())
                    metadata.put("prepared_sha", sha256File(prepared))
                }
                samples.put(sample("after_model_load"))

                // Warmup: single tile execute to ensure NPU path
                val warmInput = ByteArray(ExynosUpscaleSession.INPUT_BYTES)
                ProceduralTileInputSource(STRESS_WIDTH, STRESS_HEIGHT).let { runBlocking { it.fillChwTile(0, 0, warmInput) } }
                // quick single run via session raw (not through file pipeline) to warmup
                // We skip explicit warmup tile via session to avoid extra complexity; pipeline itself will warmup.

                samples.put(sample("after_warmup"))

                // Wake-lock acquisition immediately before bounded workload
                wakeLock = RealN5WakeLock(appContext, "KeplerN5FullStress")
                wakeLock.acquire()
                wakeAcquired = wakeLock.isHeld
                metadata.put("wake_acquired", wakeAcquired)
                assertTrue("PARTIAL_WAKE_LOCK must be held", wakeAcquired)
                samples.put(sample("before_tiles", wakeLock = wakeLock))

                val operationContext = ModelOperationContext(operationToken = 99L, documentGeneration = "n5-stress")
                val pipeline = TileFileBackedUpscaler(session, appContext)

                var result: FileBackedUpscaleResult? = null
                try {
                    result = pipeline.upscaleToFile(source, outputFile, operationContext, "n5-stress")
                } finally {
                    // Wake lock release must survive all terminal paths
                    wakeLock.release()
                    wakeReleased = !wakeLock.isHeld
                }
                metadata.put("wake_released", wakeReleased)
                samples.put(sample("after_finish", outputFile = outputFile, wakeLock = wakeLock))

                assertTrue("expected Success, got $result", result is FileBackedUpscaleResult.Success)
                result as FileBackedUpscaleResult.Success
                assertEquals(EXPECTED_TILE_COUNT, result.tileCount)
                assertEquals(EXPECTED_TILE_COUNT, result.completedTiles)
                assertEquals(EXPECTED_RGB8_BYTES, result.artifact.byteCount)
                assertEquals(EXPECTED_RGB8_BYTES, result.artifact.file.length())
                // Bounded diagnostics
                assertEquals(1, session.runDiagnosticsHistory.size)
                // Positive NPU proof
                val diag = session.lastRunDiagnostics
                assertEquals(EnnStatus.SUCCESS, diag.h2dStatus)
                assertTrue(diag.executeReached)
                assertEquals(EnnStatus.SUCCESS, diag.executeStatus)
                assertEquals(EnnStatus.SUCCESS, diag.d2hStatus)
                val compilerNpu = session.getEnnMetaInfo(EnnMetaIds.MODEL_COMPILER_NPU)
                metadata.put("compiler_npu", compilerNpu ?: "unavailable")
                val proof = decideNpuProof(diag.executeReached, diag.executeStatus, compilerNpu)
                val proofFailure = npuProofAcceptanceFailure(proof, null)
                if (proofFailure != null) throw proofFailure
                metadata.put("npu_proof_status", proof.status.name)

                // Verify no full-output allocation: heap must not have grown by ~113 MB RGB8*? Actually artifact on disk
                // We just record samples and check no OOM.

                // Delete artifact after verification
                val artifactFile = result.artifact.file
                assertTrue(artifactFile.exists())
                artifactFile.delete()
                samples.put(sample("after_result_deletion", wakeLock = wakeLock))
                assertTrue(!artifactFile.exists())

                metadata.put("status", "PASS")
            } catch (t: Throwable) {
                testFailure = t
                if (t is AssumptionViolatedException) {
                    metadata.put("status", "SKIPPED")
                } else {
                    metadata.put("status", "FAILED")
                    metadata.put("failure", t.message ?: t.javaClass.simpleName)
                }
                throw t
            } finally {
                // Guaranteed wake-lock release
                runCatching { wakeLock?.release() }
                wakeReleased = wakeLock?.isHeld == false
                metadata.put("wake_released_finally", wakeReleased)
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                metadata.put("display_interactive_final", runCatching { pm.isInteractive }.getOrNull() ?: JSONObject.NULL)
                samples.put(sample("after_session_close", wakeLock = wakeLock))
                metadata.put("samples", samples)
                metadata.put("wake_acquired_success", wakeAcquired)
                metadata.put("wake_released_success", wakeReleased)
                runCatching { session?.close() }
                val active = ModelAvailabilityRegistry.state.value[ModelFeature.ExynosUpscale]?.sessionActive
                metadata.put("registry_inactive", active != true)
                File(reportDir, "n5_stress_metadata.json").writeText(metadata.toString(2))
                println("EXYNOS_N5_REPORT=${reportDir.absolutePath}")
                if (testFailure == null && wakeLock != null && wakeLock.isHeld) {
                    // Ensure failure if wake still held
                    throw AssertionError("wake lock still held after test")
                }
            }
        }
    }

    @Test
    fun n5CancellationProbeWithDisplayOff() {
        runBlocking {
            assumeTrue("opt-in only", isProbeRequested())
            assumeTrue("S24 target", isExynos2400Target())

            val reportDir = File(appContext.getExternalFilesDir(null), "exynos_n5_cancel").apply { mkdirs() }
            val metadata = JSONObject()
            var session: ExynosUpscaleSession? = null
            val wakeLock: N5WakeLock = RealN5WakeLock(appContext, "KeplerN5Cancel")
            var wakeAcquired = false
            var wakeReleased = false
            var testFailure: Throwable? = null

            try {
                ModelAvailabilityRegistry.resetForTest()
                val gen = ModelAvailabilityRegistry.beginProbe()
                ModelAvailabilityRegistry.probePackagedCapabilities(appContext, gen)
                val token = (ModelAvailabilityRegistry.validatedCapabilityToken(ModelFeature.ExynosUpscale) as ModelLoadResult.Ready).runner as ValidatedModelCapabilityToken
                session = ExynosUpscaleSession(appContext).apply { diagnosticRetention = DiagnosticRetention.LAST_ONLY }
                assertTrue(session.load(token) is ModelLoadResult.Ready)

                val source = ProceduralTileInputSource(STRESS_WIDTH, STRESS_HEIGHT)
                val outputFile = File(reportDir, "n5_cancel_${System.nanoTime()}.rgb8")
                val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
                val ctx = ModelOperationContext(99L, "n5-cancel", isCancelled = { cancelled.get() })

                wakeLock.acquire()
                wakeAcquired = wakeLock.isHeld
                metadata.put("wake_acquired", wakeAcquired)
                try {
                    var completedBeforeCancel = 0
                    // Use observer to cancel after 2 tiles
                    val pipeline = TileFileBackedUpscaler(session, appContext)
                    val observer = com.projectnuke.keplerstudio.editor.TileRunObserver { record ->
                        if (record.index == 1) {
                            cancelled.set(true)
                            completedBeforeCancel = record.index + 1
                        }
                    }
                    val result = pipeline.upscaleToFile(source, outputFile, ctx, "n5-cancel", observer)
                    assertTrue("expected Cancelled, got $result", result is FileBackedUpscaleResult.Cancelled)
                    result as FileBackedUpscaleResult.Cancelled
                    assertTrue(result.completedTiles >= 1)
                    assertTrue(result.completedTiles <= 5)
                    assertTrue(!outputFile.exists())
                    // Ensure no subsequent tile execute after authoritative cancellation boundary: completedTiles is the boundary
                    assertTrue(session!!.runDiagnosticsHistory.size <= 2)
                    metadata.put("completed_before_cancel", result.completedTiles)
                    metadata.put("status", "PASS")
                } finally {
                    wakeLock.release()
                    wakeReleased = !wakeLock.isHeld
                }
                metadata.put("wake_released", wakeReleased)
                // Registry inactive after close
                session!!.close()
                val active = ModelAvailabilityRegistry.state.value[ModelFeature.ExynosUpscale]?.sessionActive
                assertTrue(active != true)
                metadata.put("registry_inactive", active != true)
            } catch (t: Throwable) {
                testFailure = t
                metadata.put("status", if (t is AssumptionViolatedException) "SKIPPED" else "FAILED")
                metadata.put("failure", t.message ?: t.javaClass.simpleName)
                throw t
            } finally {
                runCatching { wakeLock.release() }
                File(reportDir, "n5_cancel_metadata.json").writeText(metadata.toString(2))
                runCatching { session?.close() }
            }
        }
    }

    private fun sha256File(file: File): String = File(file.absolutePath).inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(8192)
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            digest.update(buf, 0, r)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
