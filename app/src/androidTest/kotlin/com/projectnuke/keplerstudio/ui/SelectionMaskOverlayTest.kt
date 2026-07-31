package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.projectnuke.keplerstudio.editor.SelectionLayer
import com.projectnuke.keplerstudio.editor.SelectionLayerKind
import org.junit.Rule
import org.junit.Test

class SelectionMaskOverlayTest {
    @get:Rule
    val compose = createComposeRule()

    private fun makeLayer(name: String = "피사체", enabled: Boolean = true, inverted: Boolean = false): SelectionLayer {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFFFFFFFF.toInt())
        return SelectionLayer(
            id = "test-layer",
            name = name,
            kind = SelectionLayerKind.Subject,
            bitmap = bmp,
            enabled = enabled,
            inverted = inverted,
        )
    }

    @Test
    fun overlayBadgeDisplayedWhenVisibleAndLayerNonNull() {
        var visible by mutableStateOf(true)
        val layer = makeLayer()
        compose.setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    SelectionMaskOverlay(
                        layer = layer,
                        visible = visible,
                        scale = 1f,
                        offset = androidx.compose.ui.geometry.Offset.Zero,
                    )
                }
            }
        }
        compose.onNodeWithText("피사체").assertIsDisplayed()
    }

    @Test
    fun overlayBadgeHiddenWhenInvisible() {
        var visible by mutableStateOf(false)
        val layer = makeLayer()
        compose.setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    SelectionMaskOverlay(
                        layer = layer,
                        visible = visible,
                        scale = 1f,
                        offset = androidx.compose.ui.geometry.Offset.Zero,
                    )
                }
            }
        }
        compose.onNodeWithText("피사체").assertDoesNotExist()
    }

    @Test
    fun overlayBadgeHiddenWhenLayerDisabled() {
        var visible by mutableStateOf(true)
        val layer = makeLayer(enabled = false)
        compose.setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    SelectionMaskOverlay(
                        layer = layer,
                        visible = visible,
                        scale = 1f,
                        offset = androidx.compose.ui.geometry.Offset.Zero,
                    )
                }
            }
        }
        compose.onNodeWithText("피사체").assertDoesNotExist()
    }

    @Test
    fun togglingOverlayVisibilityChangesDrawPath() {
        var visible by mutableStateOf(false)
        val layer = makeLayer()
        compose.setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize().padding(200.dp)) {
                    SelectionMaskOverlay(
                        layer = layer,
                        visible = visible,
                        scale = 1f,
                        offset = androidx.compose.ui.geometry.Offset.Zero,
                    )
                }
            }
        }
        compose.onNodeWithText("피사체").assertDoesNotExist()
        compose.runOnIdle { visible = true }
        compose.waitForIdle()
        compose.onNodeWithText("피사체").assertIsDisplayed()
    }

    @Test
    fun overlayRespectsActiveLayerRename() {
        var visible by mutableStateOf(true)
        val layer = makeLayer(name = "브러시 마스크 1")
        compose.setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    SelectionMaskOverlay(
                        layer = layer,
                        visible = visible,
                        scale = 1f,
                        offset = androidx.compose.ui.geometry.Offset.Zero,
                    )
                }
            }
        }
        compose.onNodeWithText("브러시 마스크 1").assertIsDisplayed()
    }
}
