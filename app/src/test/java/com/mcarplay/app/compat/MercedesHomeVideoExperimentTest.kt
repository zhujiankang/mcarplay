package com.mcarplay.app.compat

import com.mcarplay.app.airplay.ModeArbiter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MercedesHomeVideoExperimentTest {
    @Test fun disabledOrUnmatchedReceiverKeepsExistingPolicy() {
        val modes = ModeArbiter()
        take(modes)
        assertFalse(MercedesHomeVideoExperiment(false, true, modes).retainStream)
        assertFalse(MercedesHomeVideoExperiment(true, false, modes).allowHiddenFrames())
    }

    @Test fun homeTransportPermissionNeverClaimsTheDisplayOrAudio() {
        val modes = ModeArbiter()
        val policy = MercedesHomeVideoExperiment(true, true, modes)
        assertFalse(policy.allowHiddenFrames())
        take(modes)
        assertTrue(policy.allowHiddenFrames())
        assertFalse(modes.phoneOwnsScreen)
        assertTrue(modes.phoneOwnsAudio)
    }

    @Test fun cameraFromCarPlayKeepsTheOriginalPausePolicy() {
        val modes = ModeArbiter()
        val policy = MercedesHomeVideoExperiment(true, true, modes)
        transfer(modes, 3)
        assertTrue(policy.retainStream)
        assertFalse(policy.allowHiddenFrames())
        transfer(modes, 4)
        assertTrue(modes.phoneOwnsScreen)
        assertFalse(policy.allowHiddenFrames())
    }

    @Test fun cameraOverHomePausesUntilEveryNamedBorrowIsReturned() {
        val modes = ModeArbiter()
        val policy = MercedesHomeVideoExperiment(true, true, modes)
        take(modes)
        transfer(modes, 3, "camera")
        transfer(modes, 3, "parking")
        assertFalse(policy.allowHiddenFrames())
        transfer(modes, 4, "camera")
        assertFalse(policy.allowHiddenFrames())
        transfer(modes, 4, "parking")
        assertTrue(policy.allowHiddenFrames())
        assertFalse(modes.phoneOwnsScreen)
    }

    @Test fun stoppedOrReplacedSessionCannotPermitHiddenFrames() {
        val modes = ModeArbiter()
        var current = true
        val policy = MercedesHomeVideoExperiment(true, true, modes) { current }
        take(modes)
        assertTrue(policy.allowHiddenFrames())
        current = false
        assertFalse(policy.retainStream)
        assertFalse(policy.allowHiddenFrames())
    }

    @Test fun userReturnChangesOwnershipOnlyAfterResourcePublicationSucceeds() {
        val modes = ModeArbiter()
        val policy = MercedesHomeVideoExperiment(true, true, modes)
        take(modes)
        assertFalse(modes.acquireScreen(fromRequestUi = true) { false })
        assertFalse(modes.phoneOwnsScreen)
        assertTrue(policy.allowHiddenFrames())
        assertTrue(modes.acquireScreen(fromRequestUi = true) { true })
        assertTrue(modes.phoneOwnsScreen)
        assertFalse(policy.allowHiddenFrames())
    }

    private fun take(modes: ModeArbiter) = transfer(modes, 1)

    private fun transfer(
        modes: ModeArbiter,
        kind: Long,
        borrower: String? = null,
    ) {
        val resource = mutableMapOf<String, Any?>("resourceID" to 1L, "transferType" to kind)
        if (borrower != null) resource["borrowID"] = borrower
        modes.changeResponse(mapOf("resources" to listOf(resource)))
    }
}
