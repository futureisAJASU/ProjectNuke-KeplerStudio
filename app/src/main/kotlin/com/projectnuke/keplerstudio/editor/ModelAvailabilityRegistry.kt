package com.projectnuke.keplerstudio.editor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ModelFeature {
    FlareGuard,
    Remaster,
    SubjectSelection,
}

data class ModelCapabilityState(
    val assetPresent: Boolean = false,
    val assetValid: Boolean = false,
    val runtimeAvailable: Boolean = false,
    val contractSupported: Boolean = false,
    val sessionReadyOrLoadable: Boolean = false,
    val runnerImplemented: Boolean = false,
    val lastFailure: String? = null,
) {
    val executable: Boolean
        get() =
            assetPresent && assetValid && runtimeAvailable && contractSupported &&
                sessionReadyOrLoadable && runnerImplemented
}

/**
 * Production-observed model capability state. Feature execution publishes the same
 * load/session result used for routing, so Settings never invents availability.
 */
object ModelAvailabilityRegistry {
    private val mutable =
        MutableStateFlow(ModelFeature.entries.associateWith { ModelCapabilityState() })
    val state: StateFlow<Map<ModelFeature, ModelCapabilityState>> = mutable.asStateFlow()

    fun reportLoad(feature: ModelFeature, result: ModelLoadResult<*>) {
        val capability =
            when (result) {
                is ModelLoadResult.Ready ->
                    ModelCapabilityState(true, true, true, true, true, true)
                is ModelLoadResult.AssetMissing ->
                    ModelCapabilityState(runnerImplemented = true, lastFailure = result.detail)
                is ModelLoadResult.AssetInvalid ->
                    ModelCapabilityState(
                        assetPresent = true,
                        runnerImplemented = true,
                        lastFailure = result.detail,
                    )
                is ModelLoadResult.UnsupportedContract ->
                    ModelCapabilityState(
                        assetPresent = true,
                        assetValid = true,
                        runtimeAvailable = true,
                        runnerImplemented = true,
                        lastFailure = result.detail,
                    )
                is ModelLoadResult.RuntimeUnavailable ->
                    ModelCapabilityState(
                        assetPresent = true,
                        assetValid = true,
                        contractSupported = true,
                        runnerImplemented = true,
                        lastFailure = result.detail,
                    )
                is ModelLoadResult.LoadFailed ->
                    ModelCapabilityState(
                        assetPresent = true,
                        assetValid = true,
                        runtimeAvailable = true,
                        contractSupported = true,
                        runnerImplemented = true,
                        lastFailure = result.detail,
                    )
            }
        mutable.value = mutable.value + (feature to capability)
    }

    fun reportEdgeSession(ready: Boolean, failure: String? = null) {
        val state =
            ModelCapabilityState(
                assetPresent = ready,
                assetValid = ready,
                runtimeAvailable = ready,
                contractSupported = ready,
                sessionReadyOrLoadable = ready,
                runnerImplemented = true,
                lastFailure = failure,
            )
        mutable.value =
            mutable.value +
                mapOf(ModelFeature.Remaster to state, ModelFeature.SubjectSelection to state)
    }

    fun routeAvailability(): RouteModelAvailability {
        val current = mutable.value
        return RouteModelAvailability(
            flareGuardModelAvailable = current[ModelFeature.FlareGuard]?.executable == true,
            remasterModelAvailable = current[ModelFeature.Remaster]?.executable == true,
            subjectSelectionModelAvailable =
                current[ModelFeature.SubjectSelection]?.executable == true,
        )
    }
}
