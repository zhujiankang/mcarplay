package com.mcarplay.app.carlink

import com.mcarplay.app.video.ExternalVideoFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Generated MPEG-TS fixtures contain no captured device traffic or OEM material. */
internal object CarlinkMediaFixture {
    private val sps = byteArrayOf(0x67, 0x42, 0x80.toByte(), 0x1f, 0xda.toByte(), 1, 0x40, 0x16, 0xe9.toByte(), 0xa8.toByte(), 0x28, 0x30)
    private val pps = byteArrayOf(0x68, 0xce.toByte(), 6, 0xe2.toByte())
    private val start = byteArrayOf(0, 0, 0, 1)
    val idr = start + sps + start + pps + start + byteArrayOf(0x65, 0x80.toByte(), 2, 3)
    val delta = start + byteArrayOf(0x41, 0x80.toByte(), 3, 4)
    val adts = byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x4c, 0x80.toByte(), 1, 0x5f, 0xfc.toByte(), 11, 22, 33)

    fun rtp(
        sequence: Int,
        vararg packets: ByteArray,
    ): ByteArray =
        ByteBuffer
            .allocate(12)
            .put(0x80.toByte())
            .put(33)
            .putShort(sequence.toShort())
            .putInt(0)
            .putInt(1)
            .array() + packets.fold(byteArrayOf()) { all, packet -> all + packet }

    fun tables(): Array<ByteArray> {
        val pat = bytes(0, 0xb0, 13, 0, 1, 0xc1, 0, 0, 0, 1, 0xe1, 0)
        val pmt = bytes(2, 0xb0, 23, 0, 1, 0xc1, 0, 0, 0xe1, 1, 0xf0, 0, 0x1b, 0xe1, 1, 0xf0, 0, 0x0f, 0xe1, 2, 0xf0, 0)

        fun crc(data: ByteArray) = data + ByteBuffer.allocate(4).putInt(CarlinkMedia.psiCrc(data)).array()
        return arrayOf(ts(0, 0, byteArrayOf(0) + crc(pat)), ts(256, 0, byteArrayOf(0) + crc(pmt)))
    }

    fun pes(
        pid: Int,
        counter: Int,
        timestamp90k: Long,
        payload: ByteArray,
    ): ByteArray {
        val stamp = timestamp90k and ((1L shl 33) - 1)
        val encoded =
            bytes(
                0x21 or (((stamp ushr 30).toInt() and 7) shl 1),
                (stamp ushr 22).toInt(),
                (((stamp ushr 15).toInt() and 127) shl 1) or 1,
                (stamp ushr 7).toInt(),
                ((stamp.toInt() and 127) shl 1) or 1,
            )
        val size = 8 + payload.size
        val body = bytes(0, 0, 1, if (pid == 257) 0xe0 else 0xc0, size ushr 8, size and 255, 0x80, 0x80, 5) + encoded + payload
        return ts(pid, counter, body)
    }

    fun ts(
        pid: Int,
        counter: Int,
        body: ByteArray,
    ): ByteArray {
        require(body.size <= 182)
        val adaptation = 183 - body.size
        val out = ByteArray(188) { 0xff.toByte() }
        bytes(0x47, 0x40 or (pid ushr 8), pid and 255, 0x30 or counter, adaptation).copyInto(out)
        if (adaptation > 0) out[5] = 0
        body.copyInto(out, 5 + adaptation)
        return out
    }

    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
}

class CarlinkMediaTest {
    @Test
    fun tsProducesH264ParametersAndMicrosecondTimestamps() {
        val frames = mutableListOf<ExternalVideoFrame>()
        val media = CarlinkMedia(frames::add, {}, {})
        media.rtp(CarlinkMediaFixture.rtp(65535, *CarlinkMediaFixture.tables()))
        media.rtp(CarlinkMediaFixture.rtp(0, CarlinkMediaFixture.pes(257, 0, 90_000, CarlinkMediaFixture.idr)))
        media.rtp(CarlinkMediaFixture.rtp(1, CarlinkMediaFixture.pes(257, 1, 93_000, CarlinkMediaFixture.delta)))
        assertEquals(1, frames.size)
        assertEquals(1_000_000L, frames.single().timestampUs)
        assertTrue(frames.single().keyFrame)
        assertTrue(frames.single().sps != null && frames.single().pps != null)
    }

    @Test
    fun aacMayCrossPesBoundariesAndKeepsPtsAcrossWrap() {
        val frames = mutableListOf<CarlinkAacFrame>()
        val media = CarlinkMedia({}, frames::add, {})
        val wrap = 1L shl 33
        media.rtp(CarlinkMediaFixture.rtp(0, *CarlinkMediaFixture.tables()))
        media.rtp(CarlinkMediaFixture.rtp(1, CarlinkMediaFixture.pes(258, 0, wrap - 900, CarlinkMediaFixture.adts.copyOfRange(0, 8))))
        assertTrue(frames.isEmpty())
        media.rtp(CarlinkMediaFixture.rtp(2, CarlinkMediaFixture.pes(258, 1, 900, CarlinkMediaFixture.adts.copyOfRange(8, 10))))
        media.rtp(CarlinkMediaFixture.rtp(3, CarlinkMediaFixture.pes(258, 2, 1800, CarlinkMediaFixture.adts)))
        assertEquals(2, frames.size)
        assertArrayEquals(byteArrayOf(11, 22, 33), frames.first().data)
        assertEquals(48000, frames.first().sampleRate)
        assertEquals(2, frames.first().channels)
        assertTrue(frames[1].timestampUs > frames[0].timestampUs)
    }

    @Test
    fun rtpGapRequiresResyncAndBadPsiCrcIsRejected() {
        var losses = 0
        val media = CarlinkMedia({}, {}, { losses++ })
        media.rtp(CarlinkMediaFixture.rtp(1, *CarlinkMediaFixture.tables()))
        val initial = losses
        media.rtp(CarlinkMediaFixture.rtp(3))
        assertEquals(initial + 1, losses)
        val corrupt = CarlinkMediaFixture.tables()[0].apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { media.rtp(CarlinkMediaFixture.rtp(4, corrupt)) }
        assertThrows(IllegalArgumentException::class.java) { media.rtp(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun outputGateWaitsForIdrAndRearmsOnBackpressure() {
        val gate = CarlinkFrameGate()
        val delta = ExternalVideoFrame(CarlinkMediaFixture.delta, false, 1)
        assertNull(gate.admit(delta))
        val missing = ExternalVideoFrame(CarlinkMediaFixture.delta, true, 2)
        assertNull(gate.admit(missing))
        val idr = ExternalVideoFrame(CarlinkMediaFixture.idr, true, 3)
        assertTrue(gate.admit(idr) != null)
        assertEquals(delta, gate.admit(delta))
        gate.reset()
        assertNull(gate.admit(delta))
        assertFalse(gate.admit(idr)!!.data.isEmpty())
    }
}
