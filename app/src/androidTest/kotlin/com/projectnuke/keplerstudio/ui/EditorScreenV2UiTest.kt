package com.projectnuke.keplerstudio.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.projectnuke.keplerstudio.editor.CorrectionEngine
import com.projectnuke.keplerstudio.editor.CorrectionEngineState
import com.projectnuke.keplerstudio.editor.DehazeEngine
import com.projectnuke.keplerstudio.editor.DetailEngine
import com.projectnuke.keplerstudio.editor.ExportHistoryRetention
import com.projectnuke.keplerstudio.editor.FallbackPolicy
import com.projectnuke.keplerstudio.editor.NativeRenderRoute
import com.projectnuke.keplerstudio.editor.NoiseEngine
import com.projectnuke.keplerstudio.editor.RenderRouteDecision
import com.projectnuke.keplerstudio.editor.ToneEngine
import com.projectnuke.keplerstudio.editor.VisiblePreviewState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EditorScreenV2UiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun narrowEditorTopBarKeepsPrimaryActionsVisible() {
        compose.setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(320.dp)) {
                    V2TopBar(
                        nativeVersion = "native-v2",
                        correctionEngineState =
                            CorrectionEngineState(
                                visiblePreview = VisiblePreviewState.Original,
                            ),
                        selectedTab = EditorDestination.Editor,
                        hasImage = true,
                        isBusy = false,
                        canExport = true,
                        canUndo = true,
                        canRedo = true,
                        onTabSelected = {},
                        onOpen = {},
                        onUndo = {},
                        onRedo = {},
                        onRotate = {},
                        onReset = {},
                        onSave = {},
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("사진 열기").assertIsDisplayed()
        compose.onNodeWithContentDescription("실행 취소").assertIsDisplayed()
        compose.onNodeWithContentDescription("다시 실행").assertIsDisplayed()
        compose.onNodeWithContentDescription("편집 작업 더보기").assertIsDisplayed()
        compose.onNodeWithText("설정").assertIsDisplayed()
    }

    @Test
    fun nonEditorDestinationDoesNotExposeEditorCommands() {
        compose.setContent {
            MaterialTheme {
                V2TopBar(
                    nativeVersion = "native-v2",
                    correctionEngineState = CorrectionEngineState(),
                    selectedTab = EditorDestination.Settings,
                    hasImage = true,
                    isBusy = false,
                    canExport = true,
                    canUndo = true,
                    canRedo = true,
                    onTabSelected = {},
                    onOpen = {},
                    onUndo = {},
                    onRedo = {},
                    onRotate = {},
                    onReset = {},
                    onSave = {},
                )
            }
        }

        compose.onNodeWithContentDescription("사진 열기").assertDoesNotExist()
        compose.onNodeWithContentDescription("편집 작업 더보기").assertDoesNotExist()
        compose.onNodeWithText("설정").assertIsDisplayed()
    }

    @Test
    fun optionRowUsesSelectableRadioSemantics() {
        var selected by mutableStateOf(CorrectionEngine.Engine1)
        compose.setContent {
            MaterialTheme {
                V2OptionRow(
                    title = "새 사진 기본 엔진",
                    values = CorrectionEngine.entries,
                    selected = selected,
                    label = CorrectionEngine::displayName,
                    onSelected = { selected = it },
                )
            }
        }

        compose
            .onNode(hasText("엔진 1") and isSelectable(), useUnmergedTree = true)
            .assertIsSelected()
        compose
            .onNode(hasText("엔진 2") and isSelectable(), useUnmergedTree = true)
            .performClick()
        compose.runOnIdle { assertEquals(CorrectionEngine.Engine2, selected) }
    }

    @Test
    fun fallbackRetryTargetsEngine2AndRevertTargetsEngine1() {
        var requestedEngine: CorrectionEngine? = null
        val fallbackState =
            CorrectionEngineState(
                documentEngine = CorrectionEngine.Engine2,
                visiblePreview =
                    VisiblePreviewState.Rendered(
                        requestedRoute = NativeRenderRoute.V2,
                        actualRoute = NativeRenderRoute.V1,
                        decision = RenderRouteDecision.RuntimeFallbackToV1,
                        algorithmVersion = "v2-test",
                    ),
                fallbackPolicy = FallbackPolicy.RetryV2OnNextOperation,
            )
        compose.setContent {
            SettingsHarness(
                correctionEngineState = fallbackState,
                onApplyCorrectionEngine = { requestedEngine = it },
            )
        }

        compose.onNodeWithText("엔진 2 다시 시도").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(CorrectionEngine.Engine2, requestedEngine) }
        compose.onNodeWithText("엔진 1로 되돌리기").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(CorrectionEngine.Engine1, requestedEngine) }
    }

    @Test
    fun savedHistoryClearRequiresConsequenceConfirmation() {
        compose.setContent {
            SettingsHarness(
                correctionEngineState =
                    CorrectionEngineState(
                        visiblePreview = VisiblePreviewState.Original,
                    ),
                savedExportCount = 2,
            )
        }

        compose.onNodeWithText("저장 기록 비우기").performScrollTo().performClick()
        compose.onNodeWithText("저장 기록을 비울까요?").assertIsDisplayed()
        compose
            .onNodeWithText("갤러리에 저장된 사진 파일은 유지됩니다.", substring = true)
            .assertIsDisplayed()
    }

    @Composable
    private fun SettingsHarness(
        correctionEngineState: CorrectionEngineState,
        savedExportCount: Int = 0,
        onApplyCorrectionEngine: (CorrectionEngine) -> Unit = {},
    ) {
        MaterialTheme {
            V2SettingsScreen(
                exportHistoryRetention = ExportHistoryRetention.Never,
                savedExportCount = savedExportCount,
                draftSavedAtMillis = null,
                recoveryDebugInfo = null,
                showRecoveryDebugCard = false,
                noiseEngine = NoiseEngine.FastEdgeAware,
                detailEngine = DetailEngine.MaskedUnsharp,
                toneEngine = ToneEngine.HistogramAuto,
                hazeEngine = DehazeEngine.FastContrast,
                correctionEngineState = correctionEngineState,
                documentActionAvailable = true,
                maintenanceBusy = false,
                comparisonBusy = false,
                onRetentionSelected = {},
                onNoiseEngineSelected = {},
                onDetailEngineSelected = {},
                onToneEngineSelected = {},
                onHazeEngineSelected = {},
                onDefaultCorrectionEngineSelected = {},
                onApplyCorrectionEngine = onApplyCorrectionEngine,
                onExperimentalLabChanged = {},
                onGenerateComparison = {},
                onGenerateProcessingEngineComparison = {},
                onGenerateEditorResolutionComparison = {},
                onCancelComparison = {},
                onClearDraft = {},
                onDismissRecoveryDebugCard = {},
                onCleanupOldTemporarySources = {},
                onClearSavedExports = {},
                modifier = Modifier.width(360.dp),
            )
        }
    }
}
