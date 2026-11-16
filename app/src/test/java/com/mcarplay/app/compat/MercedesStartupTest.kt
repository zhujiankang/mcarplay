package com.mcarplay.app.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MercedesStartupTest {
    @Test
    fun unverifiedIdentityCannotChangeAnotherReceiversRecordSemantics() {
        for ((manufacturer, model) in listOf(null to null, "Other" to "MB_D-M-5", "Mercedes-Benz" to "Other")) {
            for (mode in MercedesStartup.Mode.entries) {
                val policy = MercedesStartup.resolve(mode, manufacturer, model)
                assertEquals(MercedesStartup.Mode.ORIGINAL, policy.mode)
                assertFalse(policy.singleRecord)
                assertFalse(policy.audioFirst)
            }
        }
    }

    @Test
    fun observedIdentityAllowsControlledStartupComparisons() {
        val single = MercedesStartup.resolve(MercedesStartup.Mode.SINGLE_RECORD, " Mercedes-Benz ", "mb_d-m-5")
        assertTrue(single.singleRecord)
        assertFalse(single.audioFirst)
        val audio = MercedesStartup.resolve(MercedesStartup.Mode.AUDIO_FIRST, "Mercedes-Benz", "MB_D-M-5")
        assertTrue(audio.singleRecord)
        assertTrue(audio.audioFirst)
        val original = MercedesStartup.resolve(MercedesStartup.Mode.ORIGINAL, "Mercedes-Benz", "MB_D-M-5")
        assertFalse(original.singleRecord)
        assertFalse(original.audioFirst)
    }
}
