package com.mcarplay.app.carlife

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLifeVideoPipelineStatsTest {
    @Test fun ingressPacketsAndParsedFramesRemainSeparateAndExpensiveCallbacksAreVisible() {
        val stats = CarLifeVideoPipelineStats()
        stats.packet(0, 1000)
        stats.parsed(2, 20_000)
        stats.callback(75_000_000)
        stats.packet(10_000_000_000, 2000)
        stats.parsed(598, 50_000)
        val first = stats.poll(10_000_000_000)!!
        assertTrue(first.contains("packets=2 bytes=3000 parsedFrames=600 sourceFps=60.000"))
        assertTrue(first.contains("parseMaxUs=50 callbackMaxUs=75000"))
        assertNull(stats.poll(10_000_000_001))
        stats.packet(20_000_000_000, 500)
        stats.parsed(10, 1000)
        val next = stats.poll(20_000_000_000)!!
        assertTrue(next.contains("packets=1 bytes=500 parsedFrames=10 sourceFps=1.000"))
        assertTrue(next.contains("callbackMaxUs=0"))
    }
}
