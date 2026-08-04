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
    @Volatile private var installed: ParameterLifecycleHooks? = null
    private val lock = Any()

    internal fun install(hooks: ParameterLifecycleHooks): AutoCloseable {
        synchronized(lock) {
            check(installed == null) { "parameter lifecycle test hooks already installed" }
            installed = hooks
        }
        return AutoCloseable {
            synchronized(lock) {
                if (installed === hooks) installed = null
            }
        }
    }

    internal fun notifyTransactionCreated(transactionId: Long) {
        installed?.onTransactionCreated?.invoke(transactionId)
    }

    internal fun notifyHistoryPublished(transactionId: Long) {
        installed?.onHistoryPublished?.invoke(transactionId)
    }

    internal fun notifyRenderRequestStarted(revision: Int) {
        installed?.onRenderRequestStarted?.invoke(revision)
    }

    internal fun notifyRenderOutputProduced(revision: Int) {
        installed?.onRenderOutputProduced?.invoke(revision)
    }

    internal fun notifyRenderOutputAdopted(revision: Int) {
        installed?.onRenderOutputAdopted?.invoke(revision)
    }

    internal fun notifyInactivityTimerFired(transactionId: Long) {
        installed?.onInactivityTimerFired?.invoke(transactionId)
    }

    internal fun notifyTransactionCommitBegan(transactionId: Long) {
        installed?.onTransactionCommitBegan?.invoke(transactionId)
    }

    internal fun notifyTransactionCommitted(adoptedRevision: Int) {
        installed?.onTransactionCommitted?.invoke(adoptedRevision)
    }

    internal fun notifyRollbackAdoptedStartState(startRevision: Int) {
        installed?.onRollbackAdoptedStartState?.invoke(startRevision)
    }

    internal fun notifyTransactionClosed(transactionId: Long) {
        installed?.onTransactionClosed?.invoke(transactionId)
    }

    internal fun notifyDraftCaptureBegan(epoch: Long) {
        installed?.onDraftCaptureBegan?.invoke(epoch)
    }

    internal fun installedForTestCount(): Int =
        synchronized(lock) { if (installed == null) 0 else 1 }
}