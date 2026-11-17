package com.mcarplay.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedNetworkRecoveryTest {
    private class Environment {
        var now = 0L
        var current = true
        var bound: String? = null
        var calls = 0
        var action: () -> String? = { null }
        val work = ArrayDeque<Pair<Long, () -> Unit>>()
        val restored = mutableListOf<String>()
        val recovery =
            BoundedNetworkRecovery(
                current = { current },
                bound = { bound },
                attempt = {
                    calls++
                    action()
                },
                restored = { restored.add(it) },
                schedule = { delay, task -> work.addLast(delay to task) },
                now = { now },
                log = {},
            )

        fun runNext() {
            val (delay, task) = work.removeFirst()
            now += delay
            task()
        }
    }

    @Test fun repeatedLossIsCoalescedAndAlreadyBoundNetworkNeedsNoRequest() {
        val e = Environment()
        assertTrue(e.recovery.request())
        assertFalse(e.recovery.request())
        e.bound = "car"
        e.runNext()
        assertEquals(0, e.calls)
        assertEquals(listOf("car"), e.restored)
    }

    @Test fun failuresStopAfterThreeAndOnlyExplicitRetryAfterCooldownReopensBudget() {
        val e = Environment()
        e.recovery.request()
        repeat(3) { e.runNext() }
        assertEquals(3, e.calls)
        assertTrue(e.work.isEmpty())
        assertFalse(e.recovery.request())
        assertFalse(e.recovery.request(true))
        e.now += 5000
        assertFalse(e.recovery.request())
        assertTrue(e.recovery.request(true))
        e.runNext()
        assertEquals(4, e.calls)
    }

    @Test fun secondAttemptRestoresExactNetworkAndNoMoreWork() {
        val e = Environment()
        e.action = { if (e.calls == 2) "verified-car" else null }
        e.recovery.request()
        e.runNext()
        e.runNext()
        assertEquals(listOf("verified-car"), e.restored)
        assertEquals(1500L, e.now)
        assertTrue(e.work.isEmpty())
    }

    @Test fun stopBeforeQueuedWorkPreventsNetworkMutation() {
        val e = Environment()
        e.recovery.request()
        e.recovery.close()
        e.runNext()
        assertEquals(0, e.calls)
        assertTrue(e.restored.isEmpty())
    }

    @Test fun stopDuringIoCannotPublishLateNetworkOrScreenReturn() {
        val e = Environment()
        e.action = {
            e.recovery.close()
            "old-car"
        }
        e.recovery.request()
        e.runNext()
        assertTrue(e.restored.isEmpty())
        assertTrue(e.work.isEmpty())
    }

    @Test fun newClientMakesOldRecoveryTaskInert() {
        val e = Environment()
        e.recovery.request()
        e.current = false
        e.runNext()
        assertEquals(0, e.calls)
        assertTrue(e.restored.isEmpty())
    }

    @Test fun requestFailureIsBoundedAndDoesNotPublishFakeBinding() {
        val e = Environment()
        e.action = { throw SecurityException("SSID unavailable") }
        e.recovery.request()
        repeat(3) { e.runNext() }
        assertEquals(3, e.calls)
        assertTrue(e.restored.isEmpty())
        assertTrue(e.work.isEmpty())
    }
}
