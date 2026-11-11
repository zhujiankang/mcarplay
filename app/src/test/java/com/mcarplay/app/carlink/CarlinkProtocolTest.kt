package com.mcarplay.app.carlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.nio.ByteBuffer

class CarlinkProtocolTest {
    @Test
    fun crcMatchesArcCheckVector() {
        assertEquals(0xbb3d, CarlinkPacket.crc("123456789".toByteArray(), 9))
    }

    @Test
    fun fragmentedFramesKeepUnsignedSequenceAndBoundaries() {
        val packet = CarlinkPacket(0xffffffff, 0xffffffff, 1, 1, 1, 3, 1, byteArrayOf(8, 1))
        val input =
            object : FilterInputStream(ByteArrayInputStream(packet.encode() + packet.encode())) {
                override fun read(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) = super.read(bytes, offset, minOf(length, 1))
            }
        repeat(2) {
            val decoded = CarlinkPacket.read(input)
            assertEquals(0xffffffff, decoded.sequence)
            assertEquals(1, decoded.source)
            assertArrayEquals(packet.payload, decoded.payload)
        }
        assertEquals(-1, input.read())
    }

    @Test
    fun corruptHeaderAndOversizeAreRejected() {
        val packet = CarlinkPacket(1, 1, 1, 1, 1, 3, 1, byteArrayOf()).encode()
        packet[13] = 4
        assertThrows(IllegalArgumentException::class.java) { CarlinkPacket.decode(packet) }
        val input = ByteArrayInputStream(ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array())
        assertThrows(IllegalArgumentException::class.java) { CarlinkPacket.read(input) }
    }

    @Test
    fun protobufMatchesKnownWireAndRejectsAmbiguity() {
        val wire = byteArrayOf(8, 1, 18, 2, 65, 66)
        assertArrayEquals(wire, CarlinkFields().put(1, 1).put(2, "AB".toByteArray()).encode())
        assertEquals(1L, CarlinkFields.decode(wire).number(1))
        assertThrows(IllegalArgumentException::class.java) { CarlinkFields.decode(byteArrayOf(8, 1, 8, 2)) }
        assertThrows(IllegalArgumentException::class.java) { CarlinkFields.decode(byteArrayOf(18, 127)) }
        assertThrows(IllegalArgumentException::class.java) { CarlinkFields.decode(byteArrayOf(8) + ByteArray(10) { 0xff.toByte() }) }
    }

    @Test
    fun confirmationAcceptsCarwithDynamicControlPortButCannotDialArbitraryHosts() {
        assertEquals(CarlinkPhoneEndpoint("127.0.0.1", 40123), endpoint("127.0.0.1:40123"))
        assertEquals(57219, endpoint("127.0.0.1").controlPort)
        listOf("192.168.1.5:57219", "localhost:57219", "127.0.0.1:0", "127.0.0.1:65536", "127.0.0.1:1:2").forEach {
            assertThrows(IllegalArgumentException::class.java) { endpoint(it) }
        }
    }

    private fun endpoint(value: String) = CarlinkPhoneEndpoint.parse(value.toByteArray(), setOf("127.0.0.1"))
}
