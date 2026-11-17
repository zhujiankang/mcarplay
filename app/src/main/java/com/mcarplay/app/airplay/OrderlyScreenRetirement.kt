package com.mcarplay.app.airplay

/**
 * Owns a quiesced sender until the receiver confirms retirement. Network waits hold no ownership or
 * video write lock. close() can dispose the captured sender even while TEARDOWN is blocked.
 */
internal class OrderlyScreenRetirement<T : Any>(
    private val isCurrent: () -> Boolean,
    private val quiesce: (T) -> Unit,
    private val stop: (T) -> Unit,
    private val teardown: () -> Boolean,
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private var pending: T? = null
    private var closed = false

    val hasPending: Boolean get() = synchronized(lock) { pending != null }

    /** Ownership transfer only; allowed after cancellation so a late failed SETUP cannot leak its sender. */
    fun begin(sender: T?): Boolean =
        synchronized(lock) {
            if (closed || (sender != null && pending != null && pending !== sender)) return@synchronized false
            if (sender != null && pending == null) {
                pending = sender
                quiesce(sender) // Atomic flag/queue clear only; never waits for a socket or codec worker.
                log("SCREEN-RETIRE: video paused; TCP retained until scoped TEARDOWN confirmation")
            }
            true
        }

    /** Also retires an uncertain descriptor with no locally installed sender. Never creates a replacement. */
    fun finish(): Boolean {
        val captured =
            synchronized(lock) {
                if (closed || !isCurrent()) return false
                pending
            }
        val confirmed = teardown()
        if (!confirmed) {
            log("SCREEN-RETIRE: TEARDOWN unconfirmed; paused sender remains owned")
            return false
        }
        val released =
            synchronized(lock) {
                if (closed || pending !== captured) return false
                pending = null
                captured
            }
        if (released != null) {
            log("SCREEN-RETIRE: TEARDOWN confirmed; closing retired video TCP")
            stop(released)
        }
        return isCurrent()
    }

    override fun close() {
        val released =
            synchronized(lock) {
                if (closed) return
                closed = true
                pending.also { pending = null }
            }
        if (released != null) {
            log("SCREEN-RETIRE: session cleanup; disposing owned pending sender")
            stop(released)
        }
    }
}
