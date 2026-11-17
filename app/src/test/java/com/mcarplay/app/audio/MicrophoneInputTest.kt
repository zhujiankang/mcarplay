package com.mcarplay.app.audio

import com.mcarplay.app.airplay.AirPlayCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class MicrophoneInputTest {
    private val key = ByteArray(32) { it.toByte() }

    private fun packet(
        sequence: Int,
        counter: Long,
        extension: Boolean = false,
        silent: Boolean = false,
    ): ByteArray {
        val head = ByteArray(if (extension) 20 else 12)
        head[0] = if (extension) 0x90.toByte() else 0x80.toByte()
        head[1] = 100
        head[2] = (sequence shr 8).toByte()
        head[3] = sequence.toByte()
        head[7] = 1
        head[11] = 1
        if (extension) head[15] = 1 // one extension word
        val pcm =
            ByteArray(480) {
                if (silent) {
                    0
                } else if (it % 2 == 0) {
                    0x12
                } else {
                    0x34
                }
            }
        val nonce = AirPlayCrypto.nonce64(counter)
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, pcm, head.copyOfRange(4, 12))
        return head + sealed + nonce.copyOfRange(4, 12)
    }

    @Test fun onlyDeclaredSpeechInputPcmIsSelected() {
        fun info(
            type: Long,
            input: Long,
        ) = mapOf(
            "audioFormats" to
                listOf(
                    mapOf("type" to type, "audioType" to "speechRecognition", "audioInputFormats" to input),
                ),
        )
        assertNull(MicrophoneProfile.select(null))
        assertNull(MicrophoneProfile.select(info(102, 0x40)))
        assertNull(MicrophoneProfile.select(info(100, 0x8000000)))
        assertNull(MicrophoneProfile.select(info(100, 0)))
        assertEquals(MicrophoneProfile(0x40, 24000), MicrophoneProfile.select(info(100, 0x8000040)))
        assertEquals(MicrophoneProfile(0x10, 16000), MicrophoneProfile.select(info(100, 0x10)))
    }

    @Test fun authenticatedDecodeHandlesLossSequenceWrapAndExtensionsButRejectsReplayTamperingAndTruncation() {
        val decoder = MicrophoneRtpDecoder(key)
        val first = packet(65535, 0)
        assertArrayEquals(byteArrayOf(0x34, 0x12), decoder.decode(first)!!.copyOf(2))
        assertNull(decoder.decode(first))
        val altered = packet(0, 1).also { it[4] = 42 }
        assertNull(decoder.decode(altered))
        assertNotNull(decoder.decode(packet(1, 2, extension = true)))
        assertNull(decoder.decode(packet(0, 1)))
        val next = packet(2, 3)
        for (size in 0..37) assertNull(decoder.decode(next.copyOf(size)))
        assertNotNull(decoder.decode(next))
        val invalidExtension = packet(3, 4, extension = true).also { it[14] = 0x7F }
        assertNull(decoder.decode(invalidExtension))
        assertNull(MicrophoneRtpDecoder(ByteArray(32)).decode(packet(1, 1)))
    }

    @Test fun udpReceiverAuthenticatesResamplesAndClosesWithoutLateDelivery() {
        val output = LinkedBlockingQueue<ByteArray>()
        val receiver = CarMicrophoneReceiver(InetAddress.getByName("127.0.0.1"), { output.offer(it) }, {})
        val port = receiver.localPort
        receiver.start(key.copyOf(), 24000)
        DatagramSocket().use { sender ->
            val encrypted = packet(1, 1)
            sender.send(DatagramPacket(encrypted, encrypted.size, InetAddress.getLoopbackAddress(), port))
            val pcm = output.poll(2, TimeUnit.SECONDS)
            assertNotNull(pcm)
            assertEquals(320, pcm!!.size) // 240 samples at 24k -> 160 at 16k, mono s16LE
            assertArrayEquals(byteArrayOf(0x34, 0x12), pcm.copyOf(2))
            receiver.close()
            val later = packet(2, 2)
            sender.send(DatagramPacket(later, later.size, InetAddress.getLoopbackAddress(), port))
            assertNull(output.poll(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun signalDiagnosticsExcludeUnauthenticatedDataAndSeparateSilenceFromQueueRejection() {
        val consumed = CountDownLatch(2)
        val summaries = LinkedBlockingQueue<String>()
        val receiver =
            CarMicrophoneReceiver(
                InetAddress.getByName("127.0.0.1"),
                {
                    consumed.countDown()
                    false
                },
                { if (it.contains("authenticated=2")) summaries.offer(it) },
            )
        val port = receiver.localPort
        receiver.start(key.copyOf(), 24000)
        try {
            DatagramSocket().use { sender ->
                val invalid = packet(1, 1).also { it[20] = (it[20].toInt() xor 1).toByte() }
                for (encrypted in listOf(invalid, packet(2, 2, silent = true), packet(3, 3))) {
                    sender.send(DatagramPacket(encrypted, encrypted.size, InetAddress.getLoopbackAddress(), port))
                }
                assertTrue(consumed.await(2, TimeUnit.SECONDS))
                receiver.close()
                val summary = summaries.poll(2, TimeUnit.SECONDS) ?: error("missing receiver summary")
                assertTrue(summary.contains("received=3 authenticated=2 queued=0 queueRejected=2"))
                assertTrue(summary.contains("pcmSamples=480 zeroPackets=1 peak=4660 rms=3295"))
            }
        } finally {
            receiver.close()
        }
    }
}
