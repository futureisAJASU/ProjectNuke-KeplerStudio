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
                sha256 = "8ee3dba4a1c8a70f847b4d44289b712e991a0558ac349f5943349c387d9d3fff",
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
        sha256: String? = null,
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
                    sha256 = sha256,
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
    /**
     * Asset is on disk and size-checked, but is not pinned in the manifest.
     * Only returned when the debug experimental override is explicitly enabled.
     * NEVER equates to [ModelAssetValidation.Valid]; the asset is loadable for
     * development only, never production-ready.
     */
    data class UnpinnedExperimental(val byteCount: Long, val sha256: String) : ModelAssetValidation
}

object ModelAssetValidator {
    fun validate(
        entry: ModelAssetManifestEntry,
        open: (String) -> InputStream?,
    ): ModelAssetValidation {
        return validate(entry, open, ModelAssetPolicy.allowUnpinnedExperimental())
    }

    /**
     * @param allowUnpinnedExperimental when true (debug-only, explicitly enabled,
     *        never active in release), an unpinned-but-size-valid asset returns
     *        [UnpinnedExperimental] instead of [Invalid]. A pinned asset whose
     *        hash mismatches is STILL rejected even when this is enabled; the
     *        override never bypasses an explicit pin.
     */
    fun validate(
        entry: ModelAssetManifestEntry,
        open: (String) -> InputStream?,
        allowUnpinnedExperimental: Boolean,
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
                    if (expectedHash != null && !hash.equals(expectedHash, ignoreCase = true)) {
                        return ModelAssetValidation.Invalid("asset SHA-256 does not match the manifest")
                    }
                    if (entry.asset.runtimeType != ModelRuntimeType.RuleStatistics && expectedHash == null) {
                        if (allowUnpinnedExperimental) {
                            return ModelAssetValidation.UnpinnedExperimental(total, hash)
                        }
                        return ModelAssetValidation.Invalid(
                            "model asset is not pinned by a manifest SHA-256",
                        )
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
        loadResult: ModelLoadResult<*>? = null,
    ): ModelAvailability =
        when {
            !entry.inferenceAdapterImplemented -> ModelAvailability.RunnerNotImplemented
            entry.asset.requiredContractSchemaVersion != ModelAssetManifest.CONTRACT_SCHEMA_VERSION ->
                ModelAvailability.ContractUnsupported
            validation is ModelAssetValidation.Missing -> ModelAvailability.AssetMissing
            validation is ModelAssetValidation.Invalid -> ModelAvailability.AssetInvalid
            validation is ModelAssetValidation.UnpinnedExperimental ->
                ModelAvailability.ExperimentalOnly
            loadResult is ModelLoadResult.UnsupportedContract -> ModelAvailability.ContractUnsupported
            loadResult is ModelLoadResult.RuntimeUnavailable -> ModelAvailability.LoadFailed
            loadResult is ModelLoadResult.LoadFailed -> ModelAvailability.LoadFailed
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
        // Only a pinned, hash-matching Valid asset is considered asset-valid.
        // An UnpinnedExperimental asset is present but NOT valid: never production-ready.
        val valid = validation is ModelAssetValidation.Valid
        val contractSupported =
            entry.asset.requiredContractSchemaVersion == ModelAssetManifest.CONTRACT_SCHEMA_VERSION
        val inferenceAvailable =
            entry.inferenceAdapterImplemented && valid && contractSupported && runtimeAvailable
        val experimentalOnly =
            (inferenceAvailable && !entry.productionReady) ||
                validation is ModelAssetValidation.UnpinnedExperimental
        return ModelReadiness(
            runnerImplemented = entry.inferenceAdapterImplemented,
            assetPresent = present,
            assetValid = valid,
            contractSupported = contractSupported,
            inferenceAvailable = inferenceAvailable,
            productionReady = inferenceAvailable && entry.productionReady,
            experimentalOnly = experimentalOnly,
        )
    }
}
