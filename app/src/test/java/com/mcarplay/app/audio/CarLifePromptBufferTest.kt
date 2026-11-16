package com.mcarplay.app.audio

import com.mcarplay.app.carlife.CarLifeAudioEvent
import com.mcarplay.app.carlife.CarLifeChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLifePromptBufferTest {
    @Test fun stalePromptIsNotPlayedAfterLongDeniedBorrowOrNextSession() {
        val buffer = CarLifePromptBuffer()
        val pcm = CarLifeAudioEvent.Pcm(ByteArray(640), 16000, 1)
        buffer.offer(CarLifeChannel.TTS, pcm, 0)
        assertTrue(buffer.drain(1500).isEmpty())
        buffer.offer(CarLifeChannel.TTS, pcm, 2000)
        buffer.clear()
        assertTrue(buffer.drain(2001).isEmpty())
    }

    @Test fun boundedPromptPrerollExcludesMusicAndKeepsOriginalPcmFormat() {
        val buffer = CarLifePromptBuffer()
        val pcm = CarLifeAudioEvent.Pcm(ByteArray(16384), 24000, 1)
        repeat(40) { buffer.offer(CarLifeChannel.TTS, pcm, 0) }
        buffer.offer(CarLifeChannel.MEDIA, pcm, 0)
        val frames = buffer.drain(100)
        assertEquals(16, frames.size)
        assertTrue(frames.all { it.first == CarLifeChannel.TTS && it.second.sampleRate == 24000 })
        assertTrue(buffer.drain(100).isEmpty())
    }
}
