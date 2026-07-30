package com.projectnuke.keplerstudio.editor

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class AlgorithmVersionPolicyTest {
    @Test
    fun explicitCurrentContractDoesNotMigrate() {
        val result =
            resolveExecutedAlgorithmVersion(
                NativeRenderRoute.V2,
                AlgorithmContracts.NATIVE_V2,
            )

        assertEquals(AlgorithmContracts.NATIVE_V2, result.executedVersion)
        assertNull(result.migratedFromVersion)
    }

    @Test
    fun legacyV2NeverMasqueradesAsCurrentContract() {
        val parsed = AlgorithmContractSet.fromLegacy("native-v2")
        val result = resolveExecutedAlgorithmVersion(NativeRenderRoute.V2, "native-v2")

        assertEquals(AlgorithmContracts.LEGACY_NATIVE_V2, parsed.nativeRenderContract)
        assertEquals(AlgorithmContracts.NATIVE_V2, result.executedVersion)
        assertEquals("native-v2", result.migratedFromVersion)
    }

    @Test
    fun legacyV1RemainsDistinctFromFrozenContract() {
        val parsed = AlgorithmContractSet.fromLegacy("native-v1")
        val result = resolveExecutedAlgorithmVersion(NativeRenderRoute.V1, "native-v1")

        assertEquals(AlgorithmContracts.LEGACY_NATIVE_V1, parsed.nativeRenderContract)
        assertEquals(AlgorithmContracts.NATIVE_V1, result.executedVersion)
        assertEquals("native-v1", result.migratedFromVersion)
    }

    @Test
    fun labelOnlyNativeContractTwoAliasesPixelsButRecordsMigration() {
        val parsed = AlgorithmContractSet.fromLegacy("native-v2-contract-2")
        val result =
            resolveExecutedAlgorithmVersion(NativeRenderRoute.V2, "native-v2-contract-2")

        assertEquals(AlgorithmContracts.NATIVE_V2, parsed.nativeRenderContract)
        assertEquals("native-v2-contract-2", result.migratedFromVersion)
    }

    @Test
    fun compositeFeatureDecisionIsSeparatedFromContracts() {
        val parsed =
            AlgorithmContractSet.fromLegacy(
                "native-v2-contract-2+remaster-V2MaskAware+flare-RuleSelected"
            )

        assertEquals(AlgorithmContracts.NATIVE_V2, parsed.nativeRenderContract)
        assertEquals(
            AlgorithmContracts.LEGACY_REMASTER_COMPOSITE,
            parsed.remasterContract,
        )
        assertEquals(AlgorithmContracts.LEGACY_FLARE_COMPOSITE, parsed.flareGuardContract)
    }

    @Test
    fun futureMetadataCannotRelabelCurrentExecution() {
        val result = resolveExecutedAlgorithmVersion(NativeRenderRoute.V1, "native-v9")

        assertEquals(AlgorithmContracts.NATIVE_V1, result.executedVersion)
        assertEquals("native-v9", result.migratedFromVersion)
    }

}
