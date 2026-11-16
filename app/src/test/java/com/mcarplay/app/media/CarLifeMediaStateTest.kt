package com.mcarplay.app.media

import com.mcarplay.app.carlife.CarLifeMediaInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarLifeMediaStateTest {
    @Test fun progressIsBoundedAndNeverCarriesAcrossTracks() {
        val state = CarLifeMediaState()
        state.progress(9000, 0)
        assertEquals(0L, state.positionAt(0, true))
        state.update(song, 1000)
        state.progress(5000, 1500)
        assertEquals(5500L, state.positionAt(2000, true))
        assertEquals(5000L, state.positionAt(2000, false))
        assertEquals(8000L, state.positionAt(50000, true))
        state.update(song.copy(songId = "different"), 51000)
        assertEquals(0L, state.positionAt(51001, true))
    }

    @Test fun uncorrelatedLyricsCannotCrossSongsOrApplicationsAndPrefixArtistIsNotEnough() {
        val state = CarLifeMediaState()
        state.update(song, 1000)
        state.update(lyric, 1500)
        state.update(lyric.copy(source = "another-player"), 2000)
        assertNull(state.lyricTitle(2000, true, null, null))
        state.update(lyric.copy(songId = "another-song"), 2500)
        assertNull(state.lyricTitle(2500, true, null, null))
        val first = CarLifeMediaState()
        first.update(song.copy(artist = "Album-RealArtist"), 0)
        assertEquals("Album-RealArtist", first.song!!.artist)
    }

    private val song = CarLifeMediaInfo("player", "Song", "Artist", "Album", byteArrayOf(1, 2, 3), 120000, "id1")
    private val lyric = song.copy(song = "A lyric line", artist = "Album-Artist", artwork = null)

    @Test fun lyricCompatibilityIsOptInFreshAndNeverOverwritesTheRealSongOrArtwork() {
        val state = CarLifeMediaState()
        state.update(song, 1000)
        state.update(lyric, 2000)
        assertNull(state.lyricTitle(2500, false, null, null))
        assertEquals("A lyric line", state.lyricTitle(2500, true, "Song", "Artist"))
        assertEquals("Song", state.song!!.song)
        assertArrayEquals(song.artwork, state.song!!.artwork)
        assertNull(state.lyricTitle(7001, true, null, null))
        assertNull(state.lyricTitle(1999, true, null, null))
        assertNull(state.lyricTitle(2500, true, "Different song", "Artist"))
        assertNull(state.lyricTitle(2500, true, "Song", "Another artist"))
    }

    @Test fun switchingTracksClearsOldLyricsAndArtworkButLateArtForTheSameTrackCanFillIn() {
        val state = CarLifeMediaState()
        state.update(song, 1000)
        state.update(lyric, 2000)
        val next = song.copy(song = "Next", songId = "id2", artwork = null)
        state.update(next, 3000)
        assertNull(state.song!!.artwork)
        assertNull(state.lyricTitle(3000, true, null, null))
        state.update(next.copy(artwork = byteArrayOf(4)), 3500)
        state.update(next, 4000)
        assertArrayEquals(byteArrayOf(4), state.song!!.artwork)
    }
}
