package com.mcarplay.app.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMetadataPrivacyTest {
    @Test fun diagnosticProducerDoesNotRecordSongOrArtistText() {
        val old = MediaSessionsState.nowPlaying
        val sink = MediaSessionsState.logSink
        val logs = mutableListOf<String>()
        try {
            MediaSessionsState.logSink = logs::add
            MediaSessionsState.publish(MediaSessionsState.NowPlaying("PRIVATE-SONG", "PRIVATE-ARTIST", true))
            assertTrue(logs.any { it.contains("playing=true") })
            assertFalse(logs.any { it.contains("PRIVATE-") })
        } finally {
            MediaSessionsState.logSink = null
            MediaSessionsState.publish(old)
            MediaSessionsState.logSink = sink
        }
    }
}
