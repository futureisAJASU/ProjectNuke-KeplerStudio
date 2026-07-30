package com.projectnuke.keplerstudio.editor

import org.json.JSONObject
/**
 * Pixel contracts are stage-specific. A feature decision is deliberately absent:
 * decisions belong to [BakedFeatureProvenance], never to a contract identifier.
 */
data class AlgorithmContractSet(
    val schemaVersion: Int = SCHEMA_VERSION,
    val nativeRenderContract: String? = null,
    val flareGuardContract: String? = null,
    val remasterContract: String? = null,
    val subjectSelectionContract: String? = null,
    val selectionBlendContract: String? = null,
    val migratedFromLegacy: String? = null,
) {
    companion object {
        const val SCHEMA_VERSION = 1

        fun native(route: NativeRenderRoute): AlgorithmContractSet =
            AlgorithmContractSet(
                nativeRenderContract =
                    if (route == NativeRenderRoute.V2) {
                        AlgorithmContracts.NATIVE_V2
                    } else {
                        AlgorithmContracts.NATIVE_V1
                    },
            )

        /**
         * Reads metadata written before structured contracts. Legacy V2 is never
         * promoted to the current V2 contract; a re-render records migration.
         */
        fun fromLegacy(value: String?): AlgorithmContractSet {
            val raw = value?.trim()?.takeIf(String::isNotEmpty)
                ?: return AlgorithmContractSet()
            val parts = raw.split('+').filter(String::isNotBlank)
            val nativePart = parts.firstOrNull()
            var set =
                when (nativePart) {
                    "native-v1" ->
                        AlgorithmContractSet(
                            nativeRenderContract = AlgorithmContracts.LEGACY_NATIVE_V1,
                            migratedFromLegacy = raw,
                        )
                    "native-v2" ->
                        AlgorithmContractSet(
                            nativeRenderContract = AlgorithmContracts.LEGACY_NATIVE_V2,
                            migratedFromLegacy = raw,
                        )
                    AlgorithmContracts.NATIVE_V1 ->
                        AlgorithmContractSet(nativeRenderContract = AlgorithmContracts.NATIVE_V1)
                    AlgorithmContracts.NATIVE_V2 ->
                        AlgorithmContractSet(nativeRenderContract = AlgorithmContracts.NATIVE_V2)
                    // This label-only bump shipped no ordinary native pixel change.
                    "native-v2-contract-2" ->
                        AlgorithmContractSet(
                            nativeRenderContract = AlgorithmContracts.NATIVE_V2_PREVIOUS,
                            migratedFromLegacy = raw,
                        )
                    else ->
                        AlgorithmContractSet(
                            nativeRenderContract = nativePart,
                            migratedFromLegacy = raw,
                        )
                }
            parts.drop(1).forEach { suffix ->
                set =
                    when {
                        suffix.startsWith("flare-") || suffix == "flare-native-rule" ->
                            set.copy(
                                flareGuardContract = AlgorithmContracts.LEGACY_FLARE_COMPOSITE,
                                migratedFromLegacy = raw,
                            )
                        suffix.startsWith("remaster-") ->
                            set.copy(
                                remasterContract = AlgorithmContracts.LEGACY_REMASTER_COMPOSITE,
                                migratedFromLegacy = raw,
                            )
                        suffix.startsWith("subject-") ->
                            set.copy(
                                subjectSelectionContract =
                                    AlgorithmContracts.LEGACY_SUBJECT_COMPOSITE,
                                migratedFromLegacy = raw,
                            )
                        else -> set
                    }
            }
            return set
        }
    }
}

internal fun AlgorithmContractSet.toJson(): JSONObject =
    JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("nativeRenderContract", nativeRenderContract ?: JSONObject.NULL)
        put("flareGuardContract", flareGuardContract ?: JSONObject.NULL)
        put("remasterContract", remasterContract ?: JSONObject.NULL)
        put("subjectSelectionContract", subjectSelectionContract ?: JSONObject.NULL)
        put("selectionBlendContract", selectionBlendContract ?: JSONObject.NULL)
        put("migratedFromLegacy", migratedFromLegacy ?: JSONObject.NULL)
    }

internal fun parseAlgorithmContractSet(
    json: JSONObject?,
    legacyVersion: String?,
): AlgorithmContractSet {
    if (json == null) return AlgorithmContractSet.fromLegacy(legacyVersion)
    fun optional(key: String): String? =
        if (!json.has(key) || json.isNull(key)) null
        else json.optString(key).takeIf(String::isNotBlank)
    val schemaVersion =
        json.optInt("schemaVersion", AlgorithmContractSet.SCHEMA_VERSION)
    val nativeContract = optional("nativeRenderContract")
    return AlgorithmContractSet(
        schemaVersion = schemaVersion,
        nativeRenderContract =
            if (schemaVersion > AlgorithmContractSet.SCHEMA_VERSION) {
                "unsupported-contract-schema-$schemaVersion:${nativeContract ?: "unknown"}"
            } else {
                nativeContract
            },
        flareGuardContract = optional("flareGuardContract"),
        remasterContract = optional("remasterContract"),
        subjectSelectionContract = optional("subjectSelectionContract"),
        selectionBlendContract = optional("selectionBlendContract"),
        migratedFromLegacy =
            optional("migratedFromLegacy")
                ?: if (schemaVersion > AlgorithmContractSet.SCHEMA_VERSION) {
                    "algorithm-contract-schema-$schemaVersion"
                } else {
                    null
                },
    )
}

internal fun AlgorithmContractSet.nativeVersionForMetadataRestore(
    fallback: String?,
): String? =
    when {
        nativeRenderContract?.startsWith("unsupported-contract-schema-") == true ->
            nativeRenderContract
        migratedFromLegacy != null -> migratedFromLegacy
        nativeRenderContract != null -> nativeRenderContract
        else -> fallback
    }

internal object AlgorithmContracts {
    const val LEGACY_NATIVE_V1 = "native-v1-legacy"
    const val LEGACY_NATIVE_V2 = "native-v2-legacy-pre-contract"
    const val NATIVE_V1 = "native-v1-contract-1"
    const val NATIVE_V2_PREVIOUS = "native-v2-contract-1"
    const val NATIVE_V2 = "native-v2-contract-3"

    const val FLARE_V1 = "flare-v1-contract-1"
    const val FLARE_V2 = "flare-v2-contract-2"
    const val REMASTER_V1 = "remaster-v1-contract-1"
    const val REMASTER_V2 = "remaster-v2-contract-3"
    const val SUBJECT_V1 = "subject-v1-contract-1"
    const val SUBJECT_V2 = "subject-v2-contract-2"
    const val SELECTION_BLEND = "selection-blend-contract-1"

    const val LEGACY_FLARE_COMPOSITE = "flare-legacy-composite"
    const val LEGACY_REMASTER_COMPOSITE = "remaster-legacy-composite"
    const val LEGACY_SUBJECT_COMPOSITE = "subject-legacy-composite"
}
