package com.mcarplay.app.net

/** Serial, bounded recovery; network work and callbacks never hold the state lock. */
internal class BoundedNetworkRecovery<N : Any>(
    private val current: () -> Boolean,
    private val bound: () -> N?,
    private val attempt: () -> N?,
    private val restored: (N) -> Unit,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val now: () -> Long,
    private val log: (String) -> Unit,
) : AutoCloseable {
    private var closed = false
    private var active = false
    private var attempts = 0
    private var nextAt = 0L

    fun request(explicit: Boolean = false): Boolean {
        val delay =
            synchronized(this) {
                if (closed || active || !current()) return false
                if (attempts >= 3) {
                    if (!explicit || now() < nextAt) return false
                    attempts = 0
                }
                active = true
                (nextAt - now()).coerceAtLeast(0)
            }
        schedule(delay, ::run)
        return true
    }

    private fun valid(): Boolean = synchronized(this) { !closed && active && current() }

    private fun run() {
        if (!valid()) return
        val number = synchronized(this) { ++attempts }
        val network = bound() ?: runCatching { attempt() }.getOrNull()
        val ok = network != null
        if (!valid()) return
        log("WIFI-RECOVERY: attempt=$number restored=$ok")
        synchronized(this) {
            active = false
            nextAt = now() + if (number >= 3) 5000 else 1500
        }
        if (network != null) {
            runCatching { restored(network) }.onFailure { log("WIFI-RECOVERY: restored callback unavailable") }
        } else if (number < 3) {
            request()
        } else {
            log("WIFI-RECOVERY: exhausted; retain existing media, wait for explicit retry")
        }
    }

    @Synchronized override fun close() {
        closed = true
        active = false
    }
}
