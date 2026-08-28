package com.projectnuke.keplerstudio.exynos

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.projectnuke.keplerstudio.editor.EnnMetaIds
import com.projectnuke.keplerstudio.editor.EnnStatus
import com.projectnuke.keplerstudio.editor.ExynosEnnNative
import com.projectnuke.keplerstudio.editor.ExynosUpscaleSession
import com.projectnuke.keplerstudio.editor.finalizeProbeReport
import com.projectnuke.keplerstudio.editor.ModelAssetManifest
import com.projectnuke.keplerstudio.editor.ModelAvailabilityRegistry
import com.projectnuke.keplerstudio.editor.ModelCapabilityObservation
import com.projectnuke.keplerstudio.editor.ModelCapabilityPhase
import com.projectnuke.keplerstudio.editor.ModelCapabilityPublisher
import com.projectnuke.keplerstudio.editor.ModelFeature
import com.projectnuke.keplerstudio.editor.ModelLoadResult
import com.projectnuke.keplerstudio.editor.ModelOperationContext
import com.projectnuke.keplerstudio.editor.ModelRunResult
import com.projectnuke.keplerstudio.editor.NpuProofStatus
import com.projectnuke.keplerstudio.editor.ValidatedModelCapabilityToken
import com.projectnuke.keplerstudio.editor.decideNpuProof
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import com.projectnuke.keplerstudio.editor.npuProofAcceptanceFailure
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AssumptionViolatedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase N2 — retail Galaxy S24 / Exynos 2400 one-tile NPU probe.
 *
 * Opt-in semantics:
 *  - no explicit `kepler.exynosNpuProbe=true` -> JUnit assumption SKIP (no report);
 *  - non-S24 target -> JUnit assumption SKIP (persists metadata with status=SKIPPED);
 *  - once BOTH are true (explicit probe requested + S24 / Exynos-2400 target gate passed),
 *    all later prerequisite or capability failures are REAL N2 failures (never skipped).
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
    fun exynosRealEsrganOneTileNpuProbe() {
        runBlocking {
        // OPT-IN GATE: default test runs skip here immediately without creating report artifacts.
        assumeTrue(
            "probe is opt-in only; rerun with -e kepler.exynosNpuProbe true",
            isProbeRequested(),
        )

        val reportDir =
            File(context.getExternalFilesDir(null), "exynos_npu_probe").apply { mkdirs() }
        val metadata = JSONObject()
        val memorySnapshots = JSONObject()
        metadata.put("report_absolute_path", reportDir.absolutePath)
        metadata.put("device", JSONObject(socProperties))
        metadata.put("build_manufacturer", Build.MANUFACTURER)
        metadata.put(
            "memory_metric",
            "native_heap_allocated_kb (android.os.Debug.getNativeHeapAllocatedSize / 1024; NOT total process PSS/RSS)",
        )

        var testFailure: Throwable? = null
        var session: ExynosUpscaleSession? = null
        val runDiagnostics = mutableListOf<com.projectnuke.keplerstudio.editor.ExynosRunDiagnostics>()

        try {
            // APPLICABILITY GATE: non-S24 target skips with a persisted report marked SKIPPED.
            assumeTrue(
                "target must be Exynos 2400 (S24 family); found: $socProperties",
                isExynos2400Target(),
            )
            metadata.put("soc_gate", "passed")

            ModelAvailabilityRegistry.resetForTest()
            val probeGeneration = ModelAvailabilityRegistry.beginProbe()
            ModelAvailabilityRegistry.probePackagedCapabilities(context, probeGeneration)

            val capability = ModelAvailabilityRegistry.state.value[ModelFeature.ExynosUpscale]
            metadata.put("probe_phase", capability?.phase?.name ?: "Unknown")
            metadata.put(
                "probe_facts",
                JSONObject(
                    mapOf(
                        "assetPresent" to (capability?.assetPresent ?: false),
                        "assetValid" to (capability?.assetValid ?: false),
                        "runtimeAvailable" to (capability?.runtimeAvailable ?: false),
                        "contractSupported" to (capability?.contractSupported ?: false),
                        "runnerImplemented" to (capability?.runnerImplemented ?: false),
                    ),
                ),
            )

            // Once both opt-in and target gate pass, capability failure MUST assert/fail, never skip.
            assertTrue(
                "ExynosUpscale capability not loadable on requested+applicable target: phase=${capability?.phase} facts=${capability?.let { JSONObject(mapOf("present" to it.assetPresent, "valid" to it.assetValid, "runtime" to it.runtimeAvailable, "contract" to it.contractSupported)) }}",
                capability?.canAttemptModelUse == true,
            )

            val tokenResult =
                ModelAvailabilityRegistry.validatedCapabilityToken(ModelFeature.ExynosUpscale)
            assertTrue("capability token refused: $tokenResult", tokenResult is ModelLoadResult.Ready)
            val token = (tokenResult as ModelLoadResult.Ready).runner as ValidatedModelCapabilityToken
            metadata.put("validated_model_id", token.modelId)
            metadata.put("validated_asset_sha256", token.approvedAssetSha256 ?: "")
            metadata.put("validation_epoch", token.validationGeneration)

            memorySnapshots.put("pre_load_kb", getNativeHeapAllocatedKb())

            session = ExynosUpscaleSession(context)
            val loadStartedNanos = SystemClock.elapsedRealtimeNanos()
            val loadResult = session.load(token)
            val loadDurationMs = (SystemClock.elapsedRealtimeNanos() - loadStartedNanos) / 1_000_000L
            memorySnapshots.put("post_load_kb", getNativeHeapAllocatedKb())
            if (loadResult !is ModelLoadResult.Ready) {
                metadata.put("load_failure_stage", "load")
                metadata.put("load_failure_detail", loadResult.toString())
                throw AssertionError("session load failed on this device: $loadResult")
            }
            metadata.put("model_load_ms", loadDurationMs)
            val preparedFile = checkNotNull(session.preparedModelFileForDiagnostics())
            val preparedSha256 = sha256(preparedFile)
            val activeManifest = checkNotNull(ModelAssetManifest.byId(token.modelId))
            metadata.put("prepared_file_path", preparedFile.absolutePath)
            metadata.put("prepared_file_bytes", preparedFile.length())
            metadata.put("prepared_file_sha256", preparedSha256)
            metadata.put("prepared_file_expected_bytes", activeManifest.asset.minimumExpectedBytes)
            metadata.put("prepared_file_expected_sha256", activeManifest.asset.sha256 ?: "")
            assertEquals(activeManifest.asset.minimumExpectedBytes, activeManifest.asset.maximumExpectedBytes)
            assertEquals(activeManifest.asset.minimumExpectedBytes, preparedFile.length())
            assertEquals(activeManifest.asset.sha256, preparedSha256)
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

            captureNpuMetaInfo(session, metadata)

            val inputPixels = deterministicTile()
            val inputBitmap = renderTileBitmap(inputPixels)
            val inputPngFailure =
                runCatching { savePng(inputBitmap, File(reportDir, "input_tile_128.png")) }
                    .exceptionOrNull()
            inputBitmap.recycle()
            metadata.put("input_png_write_succeeded", inputPngFailure == null)
            if (inputPngFailure != null) {
                throw AssertionError("input PNG write failed", inputPngFailure)
            }
            metadata.put("input_png_sha256", sha256(File(reportDir, "input_tile_128.png")))
            metadata.put("input_tile_sha_source", "deterministicTile(): documented generator")
            metadata.put("input_pixels", inputPixels.size)

            val operationContext =
                ModelOperationContext(operationToken = 1L, documentGeneration = "n2-probe")

            val timings = JSONArray()
            var previousBitmap: Bitmap? = null
            repeat(3) { index ->
                val startedNanos = SystemClock.elapsedRealtimeNanos()
                val result = session.run(inputPixels, operationContext, if (index == 0) "cold" else "warm_${index}")
                val durationMs = (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000L
                val diag = session.lastRunDiagnostics
                runDiagnostics.add(diag)

                val runRecord = JSONObject()
                runRecord.put("run_index", index)
                runRecord.put("ms", durationMs)
                runRecord.put("h2d_status", diag.h2dStatus?.let(EnnStatus::describe) ?: "not_reached")
                runRecord.put("execute_reached", diag.executeReached)
                runRecord.put(
                    "execute_status",
                    diag.executeStatus?.let(EnnStatus::describe) ?: "not_reached",
                )
                runRecord.put("d2h_status", diag.d2hStatus?.let(EnnStatus::describe) ?: "not_reached")
                runRecord.put("output_decode_passed", diag.outputDecodePassed)
                if (diag.throwableStage != null) {
                    runRecord.put("throwable_stage", diag.throwableStage)
                    runRecord.put("throwable_detail", diag.throwableDetail ?: "")
                }
                timings.put(runRecord)

                if (result is ModelRunResult.Failure) {
                    runRecord.put("failure_reason", result.failure.reason.name)
                    runRecord.put("failure_detail", result.failure.detail)
                    metadata.put("inference_failure_stage", "inference_$index")
                    metadata.put("inference_failure_detail", result.failure.toString())
                    throw AssertionError("inference #$index failed: ${result.failure}")
                }
                previousBitmap?.recycle()
                previousBitmap = (result as ModelRunResult.Success<Bitmap>).value
                if (index == 0) {
                    memorySnapshots.put("post_cold_inference_kb", getNativeHeapAllocatedKb())
                }
            }
            memorySnapshots.put("post_warm_inferences_kb", getNativeHeapAllocatedKb())
            metadata.put("runs", timings)

            val bitmap = previousBitmap!!
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
            val outputPngFailure =
                runCatching { savePng(bitmap, File(reportDir, "output_tile_x4_512.png")) }
                    .exceptionOrNull()
            metadata.put("output_png_write_succeeded", outputPngFailure == null)
            if (outputPngFailure != null) {
                metadata.put("output_png_bytes", 0L)
                throw AssertionError("output PNG write failed", outputPngFailure)
            }
            metadata.put(
                "output_png_bytes",
                File(reportDir, "output_tile_x4_512.png").length(),
            )
            metadata.put("output_png_sha256", sha256(File(reportDir, "output_tile_x4_512.png")))

            val firstCloseStartedNanos = SystemClock.elapsedRealtimeNanos()
            session.close()
            val firstCloseMs = (SystemClock.elapsedRealtimeNanos() - firstCloseStartedNanos) / 1_000_000L
            val firstTeardown = checkNotNull(session.lastTeardownResult)
            assertTrue("first close must settle all attempted native steps", firstTeardown.allAttemptedSucceeded)
            val secondLoadStartedNanos = SystemClock.elapsedRealtimeNanos()
            assertTrue(session.load(token) is ModelLoadResult.Ready)
            val secondLoadMs = (SystemClock.elapsedRealtimeNanos() - secondLoadStartedNanos) / 1_000_000L
            val secondRunStartedNanos = SystemClock.elapsedRealtimeNanos()
            val rerun = session.run(inputPixels, operationContext, "lifecycle_roundtrip")
            val secondRunMs = (SystemClock.elapsedRealtimeNanos() - secondRunStartedNanos) / 1_000_000L
            runDiagnostics.add(session.lastRunDiagnostics)
            assertTrue("second lifecycle inference failed: $rerun", rerun is ModelRunResult.Success)
            (rerun as ModelRunResult.Success<Bitmap>).value.recycle()
            val secondCloseStartedNanos = SystemClock.elapsedRealtimeNanos()
            session.close()
            val secondCloseMs = (SystemClock.elapsedRealtimeNanos() - secondCloseStartedNanos) / 1_000_000L
            val secondTeardown = checkNotNull(session.lastTeardownResult)
            assertTrue("second close must settle all attempted native steps", secondTeardown.allAttemptedSucceeded)
            metadata.put(
                "lifecycle_timings_ms",
                JSONObject(
                    mapOf(
                        "first_load" to loadDurationMs,
                        "first_close" to firstCloseMs,
                        "second_load" to secondLoadMs,
                        "second_run" to secondRunMs,
                        "second_close" to secondCloseMs,
                    ),
                ),
            )
            memorySnapshots.put("post_close_kb", getNativeHeapAllocatedKb())

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
            metadata.put("status", "PASS")
        } catch (t: Throwable) {
            testFailure = t
            if (t is AssumptionViolatedException) {
                metadata.put("status", "SKIPPED")
                metadata.put("skip_reason", t.message ?: "assumption not satisfied")
            } else {
                metadata.put("status", "FAILED")
                metadata.put("failure_class", t.javaClass.simpleName)
                metadata.put("failure_message", t.message ?: "unknown")
            }
            throw t
        } finally {
            val closeFailure = runCatching { session?.close() }.exceptionOrNull()
            if (closeFailure != null) {
                metadata.put("session_close_failure", closeFailure.toString())
                if (testFailure == null) {
                    metadata.put("status", "FAILED")
                    metadata.put("failure_class", closeFailure.javaClass.simpleName)
                    metadata.put("failure_message", "session close failed: ${closeFailure.message}")
                }
            }
            if (!memorySnapshots.has("post_close_kb")) {
                memorySnapshots.put("post_close_kb", getNativeHeapAllocatedKb())
            }
            metadata.put("memory_snapshots_native_heap_kb", memorySnapshots)

            val loadAttempts = session?.loadDiagnosticsHistory ?: emptyList()
            val loadDiag = loadAttempts.lastOrNull()
            val openOk = loadAttempts.any { it.openModelStatus == EnnStatus.SUCCESS }
            val allocateOk = loadAttempts.any { it.allocationStatus == EnnStatus.SUCCESS }
            val executeAttempted = runDiagnostics.any { it.executeReached }
            val executeSucceeded = runDiagnostics.any { it.executeReached && it.executeStatus == EnnStatus.SUCCESS }
            val outputReadSucceeded = runDiagnostics.any { it.d2hStatus == EnnStatus.SUCCESS && it.outputDecodePassed }

            metadata.put("npu_open_succeeded", openOk)
            metadata.put("npu_buffer_allocation_succeeded", allocateOk)
            metadata.put("npu_execute_attempted", executeAttempted)
            metadata.put("npu_execute_succeeded", executeSucceeded)
            metadata.put("npu_output_read_succeeded", outputReadSucceeded)
            metadata.put(
                "load_attempts",
                JSONArray(loadAttempts.mapIndexed { index, diag ->
                    JSONObject(
                        mapOf(
                            "index" to index,
                            "initialize_status" to (diag.initializeStatus?.let(EnnStatus::describe) ?: "not_reached"),
                            "open_model_status" to (diag.openModelStatus?.let(EnnStatus::describe) ?: "not_reached"),
                            "allocation_status" to (diag.allocationStatus?.let(EnnStatus::describe) ?: "not_reached"),
                            "contract_validation_passed" to diag.contractValidationPassed,
                            "throwable_stage" to (diag.throwableStage ?: ""),
                        ),
                    )
                }),
            )
            metadata.put(
                "run_attempts",
                JSONArray(runDiagnostics.mapIndexed { index, diag ->
                    JSONObject(mapOf("index" to index, "label" to (diag.attemptLabel ?: ""), "h2d_status" to (diag.h2dStatus?.let(EnnStatus::describe) ?: "not_reached"), "execute_reached" to diag.executeReached, "execute_status" to (diag.executeStatus?.let(EnnStatus::describe) ?: "not_reached"), "d2h_status" to (diag.d2hStatus?.let(EnnStatus::describe) ?: "not_reached"), "output_decode_passed" to diag.outputDecodePassed))
                }),
            )

            if (loadDiag != null) {
                metadata.put(
                    "exynos_load_diagnostics",
                    JSONObject(
                        mapOf(
                            "initialize_status" to (loadDiag.initializeStatus?.let(EnnStatus::describe) ?: "not_reached"),
                            "open_model_status" to (loadDiag.openModelStatus?.let(EnnStatus::describe) ?: "not_reached"),
                            "allocation_status" to (loadDiag.allocationStatus?.let(EnnStatus::describe) ?: "not_reached"),
                            "allocation_in_out" to "${loadDiag.allocationNIn}/${loadDiag.allocationNOut}",
                            "contract_validation_reached" to loadDiag.contractValidationReached,
                            "contract_validation_passed" to loadDiag.contractValidationPassed,
                            "throwable_stage" to (loadDiag.throwableStage ?: ""),
                        ),
                    ),
                )
            }

            val metaInfo = metadata.optJSONObject("npu_meta_info") ?: JSONObject()
            val compilerNpu = metaInfo.optString("compiler_npu", "unavailable")
            val executeAttempt = runDiagnostics.firstOrNull { it.executeReached && it.executeStatus == EnnStatus.SUCCESS }
                ?: runDiagnostics.firstOrNull { it.executeReached }
            val proofDecision = decideNpuProof(
                executeReached = executeAttempt != null,
                executeStatus = executeAttempt?.executeStatus,
                compilerNpu = compilerNpu,
            )
            metadata.put("enn_execute_observed", proofDecision.ennExecuteObserved)
            metadata.put("accelerator_evidence", metaInfo)
            metadata.put("npu_execution_observed", proofDecision.npuExecutionObserved)
            metadata.put("npu_proof_status", proofDecision.status.name)
            if (proofDecision.status == NpuProofStatus.OBSERVED) {
                metadata.put("npu_proof", "EnnExecuteModel returned ENN_RET_SUCCESS and MODEL_COMPILER_NPU provided positive identity")
            }
            val proofFailure = npuProofAcceptanceFailure(proofDecision, testFailure ?: closeFailure)
            if (proofFailure != null) {
                metadata.put("status", "FAILED")
                metadata.put("failure_stage", "npu_proof")
                metadata.put("failure_class", proofFailure.javaClass.simpleName)
                metadata.put("failure_message", proofFailure.message ?: "N2 accelerator proof incomplete")
            }

            val capabilityAfter = ModelAvailabilityRegistry.state.value[ModelFeature.ExynosUpscale]
            val sessionInactiveAfterClose = capabilityAfter?.sessionActive != true
            metadata.put("session_inactive_after_close", sessionInactiveAfterClose)
            assertTrue(
                "Exynos availability registry must not retain an active session after close",
                sessionInactiveAfterClose,
            )
            metadata.put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")

            val report = finalizeProbeReport(
                writeReport = { File(reportDir, "metadata.json").writeText(metadata.toString(2)) },
                originalFailure = testFailure ?: proofFailure,
                closeFailure = closeFailure,
            )
            if (report.persisted) println("EXYNOS_NPU_PROBE_REPORT=${reportDir.absolutePath}")
            else {
                Log.w("KeplerExynosProbe", "Failed to write probe report", report.writeFailure)
                println("EXYNOS_NPU_PROBE_REPORT_WRITE_FAILED=${reportDir.absolutePath}")
            }
            if (testFailure == null && report.primaryFailure != null) throw report.primaryFailure
        }
        }
    }

    private fun isExynos2400Target(): Boolean {
        val socModel = socProperties["ro.soc.model"]?.lowercase() ?: ""
        val boardPlatform = socProperties["ro.board.platform"]?.lowercase() ?: ""
        val hardware = socProperties["ro.hardware"]?.lowercase() ?: ""
        val product = socProperties["ro.product.device"]?.lowercase() ?: ""

        val isSocModel2400 = socModel.contains("2400") || socModel.contains("s5e9945")
        val isBoardExynos =
            boardPlatform.contains("exynos") || boardPlatform.contains("e1s") || boardPlatform.contains("e1q")
        val isS24Family = product.contains("e1s") || product.contains("e1q") || product.contains("s24")

        return (isSocModel2400 || isBoardExynos) && isS24Family
    }

    private fun captureNpuMetaInfo(session: ExynosUpscaleSession, metadata: JSONObject) {
        val metaIds =
            linkedMapOf(
                EnnMetaIds.MODEL_COMPILER_NNC to "compiler_nnc",
                EnnMetaIds.MODEL_COMPILER_NPU to "compiler_npu",
                EnnMetaIds.MODEL_SCHEMA to "model_schema",
                EnnMetaIds.MODEL_VERSION to "model_version",
                EnnMetaIds.DD to "dd",
                EnnMetaIds.UNIFIED_FW to "unified_fw",
                EnnMetaIds.NPU_FW to "npu_fw",
            )
        val metaJson = JSONObject()
        var reflectionError: String? = null
        try {
            metaIds.forEach { (id, name) ->
                try {
                    val meta = session.getEnnMetaInfo(id)
                    metaJson.put(name, if (meta.isNullOrBlank()) "unavailable" else meta)
                } catch (e: Exception) {
                    metaJson.put(name, "unavailable")
                }
            }
        } catch (e: Exception) {
            reflectionError = e.message ?: e.javaClass.simpleName
        }
        if (reflectionError != null) {
            metaJson.put("capture_error", reflectionError)
        }
        metadata.put("npu_meta_info", metaJson)
    }

    private fun getNativeHeapAllocatedKb(): Long {
        return try {
            val debugClass = Class.forName("android.os.Debug")
            val method = debugClass.getMethod("getNativeHeapAllocatedSize")
            (method.invoke(null) as Long) / 1024
        } catch (e: Exception) {
            -1L
        }
    }

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
                x < w / 2 && y < h / 2 -> {
                    r = x * 255 / (w / 2 - 1)
                    g = y * 255 / (h / 2 - 1)
                    b = ((x + y) * 255) / (w / 2 + h / 2 - 2)
                }
                x >= w / 2 && y < h / 2 -> {
                    when ((x / (w / 8)) % 4) {
                        0 -> {
                            r = 255
                            g = 0
                            b = 0
                        }
                        1 -> {
                            r = 0
                            g = 255
                            b = 0
                        }
                        2 -> {
                            r = 0
                            g = 0
                            b = 255
                        }
                        else -> {
                            r = 255
                            g = 255
                            b = 255
                        }
                    }
                }
                x < w / 2 && y >= h / 2 -> {
                    val on = ((x / 4) + (y / 4)) % 2 == 0
                    r = if (on) 240 else 16
                    g = if (on) 240 else 16
                    b = if (on) 240 else 16
                }
                else -> {
                    r = 128
                    g = 128
                    b = 128
                }
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

    private fun sha256(file: File): String =
        FileInputStream(file).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        }
}
