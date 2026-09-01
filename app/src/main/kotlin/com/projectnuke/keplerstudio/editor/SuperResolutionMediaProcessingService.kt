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
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
    val prepareSource: suspend () -> Bitmap,
)

sealed interface SuperResolutionStartResult {
    data class Started(val operationId: Long) : SuperResolutionStartResult
    data class AlreadyRunning(val operationId: Long) : SuperResolutionStartResult
    data class Failed(val message: String) : SuperResolutionStartResult
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
    private var activeOperationId: Long? = null
    private var cancelOwner: (() -> Unit)? = null
    private var recoveryActive: Boolean = false

    internal fun nextOperationId(): Long = sequence.incrementAndGet()

    internal fun submit(
        context: Context,
        request: SuperResolutionServiceRequest,
    ): SuperResolutionStartResult {
        val admission = admit(request)
        if (admission !is SuperResolutionStartResult.Started) return admission
        return try {
            val intent =
                Intent(context, SuperResolutionMediaProcessingService::class.java)
                    .setAction(SuperResolutionMediaProcessingService.ACTION_START)
                    .putExtra(SuperResolutionMediaProcessingService.EXTRA_OPERATION_ID, request.operationId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            admission
        } catch (failure: Throwable) {
            rollbackPending(request.operationId)
            SuperResolutionStartResult.Failed(failure.message ?: "foreground service start failed")
        }
    }

    private fun admit(request: SuperResolutionServiceRequest): SuperResolutionStartResult =
        synchronized(lock) {
            if (recoveryActive) {
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

    private fun rollbackPending(operationId: Long) {
        synchronized(lock) {
            if (pending?.operationId == operationId) pending = null
            if (mutableState.value.operationId == operationId) {
                mutableState.value = SuperResolutionProductOperationState()
            }
        }
    }

    internal fun admitForTest(request: SuperResolutionServiceRequest): SuperResolutionStartResult =
        admit(request)

    internal fun claim(operationId: Long): SuperResolutionServiceRequest? =
        synchronized(lock) {
            val request = pending?.takeIf { it.operationId == operationId } ?: return@synchronized null
            pending = null
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
        val operationId = synchronized(lock) { activeOperationId } ?: return false
        return cancel(operationId)
    }

    internal fun cancel(operationId: Long): Boolean {
        val cancel = synchronized(lock) {
            if (activeOperationId == operationId) cancelOwner else null
        }
        if (cancel == null) return false
        cancel.invoke()
        return true
    }

    internal fun finish(operationId: Long, status: SuperResolutionExportStatus) {
        synchronized(lock) {
            if (activeOperationId != operationId) return
            activeOperationId = null
            cancelOwner = null
            mutableState.value = mutableState.value.copy(status = status)
        }
    }

    internal fun hasActiveOperation(): Boolean = synchronized(lock) {
        pending != null || activeOperationId != null || mutableState.value.status.isBusy
    }

    fun acknowledgeTerminal() {
        synchronized(lock) {
            if (pending == null && activeOperationId == null && !mutableState.value.status.isBusy) {
                mutableState.value = SuperResolutionProductOperationState()
            }
        }
    }

    internal fun beginDebtRecovery(): Boolean = synchronized(lock) {
        if (pending != null || activeOperationId != null || mutableState.value.status.isBusy || recoveryActive) {
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
        synchronized(lock) {
            cancelOwner?.invoke()
            pending = null
            activeOperationId = null
            cancelOwner = null
            recoveryActive = false
            mutableState.value = SuperResolutionProductOperationState()
        }
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
    private var ownerJob: Job? = null
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
                SuperResolutionOperationRegistry.cancel(operationId)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val operationId = intent.getLongExtra(EXTRA_OPERATION_ID, -1L)
                val request = SuperResolutionOperationRegistry.claim(operationId)
                if (request == null || ownerJob?.isActive == true) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                startAsForeground(request)
                val job = serviceScope.launch { execute(request, startId) }
                ownerJob = job
                check(SuperResolutionOperationRegistry.bindOwner(operationId) { job.cancel() })
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground(request: SuperResolutionServiceRequest) {
        val notification = buildNotification(request.operationId, preparingNotificationStatus(request.preflight))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type =
                if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                else 0
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun execute(request: SuperResolutionServiceRequest, startId: Int) {
        var source: Bitmap? = null
        val terminal =
            try {
                markJournal(request.operationId)
                currentCoroutineContext().ensureActive()
                source = request.prepareSource()
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
                            isCancelled = { ownerJob?.isCancelled == true },
                        )
                    val result =
                        SuperResolutionExportOrchestrator.exportBitmap(
                            context = applicationContext,
                            inputBitmap = checkNotNull(source),
                            fileName = "KeplerStudio_AI4x_${productTimestamp()}.png",
                            operationContext = operationContext,
                            isCurrent = { SuperResolutionOperationRegistry.isOwner(request.operationId) },
                            isCancelled = { ownerJob?.isCancelled == true },
                            onProgress = { progress ->
                                val status = SuperResolutionExportStatus(
                                    phase = progress.phase,
                                    progress = progress,
                                    isBusy = true,
                                )
                                SuperResolutionOperationRegistry.publish(request.operationId, status)
                                updateNotification(request.operationId, status)
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
                clearJournal(request.operationId)
            }
        SuperResolutionOperationRegistry.finish(request.operationId, terminal)
        settleNotification(request.operationId, terminal)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf(startId)
    }

    private fun updateNotification(operationId: Long, status: SuperResolutionExportStatus) {
        val percent = (status.progress.overallFraction.coerceIn(0f, 1f) * 100f).toInt()
        if (status.phase == lastNotificationPhase && percent < lastNotificationPercent + 2) return
        lastNotificationPhase = status.phase
        lastNotificationPercent = percent
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(operationId, status))
    }

    private fun settleNotification(operationId: Long, status: SuperResolutionExportStatus) {
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
        ownerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        ownerJob?.cancel(CancellationException("media processing foreground-service timeout"))
        stopSelf(startId)
    }

    private fun markJournal(operationId: Long) {
        getSharedPreferences(JOURNAL_PREFS, MODE_PRIVATE)
            .edit()
            .putLong(JOURNAL_OPERATION_ID, operationId)
            .putLong(JOURNAL_STARTED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun clearJournal(operationId: Long) {
        val prefs = getSharedPreferences(JOURNAL_PREFS, MODE_PRIVATE)
        if (prefs.getLong(JOURNAL_OPERATION_ID, -1L) == operationId) prefs.edit().clear().apply()
    }

    companion object {
        internal const val ACTION_START = "com.projectnuke.keplerstudio.action.START_AI4X"
        internal const val ACTION_CANCEL = "com.projectnuke.keplerstudio.action.CANCEL_AI4X"
        internal const val EXTRA_OPERATION_ID = "operation_id"
        internal const val JOURNAL_PREFS = "super_resolution_operation_journal"
        internal const val JOURNAL_OPERATION_ID = "operation_id"
        internal const val JOURNAL_STARTED_AT = "started_at"
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

internal suspend fun reconcileSuperResolutionProcessDeathDebt(context: Context) {
    if (!SuperResolutionOperationRegistry.beginDebtRecovery()) return
    try {
        withContext(Dispatchers.IO) {
            context.cacheDir.listFiles()
                .orEmpty()
                .filter { file ->
                    file.name.startsWith("sr6_") &&
                        (file.name.endsWith(".rgb8") || file.name.endsWith(".tmp"))
                }
                .forEach { file -> runCatching { if (file.exists()) file.delete() } }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(MediaStore.Images.Media._ID)
                val resolver = context.contentResolver
                listOf("KeplerStudio_AI4x_%", "KeplerStudio_SR4x_%").forEach { prefix ->
                    val selection =
                        "${MediaStore.Images.Media.IS_PENDING} = 1 AND " +
                            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
                    runCatching {
                        resolver.query(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            projection,
                            selection,
                            arrayOf(prefix),
                            null,
                        )?.use { cursor ->
                            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                            while (cursor.moveToNext()) {
                                val uri = android.content.ContentUris.withAppendedId(
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                    cursor.getLong(idColumn),
                                )
                                runCatching { resolver.delete(uri, null, null) }
                            }
                        }
                    }
                }
            }
            context.getSharedPreferences(
                SuperResolutionMediaProcessingService.JOURNAL_PREFS,
                Context.MODE_PRIVATE,
            ).edit().clear().apply()
        }
    } finally {
        SuperResolutionOperationRegistry.endDebtRecovery()
    }
}
