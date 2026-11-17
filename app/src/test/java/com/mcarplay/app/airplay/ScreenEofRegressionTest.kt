package com.mcarplay.app.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ScreenEofRegressionTest {
    @Test fun legacyStopBeforeTeardownCanTerminateTheControlSession() {
        ScreenEofReceiver().use { receiver ->
            val client = receiver.client()
            try {
                assertTrue(client.reconnect())
                val (sender, video) = receiver.sender(client)
                sender.stop() // 0.1.18 permanent-Home order
                assertTrue(video.ended.await(2, TimeUnit.SECONDS))
                assertTrue(receiver.unexpectedEof.await(2, TimeUnit.SECONDS))
                assertFalse(client.teardownScreenStream())
                assertFalse(client.assertModes())
            } finally {
                client.close()
            }
        }
    }
}
