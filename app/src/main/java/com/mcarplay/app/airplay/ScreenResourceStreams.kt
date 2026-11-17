package com.mcarplay.app.airplay

/** Serial main-screen lifecycle. Local failures never tear down audio, CarWith or the control session. */
internal class ScreenResourceStreams(
    private val isCurrent: () -> Boolean,
    private val phoneOwnsScreen: () -> Boolean,
    private val retireScreen: () -> Boolean,
    private val restoreScreen: () -> RestoreResult,
    private val onFailure: (String) -> Unit,
    private val retainScreenStream: () -> Boolean = { false },
    private val onRetainedScreenVisibility: (Boolean) -> Unit = {},
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onRetry: (String) -> Unit = {},
    private val onClose: () -> Unit = {},
) {
    enum class RestoreResult { RESTORED, ABORTED, FAILED }

    enum class State { ATTACHED, HIDDEN, DETACHED, RETIRE_PENDING, RESTORE_PENDING, EXHAUSTED, CLOSED }

    private val disposed =
        java.util.concurrent.atomic
            .AtomicBoolean()

    @Volatile private var closed = false

    @Volatile var state = State.ATTACHED
        private set

    @Volatile var attached = true
        private set

    private var retainedHidden = false
    private var retirementPending = false
    private var failures = 0
    private var nextAttempt = 0L

    val recoveryPending: Boolean get() = state == State.RETIRE_PENDING || state == State.RESTORE_PENDING

    val outputPaused: Boolean get() = !attached || !phoneOwnsScreen()

    /** Stop future reconciliation, retaining pending TCP for the caller's orderly session TEARDOWN. */
    fun quiesceForSessionStop() {
        closed = true
        state = State.CLOSED
    }

    fun close() {
        quiesceForSessionStop()
        if (disposed.compareAndSet(false, true)) onClose()
    }

    private fun alive(): Boolean = !closed && isCurrent()

    private fun retry(reason: String) {
        if (!alive()) return
        failures++
        nextAttempt = now() + RETRY_DELAY_MS
        state =
            if (failures >= MAX_ATTEMPTS) {
                State.EXHAUSTED
            } else if (retirementPending) {
                State.RETIRE_PENDING
            } else {
                State.RESTORE_PENDING
            }
        onRetry("$reason attempt=$failures/$MAX_ATTEMPTS state=$state")
        if (state == State.EXHAUSTED) onFailure(reason)
    }

    /** Called on the control worker only after verifying the ended sender is still installed. */
    fun streamEnded() {
        if (!alive() || state == State.EXHAUSTED) return
        attached = false
        retirementPending = true
        state = State.RETIRE_PENDING
    }

    /** Explicit UI can start a fresh bounded recovery after cooldown; ordinary events/invitations cannot. */
    fun reconcile(
        forceRebuild: Boolean = false,
        explicitRetry: Boolean = false,
    ): Boolean {
        if (!alive()) return false
        if (state == State.EXHAUSTED) {
            if (!explicitRetry || now() < nextAttempt) return false
            failures = 0
        }
        if (now() < nextAttempt) return false
        try {
            if (attached && retainScreenStream()) {
                val visible = phoneOwnsScreen()
                if (retainedHidden != !visible) {
                    retainedHidden = !visible
                    onRetainedScreenVisibility(visible)
                }
                state = if (visible) State.ATTACHED else State.HIDDEN
                return alive()
            }
            if (attached && (!phoneOwnsScreen() || forceRebuild)) {
                retainedHidden = false
                attached = false
                retirementPending = true
            }
            if (retirementPending) {
                state = State.RETIRE_PENDING
                if (!retireScreen()) {
                    retry("主屏流退役未确认")
                    return false
                }
                retirementPending = false
                state = State.DETACHED
            }
            if (!alive()) return false
            if (!phoneOwnsScreen()) {
                // A camera/car takeover aborts restoration, never re-acquires the resource.
                state = State.DETACHED
                failures = 0
                nextAttempt = 0
                return true
            }
            if (!attached) {
                state = State.RESTORE_PENDING
                when (restoreScreen()) {
                    RestoreResult.ABORTED -> return false
                    RestoreResult.FAILED -> {
                        retry("主屏流恢复失败")
                        return false
                    }
                    RestoreResult.RESTORED -> attached = true
                }
            }
            if (!alive()) return false
            state = State.ATTACHED
            failures = 0
            nextAttempt = 0
            return true
        } catch (error: Exception) {
            // State records whether the exact old stream still needs retirement before any SETUP.
            retry("主屏流切换异常：${error.javaClass.simpleName}")
            return false
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 2_500L
    }
}
