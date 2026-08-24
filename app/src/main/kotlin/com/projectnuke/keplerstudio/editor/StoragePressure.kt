package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive


/**
 * Injectable disk-capacity boundary. Production reads the OS view of usable
 * bytes; tests inject deterministic capacity so every pressure scenario is
 * reproducible. A null result means "capacity unknown" and must never be
 * interpreted as zero.
 */
internal fun interface DiskCapacityBoundary {
    fun usableBytes(volumeFile: File): Long?
}

internal object SystemDiskCapacityBoundary : DiskCapacityBoundary {
    override fun usableBytes(volumeFile: File): Long? =
        runCatching { volumeFile.usableSpace }.getOrNull()?.takeIf { it >= 0L }
}

/** Overflow-safe addition: any overflow saturates at Long.MAX_VALUE. */
internal fun saturatingAdd(a: Long, b: Long): Long {
    if (a < 0 || b < 0) return Long.MAX_VALUE
    val total = a + b
    return if (total < 0) Long.MAX_VALUE else total
}

/**
 * Process-wide registry for history-side disk reclamation. The global
 * pressure sequence may ASK history to reclaim through its own coordinator;
 * it never touches editor_history_v3 directories itself.
 *
 * The production handler is owned by the live EditorViewModel: each editor
 * registers its own history-coordinator-bound handler with owner identity.
 * Without a handler the sequence skips the history step truthfully.
 */
internal object HistoryPressureRecovery {
    internal fun interface Handler {
        /** Best-effort reclamation estimate (>= 0 freed bytes) or null if unavailable. */
        suspend fun reclaimHistoryBytes(): Long?
    }

    private val lock = Any()

    private var handler: Handler? = null
    private var owner: Any? = null

    /**
     * Installs [newHandler] on behalf of [owner], atomically replacing any
     * previous registration (the newest live owner wins linearly). The
     * returned AutoCloseable unregisters ONLY while [owner] is still the
     * current owner: a stale owner's teardown can never remove a newer
     * owner's handler, so VM teardown cannot strip another editor's
     * registration and replacement is linearizable. Registration is purely
     * in-memory — process death clears it naturally and no persistent
     * correctness depends on it surviving.
     */
    fun install(owner: Any, newHandler: Handler): AutoCloseable =
        synchronized(lock) {
            this.owner = owner
            this.handler = newHandler
            AutoCloseable {
                synchronized(lock) {
                    if (this.owner === owner) {
                        this.owner = null
                        this.handler = null
                    }
                }
            }
        }

    internal fun isInstalled(): Boolean = synchronized(lock) { handler != null }

    /** Test-only: true when [candidate] is currently the registered owner. */
    internal fun isOwnerInstalled(candidate: Any): Boolean =
        synchronized(lock) { owner != null && owner === candidate }

    /**
     * Suspends while asking the registered history coordinator to reclaim.
     * Caller cancellation propagates naturally through the suspension; a
     * failing or unavailable handler yields null (step skipped truthfully).
     */
    internal suspend fun reclaimSuspendingOrNull(): Long? {
        val current = synchronized(lock) { handler } ?: return null
        return try {
            current.reclaimHistoryBytes()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            null
        }
    }

    internal suspend fun tryReclaimAndReportAttempt(): Boolean {
        val current = synchronized(lock) { handler } ?: return false
        try {
            current.reclaimHistoryBytes()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // unavailable -> still an attempt, capacity reread is authoritative
        }
        return true
    }
}

/** The question the policy answers: REQUIRED_WRITE_BYTES + RESERVE_BYTES satisfiable? */
internal data class StorageHeadroomRequest(
    val requiredWriteBytes: Long,
    val reserveBytes: Long,
) {
    val neededBytes: Long get() = saturatingAdd(requiredWriteBytes.coerceAtLeast(1L), reserveBytes)
}

internal class StoragePressureController(
    private val capacity: DiskCapacityBoundary,
    private val reserveBytes: Long,
    private val pressureSweep: (Context) -> TransientMaintenanceReport,
) {
    /**
     * Runs [action] only when the volume hosting [targetVolumeFile] can safely
     * satisfy REQUIRED_WRITE_BYTES + RESERVE_BYTES. When it cannot, runs ONE
     * pressure-recovery pass - transient unowned artifacts first, then a
     * request to the registered history coordinator - and re-reads capacity
     * after each step. If headroom still cannot be established the truthful
     * [onInsufficient] result is returned; authoritative current roots are
     * NEVER deleted to make a write succeed.
     *
     * Unknown capacity (null) proceeds with the action: writes detect real
     * failures downstream and clean up truthfully; inventing a verdict from
     * missing data would be less safe than trying.
     */
    suspend fun <T> ensureWriteHeadroom(
        context: Context,
        targetVolumeFile: File,
        requiredBytes: Long,
        onInsufficient: () -> T,
        action: () -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        val request = StorageHeadroomRequest(requiredBytes, reserveBytes)
        var usable = capacity.usableBytes(targetVolumeFile)
        if (usable == null || usable >= request.neededBytes) return action()

        val sweepReport = pressureSweep(context)
        currentCoroutineContext().ensureActive()
        usable = capacity.usableBytes(targetVolumeFile)
        if (usable != null && usable >= request.neededBytes) return action()

        // History attempt itself propagates CancellationException; other failures
        // are treated as unavailable but still use authoritative capacity reread.
        val historyAttempted = HistoryPressureRecovery.tryReclaimAndReportAttempt()
        if (historyAttempted) {
            currentCoroutineContext().ensureActive()
            usable = capacity.usableBytes(targetVolumeFile)
            if (usable != null && usable >= request.neededBytes) return action()
        }
        currentCoroutineContext().ensureActive()
        return onInsufficient()
    }
}

/**
 * Process-wide pressure controller used by storage internals. Tests replace
 * it via [installForTest] to inject deterministic capacity, sweeps, and
 * history behavior.
 */
internal object StoragePressure {
    @Volatile internal var controller: StoragePressureController = StoragePressureController(
        capacity = SystemDiskCapacityBoundary,
        reserveBytes = DEFAULT_STORAGE_RESERVE_BYTES,
        pressureSweep = { context -> TransientSourceMaintenance.cleanup(context, TransientMaintenanceMode.PRESSURE) },
    )
        private set

    fun installForTest(controller: StoragePressureController): AutoCloseable {
        val previous = this.controller
        this.controller = controller
        return AutoCloseable { this.controller = previous }
    }

    internal const val DEFAULT_STORAGE_RESERVE_BYTES: Long = 8L * 1024L * 1024L
}

/**
 * Best-effort declared size of an incoming Uri. Null when unknown; callers
 * must not invent estimates from a null result.
 */
internal fun queryDeclaredSourceSize(context: Context, uri: Uri): Long? =
    runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val columnIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (columnIndex < 0 || !cursor.moveToFirst()) return@use null
                val value = cursor.getLong(columnIndex)
                value.takeIf { it > 0L }
            }
    }.getOrNull()
