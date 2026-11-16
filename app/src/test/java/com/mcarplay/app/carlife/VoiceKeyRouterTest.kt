package com.mcarplay.app.carlife

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceKeyRouterTest {
    @Test fun prewarmAloneNeverStartsAndShortPressDoesNotStopRecognition() {
        var now = 1000L
        val keys = mutableListOf<Int>()
        val router = VoiceKeyRouter({ now }, keys::add)
        router.siri(1)
        assertEquals(emptyList<Int>(), keys)
        router.siri(2)
        repeat(10) { router.siri(2) }
        now += 300
        router.siri(3)
        router.siri(3)
        assertEquals(listOf(0x21), keys)
    }

    @Test fun longPressStopsAndStaleOrDuplicateReleasesDoNothing() {
        var now = 1000L
        val keys = mutableListOf<Int>()
        val router = VoiceKeyRouter({ now }, keys::add)
        router.hid(true)
        now += 1700
        router.hid(false)
        router.hid(false)
        now += 1000
        router.hid(true)
        now += 16000
        router.hid(false)
        assertEquals(listOf(0x21, 0x22, 0x21), keys)
    }

    @Test fun mercedesFallbackRequiresFreshPrewarmReleaseAndResetClearsIt() {
        var now = 1000L
        val keys = mutableListOf<Int>()
        val router = VoiceKeyRouter({ now }, keys::add)
        router.siri(1)
        router.siri(3, false)
        router.siri(1)
        now += 200
        router.siri(3, true)
        router.siri(3, true)
        now += 1000
        router.siri(1)
        router.reset()
        router.siri(3, true)
        now += 2000
        router.siri(1)
        now += 1501
        router.siri(3, true)
        assertEquals(listOf(0x21), keys)
    }
}
