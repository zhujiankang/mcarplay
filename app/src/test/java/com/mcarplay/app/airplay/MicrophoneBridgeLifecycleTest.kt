package com.mcarplay.app.airplay

import android.app.Application
import com.mcarplay.app.audio.CarLifeMicrophoneBridge
import com.mcarplay.app.carlife.CarLifeChannel
import com.mcarplay.app.carlife.CarLifeChannelConnector
import com.mcarplay.app.carlife.CarLifePayloadCipher
import com.mcarplay.app.carlife.CarLifeProtocol
import com.mcarplay.app.carlife.CarLifeSession
import com.mcarplay.app.carlife.CarLifeStateMachine
import com.mcarplay.app.carlife.readCarLifeSocket
import com.mcarplay.app.compat.MercedesStartup
import com.mcarplay.app.iap2.Iap2SenderSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class MicrophoneBridgeLifecycleTest {
    @Test fun captureDeadlineDoesNotWaitForBlockedSetupWorker() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            val source = source()
            val timeout = java.util.concurrent.CountDownLatch(1)
            val bridge =
                CarLifeMicrophoneBridge(log = { if (it.contains("recognition timeout")) timeout.countDown() }, recognitionTimeoutMs = 1_000)
            val held = LinkedBlockingQueue<AirPlayRecordLifecycleTest.Request>()
            peer.reply = { if (it.method == "SETUP") held.offer(it) else it.respond(200) }
            try {
                bridge.request(client, source, ByteArray(32), 102)
                assertEquals("POST", peer.next()!!.method)
                assertEquals("SETUP", peer.next()!!.method)
                val pending = held.poll(2, java.util.concurrent.TimeUnit.SECONDS)!!
                assertTrue(source.offerMicrophone(ByteArray(0)))
                assertTrue(timeout.await(2, java.util.concurrent.TimeUnit.SECONDS))
                assertFalse(source.offerMicrophone(ByteArray(640)))
                pending.respond(200)
                assertEquals("TEARDOWN", peer.next()!!.method)
            } finally {
                bridge.stop()
                source.stop()
                client.close()
            }
        }
    }

    @Test fun rejectedInputNeverTeardownsAnUnknownStreamAndLeavesControlResponsive() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            val source = source()
            val bridge = CarLifeMicrophoneBridge({})
            peer.reply = { it.respond(if (it.method == "SETUP") 400 else 200) }
            try {
                bridge.request(client, source, ByteArray(32), 102)
                assertEquals("POST", peer.next()!!.method)
                val setup = peer.next()!!
                assertEquals("SETUP", setup.method)
                assertTrue(setup.path.startsWith("rtsp://127.0.0.1:"))
                val stream = ((BinaryPlist.parse(setup.body) as Map<*, *>)["streams"] as List<*>).single() as Map<*, *>
                val data = (stream["dataPort"] as Number).toInt()
                val control = (stream["controlPort"] as Number).toInt()
                assertTrue(data in 1..65535 && control in 1..65535 && data != control)
                assertEquals("POST", peer.next()!!.method) // clear speech state, no unknown-stream TEARDOWN
                assertNull(peer.next(100))
                assertTrue(client.assertModes())
                assertEquals("POST", peer.next()!!.method)
                assertTrue(source.isRunning)
                assertFalse(source.offerMicrophone(ByteArray(640)))
            } finally {
                bridge.stop()
                source.stop()
                client.close()
            }
        }
    }

    @Test fun acceptedInputWithoutAuthenticatedPacketsIsStoppedWithoutRepeatingRecord() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            val source = source()
            val bridge = CarLifeMicrophoneBridge({}, firstPacketTimeoutMs = 100)
            try {
                bridge.request(client, source, ByteArray(32), 102)
                assertEquals("POST", peer.next()!!.method)
                assertEquals("SETUP", peer.next()!!.method)
                assertEquals("TEARDOWN", peer.next()!!.method)
                assertEquals("POST", peer.next()!!.method)
                assertFalse(source.offerMicrophone(ByteArray(640)))
                assertNull(peer.next(100))
                assertTrue(source.isRunning)
            } finally {
                bridge.stop()
                source.stop()
                client.close()
            }
        }
    }

    @Test fun plistErrorInsideSuccessfulRtspIsNotAnAcceptedMicrophoneStream() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            peer.reply = { it.respond(200, BinaryPlist.encode(mapOf("status" to -50L))) }
            try {
                assertFalse(
                    client.setupMicrophone(
                        com.mcarplay.app.audio
                            .MicrophoneProfile(0x40, 24000),
                        123,
                        23456,
                        client.connectionGeneration,
                        23457,
                    ),
                )
                assertEquals("SETUP", peer.next()!!.method)
            } finally {
                client.close()
            }
        }
    }

    @Test fun nativeAudioIsBorrowedForSpeechAndReturnedOnlyAfterInputRetirement() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            val source = source()
            client.modes.onChangeModes(
                mapOf(
                    "resources" to
                        listOf(
                            mapOf(
                                "resourceID" to 2L,
                                "transferType" to 1L,
                                "takeConstraint" to 500L,
                                "borrowConstraint" to 500L,
                            ),
                        ),
                ),
            )
            val bridge = CarLifeMicrophoneBridge({}, firstPacketTimeoutMs = 100)
            try {
                bridge.request(client, source, ByteArray(32), 102)
                assertEquals("POST", peer.next()!!.method) // borrow audio
                assertEquals("POST", peer.next()!!.method) // Speech state
                assertEquals("SETUP", peer.next()!!.method)
                assertEquals("TEARDOWN", peer.next()!!.method)
                assertEquals("POST", peer.next()!!.method) // restore native audio owner
                repeat(50) { if (client.modes.phoneOwnsAudio) Thread.sleep(10) }
                assertFalse(client.modes.phoneOwnsAudio)
                assertFalse(client.modes.recognizingSpeech)
                assertTrue(source.isRunning)
            } finally {
                bridge.stop()
                source.stop()
                client.close()
            }
        }
    }

    @Test fun constrainedCarAudioAndUnconfirmedOutputRetirementNeverOpenMicrophone() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            val source = source()
            val bridge = CarLifeMicrophoneBridge({}, prepareAudio = { _, _ -> false })
            try {
                client.modes.onChangeModes(
                    mapOf(
                        "resources" to
                            listOf(
                                mapOf(
                                    "resourceID" to 2L,
                                    "transferType" to 3L,
                                    "unborrowConstraint" to 1000L,
                                ),
                            ),
                    ),
                )
                bridge.request(client, source, ByteArray(32), 102)
                val cleared = peer.next()!!
                assertEquals("POST", cleared.method) // cleanup state, no microphone SETUP
                assertNull(peer.next(100))
                client.modes.onChangeModes(mapOf("resources" to listOf(mapOf("resourceID" to 2L, "transferType" to 4L))))
                bridge.request(client, source, ByteArray(32), 100)
                assertEquals("POST", peer.next()!!.method) // cleanup state, failed output retirement
                assertNull(peer.next(100))
                assertTrue(source.isRunning)
            } finally {
                bridge.stop()
                source.stop()
                client.close()
            }
        }
    }

    @Test fun sharedResourceWorkerRetiresActualType100OutputBeforeSpeechSetup() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            val source = source()
            val worker =
                java.util.concurrent.Executors
                    .newSingleThreadExecutor()
            peer.reply = { request ->
                if (request.method == "SETUP") {
                    request.respond(200, BinaryPlist.encode(mapOf("streams" to listOf(mapOf("type" to 100L, "dataPort" to 23456L)))))
                } else {
                    request.respond(200)
                }
            }
            val bridge =
                CarLifeMicrophoneBridge(
                    {},
                    worker = worker,
                    prepareAudio = { activeClient, epoch -> activeClient.teardownOutputAudio(epoch) },
                )
            try {
                assertTrue(
                    client.runAudioStream(
                        ByteArray(32),
                        com.mcarplay.app.audio
                            .AudioProfile("media", 0x8000, 48000, 200),
                    ) != null,
                )
                assertEquals("SETUP", peer.next()!!.method)
                assertEquals("GET_PARAMETER", peer.next()!!.method)
                assertEquals("SET_PARAMETER", peer.next()!!.method) // existing volume fallback after empty query
                bridge.request(client, source, ByteArray(32), 100)
                assertEquals("TEARDOWN", peer.next()!!.method) // exact previous media output first
                assertEquals("POST", peer.next()!!.method)
                assertEquals("SETUP", peer.next()!!.method) // then speech input
                worker.submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS)
                assertTrue(client.modes.recognizingSpeech)
                bridge.stop()
                assertEquals("TEARDOWN", peer.next()!!.method)
                assertEquals("POST", peer.next()!!.method)
                worker.submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS)
                assertFalse(client.modes.recognizingSpeech)
                assertTrue(source.isRunning)
            } finally {
                bridge.stop()
                worker.submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS)
                source.stop()
                client.close()
                worker.shutdownNow()
            }
        }
    }

    private fun source(socket: Socket? = null): CarLifeSession {
        val source = CarLifeSession(Application(), onAccessUnit = {}, onEnded = {}, log = {})

        fun field(name: String) = CarLifeSession::class.java.getDeclaredField(name).apply { isAccessible = true }
        (field("running").get(source) as AtomicBoolean).set(true)
        val state = field("stateMachine").get(source) as CarLifeStateMachine
        CarLifeStateMachine::class.java
            .getDeclaredField("state")
            .apply { isAccessible = true }
            .set(state, CarLifeStateMachine.State.VIDEO_RUNNING)
        CarLifeStateMachine::class.java
            .getDeclaredField("payloadCipher")
            .apply { isAccessible = true }
            .set(state, CarLifePayloadCipher(ByteArray(16)))
        if (socket != null) {
            val connector = CarLifeChannelConnector({ socket })

            @Suppress("UNCHECKED_CAST")
            val sockets =
                CarLifeChannelConnector::class.java
                    .getDeclaredField("sockets")
                    .apply { isAccessible = true }
                    .get(connector) as MutableMap<CarLifeChannel, Socket>
            sockets[CarLifeChannel.VOICE] = socket
            field("connector").set(source, connector)
        }
        return source
    }

    private fun client(peer: AirPlayRecordLifecycleTest.Peer): AirPlayClient {
        val client =
            AirPlayClient(
                Iap2SenderSession.CarPlayEndpoint("test", "", 0, 0, peer.port, null, null, null, listOf("127.0.0.1")),
                {},
            )
        assertTrue(client.reconnect())
        AirPlayClient::class.java.getDeclaredField("negotiatedInfo").apply { isAccessible = true }.set(
            client,
            mapOf(
                "manufacturer" to "Mercedes-Benz",
                "model" to "MB_D-M-5",
                "audioFormats" to
                    listOf(
                        mapOf("type" to 100L, "audioType" to "speechRecognition", "audioInputFormats" to 0x8000040L),
                    ),
            ),
        )
        client.configureStartup(MercedesStartup.Mode.SINGLE_RECORD)
        assertTrue(client.record())
        assertEquals("RECORD", peer.next()!!.method)
        return client
    }

    @Test fun encryptedCarMicrophoneReachesCarLifeAsEncrypted16kMono() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            ServerSocket(0).use { server ->
                Socket("127.0.0.1", server.localPort).use { sending ->
                    server.accept().use { carwith ->
                        carwith.soTimeout = 3000
                        val source = source(sending)
                        val client = client(peer)
                        val bridge = CarLifeMicrophoneBridge({})
                        try {
                            val secret = ByteArray(32) { 3 }
                            bridge.request(client, source, secret, null)
                            assertEquals("POST", peer.next()!!.method)
                            val setup = peer.next()!!
                            assertEquals("SETUP", setup.method)
                            val stream = ((BinaryPlist.parse(setup.body) as Map<*, *>)["streams"] as List<*>).single() as Map<*, *>
                            val port = (stream["dataPort"] as Number).toInt()
                            val id = (stream["streamConnectionID"] as Number).toLong()
                            val key = client.microphoneKey(secret, id)
                            DatagramSocket().use { udp ->
                                for (sequence in 1..2) {
                                    val header =
                                        ByteArray(12).also {
                                            it[0] = 0x80.toByte()
                                            it[1] = 100
                                            it[3] = sequence.toByte()
                                        }
                                    val nonce = AirPlayCrypto.nonce64(sequence.toLong())
                                    val pcm = ByteArray(480) { if (it % 2 == 0) 1 else 2 }
                                    val packet =
                                        header + AirPlayCrypto.chachaSeal(key, nonce, pcm, header.copyOfRange(4, 12)) +
                                            nonce.copyOfRange(4, 12)
                                    udp.send(DatagramPacket(packet, packet.size, InetAddress.getLoopbackAddress(), port))
                                }
                            }
                            val frame = readCarLifeSocket(carwith.getInputStream(), CarLifeProtocol.PORT_VOICE)
                            assertEquals(0x58001, frame.kind)
                            val pcm = CarLifePayloadCipher(ByteArray(16)).decrypt(frame.payload)
                            assertArrayEquals(ByteArray(640) { if (it % 2 == 0) 2 else 1 }, pcm)
                            bridge.stop()
                            var sawTeardown = false
                            repeat(4) {
                                val request = peer.next(500)
                                if (request?.method == "TEARDOWN") sawTeardown = true
                            }
                            assertTrue(sawTeardown)
                        } finally {
                            bridge.stop()
                            source.stop()
                            client.close()
                        }
                    }
                }
            }
        }
    }

    @Test fun stoppingDuringSetupNeverActivatesInputOrRepeatsRecord() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            val source = source()
            val bridge = CarLifeMicrophoneBridge({})
            val held = LinkedBlockingQueue<AirPlayRecordLifecycleTest.Request>()
            peer.reply = { if (it.method == "SETUP") held.offer(it) else it.respond(200) }
            try {
                bridge.request(client, source, ByteArray(32), 102)
                assertEquals("POST", peer.next()!!.method)
                assertEquals("SETUP", peer.next()!!.method)
                val pending = held.poll(2, java.util.concurrent.TimeUnit.SECONDS)!!
                bridge.stop()
                pending.respond(200)
                assertEquals("TEARDOWN", peer.next()!!.method)
                assertEquals("POST", peer.next()!!.method) // report speech state cleared
                assertNull(peer.next(150))
            } finally {
                bridge.stop()
                source.stop()
                client.close()
            }
        }
    }
}
