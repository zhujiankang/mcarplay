package com.mcarplay.app.iap2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2MediaTransportTest {
    private fun frame(
        control: Int,
        sequence: Int,
        ack: Int,
        session: Int,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        val size = 9 + if (payload.isEmpty()) 0 else payload.size + 1
        val bytes = ByteArray(size)
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0x5A
        bytes[2] = (size ushr 8).toByte()
        bytes[3] = size.toByte()
        bytes[4] = control.toByte()
        bytes[5] = sequence.toByte()
        bytes[6] = ack.toByte()
        bytes[7] = session.toByte()
        bytes[8] = (-bytes.take(8).sumOf { it.toInt() and 255 }).toByte()
        if (payload.isNotEmpty()) {
            payload.copyInto(bytes, 9)
            bytes[bytes.lastIndex] = (-payload.sumOf { it.toInt() and 255 }).toByte()
        }
        return bytes
    }

    private fun connected(maxLength: Int = 2048): Iap2LinkEngine {
        val engine = Iap2LinkEngine()
        engine.start(0)
        engine.takeOutput()
        val sync =
            Iap2LinkEngine.SynchronizationPayload(
                5,
                maxLength,
                4000,
                500,
                4,
                3,
                listOf(
                    Iap2LinkEngine.SessionDescriptor(1, 0, 2),
                    Iap2LinkEngine.SessionDescriptor(2, 1, 2),
                    Iap2LinkEngine.SessionDescriptor(3, 2, 1),
                ),
            )
        engine.feed(frame(0x80, 99, 99, 0, sync.encode()), 0)
        engine.takeOutput()
        engine.feed(frame(0x40, 99, 100, 0), 1)
        engine.takeOutput()
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state)
        return engine
    }

    @Test fun mercedesFileSessionTwoIsNegotiatedAndNeverMisroutedAsEa() {
        val engine = connected()
        var ea = 0
        val files = mutableListOf<ByteArray>()
        engine.onEaData = { ea++ }
        engine.onFileTransferData = { files.add(it) }
        assertEquals(2, engine.fileTransferSessionId)
        assertTrue(engine.sendFileTransfer(byteArrayOf(7, 4), 2))
        val sent = engine.takeOutput()
        assertEquals(2, sent[7].toInt())
        val ack = sent[5].toInt() and 255
        engine.feed(frame(0x40, 100, ack, 2, byteArrayOf(7, 1)), 3)
        engine.feed(frame(0x40, 100, ack, 2, byteArrayOf(7, 1)), 4)
        assertEquals(1, files.size)
        assertArrayEquals(byteArrayOf(7, 1), files.single())
        assertEquals(0, ea)
        engine.feed(frame(0x40, 101, ack, 3, byteArrayOf(9)), 5)
        assertEquals(1, ea)
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state)
    }

    @Test fun missingArtworkAcknowledgementsSuspendNewMediaWithoutDiscardingLinkSequences() {
        val engine = connected()
        var stalled = 0
        engine.onOptionalPacketStalled = { stalled++ }
        assertTrue(engine.sendFileTransfer(byteArrayOf(1, 4), 1))
        val original = engine.takeOutput()
        engine.advanceTime(4001)
        assertArrayEquals(original, engine.takeOutput())
        engine.advanceTime(8001)
        assertEquals(1, stalled)
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state)
        assertArrayEquals("Transmitted sequence must remain retryable", original, engine.takeOutput())
        val sequence = original[5].toInt() and 255
        engine.feed(frame(0x40, 99, sequence, 0), 8002)
        engine.takeOutput()
        engine.advanceTime(12001)
        assertTrue(engine.takeOutput().isEmpty())
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state)
        assertTrue(engine.sendControl(byteArrayOf(1), 12002))
        for (time in listOf(16002L, 20002L, 24002L, 28002L, 32002L)) engine.advanceTime(time)
        assertEquals("Critical-control retry policy must remain intact", Iap2LinkEngine.State.DEAD, engine.state)
    }

    @Test fun permanentlyUnacknowledgedArtworkUsesTheExistingLinkFailurePolicy() {
        val engine = connected()
        assertTrue(engine.sendFileTransfer(byteArrayOf(1, 4), 1))
        engine.takeOutput()
        for (time in listOf(4001L, 8001L, 12001L, 16001L, 20001L)) engine.advanceTime(time)
        assertEquals(Iap2LinkEngine.State.DEAD, engine.state)
    }

    @Test fun criticalAckFailureReportsMessageContextWithoutPayload() {
        val engine = connected()
        val secret = "private-auth-material"
        val payload = Iap2Csm.encode(0xAA02, secret.toByteArray())
        assertTrue(engine.sendControl(payload, 1))
        engine.takeOutput()
        for (time in listOf(4001L, 8001L, 12001L, 16001L, 20001L)) engine.advanceTime(time)
        val reason = (engine.pollEvent() as Iap2LinkEngine.Event.Dead).reason.orEmpty()
        assertTrue(reason.contains("session=1 csm=0xaa02 bytes=${payload.size} retries=5 lastAck=100"))
        assertFalse(reason.contains(secret))
        assertFalse(reason.contains(secret.toByteArray().joinToString("") { "%02x".format(it) }))
    }

    @Test fun artworkQueueAndNegotiatedPacketLimitAreBoundedWithoutKillingTheLink() {
        val engine = connected(128)
        assertFalse(engine.sendFileTransfer(ByteArray(129), 1))
        assertFalse(engine.sendControl(ByteArray(129), 1, optional = true))
        repeat(4) { assertTrue(engine.sendFileTransfer(byteArrayOf(1, 4), 1)) }
        assertFalse(engine.sendFileTransfer(byteArrayOf(1, 4), 1))
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state)
    }
}
