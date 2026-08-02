package com.projectnuke.keplerstudio.ui

import com.projectnuke.keplerstudio.editor.ModelRuntimeType
import com.projectnuke.keplerstudio.editor.ModelFeature
import com.projectnuke.keplerstudio.editor.ValidatedModelCapabilityToken
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RemasterModelSessionValidationTest {

    private fun token(epoch: Long) =
        ValidatedModelCapabilityToken(
            feature = ModelFeature.SubjectSelection,
            modelId = "edge_masker",
            approvedAssetPath = "models/edge_masker.task",
            semanticVersion = "1.0.0",
            contractSchema = 1,
            runtimeType = ModelRuntimeType.MediaPipeTask,
            approvedAssetSha256 = null,
            packagingVersion = "bundled-v1",
            validationSequence = epoch,
            validationGeneration = epoch,
        )

    @Test
    fun `new validation epoch invalidates the identity of an older loaded session`() {
        val sessionA = token(10).sessionIdentity()
        val sessionB = token(11).sessionIdentity()

        assertNotEquals(sessionA, sessionB)
        assertFalse(sessionA == sessionB)
        assertTrue(sessionB.validationEpoch > sessionA.validationEpoch)
    }

    @Test
    fun `asset and contract facts are part of session identity`() {
        val base = token(10).sessionIdentity()
        val changedAsset = token(10).copyForTest(approvedAssetPath = "models/other.task").sessionIdentity()
        assertNotEquals(base, changedAsset)
    }
}

private fun ValidatedModelCapabilityToken.copyForTest(
    approvedAssetPath: String,
) =
    ValidatedModelCapabilityToken(
        feature = feature,
        modelId = modelId,
        approvedAssetPath = approvedAssetPath,
        semanticVersion = semanticVersion,
        contractSchema = contractSchema,
        runtimeType = runtimeType,
        approvedAssetSha256 = approvedAssetSha256,
        packagingVersion = packagingVersion,
        validationSequence = validationSequence,
        validationGeneration = validationGeneration,
    )
