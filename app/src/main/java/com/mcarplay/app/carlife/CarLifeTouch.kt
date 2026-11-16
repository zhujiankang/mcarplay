package com.mcarplay.app.carlife

import com.mcarplay.app.video.VideoGeometry

enum class CarLifeTouchAction {
    DOWN,
    MOVE,
    UP,
}

/**
 * CarLife accepts one strict touch gesture: DOWN, zero or more MOVEs, then UP.
 * Keeping the transition rules here also gives the network layer a single
 * place to synthesize a final UP when a channel or session is torn down.
 */
class CarLifeTouchState {
    private var active = false
    private var lastX = 0
    private var lastY = 0

    @Synchronized
    fun onContact(
        x: Int,
        y: Int,
        down: Boolean,
        width: Int,
        height: Int,
    ): CarLifeTouchAction? {
        if (width <= 0 || height <= 0 || x !in 0 until width || y !in 0 until height) return null
        val action =
            when {
                down && !active -> CarLifeTouchAction.DOWN
                down -> CarLifeTouchAction.MOVE
                active -> CarLifeTouchAction.UP
                else -> return null
            }
        lastX = x
        lastY = y
        active = action != CarLifeTouchAction.UP
        return action
    }

    @Synchronized
    fun releasePoint(): Pair<Int, Int>? {
        if (!active) return null
        active = false
        return lastX to lastY
    }

    @Synchronized
    fun reset() {
        active = false
    }
}

internal fun mapCarLifeTouch(
    x: Int,
    y: Int,
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
    aspectFit: Boolean = false,
    contentWidth: Int = targetWidth,
    contentHeight: Int = targetHeight,
): Pair<Int, Int>? {
    if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return null
    if (aspectFit) {
        if (contentWidth <= 0 || contentHeight <= 0) return null
        val rect = VideoGeometry.fit(contentWidth, contentHeight, sourceWidth, sourceHeight)
        if (x !in rect.x until rect.x + rect.width || y !in rect.y until rect.y + rect.height) return null
        val mappedX = ((x - rect.x).toLong() * targetWidth / rect.width).toInt().coerceIn(0, targetWidth - 1)
        val mappedY = ((y - rect.y).toLong() * targetHeight / rect.height).toInt().coerceIn(0, targetHeight - 1)
        return mappedX to mappedY
    }
    val mappedX = (x.toLong() * targetWidth / sourceWidth).toInt().coerceIn(0, targetWidth - 1)
    val mappedY = (y.toLong() * targetHeight / sourceHeight).toInt().coerceIn(0, targetHeight - 1)
    return mappedX to mappedY
}
