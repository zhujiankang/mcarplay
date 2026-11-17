package com.mcarplay.app.airplay

import com.mcarplay.app.SessionController
import com.mcarplay.app.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Exercise the actual controller callback with no installed video sender after native Home. */
class MercedesScreenInviteTest {
    @Test fun invitationRestoresRetiredScreenButCannotOverrideCameraLoan() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client =
                AirPlayClient(
                    Iap2SenderSession.CarPlayEndpoint("test", "", 0, 0, peer.port, null, null, null, listOf("127.0.0.1")),
                    {},
                )

            fun field(name: String) = SessionController::class.java.getDeclaredField(name).apply { isAccessible = true }
            val previousClient = SessionController.currentClient
            val previousCancel = SessionController.connectCancelled
            val previousSender = SessionController.videoSender
            val previousResources = field("screenResourceStreams").get(SessionController)
            val executor = field("controlActions").get(SessionController) as ExecutorService

            fun invite() {
                SessionController::class.java
                    .getDeclaredMethod("onCtrlInvite")
                    .apply { isAccessible = true }
                    .invoke(SessionController)
                executor.submit {}.get(3, TimeUnit.SECONDS)
            }
            try {
                assertTrue(client.reconnect())
                AirPlayClient::class.java.getDeclaredField("negotiatedInfo").apply { isAccessible = true }.set(
                    client,
                    mapOf("manufacturer" to "Mercedes-Benz", "model" to "MB_D-M-5"),
                )
                SessionController.currentClient = client
                SessionController.connectCancelled = false
                SessionController.videoSender = null
                var restores = 0
                val streams =
                    ScreenResourceStreams(
                        { true },
                        { client.modes.phoneOwnsScreen },
                        { true },
                        {
                            restores++
                            ScreenResourceStreams.RestoreResult.RESTORED
                        },
                        { error(it) },
                    )
                field("screenResourceStreams").set(SessionController, streams)
                client.modes.onUserReturnedToCar()
                assertTrue(streams.reconcile())
                assertFalse(streams.attached)
                invite()
                assertEquals("POST", peer.next()!!.method)
                assertTrue(streams.attached)
                assertEquals(1, restores)
                client.modes.changeResponse(
                    mapOf(
                        "resources" to
                            listOf(
                                mapOf("resourceID" to 1L, "transferType" to 3L, "unborrowConstraint" to 1000L),
                            ),
                    ),
                )
                assertTrue(streams.reconcile())
                invite()
                assertNull(peer.next(100))
                assertFalse(client.modes.phoneOwnsScreen)
                assertEquals(1, restores)
            } finally {
                executor.submit {}.get(3, TimeUnit.SECONDS)
                field("screenResourceStreams").set(SessionController, previousResources)
                SessionController.currentClient = previousClient
                SessionController.connectCancelled = previousCancel
                SessionController.videoSender = previousSender
                client.close()
            }
        }
    }
}
