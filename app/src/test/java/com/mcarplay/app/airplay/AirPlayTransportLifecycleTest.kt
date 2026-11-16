package com.mcarplay.app.airplay

import com.mcarplay.app.SessionController
import com.mcarplay.app.compat.ReceiverCompatibility
import com.mcarplay.app.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class AirPlayTransportLifecycleTest {
    private fun client(port: Int = 7000) =
        AirPlayClient(
            Iap2SenderSession.CarPlayEndpoint("test", "", 0, 0, port, null, null, null, listOf("127.0.0.1")),
            log = {},
        )

    private fun field(name: String) = AirPlayClient::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun starting() =
        SessionController::class.java
            .getDeclaredField("sessionStarting")
            .apply { isAccessible = true }
            .get(SessionController) as AtomicBoolean

    @Test
    fun earlyAudioUiRequestCannotActivateAnUnrecordedConnection() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer.port)
            val previousClient = SessionController.currentClient
            val previousSecret = SessionController.currentSharedSecret
            try {
                assertTrue(client.reconnect())
                SessionController.currentClient = client
                SessionController.currentSharedSecret = ByteArray(32)
                SessionController.maybeStartAudioNow(android.app.Application())
                assertEquals(null, peer.next(150))
                assertFalse(client.sessionActive)
            } finally {
                client.close()
                SessionController.currentClient = previousClient
                SessionController.currentSharedSecret = previousSecret
            }
        }
    }

    @Test
    fun terminalCleanupClosesTheOwnedControlConnectionAndStopsFeedback() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client = client(peer.port)
            val discovery = SessionController::class.java.getDeclaredField("discoverySuppressed").apply { isAccessible = true }
            val previousDiscovery = discovery.getBoolean(SessionController)
            val previousClient = SessionController.currentClient
            val previousCancelled = SessionController.connectCancelled
            val previousStarting = starting().getAndSet(false)
            discovery.setBoolean(SessionController, false)
            try {
                val generation = SessionController.beginEndpointAttempt(null)!!
                assertTrue(client.reconnect())
                SessionController.currentClient = client
                client.startFeedbackLoop()
                assertEquals("/feedback", peer.next()!!.path)
                val epoch = client.connectionGeneration
                val end =
                    SessionController::class.java.declaredMethods
                        .single { it.name == "endClientSession" && it.parameterCount == 5 }
                        .apply { isAccessible = true }
                end.invoke(SessionController, client, generation, "1234abcd", "loopback test", null)
                assertEquals(null, SessionController.currentClient)
                assertFalse(client.isConnectionCurrent(epoch))
                assertTrue(SessionController.connectCancelled)
                assertFalse(starting().get())
                assertEquals(null, peer.next(1_200))
            } finally {
                client.close()
                discovery.setBoolean(SessionController, previousDiscovery)
                SessionController.currentClient = previousClient
                SessionController.connectCancelled = previousCancelled
                starting().set(previousStarting)
            }
        }
    }

    @Test
    fun queuedTransportCallbackLosesOwnershipWhenTheEventSocketIsReplaced() {
        val client = client()
        val event = Socket()
        val replacement = Socket()
        field("socket").set(client, Socket())
        field("sessionSetupAccepted").setBoolean(client, true)
        field("eventCommandSocket").set(client, event)
        var ownsTransport: (() -> Boolean)? = null
        client.onTransportClosed = { ownsTransport = it }
        try {
            client.notifyTransportClosed(client.connectionGeneration, event)
            assertTrue(ownsTransport!!.invoke())
            field("eventCommandSocket").set(client, replacement)
            assertFalse(ownsTransport!!.invoke())
        } finally {
            client.close()
            event.close()
        }
    }

    @Test
    fun newEndpointClearsRetiredCancellationAndRejectsOldCallbacks() {
        val discovery = SessionController::class.java.getDeclaredField("discoverySuppressed").apply { isAccessible = true }
        val previousSuppressed = discovery.getBoolean(SessionController)
        val previousCancelled = SessionController.connectCancelled
        val previousStarting = starting().getAndSet(false)
        val previousClient = SessionController.currentClient
        val client = client()
        discovery.setBoolean(SessionController, false)
        SessionController.connectCancelled = true
        try {
            val grace =
                SessionController::class.java
                    .getDeclaredField("bringupGrace")
                    .apply { isAccessible = true }
                    .get(SessionController) as com.mcarplay.app.bt.BluetoothBringupGrace
            grace.cancel()
            assertTrue(grace.begin() != null)
            val old = SessionController.beginEndpointAttempt(null)!!
            assertTrue("new endpoint must invalidate the preceding disconnect timer", grace.begin() != null)
            grace.cancel()
            assertFalse(SessionController.connectCancelled)
            assertEquals(null, SessionController.beginEndpointAttempt(null))
            starting().set(false)
            SessionController.connectCancelled = true
            val fresh = SessionController.beginEndpointAttempt(null)!!
            assertTrue(fresh > old)
            assertFalse(SessionController.connectCancelled)
            SessionController.currentClient = client
            val end =
                SessionController::class.java.declaredMethods
                    .single { it.name == "endClientSession" && it.parameterCount == 5 }
                    .apply { isAccessible = true }
            end.invoke(SessionController, client, old, "1234abcd", "stale callback", null)
            assertTrue(SessionController.currentClient === client)
            assertTrue(starting().get())
            assertFalse(SessionController.connectCancelled)
        } finally {
            client.close()
            SessionController.currentClient = previousClient
            discovery.setBoolean(SessionController, previousSuppressed)
            SessionController.connectCancelled = previousCancelled
            starting().set(previousStarting)
        }
    }

    @Test
    fun manualStopSuppressionRejectsLateEndpointAdmission() {
        val discovery = SessionController::class.java.getDeclaredField("discoverySuppressed").apply { isAccessible = true }
        val previous = discovery.getBoolean(SessionController)
        val previousStarting = starting().getAndSet(false)
        discovery.setBoolean(SessionController, true)
        try {
            assertEquals(null, SessionController.beginEndpointAttempt(null))
            assertFalse(starting().get())
        } finally {
            discovery.setBoolean(SessionController, previous)
            starting().set(previousStarting)
        }
    }

    @Test
    fun confirmedSetupProtectsGenericReceiverDuringRecordHandoffWindow() {
        val client = client()
        val previous = SessionController.currentClient
        val previousCancelled = SessionController.connectCancelled
        val previousStarting = starting().getAndSet(true)
        field("socket").set(client, Socket())
        field("sessionSetupAccepted").setBoolean(client, true)
        SessionController.currentClient = client
        SessionController.connectCancelled = false
        try {
            assertFalse(client.sessionActive)
            assertFalse(client.networkHandoffReady)
            assertTrue(client.networkSessionEstablished)
            assertEquals(ReceiverCompatibility.BluetoothLossAction.KEEP_NETWORK_SESSION, SessionController.bluetoothDisconnectAction())
            SessionController.onSenderLinkClosed { true }
            assertTrue(starting().get())
            assertFalse(SessionController.connectCancelled)
        } finally {
            client.close()
            SessionController.currentClient = previous
            SessionController.connectCancelled = previousCancelled
            starting().set(previousStarting)
        }
    }

    @Test
    fun staleBluetoothOwnerCannotCancelNewAttempt() {
        val previous = SessionController.connectCancelled
        val previousStarting = starting().getAndSet(true)
        SessionController.connectCancelled = false
        try {
            SessionController.onSenderLinkClosed { false }
            assertTrue(starting().get())
            assertFalse(SessionController.connectCancelled)
        } finally {
            SessionController.connectCancelled = previous
            starting().set(previousStarting)
        }
    }

    @Test
    fun terminalNotificationIsOncePerCurrentEventSocketAndIgnoresRetiredEpochs() {
        val client = client()
        val current = Socket()
        val retired = Socket()
        var count = 0
        field("socket").set(client, Socket())
        field("sessionSetupAccepted").setBoolean(client, true)
        field("eventCommandSocket").set(client, current)
        client.onTransportClosed = { count++ }
        try {
            val epoch = client.connectionGeneration
            client.notifyTransportClosed(epoch - 1, current)
            client.notifyTransportClosed(epoch, retired)
            assertEquals(0, count)
            client.notifyTransportClosed(epoch, current)
            client.notifyTransportClosed(epoch, current)
            assertEquals(1, count)
            client.close()
            client.notifyTransportClosed(epoch, current)
            assertEquals(1, count)
            assertFalse(client.networkSessionEstablished)
        } finally {
            client.close()
            retired.close()
        }
    }

    @Test
    fun intentionalStopDoesNotReportAnotherTransportFailure() {
        val client = client()
        val event = Socket()
        field("socket").set(client, Socket())
        field("sessionSetupAccepted").setBoolean(client, true)
        field("eventCommandSocket").set(client, event)
        var count = 0
        client.onTransportClosed = { count++ }
        try {
            client.beginOrderlyStop()
            client.notifyTransportClosed(client.connectionGeneration, event)
            assertEquals(0, count)
        } finally {
            client.close()
        }
    }
}
