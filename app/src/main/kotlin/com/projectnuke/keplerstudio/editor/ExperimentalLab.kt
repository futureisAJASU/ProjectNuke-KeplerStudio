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

data class ExperimentalLabSelection(
    val nativeRender: NativeRenderRoute = NativeRenderRoute.V1,
    val flareGuard: FlareGuardRoute = FlareGuardRoute.V1,
    val remaster: RemasterRoute = RemasterRoute.V1,
    val subjectSelection: SubjectSelectionRoute = SubjectSelectionRoute.V1,
)

/**
 * Debug-session-only routing. Drafts deliberately never persist these values, so an old Draft has
 * the same V1 interpretation until a developer explicitly selects an experimental route again.
 */
object ExperimentalLabController {
    private val current = AtomicReference(ExperimentalLabSelection())
    private val mutableState = MutableStateFlow(current.get())

    val state: StateFlow<ExperimentalLabSelection> = mutableState.asStateFlow()

    fun snapshot(): ExperimentalLabSelection = current.get()

    fun updateDebug(transform: (ExperimentalLabSelection) -> ExperimentalLabSelection) {
        check(BuildConfig.DEBUG) { "Experimental Lab is unavailable in release builds" }
        val updated = transform(current.get())
        current.set(updated)
        mutableState.value = updated
    }

    internal fun resetForTest() {
        current.set(ExperimentalLabSelection())
        mutableState.value = current.get()
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
