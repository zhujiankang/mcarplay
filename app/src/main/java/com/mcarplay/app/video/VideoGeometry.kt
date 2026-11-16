package com.mcarplay.app.video

import kotlin.math.abs
import kotlin.math.roundToInt

/** 编码尺寸和显示内容区域分开计算；同一适配用于 GL 绘制与触摸反向映射。 */
internal object VideoGeometry {
    data class Rect(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    fun aspectMismatch(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Boolean {
        if (minOf(sourceWidth, sourceHeight, targetWidth, targetHeight) <= 0) return false
        val ratio = sourceWidth.toDouble() * targetHeight / (sourceHeight.toDouble() * targetWidth)
        return abs(ratio - 1.0) > 0.01
    }

    fun fit(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Rect {
        require(minOf(sourceWidth, sourceHeight, targetWidth, targetHeight) > 0)
        val scale = minOf(targetWidth.toDouble() / sourceWidth, targetHeight.toDouble() / sourceHeight)
        val width = (sourceWidth * scale).roundToInt().coerceIn(1, targetWidth)
        val height = (sourceHeight * scale).roundToInt().coerceIn(1, targetHeight)
        return Rect((targetWidth - width) / 2, (targetHeight - height) / 2, width, height)
    }
}
