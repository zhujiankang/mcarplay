package com.mcarplay.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkBindingTest {
    @Test fun adoptionKeepsBindingUntilVerifiedReplacementAndReturnsOldOwner() {
        val e = Environment()
        val old = Any()
        val fresh = Any()
        val first = e.liveNetwork(200)
        val next = e.liveNetwork(201)
        e.binding.replaceRequest(old)
        assertTrue(e.binding.bind(old, first))
        assertSame(old, e.binding.adopt(old, fresh, next)?.previous)
        assertSame(fresh, e.binding.owner())
        assertSame(next, e.binding.current())
        assertEquals(listOf(first, next), e.bindings)
        assertFalse(e.binding.lost(old, next))
    }

    @Test fun staleRecoveryCannotReplaceNewerOwner() {
        val e = Environment()
        val old = Any()
        val fresh = Any()
        val network = e.liveNetwork(200)
        e.binding.replaceRequest(fresh)
        assertTrue(e.binding.bind(fresh, network))
        assertNull(e.binding.adopt(old, Any(), e.liveNetwork(201)))
        assertSame(fresh, e.binding.owner())
        assertSame(network, e.binding.current())
        assertEquals(listOf(network), e.bindings)
    }

    @Test fun failedAdoptionPreservesOwnerAndNetwork() {
        val e = Environment()
        val old = Any()
        val network = e.liveNetwork(200)
        e.binding.replaceRequest(old)
        assertTrue(e.binding.bind(old, network))
        e.allowBinding = false
        assertNull(e.binding.adopt(old, Any(), e.liveNetwork(201)))
        assertSame(old, e.binding.owner())
        assertSame(network, e.binding.current())
        assertEquals(listOf(network), e.bindings)
    }

    @Test fun ownerlessAdoptionCannotOverrideNewRequest() {
        val e = Environment()
        val owner = Any()
        val network = e.liveNetwork(200)
        assertNull(e.binding.adopt(null, owner, network)?.previous)
        assertSame(owner, e.binding.owner())
        assertNull(e.binding.adopt(null, Any(), e.liveNetwork(201)))
        assertSame(network, e.binding.current())
    }

    @Test fun scopedRetirementCannotReleaseNewerNetworkRequest() {
        val e = Environment()
        val old = Any()
        val fresh = Any()
        val network = e.liveNetwork(200)
        e.binding.replaceRequest(old)
        e.binding.replaceRequest(fresh)
        assertTrue(e.binding.bind(fresh, network))
        assertFalse(e.binding.retire(old))
        assertSame(network, e.binding.current())
    }

    @Test fun scopedRetirementUnbindsOnlyItsOwnRequest() {
        val e = Environment()
        val owner = Any()
        e.binding.replaceRequest(owner)
        assertTrue(e.binding.bind(owner, e.liveNetwork(200)))
        assertTrue(e.binding.retire(owner))
        assertNull(e.binding.current())
        assertFalse(e.binding.retire(owner))
    }

    @Test
    fun lossWithRecreatedNetworkValueClearsProcessBinding() {
        val environment = Environment()
        val owner = Any()
        val available = environment.liveNetwork(111)
        val lost = TestNetwork(111)
        assertNotSame(available, lost)
        environment.binding.replaceRequest(owner)
        assertTrue(environment.binding.bind(owner, available))

        assertTrue(environment.binding.lost(owner, lost))
        assertNull(environment.binding.current())
        assertEquals(listOf(available, null), environment.bindings)
    }

    @Test
    fun deadNetworkIsNotReusedEvenWhenLossCallbackWasMissed() {
        val environment = Environment()
        val owner = Any()
        val network = environment.liveNetwork(111)
        environment.binding.replaceRequest(owner)
        assertTrue(environment.binding.bind(owner, network))
        environment.liveIds.clear()

        assertNull(environment.binding.current())
        assertNull(environment.binding.current())
        assertEquals(listOf(network, null), environment.bindings)
    }

    @Test
    fun retiredRequestCannotBindOrClearReplacementNetwork() {
        val environment = Environment()
        val oldOwner = Any()
        val newOwner = Any()
        val oldNetwork = environment.liveNetwork(111)
        val newNetwork = environment.liveNetwork(112)
        environment.binding.replaceRequest(oldOwner)
        assertTrue(environment.binding.bind(oldOwner, oldNetwork))
        assertSame(oldOwner, environment.binding.replaceRequest(newOwner))
        assertTrue(environment.binding.bind(newOwner, newNetwork))

        assertFalse(environment.binding.bind(oldOwner, oldNetwork))
        assertFalse(environment.binding.lost(oldOwner, TestNetwork(112)))
        assertSame(newNetwork, environment.binding.current())
        assertEquals(listOf(oldNetwork, null, newNetwork), environment.bindings)
    }

    @Test
    fun failedProcessBindingIsNotReportedAsConnected() {
        val environment = Environment()
        val owner = Any()
        val network = environment.liveNetwork(111)
        environment.binding.replaceRequest(owner)
        environment.allowBinding = false

        assertFalse(environment.binding.bind(owner, network))
        assertNull(environment.binding.current())
        assertTrue(environment.bindings.isEmpty())
    }

    @Test
    fun lossOfAnotherNetworkDoesNotClearCurrentBinding() {
        val environment = Environment()
        val owner = Any()
        val network = environment.liveNetwork(112)
        environment.binding.replaceRequest(owner)
        assertTrue(environment.binding.bind(owner, network))

        assertFalse(environment.binding.lost(owner, TestNetwork(111)))
        assertSame(network, environment.binding.current())
    }

    @Test
    fun releaseRejectsCallbacksThatArriveAfterUnregistration() {
        val environment = Environment()
        val owner = Any()
        val network = environment.liveNetwork(111)
        environment.binding.replaceRequest(owner)
        assertTrue(environment.binding.bind(owner, network))

        assertSame(owner, environment.binding.replaceRequest(null))
        assertFalse(environment.binding.isCurrent(owner))
        assertFalse(environment.binding.bind(owner, network))
        assertNull(environment.binding.current())
        assertEquals(listOf(network, null), environment.bindings)
    }

    @Test
    fun lossAfterProactiveInvalidationIsStillReportedOnce() {
        val environment = Environment()
        val owner = Any()
        val network = environment.liveNetwork(111)
        environment.binding.replaceRequest(owner)
        assertTrue(environment.binding.bind(owner, network))
        environment.liveIds.clear()
        assertNull(environment.binding.current())

        assertTrue("Async onLost after current() must still notify", environment.binding.lost(owner, TestNetwork(111)))
        assertFalse("Notification is one-shot", environment.binding.lost(owner, TestNetwork(111)))
        assertEquals(listOf(network, null), environment.bindings)
    }

    @Test
    fun invalidatedNetworkOfRetiredRequestIsNotReported() {
        val environment = Environment()
        val owner = Any()
        val network = environment.liveNetwork(111)
        environment.binding.replaceRequest(owner)
        assertTrue(environment.binding.bind(owner, network))
        environment.liveIds.clear()
        assertNull(environment.binding.current())
        val next = Any()
        environment.binding.replaceRequest(next)

        assertFalse(environment.binding.lost(owner, network))
        assertFalse(environment.binding.lost(next, network))
    }

    private data class TestNetwork(
        val id: Int,
    )

    private class Environment {
        val liveIds = mutableSetOf<Int>()
        val bindings = mutableListOf<TestNetwork?>()
        var allowBinding = true
        val binding =
            NetworkBinding<TestNetwork, Any>(
                bindProcess = { network ->
                    if (network != null && !allowBinding) {
                        false
                    } else {
                        bindings += network
                        true
                    }
                },
                isUsable = { it.id in liveIds },
            )

        fun liveNetwork(id: Int): TestNetwork = TestNetwork(id).also { liveIds += id }
    }
}
