package com.mcarplay.app.bt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothBringupGraceTest {
    private var now = 100L
    private val grace = BluetoothBringupGrace { now }

    @Test
    fun repeatedDisconnectDoesNotExtendWindow() {
        val token = grace.begin()!!
        now += 40_000
        assertNull(grace.begin())
        assertFalse(grace.expire(token))
        now += 20_000
        assertTrue(grace.expire(token))
        assertFalse(grace.expire(token))
    }

    @Test
    fun reconnectOrUserStopInvalidatesQueuedExpiry() {
        val token = grace.begin()!!
        grace.cancel()
        now += 60_000
        assertFalse(grace.expire(token))
    }

    @Test
    fun oldExpiryCannotStopNewConnection() {
        val old = grace.begin()!!
        grace.cancel()
        now += 50_000
        val current = grace.begin()!!
        now += 60_000
        assertFalse(grace.expire(old))
        assertTrue(grace.expire(current))
        assertNotNull(grace.begin())
    }
}
