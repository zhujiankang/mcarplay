package com.mcarplay.app.airplay

import com.mcarplay.app.audio.MicrophoneProfile
import com.mcarplay.app.compat.MercedesStartup
import com.mcarplay.app.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneStreamTest {
    @Test fun microphoneUsesDeclaredInputAndScopedTeardownWithoutRepeatingMercedesRecord() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client =
                AirPlayClient(
                    Iap2SenderSession.CarPlayEndpoint("test", "", 0, 0, peer.port, null, null, null, listOf("127.0.0.1")),
                    {},
                )
            try {
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
                val epoch = client.connectionGeneration
                assertTrue(client.setupMicrophone(MicrophoneProfile(0x40, 24000), 123, 23456, epoch))
                val setup = peer.next()!!
                assertEquals("SETUP", setup.method)
                val streams = (BinaryPlist.parse(setup.body) as Map<*, *>)["streams"] as List<*>
                val input = streams.single() as Map<*, *>
                assertEquals(100L, input["type"])
                assertEquals(true, input["input"])
                assertEquals(23456L, input["dataPort"])
                assertEquals(0x40L, input["audioFormat"])
                assertTrue(client.record())
                assertNull(peer.next(100))
                assertTrue(client.teardownMicrophone(123, epoch))
                val teardown = peer.next()!!
                val stop = (BinaryPlist.parse(teardown.body) as Map<*, *>)["streams"] as List<*>
                assertEquals(mapOf("type" to 100L, "streamConnectionID" to 123L), stop.single())
                client.close()
                assertTrue(client.reconnect())
                assertFalse(client.setupMicrophone(MicrophoneProfile(0x40, 24000), 123, 23456, epoch))
                assertFalse(client.teardownMicrophone(123, epoch))
                assertFalse(client.record(expectedEpoch = epoch))
                assertFalse(client.setSpeechRecognitionState(true, epoch))
                assertNull(peer.next(100))
            } finally {
                client.close()
            }
        }
    }

    @Test fun speechStateNeverChangesScreenOrAudioOwnership() {
        val modes = ModeArbiter()
        val before = modes.resourcesPayload()
        modes.setSpeechRecognition(true)
        val speech = (modes.parameters()["appStates"] as List<*>).map { it as Map<*, *> }.single { it["appStateID"] == 1L }
        assertEquals(1L, speech["entity"])
        assertEquals(2L, speech["speechMode"])
        assertEquals(before, modes.resourcesPayload())
        modes.setSpeechRecognition(false)
        val ended = (modes.parameters()["appStates"] as List<*>).map { it as Map<*, *> }.single { it["appStateID"] == 1L }
        assertEquals(0L, ended["entity"])
        assertEquals(-1L, ended["speechMode"])
    }
}
