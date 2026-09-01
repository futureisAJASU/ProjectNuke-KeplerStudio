package com.projectnuke.keplerstudio.editor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.IBinder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SuperResolutionProductOperationState(
    val operationId: Long? = null,
    val documentIdentity: String? = null,
    val status: SuperResolutionExportStatus = SuperResolutionExportStatus(),
)

internal data class SuperResolutionServiceRequest(
    val operationId: Long,
    val documentGeneration: String,
    val documentIdentity: String,
    val preflight: SuperResolutionPreflight,
    val sourceRequest: FullExportSourceRequest,
)

sealed interface SuperResolutionStartResult {
    data class Started(val operationId: Long) : SuperResolutionStartResult
    data class AlreadyRunning(val operationId: Long) : SuperResolutionStartResult
    data class Failed(val message: String) : SuperResolutionStartResult
}

/** Test-only seam; production delegates directly to Service.startForeground. */
internal object SuperResolutionForegroundPromotionSeam {
    @Volatile var start: ((Service, Int, Notification, Int?) -> Unit)? = null

    internal fun resetForTest() {
        start = null
    }
}

internal object SuperResolutionServiceLaunchSeam {
    @Volatile var beforeJobStart: ((Long) -> Unit)? = null
    @Volatile var afterOwnerBound: ((Long, Job) -> Unit)? = null

    internal fun resetForTest() {
        beforeJobStart = null
        afterOwnerBound = null
    }
}

internal object SuperResolutionServiceExecutionSeam {
    @Volatile var beforePreparation: (suspend (Long) -> Unit)? = null

    internal fun resetForTest() {
        beforePreparation = null
    }
}

/** Test-only seam for exercising real registry admission without starting Android services. */
internal object SuperResolutionServiceStartSeam {
    @Volatile var start: ((Context, Intent) -> Unit)? = null

    internal fun resetForTest() {
        start = null
    }
}

/**
 * Process-level operation identity and observable state. The foreground [Service] owns the
 * authoritative coroutine; this registry only arbitrates one request and lets recreated UI
 * attach to that same owner.
 */
object SuperResolutionOperationRegistry {
    private val lock = Any()
    private val sequence = AtomicLong()
    private val mutableState = MutableStateFlow(SuperResolutionProductOperationState())
    val state: StateFlow<SuperResolutionProductOperationState> = mutableState.asStateFlow()

    private var pending: SuperResolutionServiceRequest? = null
    private var pendingJournal: SuperResolutionOperationJournal? = null
    private var activeOperationId: Long? = null
    private var cancelOwner: (() -> Unit)? = null
    private var settlingOperationId: Long? = null
    private var recoveryActive: Boolean = false

    internal fun nextOperationId(): Long = sequence.incrementAndGet()

    internal fun submit(
        context: Context,
        request: SuperResolutionServiceRequest,
    ): SuperResolutionStartResult {
        val appContext = context.applicationContext
        val journal = SuperResolutionOperationJournal(appContext)
        val admission = try {
            synchronized(lock) {
                when {
                    journal.hasData() -> SuperResolutionStartResult.Failed("previous SR operation debt is still being recovered")
                    recoveryActive || settlingOperationId != null ->
                        SuperResolutionStartResult.Failed("process-death cleanup is still settling")
                    pending != null || activeOperationId != null || mutableState.value.status.isBusy ->
                        SuperResolutionStartResult.AlreadyRunning(
                            pending?.operationId ?: activeOperationId ?: checkNotNull(mutableState.value.operationId),
                        )
                    else -> {
                        val now = System.currentTimeMillis()
                        journal.write(
                            SuperResolutionDebtRecord(
                                operationId = request.operationId,
                                phase = SuperResolutionJournalPhase.ADMITTED,
                                startedAtMillis = now,
                                updatedAtMillis = now,
                            ),
                        )
                        pending = request
                        pendingJournal = journal
                        mutableState.value =
                            SuperResolutionProductOperationState(
                                operationId = request.operationId,
                                documentIdentity = request.documentIdentity,
                                status = preparingStatus(request.preflight),
                            )
                        SuperResolutionStartResult.Started(request.operationId)
                    }
                }
            }
        } catch (failure: Throwable) {
            request.sourceRequest.close()
            return SuperResolutionStartResult.Failed(failure.message ?: "SR admission failed")
        }
        if (admission !is SuperResolutionStartResult.Started) {
            request.sourceRequest.close()
            return admission
        }
        return try {
            val intent =
                Intent(context, SuperResolutionMediaProcessingService::class.java)
                    .setAction(SuperResolutionMediaProcessingService.ACTION_START)
                    .putExtra(SuperResolutionMediaProcessingService.EXTRA_OPERATION_ID, request.operationId)
            if (SuperResolutionServiceStartSeam.start != null) {
                SuperResolutionServiceStartSeam.start!!.invoke(context, intent)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            admission
        } catch (failure: Throwable) {
            settleAdmissionFailure(request.operationId, journal)
            SuperResolutionStartResult.Failed(failure.message ?: "foreground service start failed")
        }
    }

    private fun settleAdmissionFailure(operationId: Long, journal: SuperResolutionOperationJournal) {
        var released: FullExportSourceRequest? = null
        synchronized(lock) {
            if (pending?.operationId == operationId) {
                released = pending?.sourceRequest
                pending = null
                pendingJournal = null
                if (mutableState.value.operationId == operationId) {
                    mutableState.value = SuperResolutionProductOperationState()
                }
            }
            if (journal.read()?.operationId == operationId) runCatching { journal.clear(operationId) }
        }
        released?.close()
    }

    private fun admit(request: SuperResolutionServiceRequest): SuperResolutionStartResult =
        synchronized(lock) {
            if (recoveryActive || settlingOperationId != null) {
                return SuperResolutionStartResult.Failed("process-death cleanup is still settling")
            }
            val existing = pending?.operationId ?: activeOperationId
            if (existing != null || mutableState.value.status.isBusy) {
                return SuperResolutionStartResult.AlreadyRunning(
                    existing ?: checkNotNull(mutableState.value.operationId),
                )
            }
            pending = request
            mutableState.value =
                SuperResolutionProductOperationState(
                    operationId = request.operationId,
                    documentIdentity = request.documentIdentity,
                    status = preparingStatus(request.preflight),
                )
            SuperResolutionStartResult.Started(request.operationId)
        }

    internal fun admitForTest(request: SuperResolutionServiceRequest): SuperResolutionStartResult =
        admit(request)

    internal fun claim(operationId: Long): SuperResolutionServiceRequest? =
        synchronized(lock) {
            if (settlingOperationId != null) return@synchronized null
            val request = pending?.takeIf { it.operationId == operationId } ?: return@synchronized null
            pending = null
            pendingJournal = null
            activeOperationId = operationId
            request
        }

    internal fun bindOwner(operationId: Long, cancel: () -> Unit): Boolean =
        synchronized(lock) {
            if (activeOperationId != operationId) false
            else {
                cancelOwner = cancel
                true
            }
        }

    internal fun releaseOwner(operationId: Long) {
        synchronized(lock) {
            if (activeOperationId == operationId) {
                activeOperationId = null
                cancelOwner = null
            }
            if (settlingOperationId == operationId) settlingOperationId = null
        }
    }

    internal fun beginPhysicalSettlement(operationId: Long): Boolean = synchronized(lock) {
        if (activeOperationId != operationId) false
        else {
            settlingOperationId = operationId
            true
        }
    }

    internal fun publish(operationId: Long, status: SuperResolutionExportStatus) {
        synchronized(lock) {
            if (activeOperationId == operationId || pending?.operationId == operationId) {
                mutableState.value =
                    mutableState.value.copy(
                        status = monotonicSuperResolutionStatus(mutableState.value.status, status),
                    )
            }
        }
    }

    internal fun isOwner(operationId: Long): Boolean = synchronized(lock) {
        activeOperationId == operationId
    }

    fun cancelCurrent(): Boolean {
        val operationId = synchronized(lock) { pending?.operationId ?: activeOperationId } ?: return false
        return cancel(operationId)
    }

    internal fun cancel(operationId: Long): Boolean {
        var pendingRequest: SuperResolutionServiceRequest? = null
        var cancelOwnerToInvoke: (() -> Unit)? = null
        var unboundCancellationAccepted = false
        synchronized(lock) {
            when {
                pending?.operationId == operationId -> {
                    pendingRequest = pending
                    pending = null
                    val journal = pendingJournal
                    pendingJournal = null
                    if (journal?.read()?.operationId == operationId) journal.clear(operationId)
                    mutableState.value =
                        mutableState.value.copy(
                            status = cancelledStatus(),
                        )
                    null
                }
                activeOperationId == operationId && cancelOwner != null -> {
                    cancelOwnerToInvoke = cancelOwner
                }
                activeOperationId == operationId && mutableState.value.status.isBusy -> {
                    // The service has claimed the request but has not bound its Job yet. Mark
                    // it terminal now; bindOwner() will fail and the service will self-stop.
                    activeOperationId = null
                    mutableState.value = mutableState.value.copy(status = cancelledStatus())
                    unboundCancellationAccepted = true
                    false
                }
                else -> null
            }
        }
        if (pendingRequest != null) {
            pendingRequest?.sourceRequest?.close()
            return true
        }
        if (unboundCancellationAccepted) return true
        val cancel = cancelOwnerToInvoke ?: return false
        cancel()
        return true
    }

    internal fun failClaim(operationId: Long, message: String) {
        synchronized(lock) {
            if (activeOperationId != operationId) return
            cancelOwner = null
            mutableState.value =
                mutableState.value.copy(
                    status =
                        SuperResolutionExportStatus(
                            phase = SuperResolutionExportPhase.Failed,
                            isBusy = false,
                            failureKind = SuperResolutionFailureKind.SourceRenderFailed,
                            failureMessage = message,
                        ),
                )
        }
    }

    internal fun finish(operationId: Long, status: SuperResolutionExportStatus) {
        synchronized(lock) {
            if (activeOperationId != operationId) return
            cancelOwner = null
            mutableState.value = mutableState.value.copy(status = status)
        }
    }

    internal fun hasActiveOperation(): Boolean = synchronized(lock) {
        pending != null || activeOperationId != null || settlingOperationId != null || mutableState.value.status.isBusy
    }

    fun acknowledgeTerminal() {
        synchronized(lock) {
            if (pending == null && activeOperationId == null && settlingOperationId == null && !mutableState.value.status.isBusy) {
                mutableState.value = SuperResolutionProductOperationState()
            }
        }
    }

    internal fun beginDebtRecovery(): Boolean = synchronized(lock) {
        if (pending != null || activeOperationId != null || settlingOperationId != null || mutableState.value.status.isBusy || recoveryActive) {
            false
        } else {
            recoveryActive = true
            true
        }
    }

    internal fun endDebtRecovery() {
        synchronized(lock) { recoveryActive = false }
    }

    internal fun resetForTest() {
        var pendingRequest: SuperResolutionServiceRequest? = null
        synchronized(lock) {
            cancelOwner?.invoke()
            pendingRequest = pending
            pending = null
            pendingJournal = null
            activeOperationId = null
            cancelOwner = null
            settlingOperationId = null
            recoveryActive = false
            mutableState.value = SuperResolutionProductOperationState()
        }
        pendingRequest?.sourceRequest?.close()
    }

    private fun preparingStatus(preflight: SuperResolutionPreflight) =
        SuperResolutionExportStatus(
            phase = SuperResolutionExportPhase.Preparing,
            isBusy = true,
            progress =
                SuperResolutionExportProgress(
                    phase = SuperResolutionExportPhase.Preparing,
                    overallFraction = 0f,
                    inputWidth = preflight.inputWidth,
                    inputHeight = preflight.inputHeight,
                    outputWidth = preflight.outputWidth,
                    outputHeight = preflight.outputHeight,
                    message = "Preparing",
                    canCancel = true,
                ),
        )
}

class SuperResolutionMediaProcessingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val serviceGenerationSequence = AtomicLong()
    private var ownerJob: Job? = null
    private var ownerOperationId: Long? = null
    private var ownerServiceGeneration: Long? = null
    private var lastNotificationPhase: SuperResolutionExportPhase? = null
    private var lastNotificationPercent = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val operationId = intent.getLongExtra(EXTRA_OPERATION_ID, -1L)
                val accepted = SuperResolutionOperationRegistry.cancel(operationId)
                if (accepted && !SuperResolutionOperationRegistry.hasActiveOperation()) {
                    runCatching { SuperResolutionOperationJournal(applicationContext).clear(operationId) }
                    stopSelf(startId)
                }
                if (!accepted && !SuperResolutionOperationRegistry.hasActiveOperation()) {
                    stopSelf(startId)
                }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val operationId = intent.getLongExtra(EXTRA_OPERATION_ID, -1L)
                val request = SuperResolutionOperationRegistry.claim(operationId)
                if (request == null) {
                    // A stale/duplicate delivery must not stop the service instance that owns
                    // a different valid operation. Only an idle service start is stoppable.
                    if (!SuperResolutionOperationRegistry.hasActiveOperation() && ownerOperationId == null) stopSelf(startId)
                    return START_NOT_STICKY
                }
                ownerOperationId = operationId
                val serviceGeneration = serviceGenerationSequence.incrementAndGet()
                ownerServiceGeneration = serviceGeneration
                if (ownerJob?.isActive == true) {
                    SuperResolutionOperationRegistry.failClaim(operationId, "service owner already active")
                    runCatching { SuperResolutionOperationJournal(applicationContext).clear(operationId) }
                    request.sourceRequest.close()
                    stopSelf(startId)
                    SuperResolutionOperationRegistry.releaseOwner(operationId)
                    ownerOperationId = null
                    ownerServiceGeneration = null
                    return START_NOT_STICKY
                }
                try {
                    startAsForeground(request)
                } catch (failure: Throwable) {
                    SuperResolutionOperationRegistry.failClaim(
                        operationId,
                        failure.message ?: "foreground promotion failed",
                    )
                    runCatching { SuperResolutionOperationJournal(applicationContext).clear(operationId) }
                    request.sourceRequest.close()
                    stopSelf(startId)
                    SuperResolutionOperationRegistry.releaseOwner(operationId)
                    ownerOperationId = null
                    ownerServiceGeneration = null
                    return START_NOT_STICKY
                }
                val job = serviceScope.launch(start = CoroutineStart.LAZY) {
                    execute(request, startId, serviceGeneration)
                }
                ownerJob = job
                if (!SuperResolutionOperationRegistry.bindOwner(operationId) { job.cancel() }) {
                    job.cancel()
                    SuperResolutionOperationRegistry.failClaim(operationId, "service owner bind failed")
                    runCatching { SuperResolutionOperationJournal(applicationContext).clear(operationId) }
                    request.sourceRequest.close()
                    stopSelf(startId)
                    SuperResolutionOperationRegistry.releaseOwner(operationId)
                    ownerOperationId = null
                    return START_NOT_STICKY
                }
                SuperResolutionServiceLaunchSeam.afterOwnerBound?.invoke(operationId, job)
                job.invokeOnCompletion { cause ->
                    if (cause != null && ownsServiceOperation(operationId, serviceGeneration)) {
                        SuperResolutionOperationRegistry.finish(operationId, cancelledStatus())
                        request.sourceRequest.close()
                        settleNotification(operationId, cancelledStatus(), serviceGeneration)
                        runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
                        stopSelf(startId)
                        releaseServiceOwner(operationId, serviceGeneration)
                    } else if (cause != null) {
                        request.sourceRequest.close()
                    }
                }
                try {
                    SuperResolutionServiceLaunchSeam.beforeJobStart?.invoke(operationId)
                } catch (failure: Throwable) {
                    job.cancel()
                    SuperResolutionOperationRegistry.failClaim(
                        operationId,
                        failure.message ?: "service launch ordering failed",
                    )
                    runCatching { SuperResolutionOperationJournal(applicationContext).clear(operationId) }
                    request.sourceRequest.close()
                    stopSelf(startId)
                    releaseServiceOwner(operationId, serviceGeneration)
                    return START_NOT_STICKY
                }
                job.start()
            }
            else -> if (!SuperResolutionOperationRegistry.hasActiveOperation()) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground(request: SuperResolutionServiceRequest) {
        val notification = buildNotification(request.operationId, preparingNotificationStatus(request.preflight))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = superResolutionForegroundServiceType(Build.VERSION.SDK_INT)
            SuperResolutionForegroundPromotionSeam.start?.invoke(this, NOTIFICATION_ID, notification, type)
                ?: startForeground(NOTIFICATION_ID, notification, type)
        } else {
            SuperResolutionForegroundPromotionSeam.start?.invoke(this, NOTIFICATION_ID, notification, null)
                ?: startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun execute(
        request: SuperResolutionServiceRequest,
        startId: Int,
        serviceGeneration: Long,
    ) {
        val operationJob = checkNotNull(currentCoroutineContext()[Job])
        var source: Bitmap? = null
        val journal = SuperResolutionOperationJournal(applicationContext)
        val terminal =
            try {
                val now = System.currentTimeMillis()
                journal.write(
                    SuperResolutionDebtRecord(
                        operationId = request.operationId,
                        phase = SuperResolutionJournalPhase.SOURCE_PREPARING,
                        startedAtMillis = now,
                        updatedAtMillis = now,
                    ),
                )
                SuperResolutionServiceExecutionSeam.beforePreparation?.invoke(request.operationId)
                currentCoroutineContext().ensureActive()
                source = prepareFullExportSourceBitmapFromRequest(request.sourceRequest)
                currentCoroutineContext().ensureActive()
                if (!SuperResolutionOperationRegistry.isOwner(request.operationId)) {
                    cancelledStatus()
                } else {
                    val operationContext =
                        ModelOperationContext(
                            operationToken = request.operationId,
                            documentGeneration = request.documentGeneration,
                            documentIdentity = request.documentIdentity,
                            isCurrent = { token, generation ->
                                token == request.operationId &&
                                    generation == request.documentGeneration &&
                                    SuperResolutionOperationRegistry.isOwner(request.operationId)
                            },
                            isCancelled = { operationJob.isCancelled },
                        )
                    val result =
                        SuperResolutionExportOrchestrator.exportBitmap(
                            context = applicationContext,
                            inputBitmap = checkNotNull(source),
                            fileName = "KeplerStudio_AI4x_${productTimestamp()}.png",
                            operationContext = operationContext,
                            isCurrent = { SuperResolutionOperationRegistry.isOwner(request.operationId) },
                            isCancelled = { operationJob.isCancelled },
                            onProgress = { progress ->
                                val status = SuperResolutionExportStatus(
                                    phase = progress.phase,
                                    progress = progress,
                                    isBusy = true,
                                )
                                if (ownsServiceOperation(request.operationId, serviceGeneration)) {
                                    SuperResolutionOperationRegistry.publish(request.operationId, status)
                                    updateNotification(request.operationId, status)
                                }
                            },
                            debtObserver = { event ->
                                val current = journal.read()
                                    ?: error("SR journal missing at ${event.phase}")
                                journal.write(
                                    current.withEvent(
                                        phase = event.phase,
                                        rgb8Path = event.rgb8Path,
                                        rgb8StagingPath = event.rgb8StagingPath,
                                        pendingUri = event.pendingUri?.toString(),
                                        mediaOwnershipToken = event.mediaOwnershipToken,
                                        mediaDisplayName = event.mediaDisplayName,
                                        mediaRelativePath = event.mediaRelativePath,
                                        mediaCollectionUri = event.mediaCollectionUri?.toString(),
                                        publicPublicationCommitted = event.publicPublicationCommitted,
                                    ),
                                )
                            },
                        )
                    result.toProductStatus()
                }
            } catch (_: CancellationException) {
                cancelledStatus()
            } catch (failure: Throwable) {
                SuperResolutionExportStatus(
                    phase = SuperResolutionExportPhase.Failed,
                    isBusy = false,
                    failureKind =
                        if (failure is BitmapAllocationRejectedException) {
                            SuperResolutionFailureKind.SourceRenderMemoryRejected
                        } else {
                            SuperResolutionFailureKind.SourceRenderFailed
                        },
                    failureMessage = failure.message,
                )
            } finally {
                source?.takeUnless(Bitmap::isRecycled)?.recycle()
            }
        if (!terminal.cleanupDebt) journal.clear(request.operationId)
        request.sourceRequest.close()
        if (!ownsServiceOperation(request.operationId, serviceGeneration)) return
        SuperResolutionOperationRegistry.finish(request.operationId, terminal)
        settleNotification(request.operationId, terminal, serviceGeneration)
        runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
        stopSelf(startId)
        releaseServiceOwner(request.operationId, serviceGeneration)
    }

    private fun updateNotification(operationId: Long, status: SuperResolutionExportStatus) {
        val percent = (status.progress.overallFraction.coerceIn(0f, 1f) * 100f).toInt()
        if (status.phase == lastNotificationPhase && percent < lastNotificationPercent + 2) return
        lastNotificationPhase = status.phase
        lastNotificationPercent = percent
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(operationId, status))
    }

    private fun settleNotification(
        operationId: Long,
        status: SuperResolutionExportStatus,
        serviceGeneration: Long,
    ) {
        if (!ownsServiceOperation(operationId, serviceGeneration)) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(operationId, status, terminal = true))
    }

    private fun buildNotification(
        operationId: Long,
        status: SuperResolutionExportStatus,
        terminal: Boolean = false,
    ): Notification {
        val builder =
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOnlyAlertOnce(true)
                .setOngoing(status.isBusy)
                .setAutoCancel(terminal && !status.isBusy)
                .setContentTitle(
                    when (status.phase) {
                        SuperResolutionExportPhase.Succeeded -> "KeplerStudio AI 4× 저장 완료"
                        SuperResolutionExportPhase.Failed -> "KeplerStudio AI 4× 저장 실패"
                        SuperResolutionExportPhase.Cancelled -> "KeplerStudio AI 4× 취소됨"
                        else -> "KeplerStudio AI 4× 처리 중"
                    },
                )
                .setContentText(notificationStageText(status))
        if (status.isBusy) {
            val percent = (status.progress.overallFraction.coerceIn(0f, 1f) * 100f).toInt()
            builder.setProgress(100, percent, status.phase == SuperResolutionExportPhase.Preparing)
            val cancelIntent =
                Intent(this, SuperResolutionMediaProcessingService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_OPERATION_ID, operationId)
            val cancelPending =
                PendingIntent.getService(
                    this,
                    operationId.toInt(),
                    cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            builder.addAction(Notification.Action.Builder(null, "취소", cancelPending).build())
        }
        status.publishedUri?.let { uri -> builder.setContentIntent(openImagePendingIntent(uri, operationId)) }
        return builder.build()
    }

    private fun openImagePendingIntent(uri: Uri, operationId: Long): PendingIntent {
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "image/png")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return PendingIntent.getActivity(
            this,
            operationId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AI 4배 초해상도",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "AI 4배 이미지 저장 진행 상태"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        val operationId = ownerOperationId
        val serviceGeneration = ownerServiceGeneration
        val job = ownerJob
        val jobWasStillRunning = job?.isCompleted == false
        if (operationId != null && serviceGeneration != null && jobWasStillRunning) {
            // Service destruction is not physical completion. Keep the exact registry owner in
            // SETTLING until the operation-local Job completion callback has observed the real
            // unwind; this also lets process-death recovery remain journal-authoritative.
            SuperResolutionOperationRegistry.beginPhysicalSettlement(operationId)
        }
        job?.cancel()
        serviceScope.cancel()
        // A job that never started or has already completed has no physical work left. Otherwise
        // its exact completion callback performs the guarded release after physical settlement;
        // a stale service instance must never release another service's owner.
        if (operationId != null && serviceGeneration != null && !jobWasStillRunning) {
            releaseServiceOwner(operationId, serviceGeneration)
        }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val operationId = ownerOperationId
        val serviceGeneration = ownerServiceGeneration
        val operationJob = ownerJob
        if (operationId != null && serviceGeneration != null &&
            SuperResolutionOperationRegistry.beginPhysicalSettlement(operationId)
        ) {
            operationJob?.cancel(CancellationException("media processing foreground-service timeout"))
            SuperResolutionOperationRegistry.finish(operationId, cancelledStatus())
        }
        if (operationId != null && serviceGeneration != null &&
            ownerOperationId == operationId && ownerServiceGeneration == serviceGeneration
        ) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        }
        stopSelf(startId)
    }

    private fun ownsServiceOperation(operationId: Long, serviceGeneration: Long): Boolean =
        ownerOperationId == operationId && ownerServiceGeneration == serviceGeneration &&
            SuperResolutionOperationRegistry.isOwner(operationId)

    private fun releaseServiceOwner(operationId: Long, serviceGeneration: Long) {
        if (ownerOperationId != operationId || ownerServiceGeneration != serviceGeneration) return
        SuperResolutionOperationRegistry.releaseOwner(operationId)
        ownerOperationId = null
        ownerServiceGeneration = null
    }

    companion object {
        internal const val ACTION_START = "com.projectnuke.keplerstudio.action.START_AI4X"
        internal const val ACTION_CANCEL = "com.projectnuke.keplerstudio.action.CANCEL_AI4X"
        internal const val EXTRA_OPERATION_ID = "operation_id"
        internal const val JOURNAL_PREFS = "super_resolution_operation_journal"
        private const val CHANNEL_ID = "ai_4x_media_processing"
        private const val NOTIFICATION_ID = 4401
    }
}

private fun SuperResolutionExportResult.toProductStatus(): SuperResolutionExportStatus =
    when (this) {
        is SuperResolutionExportResult.Success ->
            SuperResolutionExportStatus(
                phase = SuperResolutionExportPhase.Succeeded,
                isBusy = false,
                publishedUri = uri,
                cleanupDebt = cleanupDebt,
                progress =
                    SuperResolutionExportProgress(
                        phase = SuperResolutionExportPhase.Succeeded,
                        overallFraction = 1f,
                        inputWidth = inputWidth,
                        inputHeight = inputHeight,
                        outputWidth = outputWidth,
                        outputHeight = outputHeight,
                        message = "AI 4배 저장 완료",
                    ),
            )
        is SuperResolutionExportResult.PublishedWithMetadataFailure ->
            SuperResolutionExportStatus(
                phase = SuperResolutionExportPhase.Succeeded,
                isBusy = false,
                publishedUri = uri,
                cleanupDebt = cleanupDebt,
                failureKind = failure,
                failureMessage = message,
                progress =
                    SuperResolutionExportProgress(
                        phase = SuperResolutionExportPhase.Succeeded,
                        overallFraction = 1f,
                        inputWidth = inputWidth,
                        inputHeight = inputHeight,
                        outputWidth = outputWidth,
                        outputHeight = outputHeight,
                        message = "사진은 저장되었지만 마무리 작업에 문제가 있습니다.",
                    ),
            )
        is SuperResolutionExportResult.Failure ->
            SuperResolutionExportStatus(
                phase = SuperResolutionExportPhase.Failed,
                isBusy = false,
                failureKind = kind,
                failureMessage = message,
                cleanupDebt = cleanupDebt,
            )
        SuperResolutionExportResult.Cancelled,
        SuperResolutionExportResult.Stale -> cancelledStatus()
    }

private fun cancelledStatus() =
    SuperResolutionExportStatus(
        phase = SuperResolutionExportPhase.Cancelled,
        isBusy = false,
        failureKind = SuperResolutionFailureKind.Cancelled,
    )

private fun preparingNotificationStatus(preflight: SuperResolutionPreflight) =
    SuperResolutionExportStatus(
        phase = SuperResolutionExportPhase.Preparing,
        isBusy = true,
        progress =
            SuperResolutionExportProgress(
                phase = SuperResolutionExportPhase.Preparing,
                inputWidth = preflight.inputWidth,
                inputHeight = preflight.inputHeight,
                outputWidth = preflight.outputWidth,
                outputHeight = preflight.outputHeight,
                canCancel = true,
            ),
    )

private fun notificationStageText(status: SuperResolutionExportStatus): String =
    when (status.phase) {
        SuperResolutionExportPhase.Preparing -> "준비 중"
        SuperResolutionExportPhase.Upscaling -> "AI 업스케일링 ${status.progress.completedTiles}/${status.progress.totalTiles}"
        SuperResolutionExportPhase.Encoding -> "이미지 생성 ${status.progress.encodingRowsCompleted}/${status.progress.encodingRowsTotal}"
        SuperResolutionExportPhase.Publishing -> "저장 중"
        SuperResolutionExportPhase.Succeeded -> status.progress.message.ifBlank { "저장 완료" }
        SuperResolutionExportPhase.Failed -> "저장하지 못했습니다. 앱에서 다시 시도해 주세요."
        SuperResolutionExportPhase.Cancelled -> "취소되었습니다."
        SuperResolutionExportPhase.Idle -> "대기 중"
    }

private fun productTimestamp(): String =
    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

internal fun superResolutionForegroundServiceType(sdkInt: Int): Int =
    when {
        sdkInt >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        sdkInt >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> 0
    }

internal suspend fun reconcileSuperResolutionProcessDeathDebt(context: Context) {
    if (!SuperResolutionOperationRegistry.beginDebtRecovery()) return
    try {
        withContext(Dispatchers.IO) {
            recoverExactSuperResolutionDebt(context)
        }
    } finally {
        SuperResolutionOperationRegistry.endDebtRecovery()
    }
}
