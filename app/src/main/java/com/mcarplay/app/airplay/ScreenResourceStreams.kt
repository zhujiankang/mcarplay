package com.mcarplay.app.airplay

/**
 * 主屏归属与媒体流的串行协调。参考 CarPToCarL 的 CarPlayResourceStreams，保留本项目的会话代数保护。
 * 调用方在控制工作线程调用 reconcile；事件线程只做仲裁和应答。
 * 默认让出时退役屏幕流、归还时重新 SETUP/RECORD；已核对的奔驰保持原屏幕连接，仅门控图像。
 * 源端、音频与控制通道由会话持有。
 */
internal class ScreenResourceStreams(
    private val isCurrent: () -> Boolean,
    private val phoneOwnsScreen: () -> Boolean,
    private val retireScreen: () -> Boolean,
    private val restoreScreen: () -> RestoreResult,
    private val onFailure: (String) -> Unit,
    /** A receiver may require one persistent video connection across native UI/camera overlays. */
    private val retainScreenStream: () -> Boolean = { false },
    private val onRetainedScreenVisibility: (Boolean) -> Unit = {},
) {
    enum class RestoreResult { RESTORED, ABORTED, FAILED }

    @Volatile
    private var closed = false

    @Volatile
    var attached = true
        private set

    private var retainedHidden = false

    val outputPaused: Boolean get() = !attached || !phoneOwnsScreen()

    fun close() {
        closed = true
    }

    private fun alive(): Boolean = !closed && isCurrent()

    private fun fail(reason: String) {
        if (!alive()) return
        closed = true
        onFailure(reason)
    }

    fun reconcile(forceRebuild: Boolean = false): Boolean {
        if (!alive()) return false
        try {
            if (attached && retainScreenStream()) {
                val visible = phoneOwnsScreen()
                val hidden = !visible
                if (retainedHidden != hidden) {
                    retainedHidden = hidden
                    onRetainedScreenVisibility(visible)
                }
                // Preserve the video socket, cipher and activation even for a stale UI rebuild request.
                // VideoSender's ownership gate drops all frames while hidden, retaining keepalives.
                return alive()
            }
            if (attached && (!phoneOwnsScreen() || forceRebuild)) {
                retainedHidden = false
                attached = false
                if (!retireScreen()) {
                    fail("主屏流退役失败")
                    return false
                }
            }
            if (!alive()) return false
            if (!phoneOwnsScreen()) return true
            if (!attached) {
                when (restoreScreen()) {
                    RestoreResult.ABORTED -> return false
                    RestoreResult.FAILED -> {
                        fail("主屏流恢复失败")
                        return false
                    }
                    RestoreResult.RESTORED -> attached = true
                }
            }
            return alive()
        } catch (error: Exception) {
            fail("主屏流切换异常：${error.javaClass.simpleName}")
            return false
        }
    }
}
