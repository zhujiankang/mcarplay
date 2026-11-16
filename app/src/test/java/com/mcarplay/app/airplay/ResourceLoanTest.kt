package com.mcarplay.app.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceLoanTest {
    private fun change(
        modes: ModeArbiter,
        type: Int,
        id: String? = null,
        constraint: Int = 1000,
        resource: Long = 1,
    ) {
        modes.onChangeModes(
            mapOf(
                "resources" to
                    listOf(
                        mapOf(
                            "resourceID" to resource,
                            "transferType" to type,
                            "borrowID" to id,
                            "takeConstraint" to 500,
                            "unborrowConstraint" to constraint,
                        ),
                    ),
            ),
        )
    }

    @Test fun temporaryBorrowAndPermanentHomeTakeHaveDifferentRetentionLifecycles() {
        val modes = ModeArbiter()
        assertTrue(modes.screenPermanentlyOwnedByPhone)
        change(modes, 3, "camera")
        assertTrue(modes.screenPermanentlyOwnedByPhone)
        change(modes, 1)
        assertFalse(modes.screenPermanentlyOwnedByPhone)
        change(modes, 2)
        assertTrue(modes.screenPermanentlyOwnedByPhone)
        assertFalse(modes.phoneOwnsScreen) // camera loan still exists after permanent ownership returns
        change(modes, 4, "camera")
        assertTrue(modes.phoneOwnsScreen)
    }

    @Test fun repeatedBorrowCanTightenConstraintsWithoutAddingAnotherLoan() {
        val modes = ModeArbiter()
        change(modes, 3, "camera", 100)
        change(modes, 3, "camera", 1000)
        assertFalse(modes.acquireScreen(true) { error("tightened constraint must win") })
        change(modes, 4, "camera")
        assertTrue(modes.phoneOwnsScreen)
    }

    @Test fun overlappingCameraLoansAreReleasedByIdAndUnknownReturnDoesNothing() {
        val modes = ModeArbiter()
        change(modes, 3, "camera")
        change(modes, 3, "park")
        change(modes, 4, "unknown")
        assertFalse(modes.phoneOwnsScreen)
        change(modes, 4, "camera")
        assertFalse(modes.phoneOwnsScreen)
        assertFalse(modes.acquireScreen(true) { error("remaining loan must block") })
        change(modes, 4, "park")
        assertTrue(modes.phoneOwnsScreen)
    }

    @Test fun repeatedNamedBorrowIsIdempotentAndAnonymousBorrowIsAStack() {
        val modes = ModeArbiter()
        change(modes, 3, "camera")
        change(modes, 3, "camera")
        change(modes, 4, "camera")
        assertTrue(modes.phoneOwnsScreen)
        change(modes, 3)
        change(modes, 3)
        change(modes, 4)
        assertFalse(modes.phoneOwnsScreen)
        change(modes, 4)
        assertTrue(modes.phoneOwnsScreen)
    }

    @Test fun permanentTakeDuringBorrowDoesNotEraseOutstandingCameraLoan() {
        val modes = ModeArbiter()
        change(modes, 3, "camera")
        change(modes, 1)
        assertFalse(modes.acquireScreen(true) { error("camera still active") })
        change(modes, 4, "camera")
        assertFalse(modes.phoneOwnsScreen)
        assertTrue(modes.acquireScreen(true) { true })
    }

    @Test fun navigationBorrowKeepsOriginalOwnerAndCommitsReturnOnlyAfterAck() {
        val modes = ModeArbiter()
        change(modes, 1, resource = 2)
        assertTrue(modes.acquireAudio(100, true) { true })
        assertTrue(modes.phoneOwnsAudio)
        assertTrue(modes.phoneBorrowsAudio)
        assertEquals(2L, modes.resourcesPayload().single { it["resourceID"] == 2L }["permanentEntity"])
        assertFalse(modes.releaseAudioBorrow { false })
        assertTrue(modes.phoneBorrowsAudio)
        assertTrue(modes.releaseAudioBorrow { true })
        assertFalse(modes.phoneOwnsAudio)
    }

    @Test fun carCallCannotBeBorrowedAndNewTakeWinsOverPendingAudioAcknowledgement() {
        val modes = ModeArbiter()
        change(modes, 3, "call", resource = 2)
        assertFalse(modes.acquireAudio(100, true) { error("call must block") })
        change(modes, 4, "call", resource = 2)
        assertFalse(
            modes.acquireAudio(500, false) {
                change(modes, 3, "new-call", resource = 2)
                true
            },
        )
        assertFalse(modes.phoneOwnsAudio)
    }

    @Test fun initialSnapshotResetsLoansAndCarAppStateReturnsAfterLocalSpeech() {
        val modes = ModeArbiter()
        change(modes, 3, "old")
        modes.applyInitial(mapOf("appStates" to listOf(mapOf("appStateID" to 1, "speechMode" to 1))))
        assertTrue(modes.phoneOwnsScreen)
        modes.setSpeechRecognition(true)

        fun speech() = (modes.parameters()["appStates"] as List<*>).map { it as Map<*, *> }.single { it["appStateID"] == 1L }
        assertEquals(1L, speech()["entity"])
        modes.setSpeechRecognition(false)
        assertEquals(2L, speech()["entity"])
        assertEquals(1L, speech()["speechMode"])
    }
}
