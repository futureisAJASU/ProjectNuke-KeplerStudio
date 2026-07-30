package com.projectnuke.keplerstudio.editor

import android.content.Context
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ModelFeature { FlareGuard, Remaster, SubjectSelection }

enum class ModelCapabilityPhase {
    Unknown,
    Probing,
    AssetMissing,
    AssetInvalid,
    RuntimeUnavailable,
    ContractUnsupported,
    RunnerUnavailable,
    Loadable,
    Loading,
    Ready,
    Failed,
    Unloaded,
}

enum class ModelCapabilityPublisher { Probe, Loader, Session, Inference, Test }

data class ModelCapabilityFailure(
    val phase: ModelCapabilityPhase,
    val detail: String?,
)

data class ModelCapabilityState(
    val phase: ModelCapabilityPhase = ModelCapabilityPhase.Unknown,
    val assetPresent: Boolean? = null,
    val assetValid: Boolean? = null,
    val runtimeAvailable: Boolean? = null,
    val contractSupported: Boolean? = null,
    val runnerImplemented: Boolean? = null,
    val lastFailure: ModelCapabilityFailure? = null,
    val probeGeneration: Long = 0L,
    val loadGeneration: Long = 0L,
    val sessionGeneration: Long = 0L,
    /** True only while a concrete runner/session for this feature is alive. */
    val sessionActive: Boolean = false,
    val observationSequence: Long = 0L,
    val publisher: ModelCapabilityPublisher? = null,
) {
    val factsLoadable: Boolean
        get() =
            assetPresent == true && assetValid == true && runtimeAvailable == true &&
                contractSupported == true && runnerImplemented == true

    val executable: Boolean
        get() =
            phase == ModelCapabilityPhase.Loadable ||
                phase == ModelCapabilityPhase.Ready ||
                (phase == ModelCapabilityPhase.Unloaded && factsLoadable)

    val statusLabel: String
        get() =
            when (phase) {
                ModelCapabilityPhase.Unknown -> "확인 전"
                ModelCapabilityPhase.Probing -> "확인 중"
                ModelCapabilityPhase.AssetMissing -> "모델 파일 없음"
                ModelCapabilityPhase.AssetInvalid -> "모델 파일 오류"
                ModelCapabilityPhase.RuntimeUnavailable -> "런타임 없음"
                ModelCapabilityPhase.ContractUnsupported -> "모델 계약 오류"
                ModelCapabilityPhase.RunnerUnavailable -> "실행기 없음"
                ModelCapabilityPhase.Loadable -> "실행 시 로드"
                ModelCapabilityPhase.Loading -> "로드 중"
                ModelCapabilityPhase.Ready -> "사용 가능"
                ModelCapabilityPhase.Failed -> "이전 로드 실패"
                ModelCapabilityPhase.Unloaded -> "실행 시 다시 로드"
            }
}

data class ModelCapabilitySnapshot(
    val capabilities: Map<ModelFeature, ModelCapabilityState>,
) {
    fun routeAvailability(): RouteModelAvailability =
        RouteModelAvailability(
            flareGuardModelAvailable = capabilities[ModelFeature.FlareGuard]?.executable == true,
            remasterModelAvailable = capabilities[ModelFeature.Remaster]?.executable == true,
            subjectSelectionModelAvailable =
                capabilities[ModelFeature.SubjectSelection]?.executable == true,
        )
}

internal data class ModelCapabilityObservation(
    val publisher: ModelCapabilityPublisher,
    val generation: Long,
    val phase: ModelCapabilityPhase,
    val assetPresent: Boolean? = null,
    val assetValid: Boolean? = null,
    val runtimeAvailable: Boolean? = null,
    val contractSupported: Boolean? = null,
    val runnerImplemented: Boolean? = null,
    val failure: ModelCapabilityFailure? = null,
)

internal fun reduceModelCapability(
    current: ModelCapabilityState,
    observation: ModelCapabilityObservation,
    sequence: Long,
): ModelCapabilityState {
    val generationIsStale =
        when (observation.publisher) {
            ModelCapabilityPublisher.Probe ->
                observation.generation < current.probeGeneration
            ModelCapabilityPublisher.Loader ->
                observation.generation < current.loadGeneration
            ModelCapabilityPublisher.Session,
            ModelCapabilityPublisher.Inference ->
                observation.generation < current.sessionGeneration
            ModelCapabilityPublisher.Test -> false
        }
    if (generationIsStale) return current

    fun Boolean?.merge(old: Boolean?): Boolean? = this ?: old
    val nonSessionObservationWhileActive =
        current.sessionActive &&
            observation.publisher in
                setOf(ModelCapabilityPublisher.Probe, ModelCapabilityPublisher.Loader)
    val nextSessionActive =
        when {
            observation.publisher == ModelCapabilityPublisher.Session &&
                observation.phase == ModelCapabilityPhase.Ready -> true
            observation.publisher == ModelCapabilityPublisher.Session &&
                observation.phase in
                    setOf(ModelCapabilityPhase.Unloaded, ModelCapabilityPhase.Failed) -> false
            else -> current.sessionActive
        }
    val merged =
        current.copy(
            assetPresent = observation.assetPresent.merge(current.assetPresent),
            assetValid = observation.assetValid.merge(current.assetValid),
            runtimeAvailable = observation.runtimeAvailable.merge(current.runtimeAvailable),
            contractSupported =
                observation.contractSupported.merge(current.contractSupported),
            runnerImplemented =
                observation.runnerImplemented.merge(current.runnerImplemented),
            // A late probe/load result may refine durable facts, but it must not attach a
            // stale failure to a runner that is already alive and executing successfully.
            lastFailure =
                if (nonSessionObservationWhileActive) current.lastFailure
                else observation.failure ?: current.lastFailure,
            probeGeneration =
                if (observation.publisher == ModelCapabilityPublisher.Probe) {
                    observation.generation
                } else {
                    current.probeGeneration
                },
            loadGeneration =
                if (observation.publisher == ModelCapabilityPublisher.Loader) {
                    observation.generation
                } else {
                    current.loadGeneration
                },
            sessionGeneration =
                if (
                    observation.publisher == ModelCapabilityPublisher.Session ||
                        observation.publisher == ModelCapabilityPublisher.Inference
                ) {
                    observation.generation
                } else {
                    current.sessionGeneration
                },
            sessionActive = nextSessionActive,
            observationSequence = sequence,
            publisher = observation.publisher,
        )

    // Probe and loader generations live in independent domains. A load/probe that
    // started before Session Ready can therefore finish later with a numerically
    // larger generation. Session ownership, not cross-domain generation ordering,
    // is the authoritative guard: only the current Session publisher may close or
    // fail a live runner.
    if (nonSessionObservationWhileActive) {
        return merged.copy(phase = current.phase)
    }

    val settledPhase =
        when {
            observation.publisher == ModelCapabilityPublisher.Session &&
                observation.phase == ModelCapabilityPhase.Unloaded ->
                if (merged.factsLoadable) ModelCapabilityPhase.Loadable
                else if (
                    current.phase in
                        setOf(
                            ModelCapabilityPhase.AssetMissing,
                            ModelCapabilityPhase.AssetInvalid,
                            ModelCapabilityPhase.RuntimeUnavailable,
                            ModelCapabilityPhase.ContractUnsupported,
                            ModelCapabilityPhase.RunnerUnavailable,
                        )
                ) {
                    current.phase
                } else {
                    ModelCapabilityPhase.Unloaded
                }
            observation.phase == ModelCapabilityPhase.Failed &&
                merged.factsLoadable -> ModelCapabilityPhase.Failed
            else -> observation.phase
        }
    return merged.copy(
        phase = settledPhase,
        lastFailure =
            if (
                settledPhase == ModelCapabilityPhase.Ready ||
                    settledPhase == ModelCapabilityPhase.Loadable
            ) {
                null
            } else {
                merged.lastFailure
            },
    )
}

object ModelAvailabilityRegistry {
    private val sequence = AtomicLong()
    private val probeGeneration = AtomicLong()
    private val loadGeneration = AtomicLong()
    private val sessionGeneration = AtomicLong()

    private fun emptyState() =
        ModelFeature.entries.associateWith { ModelCapabilityState() }

    private val mutable = MutableStateFlow(emptyState())
    val state: StateFlow<Map<ModelFeature, ModelCapabilityState>> = mutable.asStateFlow()

    fun snapshot(): ModelCapabilitySnapshot = ModelCapabilitySnapshot(mutable.value)
    fun routeAvailability(): RouteModelAvailability = snapshot().routeAvailability()

    fun beginProbe(): Long = probeGeneration.incrementAndGet().also { generation ->
        applyToFeatures(
            ModelFeature.entries,
            ModelCapabilityObservation(
                ModelCapabilityPublisher.Probe,
                generation,
                ModelCapabilityPhase.Probing,
            ),
        )
    }

    fun probePackagedCapabilities(context: Context, generation: Long = beginProbe()) {
        apply(
            ModelFeature.FlareGuard,
            probeAsset(
                context,
                generation,
                "flare_masker",
                "org.tensorflow.lite.InterpreterApi",
            ),
        )
        val edge =
            probeAsset(
                context,
                generation,
                "edge_masker",
                "com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter",
            )
        applyToFeatures(listOf(ModelFeature.Remaster, ModelFeature.SubjectSelection), edge)
    }

    fun reportLoading(feature: ModelFeature): Long =
        loadGeneration.incrementAndGet().also { generation ->
            apply(
                feature,
                ModelCapabilityObservation(
                    ModelCapabilityPublisher.Loader,
                    generation,
                    ModelCapabilityPhase.Loading,
                ),
            )
        }

    fun reportEdgeLoading(): Long =
        loadGeneration.incrementAndGet().also { generation ->
            applyToFeatures(
                listOf(ModelFeature.Remaster, ModelFeature.SubjectSelection),
                ModelCapabilityObservation(
                    ModelCapabilityPublisher.Loader,
                    generation,
                    ModelCapabilityPhase.Loading,
                ),
            )
        }

    fun reportLoad(
        feature: ModelFeature,
        result: ModelLoadResult<*>,
        generation: Long = loadGeneration.incrementAndGet(),
    ) {
        apply(feature, observationFromLoad(result, generation))
    }

    fun reportEdgeLoad(
        result: ModelLoadResult<*>,
        generation: Long = loadGeneration.incrementAndGet(),
    ) {
        applyToFeatures(
            listOf(ModelFeature.Remaster, ModelFeature.SubjectSelection),
            observationFromLoad(result, generation),
        )
    }

    fun reportSessionReady(features: Collection<ModelFeature>): Long =
        sessionGeneration.incrementAndGet().also { generation ->
            applyToFeatures(
                features,
                ModelCapabilityObservation(
                    ModelCapabilityPublisher.Session,
                    generation,
                    ModelCapabilityPhase.Ready,
                    assetPresent = true,
                    assetValid = true,
                    runtimeAvailable = true,
                    contractSupported = true,
                    runnerImplemented = true,
                ),
            )
        }

    fun reportSessionClosed(features: Collection<ModelFeature>, generation: Long) {
        applyToFeatures(
            features,
            ModelCapabilityObservation(
                ModelCapabilityPublisher.Session,
                generation,
                ModelCapabilityPhase.Unloaded,
            ),
        )
    }

    fun reportEdgeUnloaded() {
        val generation = sessionGeneration.incrementAndGet()
        applyToFeatures(
            listOf(ModelFeature.Remaster, ModelFeature.SubjectSelection),
            ModelCapabilityObservation(
                ModelCapabilityPublisher.Session,
                generation,
                ModelCapabilityPhase.Unloaded,
            ),
        )
    }

    internal fun applyForTest(feature: ModelFeature, observation: ModelCapabilityObservation) {
        apply(feature, observation)
    }

    internal fun resetForTest() {
        sequence.set(0L)
        probeGeneration.set(0L)
        loadGeneration.set(0L)
        sessionGeneration.set(0L)
        mutable.value = emptyState()
    }

    private fun apply(feature: ModelFeature, observation: ModelCapabilityObservation) {
        val nextSequence = sequence.incrementAndGet()
        mutable.update { current ->
            current +
                (feature to
                    reduceModelCapability(
                        current.getValue(feature),
                        observation,
                        nextSequence,
                    ))
        }
    }

    private fun applyToFeatures(
        features: Collection<ModelFeature>,
        observation: ModelCapabilityObservation,
    ) {
        val nextSequence = sequence.incrementAndGet()
        mutable.update { current ->
            current.mapValues { (feature, state) ->
                if (feature in features) {
                    reduceModelCapability(state, observation, nextSequence)
                } else {
                    state
                }
            }
        }
    }

    private fun observationFromLoad(
        result: ModelLoadResult<*>,
        generation: Long,
    ): ModelCapabilityObservation =
        when (result) {
            is ModelLoadResult.Ready ->
                ModelCapabilityObservation(
                    ModelCapabilityPublisher.Loader,
                    generation,
                    ModelCapabilityPhase.Loadable,
                    true,
                    true,
                    true,
                    true,
                    true,
                )
            is ModelLoadResult.AssetMissing ->
                failedObservation(
                    generation,
                    ModelCapabilityPhase.AssetMissing,
                    result.detail,
                    assetPresent = false,
                )
            is ModelLoadResult.AssetInvalid ->
                failedObservation(
                    generation,
                    ModelCapabilityPhase.AssetInvalid,
                    result.detail,
                    assetPresent = true,
                    assetValid = false,
                )
            is ModelLoadResult.UnsupportedContract ->
                failedObservation(
                    generation,
                    ModelCapabilityPhase.ContractUnsupported,
                    result.detail,
                    true,
                    true,
                    true,
                    runnerImplemented = true,
                )
            is ModelLoadResult.RuntimeUnavailable ->
                failedObservation(
                    generation,
                    ModelCapabilityPhase.RuntimeUnavailable,
                    result.detail,
                    true,
                    true,
                    contractSupported = true,
                    runnerImplemented = true,
                )
            is ModelLoadResult.LoadFailed ->
                failedObservation(
                    generation,
                    ModelCapabilityPhase.Failed,
                    result.detail,
                    runnerImplemented = true,
                )
        }

    private fun failedObservation(
        generation: Long,
        phase: ModelCapabilityPhase,
        detail: String?,
        assetPresent: Boolean? = null,
        assetValid: Boolean? = null,
        runtimeAvailable: Boolean? = null,
        contractSupported: Boolean? = null,
        runnerImplemented: Boolean? = null,
    ) = ModelCapabilityObservation(
        ModelCapabilityPublisher.Loader,
        generation,
        phase,
        assetPresent,
        assetValid,
        runtimeAvailable,
        contractSupported,
        runnerImplemented,
        ModelCapabilityFailure(phase, detail),
    )

    private fun probeAsset(
        context: Context,
        generation: Long,
        manifestId: String,
        runtimeClass: String,
    ): ModelCapabilityObservation {
        val manifest =
            ModelAssetManifest.byId(manifestId)
                ?: return probeFailure(
                    generation,
                    ModelCapabilityPhase.AssetMissing,
                    "manifest entry missing",
                    assetPresent = false,
                    runnerImplemented = false,
                )
        if (manifest.asset.requiredContractSchemaVersion !=
            ModelAssetManifest.CONTRACT_SCHEMA_VERSION
        ) {
            return probeFailure(
                generation,
                ModelCapabilityPhase.ContractUnsupported,
                "contract schema is unsupported",
                contractSupported = false,
            )
        }
        when (
            val validation =
                ModelAssetValidator.validate(manifest) { path ->
                    runCatching { context.assets.open(path) }.getOrNull()
                }
        ) {
            ModelAssetValidation.Missing ->
                return probeFailure(
                    generation,
                    ModelCapabilityPhase.AssetMissing,
                    "packaged asset missing",
                    assetPresent = false,
                )
            is ModelAssetValidation.Invalid ->
                return probeFailure(
                    generation,
                    ModelCapabilityPhase.AssetInvalid,
                    validation.detail,
                    assetPresent = true,
                    assetValid = false,
                )
            is ModelAssetValidation.Valid -> Unit
        }
        if (runCatching { Class.forName(runtimeClass, false, context.classLoader) }.isFailure) {
            return probeFailure(
                generation,
                ModelCapabilityPhase.RuntimeUnavailable,
                "required runtime is unavailable",
                true,
                true,
                contractSupported = true,
                runnerImplemented = true,
            )
        }
        return ModelCapabilityObservation(
            ModelCapabilityPublisher.Probe,
            generation,
            ModelCapabilityPhase.Loadable,
            true,
            true,
            true,
            true,
            true,
        )
    }

    private fun probeFailure(
        generation: Long,
        phase: ModelCapabilityPhase,
        detail: String,
        assetPresent: Boolean? = null,
        assetValid: Boolean? = null,
        runtimeAvailable: Boolean? = null,
        contractSupported: Boolean? = null,
        runnerImplemented: Boolean? = true,
    ) = ModelCapabilityObservation(
        ModelCapabilityPublisher.Probe,
        generation,
        phase,
        assetPresent,
        assetValid,
        runtimeAvailable,
        contractSupported,
        runnerImplemented,
        ModelCapabilityFailure(phase, detail),
    )
}
