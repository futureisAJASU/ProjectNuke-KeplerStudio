package com.projectnuke.keplerstudio.editor

import java.io.InputStream
import java.security.MessageDigest

enum class ModelAvailability {
    RunnerNotImplemented,
    AssetMissing,
    AssetInvalid,
    ContractUnsupported,
    LoadFailed,
    Loaded,
    InferenceAvailable,
    ExperimentalOnly,
}

data class ModelAssetManifestEntry(
    val id: String,
    val asset: ModelAssetContract,
    val outputSemantic: ModelOutputSemantic,
    val inferenceAdapterImplemented: Boolean,
    val productionReady: Boolean,
)

object ModelAssetManifest {
    const val CONTRACT_SCHEMA_VERSION = 2

    val entries: List<ModelAssetManifestEntry> =
        listOf(
            entry(
                id = "flare_masker",
                path = "models/flare_guard.tflite",
                runtime = ModelRuntimeType.LiteRT,
                semantic = ModelOutputSemantic.SingleChannelAlphaMask,
                runnerImplemented = true,
                productionReady = false,
                minimumBytes = 1_024L,
                maximumBytes = 64L * 1024L * 1024L,
            ),
            entry(
                id = "flare_restorer",
                path = "models/flare_restorer.tflite",
                runtime = ModelRuntimeType.LiteRT,
                semantic = ModelOutputSemantic.RestorationImage,
                runnerImplemented = false,
                productionReady = false,
                minimumBytes = 1_024L,
                maximumBytes = 512L * 1024L * 1024L,
            ),
            entry(
                id = "edge_masker",
                path = "models/edge_masker.task",
                runtime = ModelRuntimeType.MediaPipeTask,
                semantic = ModelOutputSemantic.ForegroundCategoryMask,
                runnerImplemented = true,
                productionReady = false,
                minimumBytes = 1_024L,
                maximumBytes = 256L * 1024L * 1024L,
            ),
            entry(
                id = "universal_balancer",
                path = "models/universal_balancer.tflite",
                runtime = ModelRuntimeType.LiteRT,
                semantic = ModelOutputSemantic.GlobalAdjustmentVector,
                runnerImplemented = false,
                productionReady = false,
                minimumBytes = 1_024L,
                maximumBytes = 64L * 1024L * 1024L,
            ),
            entry(
                id = "universal_auto_router",
                path = "",
                runtime = ModelRuntimeType.RuleStatistics,
                semantic = ModelOutputSemantic.NoExecutableModel,
                runnerImplemented = true,
                productionReady = true,
                minimumBytes = 0L,
                maximumBytes = 0L,
            ),
        )

    fun byId(id: String): ModelAssetManifestEntry? = entries.firstOrNull { it.id == id }

    private fun entry(
        id: String,
        path: String,
        runtime: ModelRuntimeType,
        semantic: ModelOutputSemantic,
        runnerImplemented: Boolean,
        productionReady: Boolean,
        minimumBytes: Long,
        maximumBytes: Long?,
    ) =
        ModelAssetManifestEntry(
            id = id,
            asset =
                ModelAssetContract(
                    assetPath = path,
                    semanticModelVersion = "1.0.0",
                    packagingVersion = "unbundled-v1",
                    minimumExpectedBytes = minimumBytes,
                    maximumExpectedBytes = maximumBytes,
                    sha256 = null,
                    runtimeType = runtime,
                    delegatePolicy =
                        if (runtime == ModelRuntimeType.LiteRT) {
                            ModelDelegatePolicy.CPUWithXnnpack
                        } else {
                            ModelDelegatePolicy.RuntimeDefault
                        },
                    requiredContractSchemaVersion = CONTRACT_SCHEMA_VERSION,
                ),
            outputSemantic = semantic,
            inferenceAdapterImplemented = runnerImplemented,
            productionReady = productionReady,
        )
}

sealed interface ModelAssetValidation {
    data object Missing : ModelAssetValidation
    data class Invalid(val detail: String) : ModelAssetValidation
    data class Valid(val byteCount: Long, val sha256: String) : ModelAssetValidation
}

object ModelAssetValidator {
    fun validate(
        entry: ModelAssetManifestEntry,
        open: (String) -> InputStream?,
    ): ModelAssetValidation {
        if (entry.asset.assetPath.isBlank()) {
            return if (entry.outputSemantic == ModelOutputSemantic.NoExecutableModel) {
                ModelAssetValidation.Valid(0L, "")
            } else {
                ModelAssetValidation.Missing
            }
        }
        val stream = open(entry.asset.assetPath) ?: return ModelAssetValidation.Missing
        return runCatching {
                stream.use {
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = it.read(buffer)
                        if (read < 0) break
                        total = Math.addExact(total, read.toLong())
                        digest.update(buffer, 0, read)
                    }
                    val maximum = entry.asset.maximumExpectedBytes
                    if (total < entry.asset.minimumExpectedBytes) {
                        return ModelAssetValidation.Invalid("asset is smaller than the manifest minimum")
                    }
                    if (maximum != null && total > maximum) {
                        return ModelAssetValidation.Invalid("asset exceeds the manifest maximum")
                    }
                    val hash = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
                    val expectedHash = entry.asset.sha256
                    if (entry.asset.runtimeType != ModelRuntimeType.RuleStatistics && expectedHash == null) {
                        return ModelAssetValidation.Invalid(
                            "model asset is not pinned by a manifest SHA-256",
                        )
                    }
                    if (expectedHash != null && !hash.equals(expectedHash, ignoreCase = true)) {
                        return ModelAssetValidation.Invalid("asset SHA-256 does not match the manifest")
                    }
                    ModelAssetValidation.Valid(total, hash)
                }
            }
            .getOrElse { ModelAssetValidation.Invalid(it.message ?: "asset validation failed") }
    }

    fun availability(
        entry: ModelAssetManifestEntry,
        validation: ModelAssetValidation,
        loaded: Boolean = false,
        inferenceAvailable: Boolean = false,
    ): ModelAvailability =
        when {
            !entry.inferenceAdapterImplemented -> ModelAvailability.RunnerNotImplemented
            entry.asset.requiredContractSchemaVersion != ModelAssetManifest.CONTRACT_SCHEMA_VERSION ->
                ModelAvailability.ContractUnsupported
            validation is ModelAssetValidation.Missing -> ModelAvailability.AssetMissing
            validation is ModelAssetValidation.Invalid -> ModelAvailability.AssetInvalid
            inferenceAvailable && entry.productionReady -> ModelAvailability.InferenceAvailable
            loaded && entry.productionReady -> ModelAvailability.Loaded
            else -> ModelAvailability.ExperimentalOnly
        }

    fun readiness(
        entry: ModelAssetManifestEntry,
        validation: ModelAssetValidation,
        runtimeAvailable: Boolean,
    ): ModelReadiness {
        val present = validation !is ModelAssetValidation.Missing
        val valid = validation is ModelAssetValidation.Valid
        val contractSupported =
            entry.asset.requiredContractSchemaVersion == ModelAssetManifest.CONTRACT_SCHEMA_VERSION
        val inferenceAvailable =
            entry.inferenceAdapterImplemented && valid && contractSupported && runtimeAvailable
        return ModelReadiness(
            runnerImplemented = entry.inferenceAdapterImplemented,
            assetPresent = present,
            assetValid = valid,
            contractSupported = contractSupported,
            inferenceAvailable = inferenceAvailable,
            productionReady = inferenceAvailable && entry.productionReady,
            experimentalOnly = inferenceAvailable && !entry.productionReady,
        )
    }
}
