package com.mcarplay.app.airplay

import com.mcarplay.app.video.VideoSender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OrderlyScreenRetirementTest {
    private fun retirement(
        client: AirPlayClient,
        stops: AtomicInteger = AtomicInteger(),
    ): OrderlyScreenRetirement<VideoSender> {
        val epoch = client.connectionGeneration
        return OrderlyScreenRetirement(
            isCurrent = { client.isConnectionCurrent(epoch) },
            quiesce = { it.quiesceForOrderlyStop() },
            stop = {
                stops.incrementAndGet()
                it.stop()
            },
            teardown = { client.teardownScreenStream(epoch) },
        )
    }

    @Test fun protocolRetirementPrecedesVideoCloseAndKeepsControlResponsive() {
        ScreenEofReceiver().use { receiver ->
            val client = receiver.client()
            assertTrue(client.reconnect())
            val (sender, video) = receiver.sender(client)
            val stops = AtomicInteger()
            val retirement = retirement(client, stops)
            try {
                assertTrue(retirement.begin(sender))
                assertFalse(video.ended.await(100, TimeUnit.MILLISECONDS))
                assertTrue(retirement.finish())
                assertTrue(video.ended.await(2, TimeUnit.SECONDS))
                assertFalse(receiver.unexpectedEof.await(100, TimeUnit.MILLISECONDS))
                assertFalse(retirement.hasPending)
                assertEquals(110L, receiver.lastTeardown!!["type"])
                assertEquals("display-test", receiver.lastTeardown!!["uuid"])
                assertTrue(client.assertModes())
                retirement.close()
                assertEquals(1, stops.get())
            } finally {
                retirement.close()
                sender.stop()
                client.close()
            }
        }
    }

    @Test fun rejectionKeepsPendingSocketAndAnotherSetupIsBlockedUntilRetrySucceeds() {
        ScreenEofReceiver().use { receiver ->
            val client = receiver.client()
            assertTrue(client.reconnect())
            val (sender, video) = receiver.sender(client)
            val retirement = retirement(client)
            try {
                receiver.rejectTeardown = true
                assertTrue(retirement.begin(sender))
                assertFalse(retirement.finish())
                assertTrue(retirement.hasPending)
                assertFalse(video.ended.await(100, TimeUnit.MILLISECONDS))
                assertTrue(client.runScreenStream(ByteArray(32), "display-test") == null)
                assertTrue(client.assertModes())
                receiver.rejectTeardown = false
                assertTrue(retirement.finish())
                assertFalse(receiver.unexpectedEof.await(100, TimeUnit.MILLISECONDS))
            } finally {
                retirement.close()
                sender.stop()
                client.close()
            }
        }
    }

    @Test fun timeoutAndLateAcknowledgementDoNotDisposeTheSenderUntilConfirmedRetry() {
        ScreenEofReceiver().use { receiver ->
            val client = receiver.client()
            assertTrue(client.reconnect())
            val (sender, video) = receiver.sender(client)
            val stops = AtomicInteger()
            val retirement = retirement(client, stops)
            try {
                receiver.holdTeardown = true
                assertTrue(retirement.begin(sender))
                assertFalse(retirement.finish())
                val late = receiver.heldTeardowns.poll(2, TimeUnit.SECONDS)!!
                assertTrue(retirement.hasPending)
                assertFalse(video.ended.await(100, TimeUnit.MILLISECONDS))
                receiver.acknowledgeRetirement(late)
                assertTrue(video.ended.await(2, TimeUnit.SECONDS))
                assertEquals(0, stops.get()) // late RTSP response cannot commit the timed-out attempt
                assertTrue(retirement.hasPending)
                receiver.holdTeardown = false
                assertTrue(retirement.finish())
                assertEquals(1, stops.get())
                assertTrue(client.assertModes())
            } finally {
                retirement.close()
                sender.stop()
                client.close()
            }
        }
    }

    @Test fun cancellationClosesPendingTcpWithoutWaitingForBlockedControlWorker() {
        ScreenEofReceiver().use { receiver ->
            val client = receiver.client()
            assertTrue(client.reconnect())
            val (sender, video) = receiver.sender(client)
            val stops = AtomicInteger()
            val retirement = retirement(client, stops)
            val worker = Executors.newSingleThreadExecutor()
            try {
                receiver.holdTeardown = true
                assertTrue(retirement.begin(sender))
                val waiting = worker.submit<Boolean> { retirement.finish() }
                assertTrue(receiver.heldTeardowns.poll(2, TimeUnit.SECONDS) != null)
                retirement.close()
                assertTrue(video.ended.await(2, TimeUnit.SECONDS))
                assertFalse(waiting.get(2, TimeUnit.SECONDS))
                assertFalse(retirement.hasPending)
                retirement.close()
                assertEquals(1, stops.get())
            } finally {
                retirement.close()
                sender.stop()
                client.close()
                worker.shutdownNow()
            }
        }
    }

    @Test fun oldRetirementCannotSendTeardownToAReconnectedClientOrCloseItsNewVideo() {
        ScreenEofReceiver().use { receiver ->
            val client = receiver.client()
            assertTrue(client.reconnect())
            val oldEpoch = client.connectionGeneration
            val (oldSender, _) = receiver.sender(client)
            val old = retirement(client)
            assertTrue(old.begin(oldSender))
            client.close()
            assertTrue(client.reconnect())
            val (newSender, newVideo) = receiver.sender(client)
            val current = retirement(client)
            try {
                assertFalse(old.finish())
                assertFalse(client.teardownScreenStream(oldEpoch))
                old.close()
                assertFalse(newVideo.ended.await(100, TimeUnit.MILLISECONDS))
                assertTrue(client.assertModes())
                assertTrue(current.begin(newSender))
                assertTrue(current.finish())
                assertTrue(client.assertModes())
            } finally {
                old.close()
                current.close()
                oldSender.stop()
                newSender.stop()
                client.close()
            }
        }
    }

    @Test fun manualSessionStopRetainsPendingVideoUntilSessionTeardownCompletes() {
        ScreenEofReceiver().use { receiver ->
            val client = receiver.client()
            assertTrue(client.reconnect())
            val (sender, video) = receiver.sender(client)
            val stops = AtomicInteger()
            val retirement = retirement(client, stops)
            val streams =
                ScreenResourceStreams(
                    { true },
                    { false },
                    { retirement.finish() },
                    { error("No restore while stopping") },
                    { error(it) },
                    onClose = retirement::close,
                )
            try {
                assertTrue(retirement.begin(sender))
                streams.quiesceForSessionStop()
                assertFalse(streams.reconcile())
                assertFalse(video.ended.await(100, TimeUnit.MILLISECONDS))
                assertTrue(client.teardownSession())
                streams.close()
                streams.close()
                assertEquals(1, stops.get())
                assertFalse(receiver.unexpectedEof.await(100, TimeUnit.MILLISECONDS))
            } finally {
                streams.close()
                sender.stop()
                client.close()
            }
        }
    }

    @Test fun exhaustedRecoveryKeepsOneOwnedSocketUntilExplicitScopeCleanup() {
        ScreenEofReceiver().use { receiver ->
            val client = receiver.client()
            assertTrue(client.reconnect())
            val (sender, video) = receiver.sender(client)
            val retirement = retirement(client)
            var now = 0L
            var failures = 0
            val streams =
                ScreenResourceStreams(
                    { true },
                    { false },
                    { retirement.begin(sender) && retirement.finish() },
                    { error("Cannot restore before retirement") },
                    { failures++ },
                    now = { now },
                    onClose = retirement::close,
                )
            try {
                receiver.rejectTeardown = true
                repeat(3) {
                    assertFalse(streams.reconcile())
                    now += ScreenResourceStreams.RETRY_DELAY_MS
                }
                assertEquals(ScreenResourceStreams.State.EXHAUSTED, streams.state)
                assertEquals(1, failures)
                assertTrue(retirement.hasPending)
                assertFalse(video.ended.await(100, TimeUnit.MILLISECONDS))
                assertTrue(client.assertModes())
                streams.close()
                assertTrue(video.ended.await(2, TimeUnit.SECONDS))
                assertFalse(retirement.hasPending)
            } finally {
                streams.close()
                sender.stop()
                client.close()
            }
        }
    }

    @Test fun failedRecordWithAnOpenDataPortAlsoUsesProtocolFirstCleanup() {
        ScreenEofReceiver().use { receiver ->
            val client = receiver.client()
            assertTrue(client.reconnect())
            val reply = receiver.control.reply
            receiver.control.reply = { if (it.method == "RECORD") it.respond(403) else reply(it) }
            val (replacement, video) = receiver.sender(client, start = false)
            val retirement = retirement(client)
            try {
                assertFalse(client.record())
                assertTrue(retirement.begin(replacement))
                assertFalse(video.ended.await(100, TimeUnit.MILLISECONDS))
                assertTrue(retirement.finish())
                assertFalse(receiver.unexpectedEof.await(100, TimeUnit.MILLISECONDS))
                assertTrue(client.assertModes())
            } finally {
                retirement.close()
                replacement.stop()
                client.close()
            }
        }
    }

    @Test fun lateCancellationCanAdoptOnlyItsOwnScopeAndCleanupIsIdempotent() {
        var current = true
        var requests = 0
        val stopped = mutableListOf<Any>()
        val retirement =
            OrderlyScreenRetirement<Any>(
                { current },
                {},
                stopped::add,
                {
                    requests++
                    true
                },
            )
        val old = Any()
        val replacement = Any()
        assertTrue(retirement.begin(old))
        assertFalse(retirement.begin(replacement))
        assertTrue(retirement.finish())
        current = false
        assertTrue(retirement.begin(replacement)) // a late failed restore after manual quiescence
        assertFalse(retirement.finish())
        assertEquals(1, requests)
        retirement.close()
        retirement.close()
        assertEquals(listOf(old, replacement), stopped)
        assertFalse(retirement.begin(Any()))
    }
}
