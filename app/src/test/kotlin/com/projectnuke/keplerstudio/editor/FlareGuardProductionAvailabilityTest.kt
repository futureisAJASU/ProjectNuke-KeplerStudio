package com.projectnuke.keplerstudio.editor

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FlareGuardProductionAvailabilityTest {
    @Test
    fun packagedPinnedAssetCanResolveModelAssistedV2() {
        val manifest = checkNotNull(ModelAssetManifest.byId("flare_masker"))
        val validation =
            ModelAssetValidator.validate(manifest) { path ->
                listOf(
                    File("src/main/assets", path),
                    File("app/src/main/assets", path),
                ).firstOrNull(File::isFile)?.inputStream()
            }

        val valid = assertIs<ModelAssetValidation.Valid>(validation)
        assertEquals(manifest.asset.sha256, valid.sha256)
        assertTrue(valid.byteCount >= manifest.asset.minimumExpectedBytes)

        val route =
            RouteResolver.resolveFlareRoute(
                CorrectionEngine.Engine2,
                FlareGuardRoute.V2ModelAssisted,
                modelAvailable = true,
            )
        assertEquals(FlareGuardRoute.V2ModelAssisted, route.actualRoute)
    }
}
