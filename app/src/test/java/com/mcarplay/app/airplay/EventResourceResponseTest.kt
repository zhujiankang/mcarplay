package com.mcarplay.app.airplay

import com.mcarplay.app.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 真正的事件 TCP 回环：不只断言序列化 helper，检查响应体、CSeq、回调顺序和重复借用。 */
class EventResourceResponseTest {
    @Test fun rejectedTransactionHasErrorBodyNoMediaCallbackAndEventChannelRemainsUsable() {
        AirPlayRecordLifecycleTest.Peer().use { control ->
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { events ->
                events.soTimeout = 3000
                val callbacks = LinkedBlockingQueue<Map<*, *>>()
                val client =
                    AirPlayClient(
                        Iap2SenderSession.CarPlayEndpoint("test", "", 0, 0, control.port, null, null, null, listOf("127.0.0.1")),
                        log = {},
                    )
                try {
                    assertTrue(client.reconnect())
                    client.connectEventChannel(
                        null,
                        AirPlayClient.SetupInfo(events.localPort, 0, null, emptyList(), 1624, 1150, "test", 60),
                    ) { callbacks.offer(it) }
                    events.accept().use { peer ->
                        peer.soTimeout = 3000
                        val before = client.modes.parameters()
                        val rejected =
                            exchange(
                                peer,
                                43,
                                mapOf(
                                    "type" to "changeModes",
                                    "params" to
                                        mapOf(
                                            "appStates" to listOf(mapOf("appStateID" to 1L, "speechMode" to 2L)),
                                            "resources" to
                                                listOf(
                                                    mapOf("resourceID" to 1L, "transferType" to 1L),
                                                    mapOf("resourceID" to 99L, "transferType" to 1L),
                                                ),
                                        ),
                                ),
                            )
                        assertEquals(mapOf("status" to 1L), rejected)
                        assertEquals(before, client.modes.parameters())
                        assertEquals(null, callbacks.poll(100, TimeUnit.MILLISECONDS))
                        val accepted =
                            exchange(
                                peer,
                                44,
                                mapOf(
                                    "type" to "changeModes",
                                    "params" to mapOf("resources" to listOf(mapOf("resourceID" to 1L, "transferType" to 1L))),
                                ),
                            )
                        assertEquals(0L, accepted["status"])
                        assertEquals("changeModes", callbacks.poll(1, TimeUnit.SECONDS)?.get("type"))
                        assertFalse(client.modes.phoneOwnsScreen)
                        assertTrue(client.modes.phoneOwnsAudio)
                        assertFalse(client.modes.recognizingSpeech)
                    }
                } finally {
                    client.close()
                }
            }
        }
    }

    private fun exchange(
        peer: Socket,
        cseq: Int,
        command: Map<String, Any>,
    ): Map<*, *> {
        val body = BinaryPlist.encode(command)
        peer.getOutputStream().apply {
            write("POST /command RTSP/1.0\r\nCSeq: $cseq\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body)
            flush()
        }
        val header = ByteArrayOutputStream()
        var tail = ""
        while (!tail.endsWith("\r\n\r\n")) {
            val byte = peer.getInputStream().read()
            check(byte >= 0)
            header.write(byte)
            tail = (tail + byte.toChar()).takeLast(4)
        }
        val head = header.toString("US-ASCII")
        assertTrue(head.startsWith("RTSP/1.0 200"))
        assertTrue(head.contains("CSeq: $cseq\r\n"))
        val size = Regex("Content-Length: (\\d+)").find(head)!!.groupValues[1].toInt()
        return BinaryPlist.parse(peer.getInputStream().readNBytes(size)) as Map<*, *>
    }

    @Test
    fun changeModesReturnsArbitratedResourcesBeforeSlowMediaWork() {
        AirPlayRecordLifecycleTest.Peer().use { control ->
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { events ->
                events.soTimeout = 3000
                val callbackEntered = CountDownLatch(1)
                val releaseCallback = CountDownLatch(1)
                val client =
                    AirPlayClient(
                        Iap2SenderSession.CarPlayEndpoint("test", "", 0, 0, control.port, null, null, null, listOf("127.0.0.1")),
                        log = {},
                    )
                try {
                    assertTrue(client.reconnect())
                    client.connectEventChannel(
                        null,
                        AirPlayClient.SetupInfo(events.localPort, 0, null, emptyList(), 1624, 1150, "test", 60),
                    ) {
                        callbackEntered.countDown()
                        releaseCallback.await(3, TimeUnit.SECONDS)
                    }
                    events.accept().use { peer ->
                        peer.soTimeout = 3000
                        val command =
                            BinaryPlist.encode(
                                mapOf(
                                    "type" to "changeModes",
                                    "params" to
                                        mapOf(
                                            "resources" to
                                                listOf(
                                                    mapOf(
                                                        "resourceID" to 1L,
                                                        "transferType" to 1L,
                                                        "transferPriority" to 500L,
                                                        "takeConstraint" to 500L,
                                                    ),
                                                ),
                                        ),
                                ),
                            )
                        peer.getOutputStream().apply {
                            write("POST /command RTSP/1.0\r\nCSeq: 42\r\nContent-Length: ${command.size}\r\n\r\n".toByteArray() + command)
                            flush()
                        }
                        val header = ByteArrayOutputStream()
                        var tail = ""
                        while (!tail.endsWith("\r\n\r\n")) {
                            val byte = peer.getInputStream().read()
                            check(byte >= 0)
                            header.write(byte)
                            tail = (tail + byte.toChar()).takeLast(4)
                        }
                        val head = header.toString("US-ASCII")
                        assertTrue(head.startsWith("RTSP/1.0 200"))
                        assertTrue(head.contains("CSeq: 42\r\n"))
                        assertTrue(head.contains("Content-Type: application/x-apple-binary-plist"))
                        val size = Regex("Content-Length: (\\d+)").find(head)!!.groupValues[1].toInt()
                        val response = BinaryPlist.parse(peer.getInputStream().readNBytes(size)) as Map<*, *>
                        assertEquals(0L, response["status"])
                        val params = response["params"] as Map<*, *>
                        val resources = params["resources"] as List<*>
                        val screen = resources.map { it as Map<*, *> }.single { it["resourceID"] == 1L }
                        assertEquals(2L, screen["entity"])
                        assertEquals(2L, screen["permanentEntity"])
                        assertFalse(client.modes.phoneOwnsScreen)
                        assertTrue(client.modes.phoneOwnsAudio)
                        assertTrue(callbackEntered.await(1, TimeUnit.SECONDS))
                        assertEquals("Response must not wait for slow stream lifecycle work", 1L, releaseCallback.count)
                    }
                } finally {
                    releaseCallback.countDown()
                    client.close()
                }
            }
        }
    }
}
