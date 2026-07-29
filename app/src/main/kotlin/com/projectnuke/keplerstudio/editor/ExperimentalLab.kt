package com.projectnuke.keplerstudio.editor

import com.projectnuke.keplerstudio.BuildConfig
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NativeRenderRoute { V1, V2, Compare }

enum class FlareGuardRoute { V1, V2Rule, V2ModelAssisted, ForcedV1Fallback, Compare }

enum class RemasterRoute { V1, V2MaskAware, V2ModelAssisted, ForcedV1Fallback, Compare }

enum class SubjectSelectionRoute { V1, V2ManualOrSynthetic, V2ModelAssisted, ForcedV1Fallback, Compare }

/**
 * Resolved route selection for all features. This is the output of route resolution —
 * the actual routes that will be used for rendering. Production callers should obtain
 * this via [RouteResolver], not via direct global state inference.
 *
 * The `nativeRender` field drives [RenderPipelinePlanner]. The feature-specific fields
 * are consumed by Flare, Remaster, and Subject Selection after route resolution.
 */
data class ExperimentalLabSelection(
    val nativeRender: NativeRenderRoute = NativeRenderRoute.V1,
    val flareGuard: FlareGuardRoute = FlareGuardRoute.V1,
    val remaster: RemasterRoute = RemasterRoute.V1,
    val subjectSelection: SubjectSelectionRoute = SubjectSelectionRoute.V1,
)

/**
 * Derive the resolved [ExperimentalLabSelection] purely from a document engine.
 * This is the document engine default — no debug overrides.
 */
internal fun routingForCorrectionEngine(engine: CorrectionEngine): ExperimentalLabSelection =
    when (engine) {
        CorrectionEngine.Engine1 -> ExperimentalLabSelection()
        CorrectionEngine.Engine2 ->
            ExperimentalLabSelection(
                nativeRender = NativeRenderRoute.V2,
                flareGuard = FlareGuardRoute.V2Rule,
                remaster = RemasterRoute.V2MaskAware,
                subjectSelection = SubjectSelectionRoute.V2ManualOrSynthetic,
            )
    }

/**
 * Debug-only experimental lab controller. Stores per-feature debug overrides as nullable
 * values (null = follow document engine). In release builds, all mutations are refused
 * and the controller always returns [DebugFeatureOverrides.None].
 *
 * The state exposed as [overridesState] is a flow of raw [DebugFeatureOverrides] objects —
 * not a global resolved selection mutated by whichever engine last queried it. The UI
 * resolves display state from the current engine + raw overrides + model availability.
 */
object ExperimentalLabController {
    private val overrides = AtomicReference(DebugFeatureOverrides.None)
    private val overridesFlow = MutableStateFlow(DebugFeatureOverrides.None)

    /**
     * Current debug overrides. In release builds this is always [DebugFeatureOverrides.None].
     */
    fun debugOverrides(): DebugFeatureOverrides =
        if (BuildConfig.DEBUG) overrides.get() else DebugFeatureOverrides.None

    /**
     * State flow of raw [DebugFeatureOverrides]. Stable — does not change with the
     * document engine. UI and renderer subscribe to this and resolve display state
     * from the current engine + overrides.
     */
    val overridesState: StateFlow<DebugFeatureOverrides> get() = overridesFlow.asStateFlow()

    /**
     * Resolved selection for the given document engine. Used by the UI to display
     * the current effective state and by tests that need to verify route resolution.
     */
    fun resolvedSelection(engine: CorrectionEngine): ExperimentalLabSelection {
        val o = debugOverrides()
        return RouteResolver.toLegacySelection(engine, o, RouteModelAvailability())
    }

    /**
     * Update debug overrides. Only callable in debug builds. The transform receives
     * the current [DebugFeatureOverrides] and returns the new one.
     */
    fun updateDebugOverrides(transform: (DebugFeatureOverrides) -> DebugFeatureOverrides) {
        check(BuildConfig.DEBUG) { "Experimental Lab is unavailable in release builds" }
        val updated = transform(overrides.get())
        overrides.set(updated)
        overridesFlow.value = updated
    }

    /**
     * Set a single per-feature override. `null` means "Follow Document Engine".
     */
    fun setNativeOverride(route: NativeRenderRoute?) {
        updateDebugOverrides { it.copy(nativeRender = route) }
    }

    fun setFlareGuardOverride(route: FlareGuardRoute?) {
        updateDebugOverrides { it.copy(flareGuard = route) }
    }

    fun setRemasterOverride(route: RemasterRoute?) {
        updateDebugOverrides { it.copy(remaster = route) }
    }

    fun setSubjectSelectionOverride(route: SubjectSelectionRoute?) {
        updateDebugOverrides { it.copy(subjectSelection = route) }
    }

    internal fun resetForTest() {
        overrides.set(DebugFeatureOverrides.None)
        overridesFlow.value = DebugFeatureOverrides.None
    }
}

object ExperimentalComparisonStore {
    private val mutable = MutableStateFlow<DebugComparisonArtifact?>(null)
    val latest: StateFlow<DebugComparisonArtifact?> = mutable.asStateFlow()

    fun publishDebug(artifact: DebugComparisonArtifact) {
        check(BuildConfig.DEBUG)
        mutable.value = artifact
    }

    fun clear() {
        mutable.value = null
    }
}
