package com.mcarplay.app.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLifePrerollTest {
    @Test
    fun configurationChangeWithoutNewIdrInvalidatesTheWholePreroll() {
        val cache = CarLifePreroll()
        cache.offer(unit(true))
        cache.offer(unit(false).copy(sps = byteArrayOf(0x67, 0x64, 0, 0x20)))
        cache.offer(unit(false))
        assertTrue(cache.drain().isEmpty())
    }

    private val sps = byteArrayOf(0x67, 0x42, 0, 0x1e)
    private val pps = byteArrayOf(0x68, 0, 0)

    private fun unit(
        key: Boolean,
        value: Byte = 1,
    ) = CarLifeVideoAccessUnit(
        1,
        byteArrayOf(0, 0, 0, 1, if (key) 0x65 else 0x41, value),
        key,
        sps,
        pps,
    )

    @Test
    fun startsAtDecodableIdrAndKeepsItsReferencesInOrder() {
        val cache = CarLifePreroll()
        cache.offer(unit(false))
        cache.offer(unit(true))
        cache.offer(unit(false, 2))
        val frames = cache.drain()
        assertEquals(2, frames.size)
        assertTrue(frames.first().keyFrame)
        assertTrue(frames.first().data.size > unit(true).data.size)
        assertArrayEquals(unit(false, 2).data, frames.last().data)
        assertTrue(cache.drain().isEmpty())
    }

    @Test
    fun newIdrReplacesOldGopInsteadOfAccumulatingOldLatency() {
        val cache = CarLifePreroll()
        cache.offer(unit(true))
        cache.offer(unit(false))
        cache.offer(unit(true, 3))
        assertEquals(1, cache.drain().size)
    }

    @Test
    fun frameLimitDiscardsIncompleteGopUntilNextIdr() {
        val cache = CarLifePreroll(maxFrames = 2)
        cache.offer(unit(true))
        cache.offer(unit(false))
        cache.offer(unit(false))
        cache.offer(unit(false))
        assertTrue(cache.drain().isEmpty())
        cache.offer(unit(true))
        assertEquals(1, cache.drain().size)
    }

    @Test
    fun byteLimitAndMissingConfigNeverProduceUndecodablePreroll() {
        val cache = CarLifePreroll(maxBytes = 1)
        cache.offer(unit(true))
        assertTrue(cache.drain().isEmpty())
        val other = CarLifePreroll()
        other.offer(unit(true).copy(sps = null, pps = null))
        assertTrue(other.drain().isEmpty())
    }

    @Test
    fun sourceRetirementClearsReferencesAndInputMutationCannotCorruptCache() {
        val cache = CarLifePreroll()
        val first = unit(true)
        val reference = unit(false)
        val expected = reference.data.copyOf()
        cache.offer(first)
        cache.offer(reference)
        reference.data.fill(0)
        assertArrayEquals(expected, cache.drain().last().data)
        cache.offer(first)
        cache.clear()
        cache.offer(unit(false))
        assertTrue(cache.drain().isEmpty())
    }
}
