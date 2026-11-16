package com.mcarplay.app.carlife

/** 源端可提前握手；正式视频初始化等车机画布到达，只发送一次，避免双 INIT 破坏源会话。 */
internal class CarLifeVideoRequest(
    initialSize: Pair<Int, Int>?,
    waitForDisplaySize: Boolean,
) {
    @Volatile
    var requestedSize: Pair<Int, Int> = initialSize ?: (CarLifeProtocol.DEFAULT_WIDTH to CarLifeProtocol.DEFAULT_HEIGHT)
        private set
    private var displayKnown = !waitForDisplaySize || initialSize != null
    private var initialized = false

    fun update(
        width: Int,
        height: Int,
    ): Boolean {
        if (initialized || width !in 2..8192 || height !in 2..8192) return false
        requestedSize = (width / 2 * 2) to (height / 2 * 2)
        displayKnown = true
        return true
    }

    fun takeInitialSize(encryptionReady: Boolean): Pair<Int, Int>? {
        if (!encryptionReady || !displayKnown || initialized) return null
        initialized = true
        return requestedSize
    }
}
