package com.mcarplay.app.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioResourceStreamsTest {
    private class Harness {
        val modes = ModeArbiter()
        val intent = CarPlayAudioIntent()
        val events = mutableListOf<String>()
        var now = 0L
        var current = true
        var startOk = true
        var stopOk = true
        var onStart = {}
        val streams =
            AudioResourceStreams(
                { current },
                modes,
                intent,
                { true },
                { priority, temporary ->
                    events += "acquire:$priority:$temporary"
                    modes.acquireAudio(priority, temporary) { true }
                },
                {
                    events += "release"
                    modes.releaseAudioBorrow { true }
                },
                { owned ->
                    events += if (owned) "start" else "stop"
                    if (owned) {
                        onStart()
                        startOk
                    } else {
                        !stopOk
                    }
                },
                { events += "media:$it" },
                { now },
                {},
            )

        fun take(
            type: Int,
            id: String? = null,
        ) {
            modes.onChangeModes(
                mapOf(
                    "resources" to
                        listOf(
                            mapOf(
                                "resourceID" to 2,
                                "transferType" to type,
                                "borrowID" to id,
                                "takeConstraint" to 500,
                                "borrowConstraint" to 100,
                                "unborrowConstraint" to 1000,
                            ),
                        ),
                ),
            )
        }
    }

    @Test fun promptPrerollKeepsItsBorrowUntilBufferedAudioCanDrain() {
        val h = Harness()
        h.take(1)
        h.streams.reconcile()
        h.intent.prompt("TTS", 1000) // retained prompt duration after delayed SETUP
        h.intent.prompt("TTS", 200) // subsequent packets must not shorten the drain deadline
        h.streams.reconcile()
        h.now = 800
        h.streams.reconcile()
        assertTrue(h.modes.phoneBorrowsAudio)
        h.now = 1751
        h.streams.reconcile()
        assertFalse(h.modes.phoneBorrowsAudio)
    }

    @Test fun explicitPauseDoesNotRestartFromLatePcmAlreadyInFlight() {
        val h = Harness()
        h.intent.observeMedia(true)
        h.streams.reconcile()
        h.intent.userPlayback(false)
        h.streams.reconcile()
        repeat(5) {
            h.intent.observeMedia(true)
            h.streams.reconcile()
        }
        assertFalse(h.intent.media)
        assertEquals(1, h.events.count { it == "start" })
    }

    @Test fun formatInitializationAloneDoesNotStartAudioAndNewPlaybackRequiresPcm() {
        val h = Harness()
        h.intent.beginMediaStream()
        h.streams.reconcile()
        assertFalse(h.events.contains("start"))
        h.intent.observeMedia(true)
        h.streams.reconcile()
        assertEquals(1, h.events.count { it == "start" })
        h.take(1)
        h.streams.reconcile()
        h.intent.observeMedia(false)
        h.intent.beginMediaStream()
        assertFalse(h.intent.media)
        h.intent.observeMedia(true)
        h.now += 2001
        h.streams.reconcile()
        assertEquals(2, h.events.count { it == "start" })
    }

    @Test fun promptBorrowsFmAndStopsOutputBeforeReturningOriginalOwner() {
        val h = Harness()
        h.take(1)
        h.streams.reconcile()
        h.events.clear()
        h.intent.prompt("TTS", h.now)
        h.streams.reconcile()
        assertEquals(listOf("acquire:100:true", "start"), h.events)
        assertTrue(h.modes.phoneBorrowsAudio)
        h.now = 751
        h.streams.reconcile()
        assertEquals(listOf("acquire:100:true", "start", "stop", "release"), h.events)
        assertFalse(h.modes.phoneOwnsAudio)
    }

    @Test fun originalFmSelectionCannotBeUndoneByRepeatedPcmOrOldPlayingState() {
        val h = Harness()
        h.intent.observeMedia(true)
        h.streams.reconcile()
        h.take(1)
        h.streams.reconcile()
        h.intent.observeMedia(false) // pause acknowledgement
        repeat(5) {
            h.intent.observeMedia(true)
            h.streams.reconcile()
        }
        assertEquals(1, h.events.count { it == "start" })
        assertFalse(h.intent.media)
        h.intent.userPlayback(true)
        h.now += 2001
        h.streams.reconcile()
        assertEquals(2, h.events.count { it == "start" })
    }

    @Test fun onlyMusicPausedByBorrowIsResumedAfterLastMatchingLoan() {
        val h = Harness()
        h.intent.observeMedia(true)
        h.streams.reconcile()
        h.take(3, "call")
        h.streams.reconcile()
        h.take(3, "mute")
        h.streams.reconcile()
        h.take(4, "call")
        h.streams.reconcile()
        assertFalse(h.intent.media)
        h.take(4, "mute")
        h.streams.reconcile()
        assertTrue(h.intent.media)
        assertEquals(1, h.events.count { it == "media:RESUME" })
        val idle = Harness()
        idle.take(3, "call")
        idle.streams.reconcile()
        idle.take(4, "call")
        idle.streams.reconcile()
        assertFalse(idle.intent.media)
        assertFalse(idle.events.contains("media:RESUME"))
    }

    @Test fun explicitPauseDuringCarBorrowCancelsAutomaticResume() {
        val h = Harness()
        h.intent.observeMedia(true)
        h.streams.reconcile()
        h.take(3, "call")
        h.streams.reconcile()
        h.intent.userPlayback(false)
        h.take(4, "call")
        h.streams.reconcile()
        assertFalse(h.intent.media)
    }

    @Test fun failedStartsAndRetirementsAreBoundedAndReturnWaitsForRetirement() {
        val h = Harness()
        h.startOk = false
        h.intent.observeMedia(true)
        repeat(20) {
            h.now = it * 100L
            h.streams.reconcile()
        }
        assertEquals(1, h.events.count { it == "start" })
        h.now = 2000
        h.startOk = true
        h.streams.reconcile()
        h.stopOk = false
        h.intent.userPlayback(false)
        h.streams.reconcile()
        repeat(5) {
            h.now += 100
            h.streams.reconcile()
        }
        assertEquals(1, h.events.count { it == "stop" })
        h.now = 4001
        h.stopOk = true
        h.streams.reconcile()
        assertEquals(2, h.events.count { it == "stop" })
    }

    @Test fun carTakeDuringSetupStopsOnlyTheNewAudioAndOldCoordinatorCannotRestartIt() {
        val h = Harness()
        h.onStart = { h.take(1) }
        h.intent.observeMedia(true)
        h.streams.reconcile()
        assertEquals("stop", h.events.last())
        h.streams.close()
        val count = h.events.size
        h.intent.userPlayback(true)
        h.now = 10000
        h.streams.reconcile()
        assertEquals(count, h.events.size)
    }
}
