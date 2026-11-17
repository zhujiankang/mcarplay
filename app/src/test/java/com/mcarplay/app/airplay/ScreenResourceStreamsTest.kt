package com.mcarplay.app.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenResourceStreamsTest {
    @Test fun retainedBorrowReturnsWithoutScreenTeardownSetupOrRecord() {
        val modes = ModeArbiter()
        val visibility = mutableListOf<Boolean>()
        val streams =
            ScreenResourceStreams(
                isCurrent = { true },
                phoneOwnsScreen = { modes.phoneOwnsScreen },
                retireScreen = { error("Retained Mercedes screen must not send TEARDOWN") },
                restoreScreen = { error("Retained Mercedes screen must not send SETUP/RECORD") },
                onFailure = { error(it) },
                retainScreenStream = { true },
                onRetainedScreenVisibility = { visible ->
                    visibility += visible
                    if (visible) modes.onScreenStreamRestored()
                },
            )
        repeat(10) {
            modes.changeResponse(
                mapOf("resources" to listOf(mapOf("resourceID" to 1L, "transferType" to 3L, "unborrowConstraint" to 1000L))),
            )
            assertFalse(modes.onUserRequestedUi().allowed)
            assertTrue(streams.reconcile(forceRebuild = true))
            assertTrue(streams.reconcile())
            assertTrue(streams.attached)
            assertTrue(streams.outputPaused)
            assertTrue(modes.phoneOwnsAudio)
            modes.changeResponse(mapOf("resources" to listOf(mapOf("resourceID" to 1L, "transferType" to 4L))))
            assertTrue(streams.reconcile(forceRebuild = true))
            assertTrue(streams.reconcile())
            assertFalse(streams.outputPaused)
            assertFalse(modes.onUserRequestedUi().rebuildStream)
        }
        assertEquals(List(10) { listOf(false, true) }.flatten(), visibility)
    }

    @Test fun retainedNativeHomeAndReturnAlsoPreserveTheScreenStream() {
        val modes = ModeArbiter()
        val visibility = mutableListOf<Boolean>()
        val streams =
            ScreenResourceStreams(
                isCurrent = { true },
                phoneOwnsScreen = { modes.phoneOwnsScreen },
                retireScreen = { error("Unexpected teardown") },
                restoreScreen = { error("Unexpected rebuild") },
                onFailure = { error(it) },
                retainScreenStream = { true },
                onRetainedScreenVisibility = visibility::add,
            )
        modes.changeResponse(takeScreen())
        assertTrue(streams.reconcile())
        assertTrue(streams.outputPaused)
        assertTrue(modes.onUserRequestedUi().allowed)
        assertTrue(streams.reconcile(forceRebuild = true))
        assertEquals(listOf(false, true), visibility)
        assertFalse(streams.outputPaused)
    }

    @Test fun oldRetainedSessionCannotPauseOrResumeAfterConnectionReplacement() {
        val modes = ModeArbiter()
        var current = true
        val events = mutableListOf<Boolean>()
        val streams =
            ScreenResourceStreams(
                isCurrent = { current },
                phoneOwnsScreen = { modes.phoneOwnsScreen },
                retireScreen = { error("Unexpected teardown") },
                restoreScreen = { error("Unexpected rebuild") },
                onFailure = { error(it) },
                retainScreenStream = { true },
                onRetainedScreenVisibility = events::add,
            )
        modes.changeResponse(takeScreen())
        assertTrue(streams.reconcile())
        current = false
        modes.onUserRequestedUi()
        assertFalse(streams.reconcile())
        current = true
        streams.close()
        assertFalse(streams.reconcile())
        assertEquals(listOf(false), events)
    }

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
        assertEquals(0, failures) // first local failure remains recoverable, not whole-session fatal
        assertEquals(ScreenResourceStreams.State.RETIRE_PENDING, streams.state)
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

    @Test fun retirementMustBeConfirmedBeforeAnyReplacementAndRetriesAreBounded() {
        var time = 0L
        var owns = false
        var retireOk = false
        val events = mutableListOf<String>()
        val streams =
            ScreenResourceStreams(
                { true },
                { owns },
                {
                    events += "retire"
                    retireOk
                },
                {
                    events += "restore"
                    ScreenResourceStreams.RestoreResult.RESTORED
                },
                { events += "exhausted" },
                now = { time },
            )
        repeat(3) { attempt ->
            time = attempt * 2500L
            assertFalse(streams.reconcile())
            repeat(20) { assertFalse(streams.reconcile()) }
        }
        assertEquals(listOf("retire", "retire", "retire", "exhausted"), events)
        owns = true
        retireOk = true
        time += 2500
        assertFalse(streams.reconcile()) // an ordinary invitation cannot reset the exhausted budget
        assertTrue(streams.reconcile(explicitRetry = true))
        assertEquals(listOf("retire", "restore"), events.takeLast(2))
        assertTrue(streams.attached)
    }

    @Test fun failedRestoreRetriesLocallyAndStopsWhileTheCarOwnsTheScreen() {
        var time = 0L
        var owns = false
        var ready = false
        var restores = 0
        val streams =
            ScreenResourceStreams(
                { true },
                { owns },
                { true },
                {
                    restores++
                    if (ready) ScreenResourceStreams.RestoreResult.RESTORED else ScreenResourceStreams.RestoreResult.FAILED
                },
                { error("Must preserve audio/control") },
                now = { time },
            )
        assertTrue(streams.reconcile())
        owns = true
        assertFalse(streams.reconcile())
        assertEquals(1, restores)
        time = 2500
        owns = false // reverse camera intervenes before the retry
        assertTrue(streams.reconcile())
        assertEquals(1, restores)
        owns = true
        ready = true
        assertTrue(streams.reconcile())
        assertEquals(2, restores)
    }

    @Test fun endedRetainedVideoRequiresRetirementRatherThanReusingItsDeadSocket() {
        val events = mutableListOf<String>()
        val streams =
            ScreenResourceStreams(
                { true },
                { true },
                {
                    events += "retire"
                    true
                },
                {
                    events += "restore"
                    ScreenResourceStreams.RestoreResult.RESTORED
                },
                { error(it) },
                retainScreenStream = { true },
            )
        streams.streamEnded()
        assertTrue(streams.recoveryPending)
        assertTrue(streams.reconcile())
        assertEquals(listOf("retire", "restore"), events)
        assertFalse(streams.outputPaused)
    }

    @Test fun replacementAndExceptionsCannotOperateAfterSessionClose() {
        var current = true
        var time = 0L
        var calls = 0
        val streams =
            ScreenResourceStreams(
                { current },
                { false },
                {
                    calls++
                    throw java.io.IOException()
                },
                { error("Must retire first") },
                { error(it) },
                now = { time },
            )
        assertFalse(streams.reconcile())
        time = 2500
        current = false
        assertFalse(streams.reconcile(explicitRetry = true))
        streams.close()
        current = true
        assertFalse(streams.reconcile())
        assertEquals(1, calls)
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
