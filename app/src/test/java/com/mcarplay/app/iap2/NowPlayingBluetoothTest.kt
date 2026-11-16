package com.mcarplay.app.iap2

import com.mcarplay.app.media.CarMediaSnapshot
import com.mcarplay.app.media.CarMediaState
import com.mcarplay.app.transport.Iap2LinkChannel
import com.mcarplay.app.transport.InMemoryStreamPair
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class NowPlayingBluetoothTest {
    @Test(timeout = 15_000)
    fun subscribedBluetoothPumpTransfersMetadataAndArtworkThenClearsTheStoppedCard() {
        val pair = InMemoryStreamPair()
        val logs = CopyOnWriteArrayList<String>()
        val phone = Iap2LinkChannel(pair.sideA, log = logs::add)
        val car = Iap2LinkChannel(pair.sideB)
        val session = Iap2SenderSession(phone, logs::add)
        val pump = Thread({ session.pumpIncomingForever() }, "media-test-pump").apply { isDaemon = true }
        val cover = ("PRIVATE-ARTWORK".repeat(200)).encodeToByteArray()
        try {
            assertTrue(phone.awaitReady(2000))
            assertTrue(car.awaitReady(2000))
            CarMediaState.current =
                CarMediaSnapshot("PRIVATE-TITLE", "PRIVATE-ARTIST", "Album", "Player", 120000, 1000, 1, cover, "cover-key")
            pump.start()
            assertTrue(car.sendControl(Iap2Csm.encode(0x5000)))
            val frame = Iap2Csm.Parser().offer(car.recvControl(3000)!!).single()
            assertEquals(0x5001, frame.messageId)
            val item = Iap2Csm.parseParams(Iap2Csm.parseParams(frame.body).single { it.id == 0 }.value).associateBy { it.id }
            assertEquals("PRIVATE-TITLE", item.getValue(1).asString())
            val id =
                item
                    .getValue(26)
                    .value
                    .single()
                    .toInt() and 255
            val setup = awaitFile(car)
            assertEquals(4, setup[1].toInt())
            assertEquals(id, setup[0].toInt() and 255)
            assertTrue(car.sendMediaFile(byteArrayOf(id.toByte(), 1)))
            val received = ArrayList<Byte>()
            var final = false
            while (!final) {
                val chunk = awaitFile(car)
                assertEquals(id, chunk[0].toInt() and 255)
                received += chunk.drop(2)
                final = chunk[1].toInt() and 0x40 != 0
            }
            assertArrayEquals(cover, received.toByteArray())
            assertTrue(car.sendMediaFile(byteArrayOf(id.toByte(), 5)))
            CarMediaState.current = null
            val stopped = Iap2Csm.Parser().offer(car.recvControl(3000)!!).single()
            val playback = Iap2Csm.parseParams(Iap2Csm.parseParams(stopped.body).single { it.id == 1 }.value).associateBy { it.id }
            assertArrayEquals(byteArrayOf(0), playback.getValue(0).value)
            assertTrue(phone.isAlive)
            assertFalse(logs.any { it.contains("PRIVATE-") })
            val secretHex = "PRIVATE-TITLE".encodeToByteArray().joinToString(" ") { "%02x".format(it) }
            assertFalse(logs.any { it.contains(secretHex) })
        } finally {
            CarMediaState.current = null
            phone.close()
            car.close()
            pump.join(2000)
            assertFalse(pump.isAlive)
        }
    }

    private fun awaitFile(channel: Iap2LinkChannel): ByteArray {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            channel.receiveMediaFile()?.let { return it }
            Thread.sleep(5)
        }
        error("No file transfer packet")
    }
}
