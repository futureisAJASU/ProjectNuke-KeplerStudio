package com.projectnuke.keplerstudio.editor

/**
 * Scoped, deterministic lifecycle observation seams for the parameter gesture
 * transaction. Mirrors the [EditorRenderer] test-override ownership rules:
 *
 * * installing twice while a previous installation is still open fails
 * * [install] returns a handle whose [AutoCloseable.close] removes only its own
 *   installation
 * * the production code path is unchanged when no hooks are installed
 * * hooks are plain process-local observers; they never change behavior
 */
internal class ParameterLifecycleHooks(
    val onTransactionCreated: (transactionId: Long) -> Unit = {},
    val onHistoryPublished: (transactionId: Long) -> Unit = {},
    val onRenderRequestStarted: (revision: Int) -> Unit = {},
    val onRenderOutputProduced: (revision: Int) -> Unit = {},
    val onRenderOutputAdopted: (revision: Int) -> Unit = {},
    val onInactivityTimerFired: (transactionId: Long) -> Unit = {},
    val onTransactionCommitBegan: (transactionId: Long) -> Unit = {},
    val onTransactionCommitted: (adoptedRevision: Int) -> Unit = {},
    val onRollbackAdoptedStartState: (startRevision: Int) -> Unit = {},
    val onTransactionClosed: (transactionId: Long) -> Unit = {},
    val onDraftCaptureBegan: (epoch: Long) -> Unit = {},
)

internal object ParameterLifecycleTestHook {
    internal class Installation internal constructor(
        internal val generation: Long,
        private val observer: ParameterLifecycleHooks,
    ) : AutoCloseable {
        @Volatile private var active = true
        internal val hooks: ParameterLifecycleHooks? get() = if (active) observer else null

        override fun close() {
            active = false
            synchronized(lock) {
                if (installed === this) installed = null
            }
        }
    }

    @Volatile private var installed: Installation? = null
    private val lock = Any()
    private var nextGeneration = 0L

    internal fun install(hooks: ParameterLifecycleHooks): Installation {
        synchronized(lock) {
            check(installed == null) { "parameter lifecycle test hooks already installed" }
            return Installation(++nextGeneration, hooks).also { installed = it }
        }
    }

    /** Capture once when a transaction is created; never re-read a later test installation. */
    internal fun capture(): Installation? = synchronized(lock) { installed }

    internal fun notifyTransactionCreated(installation: Installation?, transactionId: Long) {
        installation?.hooks?.onTransactionCreated?.invoke(transactionId)
    }

    internal fun notifyTransactionCreated(transactionId: Long) {
        capture()?.hooks?.onTransactionCreated?.invoke(transactionId)
    }

    internal fun notifyHistoryPublished(transactionId: Long) {
        capture()?.hooks?.onHistoryPublished?.invoke(transactionId)
    }

    internal fun notifyRenderRequestStarted(revision: Int) {
        capture()?.hooks?.onRenderRequestStarted?.invoke(revision)
    }

    internal fun notifyRenderOutputProduced(revision: Int) {
        capture()?.hooks?.onRenderOutputProduced?.invoke(revision)
    }

    internal fun notifyRenderOutputAdopted(revision: Int) {
        capture()?.hooks?.onRenderOutputAdopted?.invoke(revision)
    }

    internal fun notifyInactivityTimerFired(transactionId: Long) {
        capture()?.hooks?.onInactivityTimerFired?.invoke(transactionId)
    }

    internal fun notifyTransactionCommitBegan(transactionId: Long) {
        capture()?.hooks?.onTransactionCommitBegan?.invoke(transactionId)
    }

    internal fun notifyTransactionCommitted(adoptedRevision: Int) {
        capture()?.hooks?.onTransactionCommitted?.invoke(adoptedRevision)
    }

    internal fun notifyRollbackAdoptedStartState(startRevision: Int) {
        capture()?.hooks?.onRollbackAdoptedStartState?.invoke(startRevision)
    }

    internal fun notifyTransactionClosed(transactionId: Long) {
        capture()?.hooks?.onTransactionClosed?.invoke(transactionId)
    }

    internal fun notifyDraftCaptureBegan(epoch: Long) {
        capture()?.hooks?.onDraftCaptureBegan?.invoke(epoch)
    }

    internal fun installedForTestCount(): Int =
        synchronized(lock) { if (installed == null) 0 else 1 }
}
