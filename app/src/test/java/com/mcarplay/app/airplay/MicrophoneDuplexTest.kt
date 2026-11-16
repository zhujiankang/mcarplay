package com.mcarplay.app.airplay

import com.mcarplay.app.audio.MicrophoneProfile
import com.mcarplay.app.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneDuplexTest {
    private val profile = MicrophoneProfile(0x40, 24000)

    private fun client(
        peer: AirPlayRecordLifecycleTest.Peer,
        duplex: Boolean = true,
    ): AirPlayClient {
        val client =
            AirPlayClient(
                Iap2SenderSession.CarPlayEndpoint(
                    "test",
                    "",
                    0,
                    0,
                    peer.port,
                    null,
                    null,
                    null,
                    listOf("127.0.0.1"),
                ),
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
                        mapOf(
                            "type" to 100L,
                            "audioType" to "speechRecognition",
                            "audioInputFormats" to 0x40L,
                            "audioOutputFormats" to if (duplex) 0x40L else 0L,
                        ),
                    ),
            ),
        )
        return client
    }

    private fun description(request: AirPlayRecordLifecycleTest.Request): Map<*, *> =
        ((BinaryPlist.parse(request.body) as Map<*, *>)["streams"] as List<*>).single() as Map<*, *>

    @Test fun inconsistentReturnedSpeechFormatCannotStartADownlinkClock() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            peer.reply = {
                it.respond(
                    200,
                    BinaryPlist.encode(
                        mapOf(
                            "streams" to
                                listOf(
                                    mapOf("type" to 100L, "streamConnectionID" to 123L, "dataPort" to 23456L, "sr" to 16000L),
                                ),
                        ),
                    ),
                )
            }
            try {
                val stream = client.openMicrophone(profile, 123, 20001, client.connectionGeneration, 20002, true)
                assertTrue(stream.accepted)
                assertFalse(stream.usable)
                assertEquals("SETUP", peer.next()!!.method)
                assertTrue(client.teardownMicrophone(123, client.connectionGeneration))
                assertEquals("TEARDOWN", peer.next()!!.method)
            } finally {
                client.close()
            }
        }
    }

    @Test fun onePortOnlyRetryPreservesTheDeclaredSpeechProfileAndCapturesDuplexPorts() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            peer.reply = {
                if (it.method == "SETUP" && description(it).containsKey("input")) {
                    it.respond(400)
                } else {
                    it.respond(
                        200,
                        BinaryPlist.encode(
                            mapOf(
                                "streams" to
                                    listOf(
                                        mapOf("type" to 100L, "streamConnectionID" to 123L, "dataPort" to 23456L, "controlPort" to 23457L),
                                    ),
                            ),
                        ),
                    )
                }
            }
            try {
                val stream = client.openMicrophone(profile, 123, 20001, client.connectionGeneration, 20002, true)
                assertTrue(stream.accepted && stream.usable && stream.portOnly)
                assertEquals(23456, stream.dataPort)
                val first = description(peer.next()!!)
                val second = description(peer.next()!!)
                assertEquals(true, first["input"])
                assertFalse(second.containsKey("input"))
                assertEquals(first - "input", second)
                assertEquals(1L, second["ct"])
                assertEquals(24000L, second["sr"])
                assertEquals(480L, second["spf"])
                assertTrue(client.teardownMicrophone(123, client.connectionGeneration))
                assertEquals("TEARDOWN", peer.next()!!.method)
                assertNull(peer.next(100))
            } finally {
                client.close()
            }
        }
    }

    @Test fun missingDuplexDeclarationPreventsPortOnlyRetries() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer, duplex = false)
            peer.reply = { it.respond(400) }
            try {
                assertFalse(client.openMicrophone(profile, 123, 20001, client.connectionGeneration, 20002, true).accepted)
                assertEquals("SETUP", peer.next()!!.method)
                assertNull(peer.next(100))
            } finally {
                client.close()
            }
        }
    }

    @Test fun cancellationBeforeSecondAttemptCannotWriteAnotherSetup() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            var current = true
            peer.reply = {
                current = false
                it.respond(400)
            }
            try {
                assertFalse(client.openMicrophone(profile, 123, 20001, client.connectionGeneration, 20002, true, { current }).accepted)
                assertEquals("SETUP", peer.next()!!.method)
                assertNull(peer.next(100))
            } finally {
                client.close()
            }
        }
    }

    @Test fun unusableAcceptedPortOnlyStreamIsScopedForCleanupAndFailedRetirementBlocksNewInput() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            peer.reply = { if (it.method == "SETUP" && description(it).containsKey("input")) it.respond(400) else it.respond(200) }
            try {
                val stream = client.openMicrophone(profile, 123, 20001, client.connectionGeneration, 20002, true)
                assertTrue(stream.accepted)
                assertFalse(stream.usable)
                peer.next()
                peer.next()
                peer.reply = { it.respond(200, BinaryPlist.encode(mapOf("status" to -50L))) }
                assertFalse(client.teardownMicrophone(123, client.connectionGeneration))
                assertEquals("TEARDOWN", peer.next()!!.method)
                assertFalse(client.openMicrophone(profile, 124, 20001, client.connectionGeneration).accepted)
                assertNull(peer.next(100))
                peer.reply = { it.respond(200) }
                assertTrue(client.teardownMicrophone(123, client.connectionGeneration))
                assertEquals("TEARDOWN", peer.next()!!.method)
                assertTrue(client.openMicrophone(profile, 124, 20001, client.connectionGeneration).accepted)
                assertEquals("SETUP", peer.next()!!.method)
            } finally {
                client.close()
            }
        }
    }
}
