package com.mcarplay.app.carlink

import com.mcarplay.app.video.ExternalVideoFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CarlinkReceiverTest {
    private fun server() = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }

    @Test
    fun closeAbortsPartialAuthAndReleasesBothListeners() {
        val receiver = CarlinkReceiver("123456", {}, {}, {}, {}, authPort = 0, rtpPort = 0)
        val authPort = receiver.localAuthPort
        val rtpPort = receiver.localRtpPort
        Socket("127.0.0.1", authPort).use { socket ->
            socket.getOutputStream().write(byteArrayOf(0, 0))
            receiver.close()
        }
        receiver.close()
        val workerNames = setOf("carlink-accept", "carlink-rtp", "carlink-session", "carlink-control")
        assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name in workerNames })
        ServerSocket().use {
            it.reuseAddress = true
            it.bind(java.net.InetSocketAddress("127.0.0.1", authPort))
        }
        ServerSocket().use {
            it.reuseAddress = true
            it.bind(java.net.InetSocketAddress("127.0.0.1", rtpPort))
        }
    }

    @Test
    fun simulatedSourceTraversesAuthControlRtspAndRtpThenDisconnects() {
        val events = CopyOnWriteArrayList<String>()
        val frames = CopyOnWriteArrayList<ExternalVideoFrame>()
        val played = CountDownLatch(1)
        val received = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        server().use { controlServer ->
            server().use { rtspServer ->
                CarlinkReceiver(
                    "123456",
                    event = {
                        events += it
                        if (it == "WFD_PLAY_CONFIRMED") played.countDown()
                        if (it == "SOURCE_DISCONNECTED") disconnected.countDown()
                    },
                    video = {
                        frames += it
                        received.countDown()
                    },
                    audio = {},
                    lost = {},
                    authPort = 0,
                    rtpPort = 0,
                    rtspPort = rtspServer.localPort,
                ).use { receiver ->
                    val client = CarlinkTestClient()
                    val key =
                        Socket("127.0.0.1", receiver.localAuthPort).use { auth ->
                            auth.soTimeout = 5000
                            auth.getOutputStream().write(client.request().encode())
                            val key = client.key(CarlinkPacket.read(auth.getInputStream()))
                            auth.getOutputStream().write(client.confirmation(key, "127.0.0.1:${controlServer.localPort}").encode())
                            key
                        }
                    controlServer.accept().use { control ->
                        control.soTimeout = 5000
                        val request = CarlinkPacket.read(control.getInputStream())
                        assertEquals(24, request.method)
                        val fields = CarlinkFields.decode(CarlinkCipher(key).open(request.payload))
                        assertEquals(1280L, fields.number(2))
                        assertEquals(720L, fields.number(3))
                        val reply = CarlinkPacket(request.sequence, 1, 1, 1, 2, 1, 25, CarlinkCipher(key).seal(byteArrayOf()))
                        control.getOutputStream().write(reply.encode())
                        rtspServer.accept().use { screen ->
                            screen.soTimeout = 5000

                            fun send(message: CarlinkRtsp) = screen.getOutputStream().write(message.encode())

                            fun read() = CarlinkRtsp.read(screen.getInputStream())
                            send(CarlinkRtsp("OPTIONS * RTSP/1.0", mapOf("cseq" to "1")))
                            assertTrue(read().line.startsWith("RTSP/1.0 200"))
                            send(CarlinkRtsp("SET_PARAMETER * RTSP/1.0", mapOf("cseq" to "2"), "wfd_trigger_method: SETUP\r\n"))
                            read()
                            val setup = read()
                            assertTrue(setup.line.startsWith("SETUP "))
                            send(CarlinkRtsp("RTSP/1.0 200 OK", mapOf("cseq" to setup.headers.getValue("cseq"), "session" to "synthetic")))
                            val play = read()
                            send(CarlinkRtsp("RTSP/1.0 200 OK", mapOf("cseq" to play.headers.getValue("cseq"))))
                            assertTrue(played.await(3, TimeUnit.SECONDS))
                            Socket("127.0.0.1", receiver.localRtpPort).use { media ->
                                val output = DataOutputStream(media.getOutputStream())
                                listOf(
                                    CarlinkMediaFixture.rtp(0, *CarlinkMediaFixture.tables()),
                                    CarlinkMediaFixture.rtp(1, CarlinkMediaFixture.pes(257, 0, 90000, CarlinkMediaFixture.idr)),
                                    CarlinkMediaFixture.rtp(2, CarlinkMediaFixture.pes(257, 1, 93000, CarlinkMediaFixture.delta)),
                                ).forEach { packet ->
                                    output.writeShort(packet.size)
                                    output.write(packet)
                                }
                                assertTrue(received.await(3, TimeUnit.SECONDS))
                                assertEquals(1_000_000L, frames.first().timestampUs)
                                assertTrue(frames.first().keyFrame)
                            }
                            assertTrue(disconnected.await(3, TimeUnit.SECONDS))
                        }
                    }
                    assertTrue(events.contains("AUTH_CONFIRMED_PEER_APP_UNVERIFIED"))
                    assertTrue(events.contains("CERTIFICATE_NOT_IMPLEMENTED_WFD_INTEROP_ATTEMPT"))
                    assertFalse(events.any { it.contains("123456") })
                }
            }
        }
    }
}
