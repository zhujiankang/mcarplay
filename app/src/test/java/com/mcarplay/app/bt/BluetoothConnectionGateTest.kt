package com.mcarplay.app.bt

import com.mcarplay.app.transport.Iap2LinkChannel
import com.mcarplay.app.transport.StreamDuplexStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BluetoothConnectionGateTest {
    @Test fun inboundDuringConnectCancelsPendingAndRejectsLateSuccess() {
        val gate = BluetoothConnectionGate()
        val cancelled = AtomicInteger()
        val outbound = gate.beginOutbound { cancelled.incrementAndGet() }!!
        val inbound = gate.acceptInbound {}!!
        assertEquals(1, cancelled.get())
        assertFalse(outbound.connected())
        assertFalse(outbound.isCurrent)
        outbound.close()
        assertTrue(inbound.isCurrent)
        assertNull(gate.beginOutbound {})
        inbound.close()
        assertFalse(gate.occupied)
    }

    @Test fun establishedOutboundIsNotReplacedByDuplicateIncoming() {
        val gate = BluetoothConnectionGate()
        val outbound = gate.beginOutbound { error("established socket must stay") }!!
        assertTrue(outbound.connected())
        assertNull(gate.acceptInbound {})
        assertTrue(outbound.isCurrent)
        outbound.close()
        assertNotNull(gate.acceptInbound {})
    }

    @Test fun duplicateIncomingCannotReplaceOwnerOrReleaseItsGate() {
        val gate = BluetoothConnectionGate()
        val first = gate.acceptInbound {}!!
        assertNull(gate.acceptInbound {})
        first.close()
        val next = gate.acceptInbound {}!!
        first.close()
        assertTrue(next.isCurrent)
        assertTrue(gate.inboundActive)
        next.close()
        assertFalse(gate.inboundActive)
    }

    @Test fun onlyOneOutboundCandidateMayConnectAtATime() {
        val gate = BluetoothConnectionGate()
        val first = gate.beginOutbound {}!!
        assertNull(gate.beginOutbound {})
        first.close()
        assertNotNull(gate.beginOutbound {})
    }

    @Test fun cancellationIsIdempotentAndFailureDoesNotLoseInboundOwnership() {
        val gate = BluetoothConnectionGate()
        var calls = 0
        val old =
            gate.beginOutbound {
                calls++
                throw IllegalStateException("close failed")
            }!!
        val inbound = gate.acceptInbound {}!!
        old.cancel()
        assertEquals(1, calls)
        assertTrue(inbound.isCurrent)
    }

    @Test fun cancellationDoesNotHoldGateMonitorWhileClosingTransport() {
        val gate = BluetoothConnectionGate()
        val worker = Executors.newSingleThreadExecutor()
        val observed =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        try {
            gate.beginOutbound {
                observed.set(worker.submit<Boolean> { gate.inboundActive }.get(1, TimeUnit.SECONDS))
            }!!
            assertNotNull(gate.acceptInbound {})
            assertTrue(observed.get())
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun concurrentIncomingAndConnectCompletionChooseOneTransport() {
        val worker = Executors.newFixedThreadPool(2)
        try {
            repeat(20) {
                val gate = BluetoothConnectionGate()
                val outbound = gate.beginOutbound {}!!
                val start = CountDownLatch(1)
                val completed =
                    worker.submit<Boolean> {
                        start.await()
                        outbound.connected()
                    }
                val incoming =
                    worker.submit<BluetoothConnectionGate.Lease?> {
                        start.await()
                        gate.acceptInbound {}
                    }
                start.countDown()
                val accepted = incoming.get(2, TimeUnit.SECONDS)
                assertEquals(accepted == null, completed.get(2, TimeUnit.SECONDS))
                assertEquals(accepted == null, outbound.isCurrent)
                accepted?.close()
                outbound.close()
                assertFalse(gate.occupied)
            }
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun logOrderingRegressionCancelsBlockedProbeAndLeavesIncomingIap2Usable() {
        val gate = BluetoothConnectionGate()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { blockedPeer ->
            Socket("127.0.0.1", blockedPeer.localPort).use { pending ->
                blockedPeer.accept().use {
                    val old = gate.beginOutbound { pending.close() }!!
                    val reader = Executors.newSingleThreadExecutor()
                    val entered = CountDownLatch(1)
                    try {
                        val finished =
                            reader.submit<Boolean> {
                                entered.countDown()
                                runCatching { pending.getInputStream().read() }.isFailure
                            }
                        assertTrue(entered.await(1, TimeUnit.SECONDS))
                        val inbound = gate.acceptInbound {}!!
                        assertTrue(finished.get(2, TimeUnit.SECONDS))
                        assertFalse(old.connected())
                        old.close()
                        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { sessionPeer ->
                            Socket("127.0.0.1", sessionPeer.localPort).use { phone ->
                                sessionPeer.accept().use { car ->
                                    Iap2LinkChannel(StreamDuplexStream(phone.getInputStream(), phone.getOutputStream())).use { phoneLink ->
                                        Iap2LinkChannel(StreamDuplexStream(car.getInputStream(), car.getOutputStream())).use { carLink ->
                                            assertTrue(phoneLink.awaitReady(3000))
                                            assertTrue(carLink.awaitReady(3000))
                                            assertNull(gate.beginOutbound { error("second candidate must not connect") })
                                            val message = byteArrayOf(1, 2, 3)
                                            phoneLink.sendControl(message)
                                            assertArrayEquals(message, carLink.recvControl(2000))
                                        }
                                    }
                                }
                            }
                        }
                        inbound.close()
                        assertFalse(gate.occupied)
                    } finally {
                        reader.shutdownNow()
                    }
                }
            }
        }
    }
}
