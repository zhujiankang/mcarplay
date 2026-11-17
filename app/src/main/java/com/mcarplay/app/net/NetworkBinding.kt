package com.mcarplay.app.net

/** Owns one request and its process binding; network values may be recreated by callbacks. */
internal class NetworkBinding<N : Any, R : Any>(
    private val bindProcess: (N?) -> Boolean,
    private val isUsable: (N) -> Boolean,
) {
    private var request: R? = null
    private var network: N? = null

    /** current() 主动清掉、但同一请求的 onLost 尚未到达的网络；onLost 到达时仍需通知一次。 */
    private var invalidated: N? = null

    @Synchronized
    fun replaceRequest(next: R?): R? {
        val previous = request
        request = next
        clearNetwork()
        invalidated = null
        return previous
    }

    @Synchronized
    fun isCurrent(owner: R): Boolean = request === owner

    @Synchronized fun owner(): R? = request

    data class Adoption<R>(
        val previous: R?,
    )

    /** Rebind a verified replacement without first unbinding, and only if the original owner is still current. */
    @Synchronized fun adopt(
        expected: R?,
        next: R,
        candidate: N,
    ): Adoption<R>? {
        if (request !== expected || !bindProcess(candidate)) return null
        val previous = request
        request = next
        network = candidate
        invalidated = null
        return Adoption(previous)
    }

    @Synchronized
    fun retire(owner: R): Boolean {
        if (request !== owner) return false
        replaceRequest(null)
        return true
    }

    @Synchronized
    fun bind(
        owner: R,
        candidate: N,
    ): Boolean {
        if (request !== owner || !bindProcess(candidate)) return false
        network = candidate
        invalidated = null
        return true
    }

    @Synchronized
    fun lost(
        owner: R,
        lost: N,
    ): Boolean {
        if (request !== owner) return false
        if (network == lost) {
            clearNetwork()
            return true
        }
        if (invalidated == lost) {
            invalidated = null
            return true
        }
        return false
    }

    @Synchronized
    fun current(): N? {
        val current = network ?: return null
        if (!isUsable(current)) {
            clearNetwork()
            invalidated = current
            return null
        }
        return current
    }

    private fun clearNetwork() {
        if (network == null) return
        network = null
        bindProcess(null)
    }
}
