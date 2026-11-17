package com.mcarplay.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SpeechDownlinkTest {
    private fun receive(socket: DatagramSocket): ByteArray {
        val packet = DatagramPacket(ByteArray(2048), 2048)
        socket.receive(packet)
        return packet.data.copyOf(packet.length)
    }

    @Test fun negotiatedMonoClockHasAuthenticatedPacketsCorrectSampleRateAndStopsWithItsLease() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { data ->
            DatagramSocket(0, InetAddress.getLoopbackAddress()).use { control ->
                data.soTimeout = 1500
                control.soTimeout = 1500
                val key = ByteArray(32) { 7 }
                val decoder = MicrophoneRtpDecoder(key.copyOf())
                val allowed = AtomicBoolean(true)
                val stopped = CountDownLatch(1)
                val sender =
                    SpeechDownlink(
                        InetAddress.getLoopbackAddress(),
                        data.localPort,
                        control.localPort,
                        key,
                        24000,
                        allowed::get,
                        { if (it.contains("stopped packets=")) stopped.countDown() },
                    )
                try {
                    val first = receive(data)
                    val second = receive(data)
                    assertArrayEquals(ByteArray(960), decoder.decode(first))
                    assertArrayEquals(ByteArray(960), decoder.decode(second))
                    assertEquals(
                        0,
                        java.nio.ByteBuffer
                            .wrap(first, 4, 4)
                            .int,
                    )
                    assertEquals(
                        480,
                        java.nio.ByteBuffer
                            .wrap(second, 4, 4)
                            .int,
                    )
                    val sync = receive(control)
                    assertEquals(20, sync.size)
                    assertEquals(0xd4, sync[1].toInt() and 255)
                    allowed.set(false)
                    assertTrue(stopped.await(2, TimeUnit.SECONDS))
                    assertTrue(key.all { it == 0.toByte() })
                } finally {
                    sender.close()
                }
            }
        }
    }

    @Test fun assistantPcmUsesTheNegotiatedMonoStreamInsteadOfOnlySilence() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { data ->
            data.soTimeout = 1500
            val key = ByteArray(32) { 9 }
            val decoder = MicrophoneRtpDecoder(key.copyOf())
            val pcm = SpeechPcmBuffer(24000)
            // Equal 48k stereo channels at value 0x1234; output is 24k mono in wire big endian.
            pcm.offer(
                com.mcarplay.app.carlife.CarLifeAudioEvent
                    .Pcm(ByteArray(3840) { if (it % 2 == 0) 0x34 else 0x12 }, 48000, 2),
            )
            val sender = SpeechDownlink(InetAddress.getLoopbackAddress(), data.localPort, 0, key, 24000, { true }, {}, pcm)
            try {
                assertArrayEquals(ByteArray(960) { if (it % 2 == 0) 0x34 else 0x12 }, decoder.decode(receive(data)))
            } finally {
                sender.close()
            }
        }
    }

    @Test fun pcmBacklogIsBoundedAndNewFormatDiscardsOldAssistantAudio() {
        val pcm = SpeechPcmBuffer(16000)
        repeat(100) {
            pcm.offer(
                com.mcarplay.app.carlife.CarLifeAudioEvent
                    .Pcm(ByteArray(640) { 1 }, 16000, 1),
            )
        }
        repeat(15) { assertTrue(pcm.frame(320).any { it != 0.toByte() }) }
        assertArrayEquals(ByteArray(640), pcm.frame(320))
        pcm.offer(
            com.mcarplay.app.carlife.CarLifeAudioEvent
                .Pcm(ByteArray(640) { 1 }, 16000, 1),
        )
        pcm.clear()
        assertArrayEquals(ByteArray(640), pcm.frame(320))
    }

    @Test fun carOwnershipBlocksEvenSilencePackets() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { data ->
            data.soTimeout = 100
            val sender = SpeechDownlink(InetAddress.getLoopbackAddress(), data.localPort, 0, ByteArray(32), 16000, { false }, {})
            try {
                try {
                    receive(data)
                    fail("Speech must not write while ownership is lost")
                } catch (_: SocketTimeoutException) {
                }
            } finally {
                sender.close()
            }
        }
    }
}
