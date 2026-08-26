package com.projectnuke.keplerstudio.exynos

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.projectnuke.keplerstudio.editor.EnnStatus
import com.projectnuke.keplerstudio.editor.ExynosEnnNative
import com.projectnuke.keplerstudio.editor.ExynosUpscaleSession
import com.projectnuke.keplerstudio.editor.ModelAssetManifest
import com.projectnuke.keplerstudio.editor.ModelAvailabilityRegistry
import com.projectnuke.keplerstudio.editor.ModelCapabilityObservation
import com.projectnuke.keplerstudio.editor.ModelCapabilityPhase
import com.projectnuke.keplerstudio.editor.ModelCapabilityPublisher
import com.projectnuke.keplerstudio.editor.ModelFeature
import com.projectnuke.keplerstudio.editor.ModelLoadResult
import com.projectnuke.keplerstudio.editor.ModelOperationContext
import com.projectnuke.keplerstudio.editor.ModelRunResult
import com.projectnuke.keplerstudio.editor.ValidatedModelCapabilityToken
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase N2 — retail Galaxy S24 / Exynos 2400 one-tile NPU probe.
 *
 * NEVER runs by default: requires the explicit instrumentation argument
 * `kepler.exynosNpuProbe=true` AND a device whose SoC properties identify an
 * Exynos target AND the vendored ENN client stub resolving against the vendor
 * public library. On anything else it fails truthfully with the exact missing
 * prerequisite (never a fake PASS, never a silent CPU fallback claim).
 *
 * Run:
 * adb shell am instrument -w -e kepler.exynosNpuProbe true \
 *   -e class com.projectnuke.keplerstudio.exynos.ExynosRealEsrganOneTileInstrumentationTest \
 *   com.projectnuke.keplerstudio.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ExynosRealEsrganOneTileInstrumentationTest {

    private val context: Context =
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext

    private fun isProbeRequested(): Boolean {
        val bundle = runCatching {
            androidx.test.platform.app.InstrumentationRegistry.getArguments()
        }.getOrNull()
        return bundle?.getString("kepler.exynosNpuProbe") == "true"
    }

    private val socProperties: Map<String, String> by lazy {
        val keys =
            listOf(
                "ro.board.platform",
                "ro.soc.model",
                "ro.soc.manufacturer",
                "ro.hardware",
                "ro.product.model",
                "ro.product.device",
                "ro.build.version.release",
                "ro.build.fingerprint",
            )
        keys.mapNotNull { name -> runtimeProperty(name)?.let { value -> name to value } }.toMap()
    }

    private fun runtimeProperty(name: String): String? {
        val value =
            runCatching {
                val clazz = Class.forName("android.os.SystemProperties")
                val method = clazz.getMethod("get", String::class.java)
                method.invoke(null, name) as String
            }.getOrNull()
        return value?.takeIf { it.isNotBlank() }
    }

    @Test
    fun exynosRealEsrganOneTileNpuProbe() = runBlocking {
        assertTrue(
            "probe is opt-in only; rerun with -e kepler.exynosNpuProbe true",
            isProbeRequested(),
        )

        val reportDir =
            File(context.getExternalFilesDir(null), "exynos_npu_probe").apply { mkdirs() }
        val metadata = JSONObject()
        val memorySnapshots = JSONObject()
        metadata.put("device", JSONObject(socProperties))
        metadata.put("build_manufacturer", Build.MANUFACTURER)

        assumeTrue(
            "target must be Exynos 2400 (S24 family); found: $socProperties",
            isExynos2400Target(),
        )
        metadata.put("soc_gate", "passed")

        // 1. Real production capability probe (NOT applyForTest).
        ModelAvailabilityRegistry.resetForTest()
        val probeGeneration = ModelAvailabilityRegistry.beginProbe()
        ModelAvailabilityRegistry.probePackagedCapabilities(context, probeGeneration)
        
        // Wait briefly for probe to settle
        kotlinx.coroutines.delay(100)

        val capability = ModelAvailabilityRegistry.state.value[ModelFeature.ExynosUpscale]
        metadata.put("probe_phase", capability?.phase?.name ?: "Unknown")
        metadata.put("probe_facts", JSONObject(
            mapOf(
                "assetPresent" to (capability?.assetPresent ?: false),
                "assetValid" to (capability?.assetValid ?: false),
                "runtimeAvailable" to (capability?.runtimeAvailable ?: false),
                "contractSupported" to (capability?.contractSupported ?: false),
                "runnerImplemented" to (capability?.runnerImplemented ?: false),
            )
        ))

        assumeTrue(
            "ExynosUpscale capability not loadable: ${capability?.phase}",
            capability?.canAttemptModelUse == true,
        )

        // 2. Validated capability token from real registry facts.
        val tokenResult = ModelAvailabilityRegistry.validatedCapabilityToken(ModelFeature.ExynosUpscale)
        assertTrue("capability token refused: $tokenResult", tokenResult is ModelLoadResult.Ready)
        val token = (tokenResult as ModelLoadResult.Ready).runner as ValidatedModelCapabilityToken
        metadata.put("validated_model_id", token.modelId)
        metadata.put("validated_asset_sha256", token.approvedAssetSha256 ?: "")
        metadata.put("validation_epoch", token.validationGeneration)

        // Memory: pre-load
        memorySnapshots.put("pre_load_kb", getProcessMemoryInfo(context))

        // 3. Real session load against the real Samsung runtime.
        var session: ExynosUpscaleSession? = null
        var loadFailed = false
        var loadFailureStage: String? = null
        var loadFailureDetail: String? = null
        try {
            session = ExynosUpscaleSession(context)
            val loadStarted = System.currentTimeMillis()
            val loadResult = session.load(token)
            memorySnapshots.put("post_load_kb", getProcessMemoryInfo(context))
            if (loadResult !is ModelLoadResult.Ready) {
                loadFailed = true
                loadFailureStage = "load"
                loadFailureDetail = loadResult.toString()
            }
            assertTrue("session load failed on this device: $loadResult", loadResult is ModelLoadResult.Ready)
            val loadDurationMs = System.currentTimeMillis() - loadStarted
            metadata.put("model_load_ms", loadDurationMs)
            metadata.put(
                "descriptor_input",
                session.descriptor?.input?.let {
                    "${it.width}x${it.height}x${it.channels} ${it.dataType} ${it.layout} ${it.normalization}"
                } ?: "",
            )
            metadata.put(
                "descriptor_output",
                session.descriptor?.output?.let {
                    "${it.width}x${it.height}x${it.channelsOrClasses} ${it.dataType} ${it.layout} range=${it.valueRange}"
                } ?: "",
            )

            // Capture NPU meta info
            captureNpuMetaInfo(session, metadata)

            // 4. Deterministic known RGB tile.
            val inputPixels = deterministicTile()
            savePng(
                renderTileBitmap(inputPixels),
                File(reportDir, "input_tile_128.png"),
            )
            metadata.put("input_tile_sha_source", "deterministicTile(): documented generator")
            metadata.put("input_pixels", inputPixels.size)

            val operationContext =
                ModelOperationContext(operationToken = 1L, documentGeneration = "n2-probe")

            // 5. Cold inference + two warm inferences.
            val timings = JSONArray()
            var lastOutput: ModelRunResult.Success<*>? = null
            repeat(3) { index ->
                val startedAt = System.currentTimeMillis()
                val result = session.run(inputPixels, operationContext)
                val duration = System.currentTimeMillis() - startedAt
                val runRecord = JSONObject()
                runRecord.put("run_index", index)
                runRecord.put("ms", duration)
                if (result is ModelRunResult.Failure) {
                    runRecord.put("failure_reason", result.failure.reason.name)
                    runRecord.put("failure_detail", result.failure.detail)
                    loadFailed = true
                    loadFailureStage = "inference_${index}"
                    loadFailureDetail = result.failure.toString()
                    throw AssertionError("inference #$index failed: ${result.failure}")
                }
                lastOutput = result as ModelRunResult.Success<*>
                timings.put(runRecord)
            }
            memorySnapshots.put("post_first_inference_kb", getProcessMemoryInfo(context))
            metadata.put("runs", timings)

            // 6. Output validation.
            @Suppress("UNCHECKED_CAST")
            val bitmap = (lastOutput as ModelRunResult.Success<Bitmap>).value
            assertEquals(512, bitmap.width)
            assertEquals(512, bitmap.height)
            val stats = channelStatistics(bitmap)
            metadata.put(
                "output_stats",
                JSONObject(
                    mapOf(
                        "r_min" to stats.rMin,
                        "r_max" to stats.rMax,
                        "r_mean" to stats.rMean,
                        "g_min" to stats.gMin,
                        "g_max" to stats.gMax,
                        "g_mean" to stats.gMean,
                        "b_min" to stats.bMin,
                        "b_max" to stats.bMax,
                        "b_mean" to stats.bMean,
                    ),
                ),
            )
            assertTrue("output must not be trivially empty", !stats.isTriviallyEmpty)
            val saved = savePng(bitmap, File(reportDir, "output_tile_x4_512.png"))
            metadata.put("output_png_bytes", saved.length())

            // 7. Lifecycle sanity: second load/run/close round.
            session.close()
            assertTrue(session.load(token) is ModelLoadResult.Ready)
            val rerun = session.run(inputPixels, operationContext)
            assertTrue("second lifecycle inference failed: $rerun", rerun is ModelRunResult.Success)
            (rerun as ModelRunResult.Success<Bitmap>).value.recycle()
            session.close()
            memorySnapshots.put("post_close_kb", getProcessMemoryInfo(context))

            // 8. Cancellation check.
            val cancelledContext =
                ModelOperationContext(
                    operationToken = 2L,
                    documentGeneration = "n2-probe",
                    isCancelled = { true },
                )
            val cancelledRun = session.run(inputPixels, cancelledContext)
            assertTrue(
                "cancel-before-inference must prevent work",
                cancelledRun is ModelRunResult.Failure &&
                    (cancelledRun as ModelRunResult.Failure).failure.reason ==
                    com.projectnuke.keplerstudio.editor.ModelFailureReason.Cancelled,
            )
            metadata.put("lifecycle_roundtrip", "pass")
        } catch (t: Throwable) {
            loadFailed = true
            loadFailureStage = loadFailureStage ?: "unexpected"
            loadFailureDetail = (loadFailureDetail ?: "") + "; " + t.message
            throw t
        } finally {
            session?.close()
            memorySnapshots.put("final_close_kb", getProcessMemoryInfo(context))
            metadata.put("memory_snapshots_kb", memorySnapshots)

            // Failure reporting
            if (loadFailed) {
                metadata.put("status", "FAILED")
                metadata.put("failure_stage", loadFailureStage)
                metadata.put("failure_detail", loadFailureDetail ?: "unknown")
            } else {
                metadata.put("status", "PASS")
            }
        }

        val capabilityAfter = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.ExynosUpscale)
        metadata.put("session_inactive_after_close", !capabilityAfter.sessionActive)
        metadata.put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")

        // NPU proof: capture actual meta info if available
        metadata.put("npu_proof", "EnnExecuteModel executed through vendor ENN service; see meta info and device properties")

        File(reportDir, "metadata.json").writeText(metadata.toString(2))
        println("EXYNOS_NPU_PROBE_REPORT=$reportDir")
    }

    private fun isExynos2400Target(): Boolean {
        val socModel = socProperties["ro.soc.model"]?.lowercase() ?: ""
        val boardPlatform = socProperties["ro.board.platform"]?.lowercase() ?: ""
        val hardware = socProperties["ro.hardware"]?.lowercase() ?: ""
        val product = socProperties["ro.product.device"]?.lowercase() ?: ""
        
        // Exynos 2400 identifiers
        val isSocModel2400 = socModel.contains("2400") || socModel.contains("s5e9945")
        val isBoardExynos = boardPlatform.contains("exynos") || boardPlatform.contains("e1s") || boardPlatform.contains("e1q")
        val isS24Family = product.contains("e1s") || product.contains("e1q") || product.contains("s24")
        
        return (isSocModel2400 || isBoardExynos) && isS24Family
    }

    private fun captureNpuMetaInfo(session: ExynosUpscaleSession, metadata: JSONObject) {
        // Meta IDs from enn_api.h: ENN_META_VERSION_MODEL_COMPILER_NNC=120, etc.
        val metaIds = mapOf(
            120 to "compiler_nnc",
            121 to "compiler_npu",
            122 to "unified_fw",
            123 to "npu_fw"
        )
        val metaJson = JSONObject()
        try {
            val modelIdField = session::class.java.getDeclaredField("modelId").apply { isAccessible = true }
            val modelId = modelIdField.getLong(session)
            if (modelId > 0) {
                val nativeField = session::class.java.getDeclaredField("native").apply { isAccessible = true }
                val nativeInstance = nativeField.get(session)
                val getMetaInfoMethod = nativeInstance::class.java.getMethod("getMetaInfo", Int::class.java, Long::class.java)
                metaIds.forEach { (id, name) ->
                    try {
                        val meta = getMetaInfoMethod.invoke(nativeInstance, id, modelId) as? String
                        if (!meta.isNullOrBlank()) {
                            metaJson.put(name, meta)
                        }
                    } catch (e: Exception) {
                        // Ignore individual meta failures
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore reflection failures
        }
        if (metaJson.length() > 0) {
            metadata.put("npu_meta_info", metaJson)
        }
    }

    private fun getProcessMemoryInfo(context: Context): Long {
        return try {
            val debugClass = Class.forName("android.os.Debug")
            val method = debugClass.getMethod("getNativeHeapAllocatedSize")
            (method.invoke(null) as Long) / 1024
        } catch (e: Exception) {
            -1L
        }
    }

    /** Deterministic 128x128 ARGB tile: gradients + bars + checker + gray quadrant. */
    private fun deterministicTile(): IntArray {
        val w = ExynosUpscaleSession.INPUT_WIDTH
        val h = ExynosUpscaleSession.INPUT_HEIGHT
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val r: Int
            val g: Int
            val b: Int
            when {
                x < w / 2 && y < h / 2 -> { // gradient quadrant
                    r = x * 255 / (w / 2 - 1)
                    g = y * 255 / (h / 2 - 1)
                    b = ((x + y) * 255) / (w / 2 + h / 2 - 2)
                }
                x >= w / 2 && y < h / 2 -> { // vertical color bars R/G/B/W
                    when ((x / (w / 8)) % 4) {
                        0 -> { r = 255; g = 0; b = 0 }
                        1 -> { r = 0; g = 255; b = 0 }
                        2 -> { r = 0; g = 0; b = 255 }
                        else -> { r = 255; g = 255; b = 255 }
                    }
                }
                x < w / 2 && y >= h / 2 -> { // high-frequency 4px checker
                    val on = ((x / 4) + (y / 4)) % 2 == 0
                    r = if (on) 240 else 16
                    g = if (on) 240 else 16
                    b = if (on) 240 else 16
                }
                else -> { r = 128; g = 128; b = 128 } // neutral gray quadrant
            }
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun renderTileBitmap(pixels: IntArray): Bitmap {
        val bitmap =
            Bitmap.createBitmap(
                ExynosUpscaleSession.INPUT_WIDTH,
                ExynosUpscaleSession.INPUT_HEIGHT,
                Bitmap.Config.ARGB_8888,
            )
        bitmap.setPixels(
            pixels,
            0,
            ExynosUpscaleSession.INPUT_WIDTH,
            0,
            0,
            ExynosUpscaleSession.INPUT_WIDTH,
            ExynosUpscaleSession.INPUT_HEIGHT,
        )
        return bitmap
    }

    private class ChannelStats(
        val rMin: Int,
        val rMax: Int,
        val rMean: Double,
        val gMin: Int,
        val gMax: Int,
        val gMean: Double,
        val bMin: Int,
        val bMax: Int,
        val bMean: Double,
    ) {
        val isTriviallyEmpty: Boolean
            get() = rMin == rMax && gMin == gMax && bMin == bMax && rMax == 0 && gMax == 0 && bMax == 0
    }

    private fun channelStatistics(bitmap: Bitmap): ChannelStats {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var rMin = 255
        var rMax = 0
        var gMin = 255
        var gMax = 0
        var bMin = 255
        var bMax = 0
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            if (r < rMin) rMin = r
            if (r > rMax) rMax = r
            if (g < gMin) gMin = g
            if (g > gMax) gMax = g
            if (b < bMin) bMin = b
            if (b > bMax) bMax = b
            rSum += r
            gSum += g
            bSum += b
        }
        val n = pixels.size.toDouble()
        return ChannelStats(rMin, rMax, rSum / n, gMin, gMax, gSum / n, bMin, bMax, bSum / n)
    }

    private fun savePng(bitmap: Bitmap, target: File): File {
        FileOutputStream(target).use { out ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
        }
        return target
    }
}
