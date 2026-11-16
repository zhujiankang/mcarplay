package com.mcarplay.app.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLifeMediaParserTest {
    private fun song(name: String = "测试歌曲"): ByteArray =
        CarLifeProtocol.pbString(1, "player.source") +
            CarLifeProtocol.pbString(2, name) + CarLifeProtocol.pbString(3, "歌手") + CarLifeProtocol.pbString(4, "专辑") +
            CarLifeProtocol.pbBytes(5, byteArrayOf(1, 2, 3)) + CarLifeProtocol.pbInt(6, 210000) +
            CarLifeProtocol.pbInt(7, 0) + CarLifeProtocol.pbString(8, "song-id") + CarLifeProtocol.pbInt(9, 0)

    @Test fun xiaomiDirectMessageMatchesTheObservedWireShape() {
        val info = CarLifeMediaParser.parse(song()).single()
        assertEquals("player.source", info.source)
        assertEquals("测试歌曲", info.song)
        assertEquals("歌手", info.artist)
        assertEquals("专辑", info.album)
        assertEquals(210000L, info.durationMs)
        assertArrayEquals(byteArrayOf(1, 2, 3), info.artwork)
        assertEquals("song-id", info.songId)
    }

    @Test fun listWrapperIsAlsoAcceptedAndTruncatedListsAreNotPartiallyPublished() {
        val list = CarLifeProtocol.pbInt(1, 2) + CarLifeProtocol.pbBytes(2, song("first")) + CarLifeProtocol.pbBytes(2, song("second"))
        assertEquals(listOf("first", "second"), CarLifeMediaParser.parse(list).map { it.song })
        assertTrue(CarLifeMediaParser.parse(list.copyOf(list.size - 1)).isEmpty())
        assertTrue(CarLifeMediaParser.parse(CarLifeProtocol.pbInt(1, 17)).isEmpty())
    }

    @Test fun malformedOversizedDuplicateAndInvalidUtf8DataNeverEscapesTheCommandReader() {
        assertTrue(CarLifeMediaParser.parse(ByteArray(1_048_577)).isEmpty())
        assertTrue(CarLifeMediaParser.parse(song() + CarLifeProtocol.pbString(2, "duplicate")).isEmpty())
        assertTrue(CarLifeMediaParser.parse(song("x".repeat(4097))).isEmpty())
        val invalid = CarLifeProtocol.pbString(1, "source") + CarLifeProtocol.pbBytes(2, byteArrayOf(0xC3.toByte()))
        assertTrue(CarLifeMediaParser.parse(invalid).isEmpty())
        assertTrue(CarLifeMediaParser.parse(byteArrayOf(0x0A, 127)).isEmpty())
        assertTrue(CarLifeMediaParser.parse(ByteArray(0)).isEmpty())
    }
}
