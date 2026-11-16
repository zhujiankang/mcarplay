package com.mcarplay.app.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenResourceStreamsTest {
    @Test
    fun carHomeThenRequestUiRetiresOnlyOnceAndRebuildsScreen() {
        val modes = ModeArbiter()
        val events = mutableListOf<String>()
        val streams = streams(modes, events)
        modes.changeResponse(takeScreen())
        assertTrue(streams.reconcile())
        assertTrue(streams.reconcile())
        assertEquals(listOf("screen TEARDOWN"), events)
        assertFalse(streams.attached)
        assertTrue(modes.phoneOwnsAudio)

        assertTrue(modes.onUserRequestedUi().allowed)
        assertTrue(streams.reconcile())
        assertTrue(streams.reconcile())
        assertEquals(listOf("screen TEARDOWN", "screen SETUP RECORD IDR"), events)
        assertTrue(streams.attached)
    }

    @Test
    fun borrowedScreenReturnsWithoutRequestUi() {
        val modes = ModeArbiter()
        val events = mutableListOf<String>()
        val streams = streams(modes, events)
        modes.changeResponse(mapOf("resources" to listOf(mapOf("resourceID" to 1L, "transferType" to 3L))))
        streams.reconcile()
        modes.changeResponse(mapOf("resources" to listOf(mapOf("resourceID" to 1L, "transferType" to 4L))))
        streams.reconcile()
        assertEquals(listOf("screen TEARDOWN", "screen SETUP RECORD IDR"), events)
    }

    @Test
    fun closedOrReplacedSessionCannotOperateOnNewConnection() {
        val modes = ModeArbiter()
        val events = mutableListOf<String>()
        var current = true
        val streams = streams(modes, events, current = { current })
        modes.changeResponse(takeScreen())
        current = false
        assertFalse(streams.reconcile())
        current = true
        streams.close()
        assertFalse(streams.reconcile())
        assertTrue(events.isEmpty())
    }

    @Test
    fun cancellationDuringTeardownDoesNotRestoreOrReportSpuriousFailure() {
        var current = true
        var restored = false
        val failures = mutableListOf<String>()
        val streams =
            ScreenResourceStreams(
                isCurrent = { current },
                phoneOwnsScreen = { true },
                retireScreen = {
                    current = false
                    true
                },
                restoreScreen = {
                    restored = true
                    ScreenResourceStreams.RestoreResult.RESTORED
                },
                onFailure = failures::add,
            )
        assertFalse(streams.reconcile(forceRebuild = true))
        assertFalse(restored)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun failedScreenTeardownCannotBeReportedAsSuccessfulReturn() {
        var failures = 0
        val streams =
            ScreenResourceStreams(
                isCurrent = { true },
                phoneOwnsScreen = { false },
                retireScreen = { false },
                restoreScreen = { error("Must not restore") },
                onFailure = { failures++ },
            )
        assertFalse(streams.reconcile())
        assertEquals(1, failures)
    }

    @Test
    fun currentOwnershipAtExecutionWinsOverAnOldUiRebuildRequest() {
        val modes = ModeArbiter()
        val events = mutableListOf<String>()
        val streams = streams(modes, events)
        modes.changeResponse(takeScreen())
        streams.reconcile(forceRebuild = true)
        assertEquals(listOf("screen TEARDOWN"), events)
        assertFalse(streams.attached)
    }

    private fun streams(
        modes: ModeArbiter,
        events: MutableList<String>,
        current: () -> Boolean = { true },
    ) = ScreenResourceStreams(
        isCurrent = current,
        phoneOwnsScreen = { modes.phoneOwnsScreen },
        retireScreen = {
            events += "screen TEARDOWN"
            true
        },
        restoreScreen = {
            events += "screen SETUP RECORD IDR"
            ScreenResourceStreams.RestoreResult.RESTORED
        },
        onFailure = { error(it) },
    )

    private fun takeScreen() =
        mapOf(
            "resources" to
                listOf(
                    mapOf(
                        "resourceID" to 1L,
                        "transferType" to 1L,
                        "transferPriority" to 500L,
                        "takeConstraint" to 500L,
                    ),
                ),
        )
}
