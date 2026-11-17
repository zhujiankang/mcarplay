package com.mcarplay.app.airplay

import com.mcarplay.app.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRecoveryRtspTest {
    @Test fun malformedSuccessKeepsExactStreamIdentityAndBlocksAnotherSetupUntilRetired() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            try {
                assertTrue(client.reconnect())
                assertNull(client.runScreenStream(ByteArray(32), "display-1")) // 200 without required streams
                val first = peer.next()!!
                val original = stream(first.body)
                assertNull(client.runScreenStream(ByteArray(32), "display-1"))
                assertNull(peer.next(100))
                assertTrue(client.teardownScreenStream())
                val retirement = peer.next()!!
                assertEquals("TEARDOWN", retirement.method)
                assertEquals(original["uuid"], stream(retirement.body)["uuid"])
                assertEquals(original["streamConnectionID"], stream(retirement.body)["streamConnectionID"])
                peer.reply = { it.respond(200, BinaryPlist.encode(mapOf("streams" to listOf(mapOf("dataPort" to 23456L))))) }
                assertNotNull(client.runScreenStream(ByteArray(32), "display-1"))
                assertEquals("SETUP", peer.next()!!.method)
            } finally {
                client.close()
            }
        }
    }

    @Test fun logicalTeardownRejectionDoesNotAuthorizeASecondScreenSetup() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            try {
                assertTrue(client.reconnect())
                assertNull(client.runScreenStream(ByteArray(32), "display-1"))
                assertEquals("SETUP", peer.next()!!.method)
                peer.reply = { it.respond(200, BinaryPlist.encode(mapOf("status" to -50L))) }
                assertFalse(client.teardownScreenStream())
                assertEquals("TEARDOWN", peer.next()!!.method)
                assertNull(client.runScreenStream(ByteArray(32), "display-1"))
                assertNull(peer.next(100))
            } finally {
                client.close()
            }
        }
    }

    @Test fun definitiveSetupRejectionDoesNotRegisterAnUnknownScreenForTeardown() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            try {
                assertTrue(client.reconnect())
                peer.reply = { it.respond(400) }
                assertNull(client.runScreenStream(ByteArray(32), "display-1"))
                assertEquals("SETUP", peer.next()!!.method)
                assertTrue(client.teardownScreenStream())
                assertNull(peer.next(100))
            } finally {
                client.close()
            }
        }
    }

    private fun stream(body: ByteArray): Map<*, *> = ((BinaryPlist.parse(body) as Map<*, *>)["streams"] as List<*>).single() as Map<*, *>

    private fun client(peer: AirPlayRecordLifecycleTest.Peer) =
        AirPlayClient(
            Iap2SenderSession.CarPlayEndpoint("test", "", 0, 0, peer.port, null, null, null, listOf("127.0.0.1")),
            {},
        )
}
