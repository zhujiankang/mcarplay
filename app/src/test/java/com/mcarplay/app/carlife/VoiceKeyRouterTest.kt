package com.mcarplay.app.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceKeyRouterTest {
    @Test fun canonicalSiriActionFromBinaryPlistReachesTheCarLifeVoiceKey() {
        var now = 1000L
        val keys = mutableListOf<Int>()
        val router = VoiceKeyRouter({ now }, keys::add)
        for (action in listOf(1L, 2L, 3L)) {
            val encoded =
                com.mcarplay.app.airplay.BinaryPlist.encode(
                    mapOf(
                        "type" to "requestSiri",
                        "params" to mapOf("siriAction" to action),
                    ),
                )
            val command =
                com.mcarplay.app.airplay.BinaryPlist
                    .parse(encoded) as Map<*, *>
            val parsed = VoiceKeyRouter.actionFrom(command)
            assertEquals(action.toInt(), parsed)
            router.siri(parsed!!, mercedesPrewarmRelease = true)
            now += 100
        }
        assertEquals(listOf(0x21), keys)
    }

    @Test fun legacyNumericAndStringActionsRemainCompatibleAndCanonicalFieldWins() {
        assertEquals(2, VoiceKeyRouter.actionFrom(mapOf("type" to "requestSiri", "params" to mapOf("action" to 2))))
        assertEquals(3, VoiceKeyRouter.actionFrom(mapOf("type" to "requestSiri", "siriAction" to " 3 ")))
        assertEquals(1, VoiceKeyRouter.actionFrom(mapOf("type" to "requestSiri", "action" to 1L)))
        assertEquals(2, VoiceKeyRouter.actionFrom(mapOf("type" to "requestSiri", "params" to mapOf("siriAction" to "2", "action" to 3))))
    }

    @Test fun malformedActionsNeverGenerateAKeyAndPrewarmAloneIsStillNotAButtonPress() {
        for (raw in listOf(null, true, 0, 4, -1, 2.5, Double.NaN, "2.5", "garbage", mapOf("value" to 2))) {
            assertNull(VoiceKeyRouter.actionFrom(mapOf("type" to "requestSiri", "params" to mapOf("siriAction" to raw))))
        }
        assertNull(VoiceKeyRouter.actionFrom(mapOf("type" to "changeModes", "params" to mapOf("siriAction" to 2))))
        assertNull(VoiceKeyRouter.actionFrom(mapOf("type" to "requestSiri", "params" to mapOf("siriAction" to 4, "action" to 2))))
        val keys = mutableListOf<Int>()
        val router = VoiceKeyRouter({ 1000L }, keys::add)
        router.siri(1, true)
        assertEquals(emptyList<Int>(), keys)
    }

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
