package com.mcarplay.app.audio

import kotlin.math.abs
import kotlin.math.sqrt

/** Aggregate s16LE signal levels only. Never retains samples and does not decide speech activity. */
internal class PcmSignalStats {
    private var samples = 0L
    private var squareSum = 0L
    private var zeroPackets = 0L
    private var peak = 0

    @Synchronized fun record(pcm: ByteArray) {
        require(pcm.size % 2 == 0)
        var allZero = pcm.isNotEmpty()
        for (i in pcm.indices step 2) {
            val value = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            samples++
            squareSum += value.toLong() * value
            peak = maxOf(peak, abs(value))
            if (value != 0) allZero = false
        }
        if (allZero) zeroPackets++
    }

    @Synchronized fun summary(): String {
        val rms = if (samples == 0L) 0 else sqrt(squareSum.toDouble() / samples).toInt()
        return "pcmSamples=$samples zeroPackets=$zeroPackets peak=$peak rms=$rms"
    }
}
