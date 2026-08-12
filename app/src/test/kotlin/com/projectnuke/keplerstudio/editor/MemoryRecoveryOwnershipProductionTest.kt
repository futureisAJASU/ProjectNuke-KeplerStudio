package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import com.projectnuke.keplerstudio.ui.selectSelectionLayer
import com.projectnuke.keplerstudio.ui.duplicateActiveSelectionLayer
import com.projectnuke.keplerstudio.ui.autoStraightenCrop
import com.projectnuke.keplerstudio.ui.setStraightenDegrees
import com.projectnuke.keplerstudio.ui.applyMaskAwareRemaster
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MemoryRecoveryOwnershipProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val application: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun setUp() {
        harness = OwnedEditorViewModelHarness(installBitmapCopySeam = true, application = application)
    }

    @After
    fun tearDown() {
        ExperimentalLabController.resetForTest()
        ExperimentalComparisonStore.clear()
        harness.close()
    }

    @Test
    fun automaticRecoveryStaleBeforeCleanupLeavesReplacementUntouched() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1001L)
        val seam = MemoryRecoveryTestSeam()
        harness.ownSeam(MemoryRecoveryTestSeam.install(seam))

        editor.requestAllocationRecovery(MemoryRetryAction.CreateBrushSelection, Long.MAX_VALUE)
        awaitEvent { seam.automaticReached.isCompleted }
        val oldToken = checkNotNull(seam.automaticReached.getCompleted()).token

        openDocument(editor, 0xff203040.toInt(), 1002L)
        editor.updateUiState { it.copy(message = "B message") }
        val releasesBeforeOldCompletion = nativeReleases
        seam.automaticRelease.complete(Unit)
        awaitEvent { editor.memoryRecoveryOwnerCloseCountForTest(oldToken) == 1 }

        assertEquals(0, seam.cleanupStarted)
        assertEquals(releasesBeforeOldCompletion, nativeReleases)
        assertEquals("B message", editor.uiState.value.message)
        assertNull(editor.uiState.value.memoryRecoveryRequest)
        assertNull(editor.memoryRecoveryOwnerPhaseForTest())
    }

    @Test
    fun documentReplacementInvalidatesPendingRecoveryDialog() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1101L)
        editor.requestAllocationRecovery(MemoryRetryAction.CreateBrushSelection, Long.MAX_VALUE)
        awaitEvent { editor.uiState.value.memoryRecoveryRequest != null }
        val token = checkNotNull(editor.memoryRecoveryTokenForTest())

        openDocument(editor, 0xff203040.toInt(), 1102L)

        assertNull(editor.uiState.value.memoryRecoveryRequest)
        assertNull(editor.memoryRecoveryOwnerPhaseForTest())
        assertEquals(1, editor.memoryRecoveryOwnerCloseCountForTest(token))
    }

    @Test
    fun staleOldRecoveryDoesNotSuppressNewDocumentFailure() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1201L)
        val seam = MemoryRecoveryTestSeam()
        harness.ownSeam(MemoryRecoveryTestSeam.install(seam))
        editor.requestAllocationRecovery(MemoryRetryAction.CreateBrushSelection, Long.MAX_VALUE)
        awaitEvent { seam.automaticReached.isCompleted }
        val oldToken = checkNotNull(seam.automaticReached.getCompleted()).token

        openDocument(editor, 0xff203040.toInt(), 1202L)
        editor.requestAllocationRecovery(MemoryRetryAction.CreateBrushSelection, Long.MAX_VALUE)
        val newToken = checkNotNull(editor.memoryRecoveryTokenForTest())
        assertNotEquals(oldToken, newToken)

        seam.automaticRelease.complete(Unit)
        awaitEvent {
            editor.memoryRecoveryOwnerPhaseForTest() == "AwaitingUserDecision" &&
                editor.memoryRecoveryTokenForTest() == newToken
        }
        assertEquals(1, seam.cleanupStarted)
        assertEquals(1, editor.memoryRecoveryOwnerCloseCountForTest(oldToken))
    }

    @Test
    fun systemTrimCleanupDoesNotSuppressForegroundRecovery() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1301L)
        val seam = MemoryRecoveryTestSeam()
        harness.ownSeam(MemoryRecoveryTestSeam.install(seam))

        editor.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        awaitEvent { seam.trimReached.isCompleted }
        editor.requestAllocationRecovery(MemoryRetryAction.CreateBrushSelection, Long.MAX_VALUE)
        assertEquals(MemoryRetryAction.CreateBrushSelection, editor.memoryRecoveryActionForTest())
        assertTrue(editor.trimMemoryCleanupActiveForTest())

        seam.trimRelease.complete(Unit)
        awaitEvent { seam.automaticReached.isCompleted }
        seam.automaticRelease.complete(Unit)
        awaitEvent { editor.uiState.value.memoryRecoveryRequest != null }
        assertEquals(MemoryRetryAction.CreateBrushSelection, editor.memoryRecoveryActionForTest())
    }

    @Test
    fun firstAutomaticRecoveryDialogIsActionableAndEntersStrongCleanup() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1311L)
        val seam = MemoryRecoveryTestSeam()
        harness.ownSeam(MemoryRecoveryTestSeam.install(seam))

        editor.requestAllocationRecovery(MemoryRetryAction.CreateBrushSelection, Long.MAX_VALUE)
        awaitEvent { seam.automaticReached.isCompleted }
        seam.automaticRelease.complete(Unit)
        awaitEvent { editor.uiState.value.memoryRecoveryRequest != null }
        val request = checkNotNull(editor.uiState.value.memoryRecoveryRequest)
        assertEquals(request.token, editor.memoryRecoveryTokenForTest())

        editor.retryPendingMemoryRecovery(request.token)
        awaitEvent { seam.strongReached.isCompleted }
        assertEquals(request.token, seam.strongReached.getCompleted().token)
        assertEquals(request.token, editor.memoryRecoveryTokenForTest())

        seam.strongRelease.complete(Unit)
        awaitEvent { editor.uiState.value.memoryRecoveryRequest == null }
    }

    @Test
    fun cancelClosesActionableOwnerWithoutStrongCleanupOrResurrection() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1321L)
        val seam = MemoryRecoveryTestSeam()
        harness.ownSeam(MemoryRecoveryTestSeam.install(seam))

        editor.requestAllocationRecovery(MemoryRetryAction.CreateBrushSelection, Long.MAX_VALUE)
        awaitEvent { seam.automaticReached.isCompleted }
        seam.automaticRelease.complete(Unit)
        awaitEvent { editor.uiState.value.memoryRecoveryRequest != null }
        val token = checkNotNull(editor.uiState.value.memoryRecoveryRequest?.token)

        editor.cancelPendingMemoryRecovery(token)

        assertEquals(1, editor.memoryRecoveryOwnerCloseCountForTest(token))
        assertNull(editor.uiState.value.memoryRecoveryRequest)
        assertNull(editor.memoryRecoveryOwnerPhaseForTest())
        assertFalse(seam.strongReached.isCompleted)
    }

    @Test
    fun strongCleanupInsufficientIsTerminalAndDoesNotReopenDialog() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1331L)
        val seam = MemoryRecoveryTestSeam()
        harness.ownSeam(MemoryRecoveryTestSeam.install(seam))

        editor.requestAllocationRecovery(MemoryRetryAction.CreateBrushSelection, Long.MAX_VALUE)
        awaitEvent { seam.automaticReached.isCompleted }
        seam.automaticRelease.complete(Unit)
        awaitEvent { editor.uiState.value.memoryRecoveryRequest != null }
        val token = checkNotNull(editor.uiState.value.memoryRecoveryRequest?.token)
        editor.retryPendingMemoryRecovery(token)
        awaitEvent { seam.strongReached.isCompleted }
        seam.strongRelease.complete(Unit)
        awaitEvent { editor.memoryRecoveryOwnerPhaseForTest() == null }

        assertNull(editor.uiState.value.memoryRecoveryRequest)
        assertEquals(1, editor.memoryRecoveryOwnerCloseCountForTest(token))
        assertEquals(2, seam.cleanupStarted)
        assertEquals(
            "정리 후에도 현재 작업에 필요한 메모리를 확보하지 못했습니다. 이미지와 적용된 편집은 안전하게 유지됩니다.",
            editor.uiState.value.message,
        )
        assertFalse(checkNotNull(editor.uiState.value.previewBitmap).isRecycled)
    }

    @Test
    fun productionAutomaticRetryFailureTransfersToOneUserDecisionWithoutSecondCleanup() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1332L)
        val seam = MemoryRecoveryTestSeam(rejectSelectionMaskAdmission = true)
        harness.ownSeam(MemoryRecoveryTestSeam.install(seam))

        awaitEvent { editor.canEnterEditorActionPure(allowMaskSupersession = true) }
        editor.createBrushSelectionInternal()
        awaitEvent { seam.automaticReached.isCompleted }
        seam.automaticRelease.complete(Unit)
        awaitEvent { editor.uiState.value.memoryRecoveryRequest != null }

        assertEquals(1, seam.cleanupStarted)
        assertEquals(1, seam.automaticEntryCount)
        assertEquals(MemoryRetryAction.CreateBrushSelection, editor.memoryRecoveryActionForTest())
        assertEquals(0, editor.uiState.value.selectionLayers.size)
    }

    @Test
    fun productionStrongRetryFailureIsTerminalWithoutThirdCleanup() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1333L)
        val seam = MemoryRecoveryTestSeam(rejectSelectionMaskAdmission = true)
        harness.ownSeam(MemoryRecoveryTestSeam.install(seam))
        val pixels = IntArray(1)
        ExperimentalComparisonStore.publishDebug(
            OwnedDebugComparisonArtifact.create(
                DebugComparisonArtifact(
                    fixtureVersion = "memory-recovery-strong-retry-failure",
                    width = 1,
                    height = 1,
                    baselineArgb = pixels.copyOf(),
                    experimentalArgb = pixels.copyOf(),
                    maskArgb = null,
                    differenceHeatmapArgb = pixels.copyOf(),
                    metrics = ImageQualityMetricsV2(
                        changedPixelRatio = 0f,
                        maximumChannelDelta = 0,
                        p95ChannelDelta = 0,
                        lumaMeanAbsoluteError = 0f,
                        chromaMeanAbsoluteError = 0f,
                        highlightClippingIncrease = 0f,
                        shadowClippingIncrease = 0f,
                        localEdgeOvershoot = 0f,
                        flatRegionVariationIncrease = 0f,
                        colorNeutralDrift = 0f,
                    ),
                ),
            ),
        )

        awaitEvent { editor.canEnterEditorActionPure(allowMaskSupersession = true) }
        editor.createBrushSelectionInternal()
        awaitEvent { seam.automaticReached.isCompleted }
        seam.automaticRelease.complete(Unit)
        awaitEvent { editor.uiState.value.memoryRecoveryRequest != null }
        val token = checkNotNull(editor.uiState.value.memoryRecoveryRequest?.token)

        editor.retryPendingMemoryRecovery(token)
        awaitEvent { seam.strongReached.isCompleted }
        seam.strongRelease.complete(Unit)
        awaitEvent { editor.memoryRecoveryOwnerPhaseForTest() == null }

        assertEquals(2, seam.cleanupStarted)
        assertEquals(1, seam.automaticEntryCount)
        assertEquals(1, seam.strongEntryCount)
        assertNull(editor.uiState.value.memoryRecoveryRequest)
        assertNull(editor.memoryRecoveryOwnerPhaseForTest())
        assertNull(editor.strongRetryAttemptForTest())
        assertEquals(
            "정리 후에도 현재 작업에 필요한 메모리를 확보하지 못했습니다. 이미지와 적용된 편집은 안전하게 유지됩니다.",
            editor.uiState.value.message,
        )
        assertTrue(checkNotNull(editor.uiState.value.previewBitmap).isRecycled.not())
    }

    @Test
    fun successfulStrongRetryClearsStrongAttemptMarker() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 0L)
        val comparisonSide = 1
        val comparisonPixels = IntArray(comparisonSide * comparisonSide)
        ExperimentalComparisonStore.publishDebug(
            OwnedDebugComparisonArtifact.create(
                DebugComparisonArtifact(
                    fixtureVersion = "memory-recovery-strong",
                    width = comparisonSide,
                    height = comparisonSide,
                    baselineArgb = comparisonPixels.copyOf(),
                    experimentalArgb = comparisonPixels.copyOf(),
                    maskArgb = null,
                    differenceHeatmapArgb = comparisonPixels.copyOf(),
                    metrics = ImageQualityMetricsV2(
                        changedPixelRatio = 0f,
                        maximumChannelDelta = 0,
                        p95ChannelDelta = 0,
                        lumaMeanAbsoluteError = 0f,
                        chromaMeanAbsoluteError = 0f,
                        highlightClippingIncrease = 0f,
                        shadowClippingIncrease = 0f,
                        localEdgeOvershoot = 0f,
                        flatRegionVariationIncrease = 0f,
                        colorNeutralDrift = 0f,
                    ),
                ),
            ),
        )
        val seam = MemoryRecoveryTestSeam()
        harness.ownSeam(MemoryRecoveryTestSeam.install(seam))
        val blocker = RetainedMemoryLedger.reserve("memory-recovery-strong-success")
        blocker.replace(
            RetainedMemoryCategory.NativeBitmap,
            BitmapMemoryBudget.availableBytes(),
        )

        editor.requestAllocationRecovery(
            MemoryRetryAction.CreateBrushSelection,
            BitmapMemoryBudget.availableBytes() / 2L,
        )
        awaitEvent { seam.automaticReached.isCompleted }
        seam.automaticRelease.complete(Unit)
        awaitEvent { editor.uiState.value.memoryRecoveryRequest != null }
        val token = checkNotNull(editor.uiState.value.memoryRecoveryRequest?.token)
        blocker.close()
        editor.retryPendingMemoryRecovery(token)
        awaitEvent { seam.strongReached.isCompleted }
        seam.strongRelease.complete(Unit)
        awaitEvent {
            editor.uiState.value.selectionLayers.size == 1 &&
                editor.strongRetryAttemptForTest() == null
        }
    }

    @Test
    fun trimWaitsForUserCleanupWhenUserReachesPreCleanupFirst() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1341L)
        val seam = MemoryRecoveryTestSeam()
        harness.ownSeam(MemoryRecoveryTestSeam.install(seam))

        editor.requestAllocationRecovery(MemoryRetryAction.CreateBrushSelection, Long.MAX_VALUE)
        awaitEvent { seam.automaticReached.isCompleted }
        editor.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertFalse(seam.trimReached.isCompleted)

        seam.automaticRelease.complete(Unit)
        awaitEvent { editor.uiState.value.memoryRecoveryRequest != null && seam.trimReached.isCompleted }
        seam.trimRelease.complete(Unit)
        awaitEvent { !editor.trimMemoryCleanupActiveForTest() }
        assertEquals(MemoryRetryAction.CreateBrushSelection, editor.memoryRecoveryActionForTest())
    }

    @Test
    fun oldCompletionCannotClearNewerRecoveryOwner() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1401L)
        val seam = MemoryRecoveryTestSeam()
        harness.ownSeam(MemoryRecoveryTestSeam.install(seam))
        editor.requestAllocationRecovery(MemoryRetryAction.CreateBrushSelection, Long.MAX_VALUE)
        awaitEvent { seam.automaticReached.isCompleted }
        val oldToken = checkNotNull(seam.automaticReached.getCompleted()).token
        openDocument(editor, 0xff203040.toInt(), 1402L)
        editor.requestAllocationRecovery(MemoryRetryAction.CreateBrushSelection, Long.MAX_VALUE)
        val newToken = checkNotNull(editor.memoryRecoveryTokenForTest())
        seam.automaticRelease.complete(Unit)
        awaitEvent { editor.memoryRecoveryTokenForTest() == newToken && editor.uiState.value.memoryRecoveryRequest != null }

        assertEquals(newToken, editor.memoryRecoveryTokenForTest())
        assertEquals("AwaitingUserDecision", editor.memoryRecoveryOwnerPhaseForTest())
        assertEquals(1, editor.memoryRecoveryOwnerCloseCountForTest(oldToken))
    }

    @Test
    fun duplicateSelectionRecoveryRejectsActiveLayerTargetDriftFromProductionEntryPoint() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1421L)
        val layerA = SelectionLayer("layer-A", "A", SelectionLayerKind.Brush, bitmap(0xff101010.toInt()))
        val layerB = SelectionLayer("layer-B", "B", SelectionLayerKind.Brush, bitmap(0xff202020.toInt()))
        editor.updateUiState {
            it.copy(selectionLayers = listOf(layerA, layerB), activeSelectionLayerId = layerA.id)
        }
        val seam = MemoryRecoveryTestSeam(rejectSelectionMaskAdmission = true)
        harness.ownSeam(MemoryRecoveryTestSeam.install(seam))

        awaitEvent { editor.canEnterEditorActionPure(allowMaskSupersession = true) }
        editor.duplicateActiveSelectionLayer()
        awaitEvent("duplicate automatic") { seam.automaticReached.isCompleted }
        assertEquals(
            SelectionRetryTarget.Layer(layerA.id),
            seam.automaticReached.getCompleted().selectionTarget,
        )
        editor.selectSelectionLayer(layerB.id)
        if (editor.uiState.value.activeSelectionLayerId != layerB.id) {
            editor.updateUiState { it.copy(activeSelectionLayerId = layerB.id) }
        }
        assertEquals(layerB.id, editor.uiState.value.activeSelectionLayerId)
        seam.automaticRelease.complete(Unit)
        awaitEvent { editor.memoryRecoveryOwnerPhaseForTest() == null }

        assertEquals(2, editor.uiState.value.selectionLayers.size)
        assertEquals(layerB.id, editor.uiState.value.activeSelectionLayerId)
        assertEquals(0, editor.undoEntryCountForTest())
        assertNull(editor.uiState.value.memoryRecoveryRequest)
    }

    @Test
    fun autoStraightenRetryBecomesStaleAfterProductionCropSetterChangesInput() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1422L)
        val seam = MemoryRecoveryTestSeam(rejectAutoStraightenInputCopy = true)
        harness.ownSeam(MemoryRecoveryTestSeam.install(seam))
        awaitEvent { editor.canEnterEditorActionPure() }

        editor.autoStraightenCrop()
        awaitEvent { seam.automaticReached.isCompleted }
        seam.automaticRelease.complete(Unit)
        awaitEvent { editor.uiState.value.memoryRecoveryRequest != null }
        val token = checkNotNull(editor.uiState.value.memoryRecoveryRequest?.token)
        editor.setStraightenDegrees(12f)
        editor.retryPendingMemoryRecovery(token)
        awaitEvent { editor.memoryRecoveryOwnerPhaseForTest() == null }

        assertEquals(12f, editor.uiState.value.cropState.straightenDegrees)
        assertEquals(1, seam.cleanupStarted)
        assertFalse(seam.strongReached.isCompleted)
    }

    @Test
    fun staleAutoStraightenFailureCannotCreateRecoveryForNewCropState() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1425L)
        val seam =
            MemoryRecoveryTestSeam(
                rejectAutoStraightenInputCopy = true,
                parkAutoStraightenInputCopy = true,
            )
        harness.ownSeam(MemoryRecoveryTestSeam.install(seam))
        awaitEvent { editor.canEnterEditorActionPure() }

        editor.autoStraightenCrop()
        awaitEvent { seam.autoStraightenInputCopyReached.isCompleted }
        editor.setStraightenDegrees(23f)
        assertEquals(23f, editor.uiState.value.cropState.straightenDegrees)
        seam.autoStraightenInputCopyRelease.complete(Unit)
        awaitEvent { seam.autoStraightenInputCopyFailureReached.isCompleted }
        awaitEvent { !editor.uiState.value.isBusy }

        assertEquals(23f, editor.uiState.value.cropState.straightenDegrees)
        assertNull(editor.memoryRecoveryOwnerPhaseForTest())
        assertNull(editor.uiState.value.memoryRecoveryRequest)
        assertEquals(0, seam.automaticEntryCount)
        assertFalse(seam.automaticReached.isCompleted)
        assertFalse(editor.uiState.value.message?.contains("기울기 보정에 실패") == true)
    }

    @Test
    fun modelAssistedMaskAwareRecoveryTreatsActiveSelectionAsIrrelevant() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1423L)
        val layer = SelectionLayer("model-layer", "model", SelectionLayerKind.Brush, bitmap(0xff101010.toInt()))
        editor.updateUiState { it.copy(selectionLayers = listOf(layer), activeSelectionLayerId = layer.id) }
        ExperimentalLabController.setRemasterOverride(RemasterRoute.V2ModelAssisted)
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        val seam = MemoryRecoveryTestSeam(rejectSelectionMaskAdmission = true)
        harness.ownSeam(MemoryRecoveryTestSeam.install(seam))
        awaitEvent { editor.canEnterEditorActionPure(allowMaskSupersession = true) }

        editor.applyMaskAwareRemaster()
        awaitEvent { seam.automaticReached.isCompleted }
        assertEquals(SelectionRetryTarget.Irrelevant, seam.automaticReached.getCompleted().selectionTarget)
        assertEquals(1, seam.automaticEntryCount)
    }

    @Test
    fun manualMaskAwareRecoveryBecomesStaleWhenActiveLayerChanges() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1424L)
        val layerA = SelectionLayer("manual-A", "A", SelectionLayerKind.Brush, bitmap(0xff101010.toInt()))
        val layerB = SelectionLayer("manual-B", "B", SelectionLayerKind.Brush, bitmap(0xff202020.toInt()))
        editor.updateUiState {
            it.copy(selectionLayers = listOf(layerA, layerB), activeSelectionLayerId = layerA.id)
        }
        ExperimentalLabController.setRemasterOverride(RemasterRoute.V2MaskAware)
        val seam = MemoryRecoveryTestSeam(rejectSelectionMaskAdmission = true)
        harness.ownSeam(MemoryRecoveryTestSeam.install(seam))
        awaitEvent { editor.canEnterEditorActionPure(allowMaskSupersession = true) }

        editor.applyMaskAwareRemaster()
        awaitEvent { seam.automaticReached.isCompleted }
        assertEquals(SelectionRetryTarget.Layer(layerA.id), seam.automaticReached.getCompleted().selectionTarget)
        editor.selectSelectionLayer(layerB.id)
        seam.automaticRelease.complete(Unit)
        awaitEvent { editor.memoryRecoveryOwnerPhaseForTest() == null }

        assertEquals(layerB.id, editor.uiState.value.activeSelectionLayerId)
        assertEquals(2, editor.uiState.value.selectionLayers.size)
        assertFalse(seam.strongReached.isCompleted)
    }

    @Test
    fun reopenSameSourceAndTokenDoesNotMatchOldNavigationAttempt() {
        val previous = navigationDescriptor("generation-A", "target-A")
        val current = navigationDescriptor("generation-B", "target-B")
            .copy(sourcePath = previous.sourcePath, baseContentToken = previous.baseContentToken)
        assertFalse(memoryRetryAttemptMatchesFailureForTest(previous, current))
    }

    @Test
    fun navigationRetryIdentityRequiresExactTarget() {
        val previous = navigationDescriptor("generation-A", "target-A")
        val sameTarget = previous.copy(token = 2L, requiredBytes = 99L)
        val otherTarget = previous.copy(token = 3L, targetEntryId = "target-B")
        assertTrue(memoryRetryAttemptMatchesFailureForTest(previous, sameTarget))
        assertFalse(memoryRetryAttemptMatchesFailureForTest(previous, otherTarget))
    }

    @Test
    fun navigationRetryIdentityRequiresExactCoordinatorGeneration() {
        val previous = navigationDescriptor("generation-A", "target-A")
        val otherGeneration = previous.copy(token = 2L, coordinatorGeneration = "generation-B")
        assertFalse(memoryRetryAttemptMatchesFailureForTest(previous, otherGeneration))
    }

    @Test
    fun selectionRetryIdentityRequiresExactCapturedLayer() {
        val previous = selectionDescriptor("layer-A")
        val sameTarget = previous.copy(token = 2L, requiredBytes = 99L)
        val otherTarget = previous.copy(
            token = 3L,
            selectionTarget = SelectionRetryTarget.Layer("layer-B"),
        )
        assertTrue(memoryRetryAttemptMatchesFailureForTest(previous, sameTarget))
        assertFalse(memoryRetryAttemptMatchesFailureForTest(previous, otherTarget))
    }

    @Test
    fun strongRetryStaleValidationStopsBeforeCleanup() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1501L)
        val firstSeam = MemoryRecoveryTestSeam()
        val firstHandle = harness.ownSeam(MemoryRecoveryTestSeam.install(firstSeam))
        editor.requestAllocationRecovery(MemoryRetryAction.CreateBrushSelection, Long.MAX_VALUE)
        awaitEvent { firstSeam.automaticReached.isCompleted }
        firstSeam.automaticRelease.complete(Unit)
        awaitEvent { editor.uiState.value.memoryRecoveryRequest != null }
        val validToken = checkNotNull(editor.memoryRecoveryTokenForTest())
        editor.retryPendingMemoryRecovery(validToken)
        awaitEvent { firstSeam.strongReached.isCompleted }
        assertEquals(validToken, firstSeam.strongReached.getCompleted().token)
        firstSeam.strongRelease.complete(Unit)
        awaitEvent { editor.uiState.value.memoryRecoveryRequest == null }
        firstHandle.close()

        val staleSeam = MemoryRecoveryTestSeam()
        harness.ownSeam(MemoryRecoveryTestSeam.install(staleSeam))
        editor.requestAllocationRecovery(MemoryRetryAction.CreateBrushSelection, Long.MAX_VALUE)
        awaitEvent { staleSeam.automaticReached.isCompleted }
        staleSeam.automaticRelease.complete(Unit)
        awaitEvent { editor.uiState.value.memoryRecoveryRequest != null }
        val staleToken = checkNotNull(editor.memoryRecoveryTokenForTest())
        val cleanupBeforeStaleRetry = staleSeam.cleanupStarted
        editor.updateUiState { it.copy(revision = it.revision + 1) }

        editor.retryPendingMemoryRecovery(staleToken)
        assertFalse(staleSeam.strongReached.isCompleted)
        assertEquals(cleanupBeforeStaleRetry, staleSeam.cleanupStarted)
    }

    @Test
    fun shutdownClosesUserAndSystemRecoveryOwnership() = runBlocking {
        val editor = harness.createEditor()
        openDocument(editor, 0xff102030.toInt(), 1601L)
        val seam = MemoryRecoveryTestSeam()
        harness.ownSeam(MemoryRecoveryTestSeam.install(seam))
        editor.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        awaitEvent { seam.trimReached.isCompleted }
        editor.requestAllocationRecovery(MemoryRetryAction.CreateBrushSelection, Long.MAX_VALUE)
        val token = checkNotNull(editor.memoryRecoveryTokenForTest())
        harness.clearViewModels()

        assertEquals(1, editor.memoryRecoveryOwnerCloseCountForTest(token))
        assertNull(editor.memoryRecoveryTokenForTest())
        assertFalse(editor.trimMemoryCleanupActiveForTest())
    }

    private var nativeReleases: Int = 0

    private fun openDocument(editor: EditorViewModel, color: Int, session: Long) {
        awaitEvent { editor.startupInitCompletion.isCompleted && editor.canEnterEditorActionPure() }
        val seam =
            OpenImageTestSeam(
                sourceTransactionFactory = { app, _ ->
                    IncomingSourceTransaction(
                        app,
                        inputStreamProvider = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                    )
                },
                decode = { bitmap(color) },
                nativeSessionFactory = { session },
                nativeSessionReleaser = { nativeReleases += 1 },
            )
        val handle = harness.ownSeam(OpenImageTestSeam.install(seam))
        editor.openImage(Uri.parse("content://memory-recovery/$session"))
        awaitEvent("open image starts") { editor.openImageJobActiveForTest() }
        awaitEvent("open image settles") {
            !editor.openImageJobActiveForTest() && !editor.uiState.value.isBusy
        }
        handle.close()
    }

    private fun navigationDescriptor(generation: String, target: String) =
        MemoryRetryDescriptor(
            token = 1L,
            action = MemoryRetryAction.HistoryUndo,
            requiredBytes = 1L,
            sourcePath = "/same/source.jpg",
            baseContentToken = "same-token",
            revision = 4,
            payload = "navigation",
            navigationDirection = true,
            targetEntryId = target,
            coordinatorGeneration = generation,
        )

    private fun selectionDescriptor(target: String) =
        MemoryRetryDescriptor(
            token = 1L,
            action = MemoryRetryAction.DuplicateSelection,
            requiredBytes = 1L,
            sourcePath = "/same/source.jpg",
            baseContentToken = "same-token",
            revision = 4,
            payload = null,
            selectionTarget = SelectionRetryTarget.Layer(target),
        )

    private fun bitmap(color: Int): Bitmap =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }

    private fun awaitEvent(predicate: () -> Boolean) = awaitEvent("event", predicate)

    private fun awaitEvent(label: String, predicate: () -> Boolean) {
        repeat(20_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (predicate()) return
            yieldToEditorBackgroundForTest()
        }
        assertTrue(label, predicate())
    }
}
