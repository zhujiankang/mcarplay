package com.mcarplay.app.carlink

import com.mcarplay.app.carlife.splitAnnexB
import com.mcarplay.app.video.ExternalVideoFrame

/** Every new CarPlay output must start from an IDR carrying both parameter sets. */
class CarlinkFrameGate {
    private var joined = false

    @Synchronized
    fun reset() {
        joined = false
    }

    @Synchronized
    fun admit(frame: ExternalVideoFrame): ExternalVideoFrame? {
        if (joined) return frame
        if (!frame.keyFrame) return null
        val types = splitAnnexB(frame.data).map { it[0].toInt() and 31 }.toSet()
        val sps = if (7 in types) byteArrayOf() else frame.sps?.let { START + it } ?: return null
        val pps = if (8 in types) byteArrayOf() else frame.pps?.let { START + it } ?: return null
        joined = true
        return frame.copy(data = sps + pps + frame.data)
    }

    private companion object {
        val START = byteArrayOf(0, 0, 0, 1)
    }
}
