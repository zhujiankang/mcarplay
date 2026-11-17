package com.mcarplay.app.transport

import com.mcarplay.app.iap2.Iap2LinkEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class Iap2NegotiationDiagnosticsTest {
    @Test fun detectOnlyInputIsDistinguishedFromNoPeerBytes() {
        val pair = InMemoryStreamPair()
        val logs = LinkedBlockingQueue<String>()
        val channel = Iap2LinkChannel(pair.sideA, log = { logs.offer(it) })
        try {
            pair.sideB.write(Iap2LinkEngine.MARKER.copyOf(2))
            pair.sideB.write(Iap2LinkEngine.MARKER.copyOfRange(2, 6))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var detected = false
            while (System.nanoTime() < deadline && !detected) {
                detected = logs.poll(100, TimeUnit.MILLISECONDS)?.contains("received DETECT") == true
            }
            assertTrue(detected)
            assertFalse(channel.awaitReady(10))
        } finally {
            channel.close()
        }
        assertTrue(logs.any { it.contains("ready=false rxBytes=6 detect=1 frames=0") })
    }

    @Test fun silentPeerIsReportedWithoutInventingSynchronizationOrAuthentication() {
        val pair = InMemoryStreamPair()
        val logs = LinkedBlockingQueue<String>()
        Iap2LinkChannel(pair.sideA, log = { logs.offer(it) }).use { channel ->
            assertFalse(channel.awaitReady(10))
        }
        assertTrue(logs.any { it.contains("ready=false rxBytes=0 detect=0 frames=0") })
    }
}
