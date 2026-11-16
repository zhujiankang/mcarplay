package com.mcarplay.app.airplay

import android.app.Application
import com.mcarplay.app.SessionController
import com.mcarplay.app.bt.Iap2BluetoothClient
import com.mcarplay.app.diagnostics.ConnectionDiagnostics
import com.mcarplay.app.diagnostics.ConnectionStage
import com.mcarplay.app.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class IncomingBluetoothRecoveryTest {
    @Test
    fun mediaFailureCanAcceptANewInboundWithoutAllowingOldTerminalCallbacks() =
        fixture {
            AirPlayRecordLifecycleTest.Peer().use { peer ->
                val old = client(peer.port)
                val replacement = client(peer.port)
                try {
                    val generation = SessionController.beginEndpointAttempt(null)!!
                    assertTrue(old.reconnect())
                    SessionController.currentClient = old
                    ConnectionDiagnostics.begin("test_media_session")
                    end(old, generation)
                    val endedAttempt = ConnectionDiagnostics.timeline.snapshot()!!.id
                    assertTrue(SessionController.connectCancelled)

                    assertTrue(SessionController.incomingBluetoothSession(Application()))
                    assertFalse(SessionController.connectCancelled)
                    val fresh = ConnectionDiagnostics.timeline.snapshot()!!
                    assertNotEquals(endedAttempt, fresh.id)
                    assertEquals(ConnectionStage.BLUETOOTH, fresh.stage)
                    assertTrue(SessionController.incomingBluetoothSession(Application()))
                    assertEquals(fresh.id, ConnectionDiagnostics.timeline.snapshot()!!.id)

                    SessionController.currentClient = replacement
                    end(old, generation)
                    assertTrue(SessionController.currentClient === replacement)
                    assertFalse(SessionController.connectCancelled)
                } finally {
                    old.close()
                    replacement.close()
                }
            }
        }

    @Test
    fun manualStopStillRejectsIncomingAndPreservesCancellation() =
        fixture {
            field("discoverySuppressed").setBoolean(SessionController, true)
            SessionController.connectCancelled = true
            assertFalse(SessionController.incomingBluetoothSession(Application()))
            assertTrue(SessionController.connectCancelled)
        }

    @Test
    fun pendingDisconnectCannotBeOvertakenByNewIncoming() =
        fixture {
            field("disconnectCompletion").set(SessionController, CountDownLatch(1))
            SessionController.connectCancelled = true
            assertFalse(SessionController.incomingBluetoothSession(Application()))
            assertTrue(SessionController.connectCancelled)
        }

    @Test
    fun retiredSessionWithoutListeningTransportCannotResurrect() =
        fixture {
            field("bluetoothClient").set(SessionController, null)
            field("bluetoothServer").set(SessionController, null)
            SessionController.connectCancelled = true
            assertFalse(SessionController.incomingBluetoothSession(Application()))
            assertTrue(SessionController.connectCancelled)
        }

    @Test
    fun newIncomingCannotClearAnInProgressAttemptCancellation() =
        fixture {
            starting().set(true)
            SessionController.connectCancelled = true
            assertFalse(SessionController.incomingBluetoothSession(Application()))
            assertTrue(SessionController.connectCancelled)
        }

    private fun fixture(run: () -> Unit) {
        val names = listOf("bluetoothClient", "bluetoothServer", "discoverySuppressed", "discoveryOnly", "disconnectCompletion")
        val previous = names.associateWith { field(it).get(SessionController) }
        val oldClient = SessionController.currentClient
        val oldCancelled = SessionController.connectCancelled
        val oldStarting = starting().getAndSet(false)
        val bt = Iap2BluetoothClient(Application(), log = {})
        Iap2BluetoothClient::class.java
            .getDeclaredField("running")
            .apply { isAccessible = true }
            .setBoolean(bt, true)
        field("bluetoothClient").set(SessionController, bt)
        field("bluetoothServer").set(SessionController, null)
        field("discoverySuppressed").setBoolean(SessionController, false)
        field("discoveryOnly").setBoolean(SessionController, false)
        field("disconnectCompletion").set(SessionController, null)
        SessionController.currentClient = null
        try {
            run()
        } finally {
            names.forEach { field(it).set(SessionController, previous[it]) }
            SessionController.currentClient = oldClient
            SessionController.connectCancelled = oldCancelled
            starting().set(oldStarting)
        }
    }

    private fun field(name: String) = SessionController::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun starting() = field("sessionStarting").get(SessionController) as AtomicBoolean

    private fun client(port: Int) =
        AirPlayClient(
            Iap2SenderSession.CarPlayEndpoint("test", "", 0, 0, port, null, null, null, listOf("127.0.0.1")),
            log = {},
        )

    private fun end(
        client: AirPlayClient,
        generation: Long,
    ) {
        SessionController::class.java.declaredMethods
            .single { it.name == "endClientSession" && it.parameterCount == 5 }
            .apply { isAccessible = true }
            .invoke(SessionController, client, generation, "test0001", "test_media_end", null)
    }
}
