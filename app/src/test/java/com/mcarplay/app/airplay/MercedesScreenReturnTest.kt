package com.mcarplay.app.airplay

import com.mcarplay.app.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MercedesScreenReturnTest {
    private fun take(
        type: Long = 1,
        constraint: Long = 500,
    ) = mapOf(
        "resources" to
            listOf(
                mapOf(
                    "resourceID" to 1L,
                    "transferType" to type,
                    "takeConstraint" to constraint,
                    "unborrowConstraint" to constraint,
                ),
            ),
    )

    private fun client(peer: AirPlayRecordLifecycleTest.Peer) =
        AirPlayClient(
            Iap2SenderSession.CarPlayEndpoint("test", "", 0, 0, peer.port, null, null, null, listOf("127.0.0.1")),
            {},
        ).also {
            assertTrue(it.reconnect())
            it.modes.onChangeModes(take())
        }

    @Test fun newInvitationRestoresNativeTakeWithoutSetupOrRecord() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            try {
                assertTrue(client.modes.screenCanReturnOnInvite)
                assertTrue(client.acquireScreenResource(false, client.connectionGeneration))
                val request = peer.next()!!
                assertEquals("POST", request.method)
                assertEquals("/command", request.path)
                val body = BinaryPlist.parse(request.body) as Map<*, *>
                assertEquals("modesChanged", body["type"])
                val resources = ((body["params"] as Map<*, *>)["resources"] as List<*>).map { it as Map<*, *> }
                assertEquals(1L, resources.single { it["resourceID"] == 1L }["entity"])
                assertTrue(client.modes.phoneOwnsScreen)
                assertNull(peer.next(100))
            } finally {
                client.close()
            }
        }
    }

    @Test fun requestUiCannotCommitBeforeAckOrOverwriteANewerCameraBorrow() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            val executor = Executors.newSingleThreadExecutor()
            peer.reply = {}
            try {
                val claim = executor.submit<Boolean> { client.acquireScreenResource(true, client.connectionGeneration) }
                val pending = peer.next()!!
                assertFalse(client.modes.phoneOwnsScreen)
                // Event-side arbitration must remain available while the control ACK is blocked.
                client.modes.changeResponse(take(3, 1000))
                pending.respond(200)
                assertFalse(claim.get(2, TimeUnit.SECONDS))
                assertFalse(client.modes.phoneOwnsScreen)
                assertFalse(client.modes.screenCanReturnOnInvite)
            } finally {
                client.close()
                executor.shutdownNow()
            }
        }
    }

    @Test fun rejectedClaimLeavesOwnershipAndPausedStreamUnchanged() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            peer.reply = { it.respond(403) }
            try {
                assertFalse(client.acquireScreenResource(false, client.connectionGeneration))
                assertEquals("POST", peer.next()!!.method)
                assertFalse(client.modes.phoneOwnsScreen)
            } finally {
                client.close()
            }
        }
    }

    @Test fun invitationNeverEndsABorrowAndExplicitUiStillRespectsHighConstraints() {
        val modes = ModeArbiter()
        modes.onChangeModes(take(3))
        assertFalse(modes.screenCanReturnOnInvite)
        assertFalse(modes.acquireScreen(false) { error("borrow must not publish") })
        assertTrue(modes.acquireScreen(true) { true })
        modes.onChangeModes(take(3, 1000))
        assertFalse(modes.acquireScreen(true) { error("camera must not publish") })
        modes.onChangeModes(take(1, 1000))
        assertFalse(modes.screenCanReturnOnInvite)
    }

    @Test fun oldConnectionCannotPublishOrCommitAClaim() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer)
            val epoch = client.connectionGeneration
            try {
                client.close()
                assertTrue(client.reconnect())
                assertFalse(client.acquireScreenResource(false, epoch))
                assertNull(peer.next(100))
            } finally {
                client.close()
            }
        }
    }
}
