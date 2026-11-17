package com.mcarplay.app.bt

/** One RFCOMM negotiation across both directions. Cancellation never runs under the gate lock. */
internal class BluetoothConnectionGate {
    private var inbound: Lease? = null
    private var outbound: Lease? = null

    inner class Lease internal constructor(
        private val cancelSocket: () -> Unit,
    ) : AutoCloseable {
        private val cancelled =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        internal var established = false
        val isCurrent: Boolean get() = synchronized(this@BluetoothConnectionGate) { inbound === this || outbound === this }

        fun connected(): Boolean =
            synchronized(this@BluetoothConnectionGate) {
                if (outbound !== this) return@synchronized false
                established = true
                true
            }

        fun cancel() {
            close()
            if (cancelled.compareAndSet(false, true)) runCatching(cancelSocket)
        }

        override fun close() {
            synchronized(this@BluetoothConnectionGate) {
                if (inbound === this) inbound = null
                if (outbound === this) outbound = null
            }
        }
    }

    val inboundActive: Boolean get() = synchronized(this) { inbound != null }
    val occupied: Boolean get() = synchronized(this) { inbound != null || outbound != null }

    /** An accepted car connection wins over a still connecting socket; established transport wins over duplicates. */
    fun acceptInbound(cancelSocket: () -> Unit): Lease? {
        val previous: Lease?
        val lease: Lease
        synchronized(this) {
            if (inbound != null || outbound?.established == true) return null
            previous = outbound
            outbound = null
            lease = Lease(cancelSocket)
            inbound = lease
        }
        previous?.cancel()
        return lease
    }

    @Synchronized fun beginOutbound(cancelSocket: () -> Unit): Lease? {
        if (inbound != null || outbound != null) return null
        return Lease(cancelSocket).also { outbound = it }
    }
}
