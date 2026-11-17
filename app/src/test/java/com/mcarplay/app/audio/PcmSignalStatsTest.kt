package com.mcarplay.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PcmSignalStatsTest {
    @Test fun silentAndEmptyPacketsAreDifferentAndStatsDoNotRetainBuffer() {
        val signal = PcmSignalStats()
        signal.record(byteArrayOf())
        val pcm = ByteArray(640)
        signal.record(pcm)
        pcm.fill(127)
        assertEquals("pcmSamples=320 zeroPackets=1 peak=0 rms=0", signal.summary())
    }

    @Test fun signedFullScaleAndAggregatedRmsDoNotOverflow() {
        val signal = PcmSignalStats()
        signal.record(byteArrayOf(0, 0x80.toByte(), 0xff.toByte(), 0x7f))
        assertEquals("pcmSamples=2 zeroPackets=0 peak=32768 rms=32767", signal.summary())
        signal.record(ByteArray(4))
        assertEquals("pcmSamples=4 zeroPackets=1 peak=32768 rms=23170", signal.summary())
    }

    @Test fun oddByteCountCannotProduceMisleadingSignalStats() {
        val signal = PcmSignalStats()
        assertThrows(IllegalArgumentException::class.java) { signal.record(byteArrayOf(1)) }
        assertEquals("pcmSamples=0 zeroPackets=0 peak=0 rms=0", signal.summary())
    }
}
