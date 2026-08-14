package com.projectnuke.keplerstudio.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.projectnuke.keplerstudio.editor.GlobalModelDiagnostics
import com.projectnuke.keplerstudio.editor.BitmapAllocationRejectedException
import com.projectnuke.keplerstudio.editor.DeterministicModelFallback
import com.projectnuke.keplerstudio.editor.MemoryTrackerScope
import com.projectnuke.keplerstudio.editor.ModelAlphaHandling
import com.projectnuke.keplerstudio.editor.ModelAssetManifest
import com.projectnuke.keplerstudio.editor.ModelChannelOrder
import com.projectnuke.keplerstudio.editor.ModelColorSpace
import com.projectnuke.keplerstudio.editor.ModelConfidence
import com.projectnuke.keplerstudio.editor.ModelFailure
import com.projectnuke.keplerstudio.editor.ModelFailureReason
import com.projectnuke.keplerstudio.editor.ModelAvailabilityRegistry
import com.projectnuke.keplerstudio.editor.ModelCapabilityPhase
import com.projectnuke.keplerstudio.editor.ModelFeature
import com.projectnuke.keplerstudio.editor.ModelInputContract
import com.projectnuke.keplerstudio.editor.ModelLoadResult
import com.projectnuke.keplerstudio.editor.ModelOperationContext
import com.projectnuke.keplerstudio.editor.ModelOutputContract
import com.projectnuke.keplerstudio.editor.ModelOutputSemantic
import com.projectnuke.keplerstudio.editor.ModelResizePolicy
import com.projectnuke.keplerstudio.editor.ModelRunResult
import com.projectnuke.keplerstudio.editor.ModelRunnerContract
import com.projectnuke.keplerstudio.editor.ModelRunnerDescriptor
import com.projectnuke.keplerstudio.editor.ModelRunnerLifecycle
import com.projectnuke.keplerstudio.editor.ModelTensorDataType
import com.projectnuke.keplerstudio.editor.ModelTensorLayout
import com.projectnuke.keplerstudio.editor.TrackedMask
import com.projectnuke.keplerstudio.editor.copyOrThrow
import com.projectnuke.keplerstudio.editor.createBitmapOrThrow
import com.projectnuke.keplerstudio.editor.createScaledBitmapOrThrow
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object RemasterModelSession : ModelRunnerContract {
    private val EDGE_FEATURES = listOf(ModelFeature.Remaster, ModelFeature.SubjectSelection)
    private val sessionStateLock = Any()

    private enum class SessionCommandKind { Load, EnsureLoaded, Unload, IdleUnload }

    private class CommandStartTestSeam(
        private val onOwnershipClaimed: (() -> Unit)?,
        private val onInitialTransitionPublished: (() -> Unit)?,
        private val onClose: (() -> Unit)?,
    ) {
        @Volatile private var active = true

        fun ownershipClaimed() {
            if (active) onOwnershipClaimed?.invoke()
        }

        fun initialTransitionPublished() {
            if (active) onInitialTransitionPublished?.invoke()
        }

        fun deactivate() {
            if (active) {
                active = false
                runCatching { onClose?.invoke() }
            }
        }
    }

    private class SessionCommand(
        val generation: Long,
        val kind: SessionCommandKind,
    ) {
        var job: Job? = null
    }

    /** Physical ownership remains until the runner has drained and is closed. */
    private class InstalledSessionOwner(
        val commandGeneration: Long,
        val registrySessionGeneration: Long,
        val validationIdentity: ModelSessionValidationIdentity,
        val candidate: RemasterModelCandidate,
        val runner: AutoCloseable,
    ) {
        var registryClosed: Boolean = false
        var physicallyClosed: Boolean = false
    }

    internal enum class InferenceStage {
        Accepted,
        AfterInferenceStatePublication,
        BeforeNativeInference,
        AfterNativeInference,
        BeforeResultPublication,
        Finalizer,
    }

    private class InferenceTestSeam(
        private val onStage: (suspend (InferenceStage) -> Unit)?,
        private val onClose: (() -> Unit)?,
        private val syntheticNativeOutput:
            ((Bitmap, MemoryTrackerScope?, ModelOperationContext, String, String) -> TrackedMask?)?,
    ) {
        @Volatile private var active = true

        suspend fun atStage(stage: InferenceStage) {
            if (active) onStage?.invoke(stage)
        }

        fun createSyntheticNativeOutput(
            bitmap: Bitmap,
            diagnostics: MemoryTrackerScope?,
            operation: ModelOperationContext,
            modelId: String,
            modelVersion: String,
        ): TrackedMask? =
            if (active) syntheticNativeOutput?.invoke(bitmap, diagnostics, operation, modelId, modelVersion)
            else null

        fun deactivate() {
            if (active) {
                active = false
                runCatching { onClose?.invoke() }
            }
        }
    }

    private class EnsureReusableTestSeam(
        private val onBeforeValidationRecheck: (suspend () -> Unit)?,
        private val onClose: (() -> Unit)?,
    ) {
        @Volatile private var active = true

        suspend fun beforeValidationRecheck() {
            if (active) onBeforeValidationRecheck?.invoke()
        }

        fun deactivate() {
            if (active) {
                active = false
                runCatching { onClose?.invoke() }
            }
        }
    }

    private val inferenceTestOwnerLock = Any()
    private var installedInferenceTestSeam: InferenceTestSeam? = null
    private val installedInferenceTestSeamCount = AtomicLong()
    private val ensureReusableTestOwnerLock = Any()
    private var installedEnsureReusableTestSeam: EnsureReusableTestSeam? = null
    private val installedEnsureReusableTestSeamCount = AtomicLong()
    private val commandStartTestOwnerLock = Any()
    private var installedCommandStartTestSeam: CommandStartTestSeam? = null
    private val installedCommandStartTestSeamCount = AtomicLong()

    private var currentCommand: SessionCommand? = null
    private var installedSessionOwner: InstalledSessionOwner? = null

    var activeModel by mutableStateOf<RemasterModelCandidate?>(null)
        private set

    var statusText by mutableStateOf("로드된 모델이 없습니다.")
        private set

    var isModelLoaded by mutableStateOf(false)
        private set

    private var closeableModel: AutoCloseable? = null
    private var sessionValidationIdentity: ModelSessionValidationIdentity? = null
    private var installedCommandGeneration: Long = 0L
    internal enum class PublicationStage { RunnerCreated, LoaderReady, SessionReady, FieldsInstalled }
    private class ModelTestSeam(
        val generation: Long,
        private val factory: ((Context, String) -> AutoCloseable?)?,
        private val postCreate: (() -> Unit)?,
        private val postReady: (() -> Unit)?,
        private val onStage: (suspend (PublicationStage) -> Unit)? = null,
        private val onClose: (() -> Unit)? = null,
        private val beforeCreate: (suspend () -> Unit)? = null,
    ) {
        @Volatile private var active = true

        fun create(context: Context, assetPath: String): AutoCloseable? {
            check(active) { "model test owner $generation closed before runner creation" }
            return factory?.invoke(context, assetPath)
        }

        fun afterCreate() { if (active) postCreate?.invoke() }
        fun afterReady() { if (active) postReady?.invoke() }
        suspend fun atStage(stage: PublicationStage) { if (active) onStage?.invoke(stage) }
        suspend fun beforeRunnerCreate() { if (active) beforeCreate?.invoke() }
        fun deactivate() {
            if (active) {
                active = false
                runCatching { onClose?.invoke() }
            }
        }
    }
    private val modelTestOwnerLock = Any()
    @Volatile private var installedModelTestOwner: ModelTestSeam? = null
    private val modelTestOwnerGeneration = AtomicLong()
    private val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val modelMutex = Mutex()
    private val commandGeneration = AtomicLong()
    private var registrySessionGeneration: Long = 0L
    var isModelLoading by mutableStateOf(false)
        private set

    var isInferring by mutableStateOf(false)
        private set

    override var lifecycle by mutableStateOf(ModelRunnerLifecycle.Unloaded)
        private set

    override val descriptor: ModelRunnerDescriptor?
        get() =
            activeModel?.let { candidate ->
                val manifest = ModelAssetManifest.byId(candidate.id) ?: return@let null
                ModelRunnerDescriptor(
                    modelId = candidate.id,
                    asset = manifest.asset,
                    input =
                        ModelInputContract(
                            width = null,
                            height = null,
                            batch = 1,
                            channels = 3,
                            layout = ModelTensorLayout.NHWC,
                            dataType = ModelTensorDataType.Float32,
                            quantization = null,
                            channelOrder = ModelChannelOrder.RGB,
                            colorSpace = ModelColorSpace.SRGB,
                            normalization = "runtime model contract",
                            acceptedBitmapConfigs = setOf(Bitmap.Config.ARGB_8888),
                            resizePolicy = ModelResizePolicy.RuntimeDefined,
                            alphaHandling = ModelAlphaHandling.Ignore,
                        ),
                    output =
                        ModelOutputContract(
                            width = null,
                            height = null,
                            channelsOrClasses = 1,
                            layout = ModelTensorLayout.HW,
                            dataType = ModelTensorDataType.Float32,
                            quantization = null,
                            semantic = manifest.outputSemantic,
                            valueRange = 0f..1f,
                            confidenceMeaning =
                                if (manifest.outputSemantic ==
                                    ModelOutputSemantic.ForegroundCategoryMask
                                ) {
                                    "MediaPipe category mask"
                                } else {
                                    null
                                },
                        ),
                    inferenceAdapterImplemented = manifest.inferenceAdapterImplemented,
                    productionReady = manifest.productionReady,
                    knownMemoryBytes = null,
                    hasUnknownRuntimeMemory = true,
                )
            }

    private data class CommandStart(
        val command: SessionCommand,
        val hadSession: Boolean,
    )

    /** Claims a command and publishes its first logical transition as one owner change. */
    private fun beginCommandAndTransition(kind: SessionCommandKind): CommandStart =
        synchronized(sessionStateLock) {
            val command = SessionCommand(commandGeneration.incrementAndGet(), kind)
            currentCommand = command
            val startSeam = synchronized(commandStartTestOwnerLock) { installedCommandStartTestSeam }
            startSeam?.ownershipClaimed()
            val hadSession = installedSessionOwner != null || isModelLoaded || closeableModel != null
            when (kind) {
                SessionCommandKind.Load,
                SessionCommandKind.EnsureLoaded -> {
                    requestLogicalCloseLocked()
                    isModelLoading = true
                    isModelLoaded = false
                    lifecycle = ModelRunnerLifecycle.Loading
                    statusText = "모델을 불러오는 중입니다."
                    GlobalModelDiagnostics.publish("RemasterModelSession", "loading")
                }
                SessionCommandKind.Unload,
                SessionCommandKind.IdleUnload -> {
                    publishLogicalClosingLocked()
                }
            }
            startSeam?.initialTransitionPublished()
            CommandStart(command, hadSession)
        }

    private fun bindCommandJob(command: SessionCommand, job: Job) {
        synchronized(sessionStateLock) { command.job = job }
    }

    private fun clearCommandIfOwned(command: SessionCommand) {
        synchronized(sessionStateLock) {
            if (currentCommand === command) currentCommand = null
            if (command.job?.isCompleted == true) command.job = null
        }
    }

    private fun isCurrentCommand(generation: Long): Boolean =
        synchronized(sessionStateLock) {
            currentCommand?.generation == generation && commandGeneration.get() == generation
        }

    private fun setStatusIfCurrent(generation: Long, text: String) {
        synchronized(sessionStateLock) {
            if (isCurrentCommand(generation)) statusText = text
        }
    }

    private fun captureInferenceTestSeam(): InferenceTestSeam? =
        synchronized(inferenceTestOwnerLock) { installedInferenceTestSeam }

    private fun captureEnsureReusableTestSeam(): EnsureReusableTestSeam? =
        synchronized(ensureReusableTestOwnerLock) { installedEnsureReusableTestSeam }

    private fun isInferenceLogicallyAvailable(): Boolean = synchronized(sessionStateLock) {
        lifecycle == ModelRunnerLifecycle.Loaded &&
            isModelLoaded &&
            !isModelLoading &&
            installedSessionOwner != null
    }

    private fun sessionValidationIsCurrent(owner: InstalledSessionOwner): Boolean {
        val token =
            (ModelAvailabilityRegistry.validatedCapabilityToken(ModelFeature.SubjectSelection)
                as? ModelLoadResult.Ready)?.runner
                ?: return false
        return token.sessionIdentity() == owner.validationIdentity &&
            ModelAvailabilityRegistry.isCurrent(token)
    }

    private fun isCurrentInferenceOwner(owner: InstalledSessionOwner): Boolean =
        synchronized(sessionStateLock) {
            installedSessionOwner === owner &&
                commandGeneration.get() == owner.commandGeneration &&
                lifecycle == ModelRunnerLifecycle.Inferencing &&
                isModelLoaded &&
                !isModelLoading &&
                sessionValidationIdentity == owner.validationIdentity &&
                registrySessionGeneration == owner.registrySessionGeneration
        } && sessionValidationIsCurrent(owner)

    private fun isCurrentInferenceOwnerForAdmission(owner: InstalledSessionOwner): Boolean =
        synchronized(sessionStateLock) {
            installedSessionOwner === owner &&
                commandGeneration.get() == owner.commandGeneration &&
                lifecycle in setOf(ModelRunnerLifecycle.Loaded, ModelRunnerLifecycle.Inferencing) &&
                isModelLoaded &&
                !isModelLoading &&
                sessionValidationIdentity == owner.validationIdentity &&
                registrySessionGeneration == owner.registrySessionGeneration
        } && sessionValidationIsCurrent(owner)

    private fun staleInferenceFailure(
        owner: InstalledSessionOwner,
        knownValidationCurrent: Boolean? = null,
    ): ModelRunResult.Failure {
        val validationCurrent = knownValidationCurrent ?: sessionValidationIsCurrent(owner)
        val reason = synchronized(sessionStateLock) {
            when {
                knownValidationCurrent == false -> ModelFailureReason.StaleGeneration
                installedSessionOwner !== owner ||
                    lifecycle == ModelRunnerLifecycle.Closing ||
                    lifecycle == ModelRunnerLifecycle.Unloaded -> ModelFailureReason.Closed
                !validationCurrent -> ModelFailureReason.StaleGeneration
                commandGeneration.get() != owner.commandGeneration -> ModelFailureReason.StaleGeneration
                else -> ModelFailureReason.CapabilityUnknown
            }
        }
        return ModelRunResult.Failure(
            ModelFailure(reason),
            DeterministicModelFallback.NoResult,
        )
    }

    private fun clearPublicSessionFields() {
        closeableModel = null
        sessionValidationIdentity = null
        registrySessionGeneration = 0L
        installedCommandGeneration = 0L
        activeModel = null
        isModelLoaded = false
    }

    private fun isExactInstalledOwnerLocked(owner: InstalledSessionOwner): Boolean =
        installedSessionOwner === owner &&
            commandGeneration.get() == owner.commandGeneration &&
            installedCommandGeneration == owner.commandGeneration &&
            registrySessionGeneration == owner.registrySessionGeneration &&
            sessionValidationIdentity == owner.validationIdentity

    /**
     * Revokes logical availability and publishes the complete Closing snapshot.
     * The optional owner makes validation-driven cleanup incapable of touching a
     * newer command/session. Physical runner close remains outside this lock.
     */
    private fun publishLogicalClosingLocked(
        owner: InstalledSessionOwner? = null,
        settleInference: Boolean = false,
    ): Boolean {
        if (owner != null && !isExactInstalledOwnerLocked(owner)) return false
        requestLogicalCloseLocked()
        isModelLoading = false
        isModelLoaded = false
        if (settleInference) isInferring = false
        lifecycle = ModelRunnerLifecycle.Closing
        statusText = "\uBAA8\uB378\uC744 \uC885\uB8CC\uD558\uB294 \uC911\uC785\uB2C8\uB2E4."
        GlobalModelDiagnostics.publish("RemasterModelSession", "closing")
        return true
    }

    /** Logical close is published before physical close so new inference cannot enter. */
    private fun requestLogicalCloseLocked() {
        val owner = installedSessionOwner
        if (owner != null) {
            if (!owner.registryClosed) {
                ModelAvailabilityRegistry.reportSessionClosed(EDGE_FEATURES, owner.registrySessionGeneration)
                owner.registryClosed = true
            }
            clearPublicSessionFields()
            return
        }
        // During the narrow Session Ready -> field transfer window the local
        // runner still owns the physical resource, but the registry/public
        // fields may already describe it. Close that logical publication here;
        // the suspended publication finally still closes the local runner.
        if (registrySessionGeneration != 0L) {
            ModelAvailabilityRegistry.reportSessionClosed(EDGE_FEATURES, registrySessionGeneration)
        }
        if (closeableModel != null || activeModel != null || registrySessionGeneration != 0L) {
            clearPublicSessionFields()
        }
    }

    private fun publishUnloadedIfCurrent(generation: Long): Boolean {
        val published = synchronized(sessionStateLock) {
            if (!isCurrentCommand(generation)) {
                false
            } else {
                isModelLoaded = false
                isModelLoading = false
                isInferring = false
                lifecycle = ModelRunnerLifecycle.Unloaded
                clearPublicSessionFields()
                statusText = "\uB85C\uB4DC\uB41C \uBAA8\uB378\uC774 \uC5C6\uC2B5\uB2C8\uB2E4."
                GlobalModelDiagnostics.publish("RemasterModelSession", "unloaded")
                true
            }
        }
        return published
    }

    private fun publishUnloadedIfClosedOwner(owner: InstalledSessionOwner): Boolean =
        synchronized(sessionStateLock) {
            if (installedSessionOwner != null ||
                commandGeneration.get() != owner.commandGeneration ||
                lifecycle != ModelRunnerLifecycle.Closing
            ) {
                false
            } else {
                isModelLoaded = false
                isModelLoading = false
                isInferring = false
                lifecycle = ModelRunnerLifecycle.Unloaded
                clearPublicSessionFields()
                statusText = "\uB85C\uB4DC\uB41C \uBAA8\uB378\uC774 \uC5C6\uC2B5\uB2C8\uB2E4."
                GlobalModelDiagnostics.publish("RemasterModelSession", "unloaded")
                true
            }
        }

    private fun settleStaleInstalledOwner(owner: InstalledSessionOwner): Boolean {
        val ownsClose = synchronized(sessionStateLock) {
            if (!isExactInstalledOwnerLocked(owner) || sessionValidationIsCurrent(owner)) {
                false
            } else {
                publishLogicalClosingLocked(owner)
            }
        }
        if (!ownsClose) return false
        closeInstalledRunnerLocked()
        publishUnloadedIfClosedOwner(owner)
        return true
    }

    fun load(context: Context, candidate: RemasterModelCandidate) {
        val command = beginCommandAndTransition(SessionCommandKind.Load).command
        val generation = command.generation
        val seam = synchronized(modelTestOwnerLock) { installedModelTestOwner }
        val job = modelScope.launch {
            try {
                modelMutex.withLock {
                    if (!isCurrentCommand(generation)) return@withLock
                val result = publishCandidateLocked(
                    context.applicationContext,
                    candidate,
                    ModelFeature.Remaster,
                    generation,
                    seam,
                )
                if (isCurrentCommand(generation)) {
                    setStatusIfCurrent(generation, statusTextFor(candidate, result))
                }
            }
            } finally {
                clearCommandIfOwned(command)
            }
        }
        bindCommandJob(command, job)
    }

    internal suspend fun ensureEdgeLoaded(context: Context): ModelLoadResult<Unit> {
        val reusableTestSeam = captureEnsureReusableTestSeam()
        val reusableOwner = synchronized(sessionStateLock) {
            installedSessionOwner?.takeIf {
                lifecycle in setOf(ModelRunnerLifecycle.Loaded, ModelRunnerLifecycle.Inferencing) &&
                    isModelLoaded &&
                    !isModelLoading
            }
        }
        if (reusableOwner != null && sessionValidationIsCurrent(reusableOwner)) {
            return modelMutex.withLock {
                reusableTestSeam?.beforeValidationRecheck()
                val validationCurrent = sessionValidationIsCurrent(reusableOwner)
                if (!validationCurrent) {
                    settleStaleInstalledOwner(reusableOwner)
                    return@withLock ModelLoadResult.RuntimeUnavailable("model session was superseded")
                }
                val reusable = synchronized(sessionStateLock) {
                    if (installedSessionOwner === reusableOwner &&
                        lifecycle != ModelRunnerLifecycle.Closing &&
                        isModelLoaded &&
                        !isModelLoading &&
                        sessionValidationIdentity == reusableOwner.validationIdentity &&
                        registrySessionGeneration == reusableOwner.registrySessionGeneration &&
                        commandGeneration.get() == reusableOwner.commandGeneration
                    ) {
                        statusText = "${reusableOwner.candidate.title} \uBAA8\uB378\uC744 \uC0AC\uC6A9\uD560 \uC218 \uC788\uC2B5\uB2C8\uB2E4."
                        true
                    } else {
                        false
                    }
                }
                if (reusable) ModelLoadResult.Ready(Unit)
                else ModelLoadResult.RuntimeUnavailable("model session was superseded")
            }
        }
        val command = beginCommandAndTransition(SessionCommandKind.EnsureLoaded).command
        val generation = command.generation
        val seam = synchronized(modelTestOwnerLock) { installedModelTestOwner }
        return try {
            modelMutex.withLock {
            if (!isCurrentCommand(generation)) {
                return@withLock ModelLoadResult.RuntimeUnavailable("model load was superseded")
            }
            val validation = ModelAvailabilityRegistry.validatedCapabilityToken(ModelFeature.SubjectSelection)
            val token = (validation as? ModelLoadResult.Ready)?.runner
                ?: return@withLock validation.asUnitFailure().also {
                    closeInstalledRunnerLocked()
                    synchronized(sessionStateLock) {
                        if (isCurrentCommand(generation)) {
                            isModelLoading = false
                            isModelLoaded = false
                            lifecycle = ModelRunnerLifecycle.Failed
                            GlobalModelDiagnostics.publish("RemasterModelSession", "unloaded")
                            setStatusIfCurrent(generation, "모델을 불러오지 못했습니다.")
                        }
                    }
                }
            if (
                    lifecycle != ModelRunnerLifecycle.Closing &&
                    activeModel?.id == "edge_masker" &&
                    isModelLoaded &&
                    closeableModel != null &&
                    sessionValidationIdentity == token.sessionIdentity()
            ) {
                    setStatusIfCurrent(
                        generation,
                        "${activeModel?.title ?: "Edge Masker"} 모델을 사용할 수 있습니다.",
                    )
                return@withLock ModelLoadResult.Ready(Unit)
            }
            val candidate = OnDeviceRemasterModels.firstOrNull { it.id == "edge_masker" }
                ?: return@withLock ModelLoadResult.RuntimeUnavailable("Edge Masker runner is not registered").also {
                    closeInstalledRunnerLocked()
                    synchronized(sessionStateLock) {
                        if (isCurrentCommand(generation)) {
                            isModelLoading = false
                            isModelLoaded = false
                            lifecycle = ModelRunnerLifecycle.Failed
                            GlobalModelDiagnostics.publish("RemasterModelSession", "unloaded")
                            setStatusIfCurrent(generation, "모델을 불러오지 못했습니다.")
                        }
                    }
                }
            publishCandidateLocked(
                context.applicationContext,
                candidate,
                ModelFeature.SubjectSelection,
                generation,
                seam,
            ).also { result ->
                setStatusIfCurrent(generation, statusTextFor(candidate, result))
            }
            }
        } finally {
            clearCommandIfOwned(command)
        }
    }

    private fun statusTextFor(
        candidate: RemasterModelCandidate,
        result: ModelLoadResult<Unit>,
    ): String =
        when (result) {
            is ModelLoadResult.Ready -> "${candidate.title} 모델을 사용할 수 있습니다."
            else -> "${candidate.title} 모델을 불러오지 못했습니다."
        }

    private suspend fun publishCandidateLocked(
        context: Context,
        candidate: RemasterModelCandidate,
        feature: ModelFeature,
        generation: Long,
        seam: ModelTestSeam?,
    ): ModelLoadResult<Unit> {
        var loadGeneration = 0L
        val localRunner = LocalRunnerOwner()
        var publishedSessionGeneration = 0L
        var fieldsInstalled = false
        var failureResult: ModelLoadResult<Unit>? = null
        try {
            closeInstalledRunnerLocked()
            val validation = ModelAvailabilityRegistry.validatedCapabilityToken(feature)
            val token = (validation as? ModelLoadResult.Ready)?.runner
                ?: return validation.asUnitFailure().also { failureResult = it }
            if (
                !isSupportedModelContract(candidate) ||
                    candidate.id != token.modelId ||
                    candidate.assetPath != token.approvedAssetPath ||
                    ModelAssetManifest.byId(candidate.id)?.asset?.sha256 != token.approvedAssetSha256 ||
                    ModelAssetManifest.byId(candidate.id)?.asset?.packagingVersion != token.packagingVersion
            ) {
                loadGeneration = ModelAvailabilityRegistry.reportEdgeLoading()
                return ModelLoadResult.UnsupportedContract("unsupported Edge Masker contract")
                    .also { failureResult = it }
            }
            ModelAvailabilityRegistry.loaderRejection(feature)?.let { rejection ->
                return rejection.also { failureResult = it }
            }

            loadGeneration = ModelAvailabilityRegistry.reportEdgeLoading()

            seam?.beforeRunnerCreate()
            localRunner.install(createImageSegmenter(context, token.approvedAssetPath, seam))
            seam?.afterCreate()
            seam?.atStage(PublicationStage.RunnerCreated)
            checkPublicationCurrent(generation, token, "runner creation")

            ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit), loadGeneration)
            seam?.atStage(PublicationStage.LoaderReady)
            checkPublicationCurrent(generation, token, "Loader Ready")

            publishedSessionGeneration = ModelAvailabilityRegistry.reportSessionReady(EDGE_FEATURES)
            seam?.afterReady()
            seam?.atStage(PublicationStage.SessionReady)
            checkPublicationCurrent(generation, token, "Session Ready")

            closeInstalledRunnerLocked()
            synchronized(sessionStateLock) {
                check(isCurrentCommand(generation) && ModelAvailabilityRegistry.isCurrent(token)) {
                    "model load was superseded before field installation"
                }
                closeableModel = localRunner.peek()
                activeModel = candidate
                sessionValidationIdentity = token.sessionIdentity()
                registrySessionGeneration = publishedSessionGeneration
                installedCommandGeneration = generation
                fieldsInstalled = true
            }
            seam?.atStage(PublicationStage.FieldsInstalled)
            checkPublicationCurrent(generation, token, "field installation")
            check(closeableModel === localRunner.peek()) { "installed runner identity changed" }

            synchronized(sessionStateLock) {
                check(isCurrentCommand(generation) && ModelAvailabilityRegistry.isCurrent(token)) {
                    "model load was superseded before final installation"
                }
                val installedRunner = localRunner.transfer()
                installedSessionOwner =
                    InstalledSessionOwner(
                        commandGeneration = generation,
                        registrySessionGeneration = publishedSessionGeneration,
                        validationIdentity = token.sessionIdentity(),
                        candidate = candidate,
                        runner = installedRunner,
                    )
                isModelLoaded = true
                isModelLoading = false
                lifecycle = ModelRunnerLifecycle.Loaded
                GlobalModelDiagnostics.publish("RemasterModelSession", "loaded")
                setStatusIfCurrent(generation, "${candidate.title} 모델을 사용할 수 있습니다.")
            }
            return ModelLoadResult.Ready(Unit)
        } catch (cancelled: CancellationException) {
            failureResult = ModelLoadResult.LoadFailed(cancelled.message ?: "model load cancelled")
            throw cancelled
        } catch (failure: Throwable) {
            return ModelLoadResult.LoadFailed(failure.message ?: "Edge Masker load failed")
                .also { failureResult = it }
        } finally {
            val failed = failureResult
            if (failed != null) {
                val superseded = generation != commandGeneration.get()
                val fieldsBelongToThisCommand = synchronized(sessionStateLock) {
                    if (fieldsInstalled && installedCommandGeneration == generation) {
                        clearPublicSessionFields()
                        true
                    } else {
                        false
                    }
                }
                if (fieldsBelongToThisCommand) {
                    if (installedSessionOwner?.commandGeneration == generation) {
                        closeInstalledRunnerLocked()
                    }
                }
                localRunner.close()
                if (publishedSessionGeneration != 0L) {
                    if (installedSessionOwner?.registrySessionGeneration != publishedSessionGeneration) {
                        ModelAvailabilityRegistry.reportSessionClosed(EDGE_FEATURES, publishedSessionGeneration)
                    }
                }
                if (loadGeneration != 0L) {
                    ModelAvailabilityRegistry.reportEdgeLoad(
                        if (superseded) ModelLoadResult.Ready(Unit) else failed,
                        loadGeneration,
                    )
                }
                if (!superseded) {
                    synchronized(sessionStateLock) {
                        if (isCurrentCommand(generation)) {
                            isModelLoaded = installedSessionOwner != null && closeableModel != null
                            isModelLoading = false
                            lifecycle = if (isModelLoaded) ModelRunnerLifecycle.Loaded else ModelRunnerLifecycle.Failed
                            setStatusIfCurrent(
                                generation,
                                if (isModelLoaded) "${activeModel?.title ?: candidate.title} 모델을 사용할 수 있습니다."
                                else statusTextFor(candidate, failed),
                            )
                            GlobalModelDiagnostics.publish(
                                "RemasterModelSession",
                                if (isModelLoaded) "loaded" else "unloaded",
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkPublicationCurrent(
        generation: Long,
        token: com.projectnuke.keplerstudio.editor.ValidatedModelCapabilityToken,
        stage: String,
    ) {
        check(isCurrentCommand(generation) && ModelAvailabilityRegistry.isCurrent(token)) {
            "model load was superseded after $stage"
        }
    }

    private fun closeInstalledRunnerLocked() {
        val runnerToClose: AutoCloseable?
        synchronized(sessionStateLock) {
            val owner = installedSessionOwner
            if (owner != null) {
                installedSessionOwner = null
                if (!owner.registryClosed) {
                    ModelAvailabilityRegistry.reportSessionClosed(EDGE_FEATURES, owner.registrySessionGeneration)
                    owner.registryClosed = true
                }
                clearPublicSessionFields()
                runnerToClose = if (!owner.physicallyClosed) {
                    owner.physicallyClosed = true
                    owner.runner
                } else {
                    null
                }
            } else {
                runnerToClose = closeableModel
                clearPublicSessionFields()
            }
        }
        runCatching { runnerToClose?.close() }
    }

    internal suspend fun createForegroundMask(
        bitmap: Bitmap,
        diagnostics: MemoryTrackerScope? = null,
        operation: ModelOperationContext,
    ): Bitmap? =
        when (val result = createForegroundMaskResult(bitmap, diagnostics, operation)) {
            is ModelRunResult.Success -> result.value.adoptOrNull()
            is ModelRunResult.Failure -> null
        }

    /**
     * Returns the foreground mask as a [TrackedMask] carrying its diagnostic edge,
     * model identity, operation token and document generation. This replaces the
     * raw `(Long) -> Unit` edge callback that allowed a Bitmap and a separately
     * mutable Long to drift; the caller transfers/adopts exactly once.
     */
    internal suspend fun createForegroundMaskResult(
        bitmap: Bitmap,
        diagnostics: MemoryTrackerScope? = null,
        operation: ModelOperationContext,
    ): ModelRunResult<TrackedMask> {
        val seam = captureInferenceTestSeam()
        if (!isInferenceLogicallyAvailable()) {
            return ModelRunResult.Failure(
                ModelFailure(ModelFailureReason.Closed),
                DeterministicModelFallback.NoResult,
            )
        }
        return modelMutex.withLock {
            val owner = synchronized(sessionStateLock) {
                if (lifecycle == ModelRunnerLifecycle.Closing ||
                    lifecycle == ModelRunnerLifecycle.Loading ||
                    lifecycle == ModelRunnerLifecycle.Unloaded
                ) {
                    null
                } else {
                    installedSessionOwner
                }
            } ?: return@withLock ModelRunResult.Failure(
                ModelFailure(ModelFailureReason.Closed),
                DeterministicModelFallback.NoResult,
            )
            if (!isCurrentInferenceOwnerForAdmission(owner)) {
                val validationCurrent = sessionValidationIsCurrent(owner)
                settleStaleInstalledOwner(owner)
                return@withLock staleInferenceFailure(owner, validationCurrent)
            }
            val model = owner.runner
            operation.validateOrThrow()
            val accepted = synchronized(sessionStateLock) {
                if (installedSessionOwner !== owner ||
                    commandGeneration.get() != owner.commandGeneration ||
                    lifecycle != ModelRunnerLifecycle.Loaded ||
                    !isModelLoaded ||
                    isModelLoading ||
                    sessionValidationIdentity != owner.validationIdentity ||
                    registrySessionGeneration != owner.registrySessionGeneration
                ) {
                    false
                } else {
                    isInferring = true
                    lifecycle = ModelRunnerLifecycle.Inferencing
                    GlobalModelDiagnostics.publish("RemasterModelSession", "inferring")
                    true
                }
            }
            if (!accepted) {
                return@withLock staleInferenceFailure(owner)
            }
            var ownedMask: TrackedMask? = null
            try {
                seam?.atStage(InferenceStage.AfterInferenceStatePublication)
                if (!sessionValidationIsCurrent(owner)) {
                    return@withLock staleInferenceFailure(owner, knownValidationCurrent = false)
                }
                seam?.atStage(InferenceStage.Accepted)
                operation.validateOrThrow()
                if (!isCurrentInferenceOwner(owner)) return@withLock staleInferenceFailure(owner)
                seam?.atStage(InferenceStage.BeforeNativeInference)
                if (!isCurrentInferenceOwner(owner)) return@withLock staleInferenceFailure(owner)
                ownedMask = seam?.createSyntheticNativeOutput(
                    bitmap,
                    diagnostics,
                    operation,
                    owner.candidate.id,
                    owner.candidate.semanticVersion,
                ) ?: createForegroundMaskFromSegmenter(
                    model,
                    bitmap,
                    diagnostics,
                    operation,
                    owner.candidate.id,
                    owner.candidate.semanticVersion,
                )
                seam?.atStage(InferenceStage.AfterNativeInference)
                operation.validateOrThrow()
                if (!isCurrentInferenceOwner(owner)) {
                    ownedMask.recycleAndRelease()
                    ownedMask = null
                    return@withLock staleInferenceFailure(owner)
                }
                seam?.atStage(InferenceStage.BeforeResultPublication)
                if (!isCurrentInferenceOwner(owner)) {
                    ownedMask.recycleAndRelease()
                    ownedMask = null
                    return@withLock staleInferenceFailure(owner)
                }
                ModelRunResult.Success(
                    ownedMask,
                    confidence = 1f,
                    ownedMask.confidenceMetrics,
                )
            } catch (cancelled: CancellationException) {
                ownedMask?.recycleAndRelease()
                throw cancelled
            } catch (allocation: BitmapAllocationRejectedException) {
                ownedMask?.recycleAndRelease()
                throw allocation
            } catch (failure: Throwable) {
                ownedMask?.recycleAndRelease()
                ModelRunResult.Failure(
                    ModelFailure(
                        if (failure is com.projectnuke.keplerstudio.editor.StaleModelGenerationException) {
                            ModelFailureReason.StaleGeneration
                        } else {
                            ModelFailureReason.InferenceFailed
                        },
                        failure.message,
                    ),
                    DeterministicModelFallback.NoResult,
                )
            } finally {
                seam?.atStage(InferenceStage.Finalizer)
                val validationCurrent = sessionValidationIsCurrent(owner)
                var validationCloseOwned = false
                synchronized(sessionStateLock) {
                    val stillOwnsPhysicalSession =
                        installedSessionOwner === owner &&
                            commandGeneration.get() == owner.commandGeneration &&
                            lifecycle == ModelRunnerLifecycle.Inferencing
                    val stillOwnsSession =
                        stillOwnsPhysicalSession &&
                            isModelLoaded &&
                            sessionValidationIdentity == owner.validationIdentity &&
                            registrySessionGeneration == owner.registrySessionGeneration &&
                            validationCurrent
                    if (installedSessionOwner === owner && isInferring) {
                        isInferring = false
                    }
                    if (stillOwnsSession) {
                        lifecycle = ModelRunnerLifecycle.Loaded
                        GlobalModelDiagnostics.publish("RemasterModelSession", "loaded")
                    } else if (stillOwnsPhysicalSession && !validationCurrent) {
                        validationCloseOwned = publishLogicalClosingLocked(
                            owner = owner,
                            settleInference = true,
                        )
                    }
                }
                if (validationCloseOwned) {
                    closeInstalledRunnerLocked()
                    publishUnloadedIfClosedOwner(owner)
                }
            }
        }
    }

    fun unload() {
        val command = beginCommandAndTransition(SessionCommandKind.Unload).command
        val generation = command.generation
        val job = modelScope.launch {
            try {
            modelMutex.withLock {
                if (!isCurrentCommand(generation)) return@withLock
                closeInstalledRunnerLocked()
                publishUnloadedIfCurrent(generation)
            }
            } finally {
                clearCommandIfOwned(command)
            }
        }
        bindCommandJob(command, job)
    }

    suspend fun unloadIdleNow(): Boolean {
        val start = beginCommandAndTransition(SessionCommandKind.IdleUnload)
        val command = start.command
        val hadSession = start.hadSession
        return try {
            modelMutex.withLock {
            if (!isCurrentCommand(command.generation)) return@withLock false
            val hadPhysicalOwner = installedSessionOwner != null || closeableModel != null
            closeInstalledRunnerLocked()
            publishUnloadedIfCurrent(command.generation)
            hadSession || hadPhysicalOwner
            }
        } finally {
            clearCommandIfOwned(command)
        }
    }

    private fun isSupportedModelContract(candidate: RemasterModelCandidate): Boolean {
        val manifest = ModelAssetManifest.byId(candidate.id) ?: return false
        return candidate.id == "edge_masker" &&
            manifest.inferenceAdapterImplemented &&
            manifest.outputSemantic == ModelOutputSemantic.ForegroundCategoryMask &&
            candidate.semanticVersion == manifest.asset.semanticModelVersion &&
            manifest.asset.requiredContractSchemaVersion == ModelAssetManifest.CONTRACT_SCHEMA_VERSION
    }

    private fun createImageSegmenter(context: Context, assetPath: String, testSeam: ModelTestSeam? = null): AutoCloseable {
        testSeam?.create(context, assetPath)?.let { return it }
        val baseOptions = BaseOptions.builder().setModelAssetPath(assetPath).build()
        val options =
            ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setOutputCategoryMask(true)
                .setOutputConfidenceMasks(false)
                .build()
        return ImageSegmenter.createFromOptions(context, options)
    }

    private fun createForegroundMaskFromSegmenter(
        segmenter: Any,
        bitmap: Bitmap,
        diagnostics: MemoryTrackerScope?,
        operation: ModelOperationContext,
        modelId: String,
        modelVersion: String,
    ): TrackedMask {
        val imageBuilderClass =
            Class.forName("com.google.mediapipe.framework.image.BitmapImageBuilder")
        val inputCopy =
            bitmap.copyOrThrow(Bitmap.Config.ARGB_8888, false) ?: error("입력 이미지를 복사하지 못했습니다.")
        val inputEdge = diagnostics?.track(inputCopy, "remaster:modelInputCopy") ?: 0L
        var mpImage: Any? = null
        try {
            val imageBuilder =
                imageBuilderClass.getConstructor(Bitmap::class.java).newInstance(inputCopy)
            mpImage = imageBuilderClass.getMethod("build").invoke(imageBuilder)
        } catch (t: Throwable) {
            if (mpImage == null) inputCopy.recycle()
            throw t
        }
        var categoryMaskImage: Any? = null
        var foregroundMask: TrackedMask? = null
        var primaryFailure: Throwable? = null
        try {
            val segmentMethod =
                segmenter.javaClass.methods.firstOrNull { method ->
                    method.name == "segment" &&
                        method.parameterTypes.size == 1 &&
                        method.parameterTypes[0].isAssignableFrom(mpImage!!.javaClass)
                } ?: error("segment 메서드를 찾을 수 없습니다.")
            val result = segmentMethod.invoke(segmenter, mpImage)
            val categoryMaskOptional =
                result.javaClass.methods
                    .firstOrNull { method ->
                        method.name == "categoryMask" && method.parameterTypes.isEmpty()
                    }
                    ?.invoke(result) ?: error("category mask 결과가 없습니다.")
            val isPresent =
                categoryMaskOptional.javaClass.getMethod("isPresent").invoke(categoryMaskOptional)
                    as Boolean
            if (!isPresent) error("category mask가 비어 있습니다.")
            categoryMaskImage =
                categoryMaskOptional.javaClass.getMethod("get").invoke(categoryMaskOptional)
            val rawMask = extractBitmapFromMpImage(categoryMaskImage as Any)
            val rawEdge = diagnostics?.track(rawMask, "remaster:rawCategoryMask") ?: 0L
            try {
                foregroundMask =
                    categoryBitmapToForegroundMask(
                        rawMask,
                        bitmap.width,
                        bitmap.height,
                        diagnostics,
                        operation,
                        modelId,
                        modelVersion,
                        requireNotNull(ModelAssetManifest.byId(modelId)?.foregroundCategoryIds) {
                            "Foreground category mapping is not declared for $modelId"
                        },
                    )
            } finally {
                if (!rawMask.isRecycled) rawMask.recycle()
                diagnostics?.release(rawEdge)
            }
            return checkNotNull(foregroundMask)
        } catch (t: Throwable) {
            primaryFailure = t
            throw t
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun closeAndCollect(action: () -> Unit) {
                try {
                    action()
                } catch (cleanup: Throwable) {
                    cleanupFailures += cleanup
                }
            }
            closeAndCollect { if (categoryMaskImage != null) closeMpImage(categoryMaskImage) }
            closeAndCollect { closeMpImage(mpImage) }
            if (!inputCopy.isRecycled) inputCopy.recycle()
            diagnostics?.release(inputEdge)
            if (cleanupFailures.isNotEmpty()) {
                if (primaryFailure != null) {
                    cleanupFailures.forEach(primaryFailure::addSuppressed)
                    foregroundMask?.recycleAndRelease()
                } else {
                    foregroundMask?.recycleAndRelease()
                    val cleanupFailure = cleanupFailures.first()
                    cleanupFailures.drop(1).forEach(cleanupFailure::addSuppressed)
                    throw cleanupFailure
                }
            } else if (primaryFailure != null) {
                foregroundMask?.recycleAndRelease()
            }
        }
    }

    private fun extractBitmapFromMpImage(maskImage: Any): Bitmap {
        val extractorClass = Class.forName("com.google.mediapipe.framework.image.BitmapExtractor")
        val extractMethod =
            extractorClass.methods.firstOrNull { method ->
                method.name == "extract" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].isAssignableFrom(maskImage.javaClass)
            } ?: error("BitmapExtractor.extract 메서드를 찾을 수 없습니다.")
        return extractMethod.invoke(null, maskImage) as Bitmap
    }

    /**
     * Builds and immediately tracks the final ARGB mask while raw/scaled masks and
     * conversion rows are still resident.
     */
    private fun categoryBitmapToForegroundMask(
        rawMask: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        diagnostics: MemoryTrackerScope?,
        operation: ModelOperationContext,
        modelId: String,
        modelVersion: String,
        foregroundCategoryIds: Set<Int>,
    ): TrackedMask {
        require(foregroundCategoryIds.isNotEmpty())
        var scaledMask: Bitmap? = null
        var out: TrackedMask? = null
        var scaledEdge = 0L
        var rowTransient = 0L
        var succeeded = false
        var primaryFailure: Throwable? = null
        try {
            scaledMask =
                if (rawMask.width == targetWidth && rawMask.height == targetHeight) {
                    rawMask
                } else {
                    createScaledBitmapOrThrow(rawMask, targetWidth, targetHeight, false)
                }
            if (scaledMask !== rawMask)
                scaledEdge = diagnostics?.track(scaledMask!!, "remaster:scaledCategoryMask") ?: 0L
            val finalBitmap = createBitmapOrThrow(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            out =
                TrackedMask.acquire(
                    finalBitmap,
                    diagnostics,
                    "remaster:finalArgbMask",
                    modelId,
                    modelVersion,
                    operation.operationToken,
                    operation.documentGeneration,
                    ModelConfidence(1f, 1f, 1f, 1f, 1f, 0f, finalPolicy = 1f),
                )
            val inRow = IntArray(targetWidth)
            val outRow = IntArray(targetWidth)
            rowTransient =
                diagnostics?.trackTransientBytes(
                    "remaster:maskRows",
                    targetWidth.toLong() * Int.SIZE_BYTES * 2L,
                ) ?: 0L
            for (y in 0 until targetHeight) {
                scaledMask.getPixels(inRow, 0, targetWidth, 0, y, targetWidth, 1)
                for (x in 0 until targetWidth) {
                    val pixel = inRow[x]
                    val alpha = (pixel ushr 24) and 0xff
                    val r = (pixel ushr 16) and 0xff
                    val g = (pixel ushr 8) and 0xff
                    val b = pixel and 0xff
                    val rgbMax = max(r, max(g, b))
                    val category = if (rgbMax > 0) rgbMax else if (alpha in 1..249) alpha else 0
                    val mask = categoryMaskAlpha(category, foregroundCategoryIds)
                    outRow[x] = -0x1000000 or (mask shl 16) or (mask shl 8) or mask
                }
                finalBitmap.setPixels(outRow, 0, targetWidth, 0, y, targetWidth, 1)
            }
            succeeded = true
            return out
        } catch (t: Throwable) {
            primaryFailure = t
            throw t
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun recycleAndCollect(action: () -> Unit) {
                try {
                    action()
                } catch (cleanup: Throwable) {
                    cleanupFailures += cleanup
                }
            }
            recycleAndCollect {
                if (scaledMask != null && scaledMask !== rawMask) scaledMask.recycle()
                diagnostics?.release(scaledEdge)
            }
            recycleAndCollect { diagnostics?.releaseTransient(rowTransient) }
            if (!succeeded) {
                recycleAndCollect { out?.recycleAndRelease() }
            }
            if (cleanupFailures.isNotEmpty()) {
                if (primaryFailure != null) {
                    cleanupFailures.forEach(primaryFailure::addSuppressed)
                    if (!succeeded) out?.recycleAndRelease()
                } else {
                    if (!succeeded) out?.recycleAndRelease()
                    val cleanupFailure = cleanupFailures.first()
                    cleanupFailures.drop(1).forEach(cleanupFailure::addSuppressed)
                    throw cleanupFailure
                }
            } else if (primaryFailure != null) {
                if (!succeeded) out?.recycleAndRelease()
            }
        }
    }

    private fun closeMpImage(image: Any?) {
        when (image) {
            null -> Unit
            is AutoCloseable -> image.close()
            else ->
                image.javaClass.methods
                    .firstOrNull { method ->
                        method.name == "close" && method.parameterTypes.isEmpty()
                    }
                    ?.invoke(image)
        }
    }

    /** One owner-bound model seam, captured before an async command starts. */
    internal fun installTestSeam(
        factory: ((Context, String) -> AutoCloseable?)? = null,
        postCreate: (() -> Unit)? = null,
        postReady: (() -> Unit)? = null,
        onStage: (suspend (PublicationStage) -> Unit)? = null,
        onClose: (() -> Unit)? = null,
        beforeCreate: (suspend () -> Unit)? = null,
    ): AutoCloseable {
        val seam = ModelTestSeam(
            modelTestOwnerGeneration.incrementAndGet(),
            factory,
            postCreate,
            postReady,
            onStage,
            onClose,
            beforeCreate,
        )
        synchronized(modelTestOwnerLock) {
            check(installedModelTestOwner == null) { "model test owner already installed" }
            installedModelTestOwner = seam
        }
        return AutoCloseable {
            seam.deactivate()
            synchronized(modelTestOwnerLock) {
                if (installedModelTestOwner === seam) installedModelTestOwner = null
            }
        }
    }

    internal fun installedTestSeamCount(): Int = synchronized(modelTestOwnerLock) { if (installedModelTestOwner == null) 0 else 1 }

    /** Captures one inference gate at operation creation; later installations cannot retarget it. */
    internal fun installInferenceTestSeam(
        onStage: (suspend (InferenceStage) -> Unit)? = null,
        onClose: (() -> Unit)? = null,
        syntheticNativeOutput:
            ((Bitmap, MemoryTrackerScope?, ModelOperationContext, String, String) -> TrackedMask?)? = null,
    ): AutoCloseable {
        val seam = InferenceTestSeam(onStage, onClose, syntheticNativeOutput)
        synchronized(inferenceTestOwnerLock) {
            check(installedInferenceTestSeam == null) { "inference test owner already installed" }
            installedInferenceTestSeam = seam
            installedInferenceTestSeamCount.incrementAndGet()
        }
        return AutoCloseable {
            seam.deactivate()
            synchronized(inferenceTestOwnerLock) {
                if (installedInferenceTestSeam === seam) {
                    installedInferenceTestSeam = null
                    installedInferenceTestSeamCount.decrementAndGet()
                }
            }
        }
    }

    internal fun installedInferenceTestSeamCount(): Int =
        installedInferenceTestSeamCount.get().toInt()

    internal fun installEnsureReusableTestSeam(
        onBeforeValidationRecheck: (suspend () -> Unit)? = null,
        onClose: (() -> Unit)? = null,
    ): AutoCloseable {
        val seam = EnsureReusableTestSeam(onBeforeValidationRecheck, onClose)
        synchronized(ensureReusableTestOwnerLock) {
            check(installedEnsureReusableTestSeam == null) {
                "ensure reusable test owner already installed"
            }
            installedEnsureReusableTestSeam = seam
            installedEnsureReusableTestSeamCount.incrementAndGet()
        }
        return AutoCloseable {
            seam.deactivate()
            synchronized(ensureReusableTestOwnerLock) {
                if (installedEnsureReusableTestSeam === seam) {
                    installedEnsureReusableTestSeam = null
                    installedEnsureReusableTestSeamCount.decrementAndGet()
                }
            }
        }
    }

    internal fun installedEnsureReusableTestSeamCount(): Int =
        installedEnsureReusableTestSeamCount.get().toInt()

    internal fun installCommandStartTestSeam(
        onOwnershipClaimed: (() -> Unit)? = null,
        onInitialTransitionPublished: (() -> Unit)? = null,
        onClose: (() -> Unit)? = null,
    ): AutoCloseable {
        val seam = CommandStartTestSeam(onOwnershipClaimed, onInitialTransitionPublished, onClose)
        synchronized(commandStartTestOwnerLock) {
            check(installedCommandStartTestSeam == null) {
                "command start test seam already installed"
            }
            installedCommandStartTestSeam = seam
            installedCommandStartTestSeamCount.incrementAndGet()
        }
        return AutoCloseable {
            seam.deactivate()
            synchronized(commandStartTestOwnerLock) {
                if (installedCommandStartTestSeam === seam) {
                    installedCommandStartTestSeam = null
                    installedCommandStartTestSeamCount.decrementAndGet()
                }
            }
        }
    }

    internal fun installedCommandStartTestSeamCount(): Int =
        installedCommandStartTestSeamCount.get().toInt()

    internal fun validationIdentityForTest(): ModelSessionValidationIdentity? = sessionValidationIdentity
    internal fun sessionGenerationForTest(): Long = registrySessionGeneration
    internal fun installedRunnerForTest(): AutoCloseable? = closeableModel
    internal fun canStartInferenceForTest(): Boolean =
        synchronized(sessionStateLock) {
            lifecycle == ModelRunnerLifecycle.Loaded &&
                isModelLoaded &&
                installedSessionOwner != null
        }
}

/** Immutable validation facts bound to a loaded native/model session. */
internal data class ModelSessionValidationIdentity(
    val modelId: String,
    val validationEpoch: Long,
    val approvedAssetPath: String,
    val approvedAssetSha256: String?,
    val packagingVersion: String,
    val semanticVersion: String,
    val contractSchema: Int,
    val runtimeType: com.projectnuke.keplerstudio.editor.ModelRuntimeType,
)

/** Runner remains local until registry/session publication has completed. */
private class LocalRunnerOwner {
    private var runner: AutoCloseable? = null

    fun install(value: AutoCloseable) {
        check(runner == null)
        runner = value
    }

    fun transfer(): AutoCloseable = checkNotNull(runner).also { runner = null }

    fun peek(): AutoCloseable = checkNotNull(runner)

    fun close() {
        val value = runner ?: return
        runner = null
        runCatching { value.close() }
    }
}

internal fun com.projectnuke.keplerstudio.editor.ValidatedModelCapabilityToken.sessionIdentity(): ModelSessionValidationIdentity =
    ModelSessionValidationIdentity(
        modelId = modelId,
        validationEpoch = validationGeneration,
        approvedAssetPath = approvedAssetPath,
        approvedAssetSha256 = approvedAssetSha256,
        packagingVersion = packagingVersion,
        semanticVersion = semanticVersion,
        contractSchema = contractSchema,
        runtimeType = runtimeType,
    )

private fun ModelLoadResult<*>.asUnitFailure(): ModelLoadResult<Unit> =
    when (this) {
        is ModelLoadResult.AssetMissing -> ModelLoadResult.AssetMissing(detail)
        is ModelLoadResult.AssetInvalid -> ModelLoadResult.AssetInvalid(detail)
        is ModelLoadResult.UnsupportedContract -> ModelLoadResult.UnsupportedContract(detail)
        is ModelLoadResult.RuntimeUnavailable -> ModelLoadResult.RuntimeUnavailable(detail)
        is ModelLoadResult.LoadFailed -> ModelLoadResult.LoadFailed(detail)
        is ModelLoadResult.Ready -> error("ready result cannot be converted to a failure")
    }

internal fun categoryMaskAlpha(category: Int, foregroundCategoryIds: Set<Int>): Int {
    require(foregroundCategoryIds.isNotEmpty()) { "Foreground category mapping must be explicit" }
    return if (category in foregroundCategoryIds) 255 else 0
}

private fun modelCapabilityFailureReason(phase: ModelCapabilityPhase?): ModelFailureReason =
    when (phase) {
        ModelCapabilityPhase.AssetMissing -> ModelFailureReason.AssetMissing
        ModelCapabilityPhase.AssetInvalid -> ModelFailureReason.AssetInvalid
        ModelCapabilityPhase.ContractUnsupported -> ModelFailureReason.ContractUnsupported
        ModelCapabilityPhase.RuntimeUnavailable,
        ModelCapabilityPhase.RunnerUnavailable -> ModelFailureReason.RuntimeUnavailable
        ModelCapabilityPhase.Unknown,
        ModelCapabilityPhase.Probing,
        ModelCapabilityPhase.Loading,
        null -> ModelFailureReason.CapabilityUnknown
        ModelCapabilityPhase.Failed -> ModelFailureReason.LoadingFailed
        ModelCapabilityPhase.Loadable,
        ModelCapabilityPhase.Ready,
        ModelCapabilityPhase.Unloaded -> ModelFailureReason.Closed
    }
