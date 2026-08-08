package com.projectnuke.keplerstudio.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelCapabilityUIStateTest {

    @Test
    fun loadableStateDerivesEnabledAndLoadLabel() {
        val state = ModelCapabilityState(
            phase = ModelCapabilityPhase.Loadable,
            assetPresent = true,
            assetValid = true,
            runtimeAvailable = true,
            contractSupported = true,
            runnerImplemented = true,
        )
        assertTrue(state.canAttemptModelUse)
        assertFalse(state.sessionReady)
        assertEquals("실행 시 로드", state.statusLabel)
    }

    @Test
    fun readyStateDerivesEnabledAndReadyLabel() {
        val state = ModelCapabilityState(
            phase = ModelCapabilityPhase.Ready,
            sessionActive = true,
            assetPresent = true,
            assetValid = true,
            runtimeAvailable = true,
            contractSupported = true,
            runnerImplemented = true,
        )
        assertTrue(state.canAttemptModelUse)
        assertTrue(state.sessionReady)
        assertEquals("사용 가능", state.statusLabel)
    }

    @Test
    fun retryableFailedDerivesEnabledAndRetryableLabel() {
        val state = ModelCapabilityState(
            phase = ModelCapabilityPhase.Failed,
            assetPresent = true,
            assetValid = true,
            runtimeAvailable = true,
            contractSupported = true,
            runnerImplemented = true,
            lastFailure = ModelCapabilityFailure(ModelCapabilityPhase.Failed, "transient"),
        )
        assertTrue(state.canAttemptModelUse)
        assertFalse(state.sessionReady)
        assertEquals("이전 로드 실패 · 다시 시도 가능", state.statusLabel)
    }

    @Test
    fun nonRetryableFailedDerivesDisabledAndFailureLabel() {
        val state = ModelCapabilityState(
            phase = ModelCapabilityPhase.Failed,
            assetPresent = false,
            assetValid = false,
            runtimeAvailable = false,
            contractSupported = false,
            runnerImplemented = false,
            lastFailure = ModelCapabilityFailure(ModelCapabilityPhase.Failed, "fatal"),
        )
        assertFalse(state.canAttemptModelUse)
        assertFalse(state.sessionReady)
        assertEquals("이전 로드 실패", state.statusLabel)
    }

    @Test
    fun loadingStateDerivesDisabled() {
        val state = ModelCapabilityState(
            phase = ModelCapabilityPhase.Loading,
            assetPresent = true,
            assetValid = true,
            runtimeAvailable = true,
            contractSupported = true,
            runnerImplemented = true,
        )
        assertFalse(state.canAttemptModelUse)
        assertFalse(state.sessionReady)
        assertEquals("로드 중", state.statusLabel)
    }

    @Test
    fun assetInvalidStateDerivesDisabled() {
        val state = ModelCapabilityState(
            phase = ModelCapabilityPhase.AssetInvalid,
            assetPresent = true,
            assetValid = false,
        )
        assertFalse(state.canAttemptModelUse)
        assertFalse(state.sessionReady)
        assertEquals("모델 파일 오류", state.statusLabel)
    }
}
