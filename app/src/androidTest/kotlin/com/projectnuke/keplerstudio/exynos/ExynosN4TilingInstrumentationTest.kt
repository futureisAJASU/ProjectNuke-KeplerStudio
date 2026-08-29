package com.projectnuke.keplerstudio.exynos

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.projectnuke.keplerstudio.editor.EnnMetaIds
import com.projectnuke.keplerstudio.editor.EnnStatus
import com.projectnuke.keplerstudio.editor.ExynosUpscaleSession
import com.projectnuke.keplerstudio.editor.ModelAssetManifest
import com.projectnuke.keplerstudio.editor.ModelAvailabilityRegistry
import com.projectnuke.keplerstudio.editor.ModelFeature
import com.projectnuke.keplerstudio.editor.ModelLoadResult
import com.projectnuke.keplerstudio.editor.ModelOperationContext
import com.projectnuke.keplerstudio.editor.TileInferenceOrchestrator
import com.projectnuke.keplerstudio.editor.TiledUpscaleResult
import com.projectnuke.keplerstudio.editor.ValidatedModelCapabilityToken
import com.projectnuke.keplerstudio.editor.decideNpuProof
import com.projectnuke.keplerstudio.editor.finalizeProbeReport
import com.projectnuke.keplerstudio.editor.npuProofAcceptanceFailure
import com.projectnuke.keplerstudio.editor.sha256Bytes
import java.io.File
import java.io.FileOutputStream
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
 * Phase N4 — S24 multi-tile FP16 NNC correctness corpus.
 *
 * Reads the canonical N4 inputs (committed under `app/src/androidTest/assets/exynos_n4/`),
 * runs the production FP16 NNC through the chosen tiler ([TileInferenceOrchestrator]) to a
 * raw assembled output, and records per-tile status/timing and the assembled-output SHA for
 * later host comparison (compare_n4.py).
 *
 * For the decomposition fixtures, it also saves each 128x128 tile's input and raw output so
 * the host can isolate compiler drift from tiling drift (N4.14).
 *
 * Opt-in + applicability gates match N2/N3 (explicit `kepler.exynosNpuProbe=true` + S24 /
 * Exynos-2400 target).
 */
@RunWith(AndroidJUnit4::class)
class ExynosN4TilingInstrumentationTest {

    private val appContext: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext: Context = InstrumentationRegistry.getInstrumentation().context

    /** Fixtures for which per-tile inputs/outputs are retained for N4.14 decomposition. */
    private val decompositionFixtures = setOf("seam_stress_188x188", "smooth_257x257")

    private fun isProbeRequested(): Boolean {
        val bundle = runCatching {
            androidx.test.platform.app.InstrumentationRegistry.getArguments()
        }.getOrNull()
        return bundle?.getString("kepler.exynosNpuProbe") == "true"
    }

    private val socProperties: Map<String, String> by lazy {
        val keys =
            listOf(
                "ro.board.platform", "ro.soc.model", "ro.soc.manufacturer", "ro.hardware",
                "ro.product.model", "ro.product.device", "ro.build.version.release", "ro.build.fingerprint",
            )
        keys.mapNotNull { name -> runtimeProperty(name)?.let { value -> name to value } }.toMap()
    }

    private fun runtimeProperty(name: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        clazz.getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    @Test
    fun exynosN4TilingCorpus() {
        runBlocking {
            assumeTrue("probe is opt-in only; rerun with -e kepler.exynosNpuProbe true", isProbeRequested())

            val reportDir = File(appContext.getExternalFilesDir(null), "exynos_n4_probe").apply { mkdirs() }
            val metadata = JSONObject()
            metadata.put("report_absolute_path", reportDir.absolutePath)
            metadata.put("device", JSONObject(socProperties))
            metadata.put("build_manufacturer", Build.MANUFACTURER)

            var testFailure: Throwable? = null
            var session: ExynosUpscaleSession? = null

            try {
                assumeTrue("target must be Exynos 2400 (S24 family); found: $socProperties", isExynos2400Target())
                metadata.put("soc_gate", "passed")

                ModelAvailabilityRegistry.resetForTest()
                val probeGeneration = ModelAvailabilityRegistry.beginProbe()
                ModelAvailabilityRegistry.probePackagedCapabilities(appContext, probeGeneration)

                val capability = ModelAvailabilityRegistry.state.value[ModelFeature.ExynosUpscale]
                assertTrue("ExynosUpscale capability not loadable", capability?.canAttemptModelUse == true)
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
                metadata.put("prepared_file_sha256", sha256File(preparedFile))
                metadata.put(
                    "prepared_file_expected_sha256",
                    ModelAssetManifest.byId(token.modelId)?.asset?.sha256 ?: "",
                )
                val npuMeta = JSONObject()
                linkedMapOf(
                    EnnMetaIds.MODEL_COMPILER_NNC to "compiler_nnc",
                    EnnMetaIds.MODEL_COMPILER_NPU to "compiler_npu",
                    EnnMetaIds.MODEL_SCHEMA to "model_schema",
                    EnnMetaIds.MODEL_VERSION to "model_version",
                ).forEach { (id, name) ->
                    npuMeta.put(
                        name,
                        runCatching { session.getEnnMetaInfo(id) }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unavailable",
                    )
                }
                metadata.put("npu_meta_info", npuMeta)

                val operationContext = ModelOperationContext(operationToken = 4L, documentGeneration = "n4-tiling")
                val fixtures = loadFixtures()

                // Warm-up with the first fixture (smallest), then the recorded corpus.
                val warmup = fixtures.first()
                runOne(session, reportDir, warmup, operationContext, "warmup", null, null, saveTiles = false)

                val fixturesJson = JSONArray()
                var totalTiles = 0
                for (fixture in fixtures) {
                    val fj = JSONObject()
                    fj.put("name", fixture.name)
                    fj.put("expected_input_sha256", fixture.inputSha256)
                    val perTile = JSONArray()
                    val saveTiles = fixture.name in decompositionFixtures
                    val timing = JSONObject()
                    val assembled = runOne(session, reportDir, fixture, operationContext, fixture.name, perTile, timing, saveTiles)
                    fj.put("assembled_output_sha256", assembled.outputSha256)
                    fj.put("assembled_output_bytes", assembled.outputBytes)
                    fj.put("tiles", perTile)
                    fj.put("timing_ms", timing)
                    fixturesJson.put(fj)
                    totalTiles += perTile.length()
                }
                metadata.put("fixtures", fixturesJson)
                metadata.put("total_tiles_across_fixtures", totalTiles)

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
                                    "execute_reached" to it.executeReached,
                                    "execute_status" to (it.executeStatus?.let(EnnStatus::describe) ?: "not_reached"),
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
                }
                val report = finalizeProbeReport(
                    writeReport = { File(reportDir, "metadata.json").writeText(metadata.toString(2)) },
                    originalFailure = testFailure ?: proofFailure,
                    closeFailure = closeFailure,
                )
                if (report.persisted) println("EXYNOS_N4_REPORT=${reportDir.absolutePath}")
                else println("EXYNOS_N4_REPORT_WRITE_FAILED=${reportDir.absolutePath}")
                if (testFailure == null && report.primaryFailure != null) throw report.primaryFailure
            }
        }
    }

    private data class AssembledInfo(val outputSha256: String, val outputBytes: Long)

    private suspend fun runOne(
        session: ExynosUpscaleSession,
        reportDir: File,
        fixture: N4Fixture,
        operationContext: ModelOperationContext,
        label: String,
        perTileJson: JSONArray?,
        timingJson: JSONObject?,
        saveTiles: Boolean,
    ): AssembledInfo {
        val inputBytes = testContext.assets.open("exynos_n4/${fixture.name}_input.f32le").use { it.readBytes() }
        val orchestrator = TileInferenceOrchestrator(session)

        val started = SystemClock.elapsedRealtimeNanos()
        val result = orchestrator.upscaleRaw(inputBytes, fixture.width, fixture.height, operationContext, label)
        val totalNanos = SystemClock.elapsedRealtimeNanos() - started
        val totalMs = totalNanos / 1_000_000L

        assertTrue("tiled run failed for $label: $result", result is TiledUpscaleResult.Success)
        result as TiledUpscaleResult.Success
        assertEquals(fixture.width * 4, result.outputWidth)
        assertEquals(fixture.height * 4, result.outputHeight)
        assertEquals(result.tileCount, result.completedTiles)

        val outFile = File(reportDir, "assembled_${fixture.name}.f32le")
        FileOutputStream(outFile).use { it.write(result.outputBytes) }
        val outSha = sha256Bytes(result.outputBytes)

        var totalInferenceNanos = 0L
        result.tiles.forEach { tile ->
            totalInferenceNanos += tile.durationNanos
            if (perTileJson != null) {
                perTileJson.put(
                    JSONObject(
                        mapOf(
                            "index" to tile.index,
                            "source_x" to tile.source.left,
                            "source_y" to tile.source.top,
                            "dest_x" to tile.dest.left,
                            "dest_y" to tile.dest.top,
                            "dest_w" to tile.dest.width,
                            "dest_h" to tile.dest.height,
                            "duration_ms" to (tile.durationNanos / 1_000_000L),
                            "h2d_status" to (tile.h2dStatus?.let(EnnStatus::describe) ?: "not_reached"),
                            "execute_status" to (tile.executeStatus?.let(EnnStatus::describe) ?: "not_reached"),
                            "d2h_status" to (tile.d2hStatus?.let(EnnStatus::describe) ?: "not_reached"),
                        ),
                    ),
                )
            }
        }

        if (saveTiles) {
            val tilesDir = File(reportDir, "tiles/${fixture.name}").apply { mkdirs() }
            saveDecompositionTiles(session, tilesDir, inputBytes, fixture.width, fixture.height, operationContext, label)
        }

        if (timingJson != null) {
            timingJson.put("total_ms", totalMs)
            timingJson.put("total_inference_nanos_sum", totalInferenceNanos)
            timingJson.put("tile_count", result.tileCount)
            timingJson.put("assembly_ms", (totalNanos - totalInferenceNanos) / 1_000_000L)
        }

        return AssembledInfo(outSha, outFile.length())
    }

    private suspend fun saveDecompositionTiles(
        session: ExynosUpscaleSession,
        tilesDir: File,
        inputBytes: ByteArray,
        width: Int,
        height: Int,
        operationContext: ModelOperationContext,
        label: String,
    ) {
        val plan = com.projectnuke.keplerstudio.editor.TilePlanner.plan(width, height)
        if (plan !is com.projectnuke.keplerstudio.editor.TilePlanResult.Planned) return
        for (tile in plan.plan.tiles) {
            val tileInput =
                TileInferenceOrchestrator.extractChwSubTile(inputBytes, width, height, tile.source.left, tile.source.top)
            val raw = session.runRawFp32Chw(tileInput, operationContext, "$label/decomp-tile-${tile.index}")
            if (!raw.succeeded) continue
            File(tilesDir, "tile_${tile.index}_input.f32le").writeBytes(tileInput)
            File(tilesDir, "tile_${tile.index}_output.f32le").writeBytes(checkNotNull(raw.outputBytes))
        }
    }

    private inner class N4Fixture(val name: String, val width: Int, val height: Int, val inputSha256: String)

    private fun loadFixtures(): List<N4Fixture> {
        val manifestText = testContext.assets.open("exynos_n4/n4_fixtures_manifest.json").use { it.readBytes() }
            .toString(Charsets.UTF_8)
        val manifest = JSONObject(manifestText)
        val arr = manifest.getJSONArray("fixtures")
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            N4Fixture(obj.getString("name"), obj.getInt("width"), obj.getInt("height"), obj.getString("raw_input_sha256"))
        }
    }

    private fun isExynos2400Target(): Boolean {
        val socModel = socProperties["ro.soc.model"]?.lowercase() ?: ""
        val boardPlatform = socProperties["ro.board.platform"]?.lowercase() ?: ""
        val product = socProperties["ro.product.device"]?.lowercase() ?: ""
        val isSocModel2400 = socModel.contains("2400") || socModel.contains("s5e9945")
        val isBoardExynos =
            boardPlatform.contains("exynos") || boardPlatform.contains("e1s") || boardPlatform.contains("e1q")
        val isS24Family = product.contains("e1s") || product.contains("e1q") || product.contains("s24")
        return (isSocModel2400 || isBoardExynos) && isS24Family
    }

    private fun sha256File(file: File): String =
        java.io.FileInputStream(file).use { input ->
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