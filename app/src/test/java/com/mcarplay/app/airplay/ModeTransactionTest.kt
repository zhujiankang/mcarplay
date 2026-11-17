package com.mcarplay.app.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeTransactionTest {
    private fun take(id: Any = 1L) = mapOf("resourceID" to id, "transferType" to 1L, "transferPriority" to 500L)

    @Test fun invalidLaterResourceRollsBackScreenAndAudioTransfersTogether() {
        val modes = ModeArbiter()
        val before = modes.parameters()
        val response = modes.changeResponse(mapOf("resources" to listOf(take(), take(2L), take(99L))))
        assertEquals(mapOf("status" to 1L), response)
        assertEquals(before, modes.parameters())
        assertEquals(null, modes.consumeAudioTransfer())
        assertFalse(modes.screenRequest().rebuildStream)
    }

    @Test fun invalidResourceDoesNotLeaveCarSpeechRecognitionActive() {
        val modes = ModeArbiter()
        val before = modes.parameters()
        assertEquals(
            1L,
            modes.changeResponse(
                mapOf(
                    "appStates" to listOf(mapOf("appStateID" to 1L, "speechMode" to 2L)),
                    "resources" to listOf(take() + ("takeConstraint" to Long.MAX_VALUE)),
                ),
            )["status"],
        )
        assertFalse(modes.recognizingSpeech)
        assertEquals(before, modes.parameters())
    }

    @Test fun malformedOrUnsupportedLiveRequestsAreRejectedWithoutInventingDefaults() {
        val cases =
            listOf(
                null,
                mapOf("resources" to "invalid"),
                mapOf("resources" to listOf(null)),
                mapOf("resources" to listOf(take(1.5))),
                mapOf("resources" to listOf(take() + ("transferType" to 999L))),
                mapOf("resources" to listOf(take() + ("transferType" to "1"))),
                mapOf("resources" to listOf(take() + ("transferPriority" to 500.5))),
                mapOf("resources" to listOf(take() + ("takeConstraint" to 100.5))),
                mapOf("resources" to listOf(mapOf("resourceID" to 1L, "permanentEntity" to 2L))),
                mapOf("resources" to listOf(take() + ("borrowID" to ByteArray(1)))),
                mapOf("appStates" to listOf(mapOf("appStateID" to 1L, "speechMode" to 99L))),
                mapOf("appStates" to listOf(mapOf("appStateID" to 2L, "state" to "false"))),
                mapOf("appStates" to listOf(mapOf("appStateID" to 4L, "state" to true))),
            )
        for (request in cases) {
            val modes = ModeArbiter()
            val before = modes.parameters()
            assertEquals("request=$request", 1L, modes.changeResponse(request)["status"])
            assertEquals(before, modes.parameters())
        }
    }

    @Test fun rejectionDoesNotInvalidateAcknowledgementOfConcurrentPhoneClaim() {
        val modes = ModeArbiter()
        modes.onChangeModes(mapOf("resources" to listOf(take())))
        assertTrue(
            modes.acquireScreen(true) {
                assertEquals(1L, modes.changeResponse(mapOf("resources" to listOf(take(99L))))["status"])
                true
            },
        )
        assertTrue(modes.phoneOwnsScreen)
    }

    @Test fun validTransactionStillInvalidatesConcurrentPhoneClaim() {
        val modes = ModeArbiter()
        modes.onChangeModes(mapOf("resources" to listOf(take())))
        assertFalse(
            modes.acquireScreen(true) {
                assertEquals(0L, modes.changeResponse(mapOf("resources" to listOf(take())))["status"])
                true
            },
        )
        assertFalse(modes.phoneOwnsScreen)
    }

    @Test fun speechStatesRetainLocalLeaseOverlayAndReturnToAccessoryState() {
        val modes = ModeArbiter()
        modes.setSpeechRecognition(true)
        assertEquals(0L, modes.changeResponse(mapOf("appStates" to listOf(mapOf("appStateID" to 1L, "speechMode" to 1L))))["status"])

        fun speech() = (modes.parameters()["appStates"] as List<*>).map { it as Map<*, *> }.single { it["appStateID"] == 1L }
        assertEquals(2L, speech()["speechMode"])
        assertEquals(1L, speech()["entity"])
        modes.setSpeechRecognition(false)
        assertEquals(1L, speech()["speechMode"])
        assertEquals(2L, speech()["entity"])
        assertEquals(0L, modes.changeResponse(mapOf("appStates" to listOf(mapOf("appStateID" to 1L, "speechMode" to -1L))))["status"])
        assertEquals(0L, speech()["entity"])
        assertFalse(modes.recognizingSpeech)
    }

    @Test fun compatibleSparseTransfersAndExtraReleaseFieldsRemainAccepted() {
        val modes = ModeArbiter()
        for (transfer in listOf(3, 4)) {
            val response =
                modes.changeResponse(
                    mapOf(
                        "resources" to
                            listOf(mapOf("resourceID" to 1, "transferType" to transfer, "borrowID" to 4, "takeConstraint" to 500)),
                    ),
                )
            assertEquals(0L, response["status"])
            assertEquals(transfer == 4, modes.phoneOwnsScreen)
        }
    }

    @Test fun unknownConstraintIsAcceptedButCannotBeOverriddenByPhone() {
        val modes = ModeArbiter()
        assertEquals(
            0L,
            modes.changeResponse(mapOf("resources" to listOf(take() + ("takeConstraint" to 501L))))["status"],
        )
        assertFalse(modes.phoneOwnsScreen)
        assertFalse(modes.acquireScreen(true) { error("unknown constraint must block") })
    }
}
