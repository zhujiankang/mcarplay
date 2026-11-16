package com.mcarplay.app.carlife

/** Video-reader-thread counters only; never records bitstream bytes, screen contents or timestamps. */
internal class CarLifeVideoPipelineStats {
    private var start: Long? = null
    private var previousRead: Long? = null
    private var packets = 0L
    private var bytes = 0L
    private var frames = 0L
    private var readGap = 0L
    private var parseMax = 0L
    private var callbackMax = 0L

    fun packet(
        nowNanos: Long,
        byteCount: Int,
    ) {
        if (start == null) start = nowNanos
        previousRead?.let { readGap = maxOf(readGap, (nowNanos - it).coerceAtLeast(0)) }
        previousRead = nowNanos
        packets++
        bytes += byteCount.coerceAtLeast(0)
    }

    fun parsed(
        count: Int,
        nanos: Long,
    ) {
        frames += count.coerceAtLeast(0)
        parseMax = maxOf(parseMax, nanos.coerceAtLeast(0))
    }

    fun callback(nanos: Long) {
        callbackMax = maxOf(callbackMax, nanos.coerceAtLeast(0))
    }

    fun poll(nowNanos: Long): String? {
        val elapsed = nowNanos - (start ?: return null)
        if (elapsed < 10_000_000_000L) return null
        val milliFps = frames * 1_000_000_000_000L / elapsed
        val summary =
            "CARLIFE-VIDEO: packets=$packets bytes=$bytes parsedFrames=$frames " +
                "sourceFps=${milliFps / 1000}.${(milliFps % 1000).toString().padStart(3, '0')} " +
                "windowMs=${elapsed / 1_000_000} readGapMaxMs=${readGap / 1_000_000} " +
                "parseMaxUs=${parseMax / 1000} callbackMaxUs=${callbackMax / 1000}"
        start = nowNanos
        packets = 0
        bytes = 0
        frames = 0
        readGap = 0
        parseMax = 0
        callbackMax = 0
        return summary
    }
}
