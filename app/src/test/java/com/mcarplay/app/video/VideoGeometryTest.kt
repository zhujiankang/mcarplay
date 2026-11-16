package com.mcarplay.app.video

import com.mcarplay.app.carlife.mapCarLifeTouch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoGeometryTest {
    @Test
    fun lowerResolutionSpsDoesNotChangeTheAdvertisedCarLifeTouchDomain() {
        assertEquals(
            812 to 575,
            mapCarLifeTouch(
                812,
                575,
                1624,
                1150,
                1624,
                1150,
                aspectFit = true,
                contentWidth = 1920,
                contentHeight = 1080,
            ),
        )
        assertNull(
            mapCarLifeTouch(
                812,
                50,
                1624,
                1150,
                1624,
                1150,
                aspectFit = true,
                contentWidth = 1920,
                contentHeight = 1080,
            ),
        )
    }

    @Test
    fun nativeMercedesCanvasNeedsNoTranscoding() {
        assertFalse(VideoGeometry.aspectMismatch(1624, 1150, 1624, 1150))
        assertEquals(VideoGeometry.Rect(0, 0, 1624, 1150), VideoGeometry.fit(1624, 1150, 1624, 1150))
        assertEquals(812 to 575, mapCarLifeTouch(812, 575, 1624, 1150, 1624, 1150, aspectFit = true))
    }

    @Test
    fun ignoredNativeSizeUsesLetterboxingAndMatchingInverseTouchMapping() {
        assertTrue(VideoGeometry.aspectMismatch(1920, 1080, 1624, 1150))
        val fit = VideoGeometry.fit(1920, 1080, 1624, 1150)
        assertEquals(VideoGeometry.Rect(0, 118, 1624, 914), fit)
        assertNull(mapCarLifeTouch(812, 0, 1624, 1150, 1920, 1080, aspectFit = true))
        assertNull(mapCarLifeTouch(812, 1032, 1624, 1150, 1920, 1080, aspectFit = true))
        assertEquals(0 to 0, mapCarLifeTouch(0, 118, 1624, 1150, 1920, 1080, aspectFit = true))
        assertEquals(960 to 540, mapCarLifeTouch(812, 575, 1624, 1150, 1920, 1080, aspectFit = true))
    }

    @Test
    fun lowerResolutionWithTheSameAspectDoesNotForceTranscoding() {
        assertFalse(VideoGeometry.aspectMismatch(1280, 720, 1920, 1080))
        assertFalse(VideoGeometry.aspectMismatch(812, 574, 1624, 1150))
    }

    @Test
    fun portraitInputFitsWithSideBarsInsteadOfStretching() {
        val rect = VideoGeometry.fit(720, 1280, 1920, 1080)
        assertEquals(VideoGeometry.Rect(656, 0, 608, 1080), rect)
        assertNull(mapCarLifeTouch(0, 540, 1920, 1080, 720, 1280, aspectFit = true))
    }
}
