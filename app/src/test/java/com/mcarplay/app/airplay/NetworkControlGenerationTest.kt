package com.mcarplay.app.airplay

import com.mcarplay.app.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkControlGenerationTest {
    private fun client(peer: AirPlayRecordLifecycleTest.Peer): AirPlayClient =
        AirPlayClient(
            Iap2SenderSession.CarPlayEndpoint("test", "", 0, 0, peer.port, null, null, null, listOf("127.0.0.1")),
            {},
        ).also { assertTrue(it.reconnect()) }

    private fun field(name: String) = AirPlayClient::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Test fun resetInvalidatesDelayedModesWithoutReplacingTheLiveRtspConnection() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            try {
                val obtain = AirPlayClient::class.java.getDeclaredMethod("obtainIapLink").apply { isAccessible = true }
                val link = obtain.invoke(client) as VirtualIap2Link
                field("mib3ControlSessionDone").setBoolean(client, true)
                val epoch = client.connectionGeneration
                val round = client.networkControlGeneration
                link.onControlSessionReset!!.invoke()
                assertEquals(round + 1, client.networkControlGeneration)
                assertFalse(client.assertModes(expectedEpoch = epoch, stillCurrent = { client.networkControlGeneration == round }))
                assertNull(peer.next(100))
                assertTrue(client.isConnectionCurrent(epoch))
                assertTrue(client.assertModes())
                assertEquals("POST", peer.next()!!.method)
            } finally {
                client.close()
            }
        }
    }

    @Test fun mib3HandoffRequiresPeerSubscriptionAfterTheChallengeResponse() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            try {
                field("recordAccepted").setBoolean(client, true)
                field("networkIapAuthenticated").setBoolean(client, true)
                field("mib3LinkArmed").setBoolean(client, true)
                assertFalse(client.networkHandoffReady)
                field("mib3ControlSessionDone").setBoolean(client, true)
                assertTrue(client.networkHandoffReady)
            } finally {
                client.close()
            }
        }
    }
}
