package com.projectnuke.keplerstudio.editor

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class AlgorithmVersionPolicyTest {
    @Test
    fun sameVersionRestoreKeepsCurrentVersionWithoutMigration() {
        val result = resolveExecutedAlgorithmVersion(NativeRenderRoute.V2, "native-v2")

        assertEquals(PixelContractVersion.V2, result.executedVersion)
        assertEquals(null, result.migratedFromVersion)
        assertNull(result.migratedFromVersion)
    }

    @Test
    fun olderV2MetadataMigratesToCurrentVersionTruthfully() {
        val result = resolveExecutedAlgorithmVersion(NativeRenderRoute.V2, "native-v2-preview-0")

        assertEquals(PixelContractVersion.V2, result.executedVersion)
        assertEquals("native-v2-preview-0", result.migratedFromVersion)
    }

    @Test
    fun futureMetadataCannotRelabelCurrentExecution() {
        val result = resolveExecutedAlgorithmVersion(NativeRenderRoute.V1, "native-v9")

        assertEquals(PixelContractVersion.V1, result.executedVersion)
        assertEquals("native-v9", result.migratedFromVersion)
    }
}
