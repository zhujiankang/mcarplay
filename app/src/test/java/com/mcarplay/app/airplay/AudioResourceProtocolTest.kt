package com.mcarplay.app.airplay

import com.mcarplay.app.audio.AudioCodec
import com.mcarplay.app.audio.AudioProfile
import com.mcarplay.app.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioResourceProtocolTest {
    private val profile = AudioProfile("media", 0x800000, 48000, 250, streamType = 102, codec = AudioCodec.AAC_LC)

    private fun client(peer: AirPlayRecordLifecycleTest.Peer) =
        AirPlayClient(
            Iap2SenderSession.CarPlayEndpoint("test", "", 0, 0, peer.port, null, null, null, listOf("127.0.0.1")),
            {},
        ).also { assertTrue(it.reconnect()) }

    private fun fm(client: AirPlayClient) {
        client.modes.onChangeModes(
            mapOf(
                "resources" to
                    listOf(
                        mapOf(
                            "resourceID" to 2L,
                            "transferType" to 1L,
                            "takeConstraint" to 500L,
                            "borrowConstraint" to 100L,
                        ),
                    ),
            ),
        )
    }

    @Test fun logicalTeardownFailureKeepsTheAcceptedDescriptorForRetry() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            try {
                peer.reply = { request ->
                    when (request.method) {
                        "SETUP" ->
                            request.respond(
                                200,
                                BinaryPlist.encode(
                                    mapOf(
                                        "streams" to
                                            listOf(
                                                mapOf("type" to 102L, "dataPort" to 12345L),
                                            ),
                                    ),
                                ),
                            )
                        "TEARDOWN" -> request.respond(200, BinaryPlist.encode(mapOf("status" to -50L)))
                        else -> request.respond(501)
                    }
                }
                client.runAudioStream(ByteArray(32), profile)!!
                repeat(3) { peer.next()!! }
                assertFalse(client.teardownOutputAudio(client.connectionGeneration))
                val first = peer.next()!!
                peer.reply = { it.respond(200) }
                assertTrue(client.teardownOutputAudio(client.connectionGeneration))
                val second = peer.next()!!
                assertEquals(BinaryPlist.parse(first.body), BinaryPlist.parse(second.body))
            } finally {
                client.close()
            }
        }
    }

    @Test fun lateVolumeProbeCannotWriteIntoOrRegisterOutputOnANewConnection() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            val worker =
                java.util.concurrent.Executors
                    .newSingleThreadExecutor()
            peer.reply = { request ->
                if (request.method == "SETUP") {
                    request.respond(
                        200,
                        BinaryPlist.encode(
                            mapOf(
                                "streams" to
                                    listOf(
                                        mapOf("type" to 102L, "dataPort" to 12345L),
                                    ),
                            ),
                        ),
                    )
                }
                // Hold the volume probe while the old connection is closed.
            }
            try {
                val epoch = client.connectionGeneration
                val pending = worker.submit<AirPlayClient.AudioStreamInfo?> { client.runAudioStream(ByteArray(32), profile) }
                assertEquals("SETUP", peer.next()!!.method)
                assertEquals("GET_PARAMETER", peer.next()!!.method)
                client.close()
                assertTrue(client.reconnect())
                assertNull(pending.get(2, java.util.concurrent.TimeUnit.SECONDS))
                assertFalse(client.teardownOutputAudio(epoch))
                assertTrue(client.teardownOutputAudio(client.connectionGeneration))
                assertNull(peer.next(100))
            } finally {
                client.close()
                worker.shutdownNow()
            }
        }
    }

    @Test fun rejectedMainAudioClaimDoesNotOptimisticallyChangeTheOwner() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            try {
                fm(client)
                peer.reply = { it.respond(403) }
                val claim = client.claimMainAudioForStreaming()
                assertFalse(claim.claimed)
                assertFalse(claim.announced)
                assertFalse(client.modes.phoneOwnsAudio)
                assertEquals("POST", peer.next()!!.method)
                assertNull(peer.next(100))
            } finally {
                client.close()
            }
        }
    }

    @Test fun actualBorrowSetupScopedTeardownAndReturnKeepTheFmPermanentOwner() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            try {
                fm(client)
                peer.reply = { request ->
                    when (request.method) {
                        "SETUP" ->
                            request.respond(
                                200,
                                BinaryPlist.encode(
                                    mapOf(
                                        "streams" to
                                            listOf(
                                                mapOf("type" to 102L, "dataPort" to 12345L, "controlPort" to 12346L),
                                            ),
                                    ),
                                ),
                            )
                        "GET_PARAMETER", "SET_PARAMETER" -> request.respond(501)
                        else -> request.respond(200)
                    }
                }
                val epoch = client.connectionGeneration
                assertTrue(client.acquireAudioResource(100, true, epoch))
                assertEquals("POST", peer.next()!!.method)
                val info = client.runAudioStream(ByteArray(32), profile)!!
                assertEquals("SETUP", peer.next()!!.method)
                assertEquals("GET_PARAMETER", peer.next()!!.method)
                assertEquals("SET_PARAMETER", peer.next()!!.method)
                assertTrue(client.modes.phoneBorrowsAudio)
                assertTrue(client.teardownOutputAudio(epoch))
                val teardown = peer.next()!!
                assertEquals("TEARDOWN", teardown.method)
                val descriptor = ((BinaryPlist.parse(teardown.body) as Map<*, *>)["streams"] as List<*>).single()
                assertEquals(mapOf("type" to 102L, "streamConnectionID" to info.streamConnectionId), descriptor)
                assertTrue(client.releaseAudioBorrow(epoch))
                assertEquals("POST", peer.next()!!.method)
                assertFalse(client.modes.phoneOwnsAudio)
                assertTrue(client.teardownOutputAudio(epoch))
                assertNull(peer.next(100)) // no wildcard screen/microphone/session teardown
            } finally {
                client.close()
            }
        }
    }

    @Test fun rejectedSetupDoesNotCreateAnOutputDescriptorToTeardown() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            try {
                peer.reply = { it.respond(400) }
                assertNull(client.runAudioStream(ByteArray(32), profile))
                assertEquals("SETUP", peer.next()!!.method)
                assertTrue(client.teardownOutputAudio(client.connectionGeneration))
                assertNull(peer.next(100))
            } finally {
                client.close()
            }
        }
    }
}
