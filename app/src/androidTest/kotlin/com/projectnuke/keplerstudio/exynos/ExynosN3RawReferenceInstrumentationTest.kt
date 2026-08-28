package com.projectnuke.keplerstudio.exynos

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.projectnuke.keplerstudio.editor.EnnMetaIds
import com.projectnuke.keplerstudio.editor.EnnStatus
import com.projectnuke.keplerstudio.editor.ExynosRawRunResult
import com.projectnuke.keplerstudio.editor.ExynosUpscaleSession
import com.projectnuke.keplerstudio.editor.ModelAssetManifest
import com.projectnuke.keplerstudio.editor.ModelAvailabilityRegistry
import com.projectnuke.keplerstudio.editor.ModelFeature
import com.projectnuke.keplerstudio.editor.ModelLoadResult
import com.projectnuke.keplerstudio.editor.ModelOperationContext
import com.projectnuke.keplerstudio.editor.ValidatedModelCapabilityToken
import com.projectnuke.keplerstudio.editor.decideNpuProof
import com.projectnuke.keplerstudio.editor.npuProofAcceptanceFailure
import com.projectnuke.keplerstudio.editor.sha256Bytes
import java.io.File
import java.io.FileInputStream
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
 * Phase N3 — raw FP32 output reference capture on the S24 / Exynos 2400.
 *
 * Feeds the exact canonical serialized FP32 CHW RGB input bytes (committed under
 * `app/src/androidTest/assets/exynos_n3/`, byte-identical to the PyTorch reference
 * inputs) through the production [ExynosUpscaleSession] raw-output seam, and records:
 *   - the SHA-256 of the exact bytes handed to H2D (input-parity proof),
 *   - the untouched FP32 D2H output tensor (npu_raw_output_*.f32le),
 *   - two repeated runs per fixture for hardware repeatability,
 *   - the On-device NPU proof (OpenModel/Allocate/Execute/meta/close).
 *
 * Opt-in + applicability gates match the N2 probe: no `kepler.exynosNpuProbe=true`
 * or a non-S24 target skips; otherwise every failure is a real N3 failure.
 */
@RunWith(AndroidJUnit4::class)
class ExynosN3RawReferenceInstrumentationTest {

    private val appContext: Context =
        InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext: Context =
        InstrumentationRegistry.getInstrumentation().context

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

    private fun runtimeProperty(name: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        clazz.getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    @Test
    fun exynosN3RawReferenceCapture() {
        runBlocking {
            assumeTrue("probe is opt-in only; rerun with -e kepler.exynosNpuProbe true", isProbeRequested())

            val reportDir = File(appContext.getExternalFilesDir(null), "exynos_n3_probe").apply { mkdirs() }
            val metadata = JSONObject()
            metadata.put("report_absolute_path", reportDir.absolutePath)
            metadata.put("device", JSONObject(socProperties))
            metadata.put("build_manufacturer", Build.MANUFACTURER)

            var testFailure: Throwable? = null
            var session: ExynosUpscaleSession? = null
            val rawResults = JSONArray()
            val fixtures = loadFixtures()

            try {
                assumeTrue("target must be Exynos 2400 (S24 family); found: $socProperties", isExynos2400Target())
                metadata.put("soc_gate", "passed")

                ModelAvailabilityRegistry.resetForTest()
                val probeGeneration = ModelAvailabilityRegistry.beginProbe()
                ModelAvailabilityRegistry.probePackagedCapabilities(appContext, probeGeneration)

                val capability = ModelAvailabilityRegistry.state.value[ModelFeature.ExynosUpscale]
                assertTrue(
                    "ExynosUpscale capability not loadable: phase=${capability?.phase}",
                    capability?.canAttemptModelUse == true,
                )
                val tokenResult = ModelAvailabilityRegistry.validatedCapabilityToken(ModelFeature.ExynosUpscale)
                assertTrue("capability token refused: $tokenResult", tokenResult is ModelLoadResult.Ready)
                val token = (tokenResult as ModelLoadResult.Ready).runner as ValidatedModelCapabilityToken
                metadata.put("validated_asset_sha256", token.approvedAssetSha256 ?: "")

                session = ExynosUpscaleSession(appContext)
                val loadResult = session.load(token)
                if (loadResult !is ModelLoadResult.Ready) {
                    throw AssertionError("session load failed on this device: $loadResult")
                }
                val preparedFile = checkNotNull(session.preparedModelFileForDiagnostics())
                metadata.put("prepared_file_bytes", preparedFile.length())
                metadata.put("prepared_file_sha256", sha256File(preparedFile))
                metadata.put(
                    "prepared_file_expected_sha256",
                    ModelAssetManifest.byId(token.modelId)?.asset?.sha256 ?: "",
                )
                val metaInfo = JSONObject()
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
                metaIds.forEach { (id, name) ->
                    metaInfo.put(
                        name,
                        runCatching { session.getEnnMetaInfo(id) }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unavailable",
                    )
                }
                metadata.put("npu_meta_info", metaInfo)

                val operationContext = ModelOperationContext(operationToken = 3L, documentGeneration = "n3-reference")

                // Warm-up with the first fixture (not recorded), then two recorded runs.
                val warmupName = fixtures.first().first
                runRawOnce(session, warmupName, operationContext, "warmup", reportDir, null, metadata)

                var maxAbsRepeatabilityDiff = 0.0
                var bitIdenticalCount = 0
                var totalFixtureCount = 0
                for ((name, expectedInputSha) in fixtures) {
                    val fixtureJson = JSONObject()
                    fixtureJson.put("name", name)
                    fixtureJson.put("expected_input_sha256", expectedInputSha)
                    val runs = JSONArray()
                    val outputs = mutableListOf<ByteArray>()
                    for (runIndex in 1..2) {
                        val runJson = JSONObject()
                        runJson.put("run", runIndex)
                        runJson.put("ms", -1L)
                        runJson.put("h2d_status", "not_reached")
                        runJson.put("execute_reached", false)
                        runJson.put("execute_status", "not_reached")
                        runJson.put("d2h_status", "not_reached")
                        runJson.put("input_sha256", "")
                        runJson.put("output_sha256", "")
                        runJson.put("output_bytes", 0)
                        runRawOnce(session, name, operationContext, "$name#$runIndex", reportDir, runJson, metadata)
                        val outFile = File(reportDir, "npu_raw_output_${name}_${runIndex}.f32le")
                        if (outFile.exists()) {
                            outputs.add(FileInputStream(outFile).readBytes())
                        }
                        runs.put(runJson)
                    }
                    if (outputs.size == 2) {
                        val identical = outputs[0].contentEquals(outputs[1])
                        fixtureJson.put("repeatability_bit_identical", identical)
                        if (identical) bitIdenticalCount++
                        val diff = maxAbsDiff(outputs[0], outputs[1])
                        fixtureJson.put("repeatability_max_abs_diff", diff)
                        maxAbsRepeatabilityDiff = maxOf(maxAbsRepeatabilityDiff, diff)
                    }
                    fixtureJson.put("runs", runs)
                    rawResults.put(fixtureJson)
                    totalFixtureCount++
                }
                metadata.put("fixtures", rawResults)
                metadata.put("repeatability_bit_identical_count", bitIdenticalCount)
                metadata.put("repeatability_total_fixtures", totalFixtureCount)
                metadata.put("repeatability_max_abs_diff", maxAbsRepeatabilityDiff)

                session.close()
                val teardown = checkNotNull(session.lastTeardownResult)
                assertTrue("close must settle all attempted native steps", teardown.allAttemptedSucceeded)
                metadata.put("close", "PASS")

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
                if (closeFailure != null && testFailure == null) {
                    metadata.put("status", "FAILED")
                    metadata.put("failure_message", "session close failed: ${closeFailure.message}")
                }

                val diag = session?.runDiagnosticsHistory ?: emptyList()
                val executeAttempt = diag.firstOrNull { it.executeReached }
                val compilerNpu = metadata.optJSONObject("npu_meta_info")?.optString("compiler_npu", "unavailable")
                val proofDecision = decideNpuProof(
                    executeReached = executeAttempt != null,
                    executeStatus = executeAttempt?.executeStatus,
                    compilerNpu = compilerNpu,
                )
                metadata.put("enn_execute_observed", proofDecision.ennExecuteObserved)
                metadata.put("npu_execution_observed", proofDecision.npuExecutionObserved)
                metadata.put("npu_proof_status", proofDecision.status.name)
                metadata.put(
                    "run_attempts",
                    JSONArray(
                        diag.map {
                            JSONObject(
                                mapOf(
                                    "label" to (it.attemptLabel ?: ""),
                                    "h2d_status" to (it.h2dStatus?.let(EnnStatus::describe) ?: "not_reached"),
                                    "execute_reached" to it.executeReached,
                                    "execute_status" to (it.executeStatus?.let(EnnStatus::describe) ?: "not_reached"),
                                    "d2h_status" to (it.d2hStatus?.let(EnnStatus::describe) ?: "not_reached"),
                                ),
                            )
                        },
                    ),
                )
                val proofFailure = npuProofAcceptanceFailure(proofDecision, testFailure ?: closeFailure)
                if (proofFailure != null) {
                    metadata.put("status", "FAILED")
                    metadata.put("failure_stage", "npu_proof")
                    metadata.put("failure_message", proofFailure.message ?: "accelerator proof incomplete")
                    if (testFailure == null) throw proofFailure
                }
                File(reportDir, "metadata.json").writeText(metadata.toString(2))
                println("EXYNOS_N3_REPORT=${reportDir.absolutePath}")
            }
        }
    }

    private suspend fun runRawOnce(
        session: ExynosUpscaleSession,
        fixtureName: String,
        operationContext: ModelOperationContext,
        label: String,
        reportDir: File,
        runJson: JSONObject?,
        metadata: JSONObject,
    ) {
        val bytes = testContext.assets.open("exynos_n3/${fixtureName}_input.f32le").use { it.readBytes() }
        val started = SystemClock.elapsedRealtimeNanos()
        val result: ExynosRawRunResult = session.runRawFp32Chw(bytes, operationContext, label)
        val ms = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L
        if (runJson != null) {
            runJson.put("ms", ms)
            runJson.put("h2d_status", result.h2dStatus?.let(EnnStatus::describe) ?: "not_reached")
            runJson.put("execute_reached", result.reachedExecute)
            runJson.put("execute_status", result.executeStatus?.let(EnnStatus::describe) ?: "not_reached")
            runJson.put("d2h_status", result.d2hStatus?.let(EnnStatus::describe) ?: "not_reached")
            runJson.put("input_sha256", result.inputSha256 ?: "")
        }
        assertTrue("raw H2D failed for $label", result.h2dStatus == EnnStatus.SUCCESS)
        assertTrue("raw execute failed for $label", result.executeStatus == EnnStatus.SUCCESS)
        assertTrue("raw D2H failed for $label", result.d2hStatus == EnnStatus.SUCCESS)
        val output = checkNotNull(result.outputBytes)
        assertEquals("raw output byte count for $label", 3 * 512 * 512 * 4, output.size)
        val outFile = File(reportDir, "npu_raw_output_${fixtureName}_${label.substringAfter('#', label)}.f32le")
        if (label == "warmup") {
            File(reportDir, "npu_raw_output_warmup.f32le").writeBytes(output)
        } else {
            outFile.writeBytes(output)
        }
        if (runJson != null) {
            runJson.put("output_bytes", output.size)
            runJson.put("output_sha256", sha256Bytes(output))
        }
    }

    private fun maxAbsDiff(a: ByteArray, b: ByteArray): Double {
        if (a.size != b.size) return Double.POSITIVE_INFINITY
        val fa = FloatArray(a.size / 4)
        val fb = FloatArray(b.size / 4)
        for (i in 0 until a.size step 4) {
            val j = i / 4
            fa[j] = Float.fromBits(
                ((a[i].toInt() and 0xFF)) or ((a[i + 1].toInt() and 0xFF) shl 8) or
                    ((a[i + 2].toInt() and 0xFF) shl 16) or ((a[i + 3].toInt() and 0xFF) shl 24),
            )
            fb[j] = Float.fromBits(
                ((b[i].toInt() and 0xFF)) or ((b[i + 1].toInt() and 0xFF) shl 8) or
                    ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24),
            )
        }
        var max = 0.0
        for (i in fa.indices) {
            max = maxOf(max, kotlin.math.abs(fa[i] - fb[i]).toDouble())
        }
        return max
    }

    private fun loadFixtures(): List<Pair<String, String>> {
        val manifestText = testContext.assets.open("exynos_n3/fixtures_manifest.json").use { it.readBytes() }
            .toString(Charsets.UTF_8)
        val manifest = JSONObject(manifestText)
        val arr = manifest.getJSONArray("fixtures")
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            obj.getString("name") to obj.getString("raw_input_sha256")
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

    private fun sha256File(file: File): String =
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