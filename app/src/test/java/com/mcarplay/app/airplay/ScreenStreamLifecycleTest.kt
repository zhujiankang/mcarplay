package com.mcarplay.app.airplay

import com.mcarplay.app.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ScreenStreamLifecycleTest {
    @Test
    fun delayedRecordFromPreviousScreenCannotActivateReplacement() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val holdFirstRecord =
                java.util.concurrent.atomic
                    .AtomicBoolean(true)
            peer.reply = { request ->
                if (request.method == "SETUP") {
                    request.respond(200, BinaryPlist.encode(mapOf("streams" to listOf(mapOf("dataPort" to 12345L)))))
                } else if (!holdFirstRecord.getAndSet(false)) {
                    request.respond(200)
                }
            }
            val client =
                AirPlayClient(
                    Iap2SenderSession.CarPlayEndpoint("test", "", 0, 0, peer.port, null, null, null, listOf("127.0.0.1")),
                    log = {},
                )
            val worker = Executors.newSingleThreadExecutor()
            try {
                assertTrue(client.reconnect())
                assertNotNull(client.runScreenStream(null, "test"))
                assertEquals("SETUP", peer.next()!!.method)
                val pending = worker.submit(Callable { client.record() })
                val oldRecord = peer.next()!!
                assertEquals("RECORD", oldRecord.method)
                assertTrue(client.teardownScreenStream())
                assertEquals("TEARDOWN", peer.next()!!.method)
                assertNotNull(client.runScreenStream(null, "test"))
                assertEquals("SETUP", peer.next()!!.method)
                oldRecord.respond(200)
                assertFalse(pending.get(3, TimeUnit.SECONDS))
                assertFalse(client.sessionActive)
                assertTrue(client.record())
                assertTrue(client.sessionActive)
            } finally {
                client.close()
                worker.shutdownNow()
            }
        }
    }

    @Test
    fun mercedesBorrowsKeepTheStreamButPermanentHomeTakeRetiresAndRestoresIt() {
        val requests =
            exchange(7) { client ->
                val setup = client.runSetup()!!
                AirPlayClient::class.java.getDeclaredField("negotiatedInfo").apply { isAccessible = true }.set(
                    client,
                    mapOf("manufacturer" to "Mercedes-Benz", "model" to "MB_D-M-5"),
                )
                client.configureStartup(com.mcarplay.app.compat.MercedesStartup.Mode.SINGLE_RECORD)
                assertNotNull(client.runScreenStream(ByteArray(32), setup.displayUuid))
                assertTrue(client.record())
                assertTrue(client.record()) // 音频追加/重复调用不能重复激活当前屏幕。
                val streams =
                    ScreenResourceStreams(
                        isCurrent = { true },
                        phoneOwnsScreen = { client.modes.phoneOwnsScreen },
                        retireScreen = { client.teardownScreenStream() },
                        restoreScreen = {
                            if (client.runScreenStream(ByteArray(32), setup.displayUuid) != null && client.record() && client.record()) {
                                ScreenResourceStreams.RestoreResult.RESTORED
                            } else {
                                ScreenResourceStreams.RestoreResult.FAILED
                            }
                        },
                        onFailure = { error(it) },
                        retainScreenStream = { client.isMercedesReceiver() && client.modes.screenPermanentlyOwnedByPhone },
                        onRetainedScreenVisibility = { visible -> if (visible) client.modes.onScreenStreamRestored() },
                    )
                repeat(5) {
                    client.modes.changeResponse(mapOf("resources" to listOf(mapOf("resourceID" to 1L, "transferType" to 3L))))
                    assertTrue(streams.reconcile())
                    assertTrue(streams.attached)
                    assertTrue(streams.outputPaused)
                    client.modes.changeResponse(mapOf("resources" to listOf(mapOf("resourceID" to 1L, "transferType" to 4L))))
                    assertTrue(streams.reconcile())
                    assertFalse(streams.outputPaused)
                }
                client.modes.changeResponse(mapOf("resources" to listOf(mapOf("resourceID" to 1L, "transferType" to 1L))))
                assertTrue(streams.reconcile())
                assertFalse(streams.attached)
                assertTrue(client.modes.acquireScreen(true) { true })
                assertTrue(streams.reconcile())
            }
        assertEquals(listOf("SETUP", "GET", "SETUP", "RECORD", "TEARDOWN", "SETUP", "RECORD"), requests.map { it.method })
        val first = ((requests[2].body["streams"] as List<*>).single() as Map<*, *>)["streamConnectionID"]
        val teardown = ((requests[4].body["streams"] as List<*>).single() as Map<*, *>)
        assertEquals(110L, teardown["type"])
        assertEquals(first, teardown["streamConnectionID"])
        assertEquals("display-test", teardown["uuid"])
        assertEquals(1, requests.count { it.path == "/setup" })
        assertEquals(1, requests.count { it.method == "TEARDOWN" })
    }

    private data class Request(
        val method: String,
        val path: String,
        val body: Map<*, *>,
    )

    @Test
    fun screenReturnReusesControlSessionAndDisplayWithFreshStreamKeys() {
        val requests =
            exchange(9) { client ->
                val setup = client.runSetup()
                assertNotNull(setup)
                val first = client.runScreenStream(ByteArray(32) { 1 }, setup!!.displayUuid)
                assertNotNull(first)
                assertTrue(client.record())
                assertTrue(client.teardownScreenStream())
                val second = client.runScreenStream(ByteArray(32) { 1 }, setup.displayUuid)
                assertNotNull(second)
                assertNotEquals(first!!.streamConnectionId, second!!.streamConnectionId)
                assertTrue(client.record())
                assertTrue(client.teardownSession())
                assertTrue(client.teardownSession())
            }
        assertEquals(
            listOf(
                "SETUP /setup",
                "GET /info",
                "SETUP /",
                "RECORD /",
                "TEARDOWN /",
                "SETUP /",
                "RECORD /",
                "TEARDOWN /",
                "TEARDOWN /",
            ),
            requests.map { "${it.method} ${it.path}" },
        )
        for (index in listOf(2, 4, 5, 7)) {
            val stream = (requests[index].body["streams"] as List<*>).single() as Map<*, *>
            assertEquals(110L, stream["type"])
            assertEquals("display-test", stream["uuid"])
        }
        assertTrue(requests.last().body.isEmpty())
        assertEquals(1, requests.count { it.path == "/setup" })
    }

    @Test
    fun cancelledSessionStillSendsFullTeardownAfterScreenTeardownFails() {
        val requests =
            exchange(3, rejectScreenTeardown = true) { client ->
                assertNotNull(client.runScreenStream(null, "display-test"))
                client.cancelled = true
                assertFalse(client.teardownSession())
            }
        assertEquals(listOf("SETUP", "TEARDOWN", "TEARDOWN"), requests.map { it.method })
        assertTrue(requests.last().body.isEmpty())
    }

    @Test
    fun stoppingSessionStillSendsScreenAndSessionTeardown() {
        val requests =
            exchange(3) { client ->
                assertNotNull(client.runScreenStream(null, "display-test"))
                client.beginOrderlyStop()
                client.cancelled = true
                assertTrue(client.teardownSession())
                assertFalse(client.record())
            }
        assertEquals(listOf("SETUP", "TEARDOWN", "TEARDOWN"), requests.map { it.method })
        assertTrue(requests.last().body.isEmpty())
    }

    private fun exchange(
        requestCount: Int,
        rejectScreenTeardown: Boolean = false,
        run: (AirPlayClient) -> Unit,
    ): List<Request> {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 5_000
                val received =
                    executor.submit(
                        Callable {
                            server.accept().use { peer ->
                                peer.soTimeout = 5_000
                                val input = peer.getInputStream()
                                List(requestCount) {
                                    val header = ByteArrayOutputStream()
                                    var tail = ""
                                    while (!tail.endsWith("\r\n\r\n")) {
                                        val next = input.read()
                                        check(next >= 0)
                                        header.write(next)
                                        tail = (tail + next.toChar()).takeLast(4)
                                        check(header.size() <= 16_384)
                                    }
                                    val lines = header.toString("UTF-8").split("\r\n")
                                    val headers =
                                        lines.drop(1).filter { ':' in it }.associate {
                                            it.substringBefore(':').lowercase() to it.substringAfter(':').trim()
                                        }
                                    val bodyBytes = input.readNBytes(headers.getValue("content-length").toInt())
                                    val body =
                                        if (bodyBytes.isEmpty()) {
                                            emptyMap<String, Any>()
                                        } else {
                                            BinaryPlist.parse(bodyBytes) as Map<*, *>
                                        }
                                    val method = lines.first().substringBefore(' ')
                                    val path = lines.first().split(' ')[1]
                                    val response =
                                        when {
                                            path == "/info" -> mapOf("displays" to listOf(mapOf("uuid" to "display-test")))
                                            path == "/setup" -> mapOf("eventPort" to 0L)
                                            method == "SETUP" -> mapOf("streams" to listOf(mapOf("dataPort" to 12345L)))
                                            else -> emptyMap<String, Any>()
                                        }
                                    val responseBody = BinaryPlist.encode(response)
                                    val status = if (rejectScreenTeardown && method == "TEARDOWN" && body.isNotEmpty()) 500 else 200
                                    peer.getOutputStream().apply {
                                        write(
                                            (
                                                "RTSP/1.0 $status response\r\nCSeq: ${headers["cseq"]}\r\n" +
                                                    "Content-Length: ${responseBody.size}\r\n\r\n"
                                            ).toByteArray(),
                                        )
                                        write(responseBody)
                                        flush()
                                    }
                                    Request(method, path, body)
                                }
                            }
                        },
                    )
                val endpoint =
                    Iap2SenderSession.CarPlayEndpoint(
                        "test",
                        "",
                        0,
                        0,
                        server.localPort,
                        null,
                        null,
                        null,
                        listOf("127.0.0.1"),
                    )
                val client = AirPlayClient(endpoint, log = {})
                try {
                    assertTrue(client.reconnect())
                    run(client)
                    return received.get(5, TimeUnit.SECONDS)
                } finally {
                    client.close()
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
