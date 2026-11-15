package com.mcarplay.app.bt

/** 有界回连窗口。重复 ACL 断开不延长期限；重连/用户停止使旧定时任务失效。 */
internal class BluetoothBringupGrace(
    private val now: () -> Long,
) {
    private var generation = 0L
    private var pending: Long? = null
    private var deadline = 0L

    @Synchronized
    fun begin(): Long? {
        if (pending != null) return null
        return (++generation).also {
            pending = it
            deadline = now() + WINDOW_MILLIS
        }
    }

    @Synchronized
    fun cancel() {
        generation++
        pending = null
    }

    @Synchronized
    fun expire(token: Long): Boolean {
        if (pending != token || now() < deadline) return false
        pending = null
        return true
    }

    companion object {
        const val WINDOW_MILLIS = 60_000L
    }
}
