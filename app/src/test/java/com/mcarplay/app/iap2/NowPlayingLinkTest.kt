package com.mcarplay.app.iap2

import com.mcarplay.app.media.CarMediaSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingLinkTest {
    @Test fun multibyteAndControlCharactersFitTheNegotiatedControlPacket() {
        val data =
            CarMediaSnapshot(
                title = "歌曲🎵\u0000\n".repeat(200),
                artist = "艺术家".repeat(200),
                album = "专辑".repeat(200),
                app = "播放器".repeat(200),
            )
        val encoded = NowPlayingLink.encode(data, null, 256)!!
        assertTrue(encoded.size <= 256)
        val item = fields(encoded, 0)
        assertTrue(item.getValue(1).asString()!!.isNotEmpty())
        assertFalse(item.getValue(1).asString()!!.any { it.isISOControl() })
    }

    private class Harness {
        var time = 0L
        var data: CarMediaSnapshot? = CarMediaSnapshot("Title", "Artist", "Album", "Player", 120000, 1234, 1)
        var fileSupported = true
        var ready = true
        var writable = true
        var limit = 2048
        val controls = mutableListOf<ByteArray>()
        val files = mutableListOf<ByteArray>()
        val logs = mutableListOf<String>()
        val link =
            NowPlayingLink(
                provider = { data },
                sendControl = { if (writable) controls.add(it.copyOf()) else false },
                sendFile = { if (writable) files.add(it.copyOf()) else false },
                maximumPayload = { limit },
                fileSupported = { fileSupported },
                ready = { ready },
                now = { time },
                log = logs::add,
            )

        fun subscribe() = link.control(Iap2Csm.Frame(0x5000, ByteArray(0)))

        fun response(
            id: Int,
            command: Int,
        ) = link.file(byteArrayOf(id.toByte(), command.toByte()))

        fun image(
            size: Int = 2501,
            key: String = "image",
        ) {
            data = data!!.copy(artwork = ByteArray(size) { (it % 251).toByte() }, artworkKey = key)
        }
    }

    private fun fields(
        frame: ByteArray,
        group: Int,
    ): Map<Int, Iap2Csm.Param> {
        val message = Iap2Csm.Parser().offer(frame).single()
        assertEquals(0x5001, message.messageId)
        return Iap2Csm.parseParams(Iap2Csm.parseParams(message.body).single { it.id == group }.value).associateBy { it.id }
    }

    @Test fun subscriptionPublishesRealFieldsAndStopClearsTheCardWithoutLoggingPrivateText() {
        val h = Harness()
        h.subscribe()
        val item = fields(h.controls.single(), 0)
        assertEquals("Title", item.getValue(1).asString())
        assertEquals("Album", item.getValue(6).asString())
        assertEquals("Artist", item.getValue(12).asString())
        assertArrayEquals(byteArrayOf(0), item.getValue(26).value)
        h.data = null
        h.time += 1000
        h.link.tick()
        assertEquals("", fields(h.controls.last(), 0).getValue(1).asString())
        assertArrayEquals(byteArrayOf(0), fields(h.controls.last(), 1).getValue(0).value)
        assertFalse(h.logs.any { it.contains("Title") || it.contains("Artist") || it.contains("Album") })
    }

    @Test fun artworkWaitsForStartThenChunksReassembleExactlyAndSuccessIsRecordedOnce() {
        val h = Harness()
        h.image()
        val original = h.data!!.artwork!!
        h.subscribe()
        assertEquals(1, h.files.size)
        val setup = h.files.single()
        assertEquals(12, setup.size)
        assertEquals(4, setup[1].toInt())
        assertEquals(2, setup[11].toInt())
        val id = setup[0].toInt() and 255
        assertEquals(
            id,
            fields(h.controls.single(), 0)
                .getValue(26)
                .value
                .single()
                .toInt() and 255,
        )
        repeat(3) { h.link.tick() }
        assertEquals(1, h.files.size)
        h.response(id, 1)
        h.link.tick()
        val chunks = h.files.drop(1)
        assertEquals(listOf(0x80, 0, 0x40), chunks.map { it[1].toInt() and 255 })
        assertArrayEquals(original, chunks.flatMap { it.drop(2) }.toByteArray())
        h.response(id, 5)
        h.response(id, 5)
        assertEquals(1, h.logs.count { it.contains("artworkSuccess") })
        h.time += 1000
        h.data = h.data!!.copy(elapsedMs = 2000)
        h.link.tick()
        assertEquals(4, h.files.size)
    }

    @Test fun smallSingleChunkUsesFirstAndLastFlagsAndNegotiatedMtuBoundsAllChunks() {
        val h = Harness()
        h.limit = 128
        h.image(10)
        h.subscribe()
        val id = h.files.single()[0].toInt() and 255
        h.response(id, 1)
        h.link.tick()
        assertEquals(0xC0, h.files.last()[1].toInt() and 255)
        assertTrue(h.files.all { it.size <= 128 })
        assertTrue(h.controls.all { it.size <= 128 })
    }

    @Test fun newImageCancelsTheOldTransferAndIgnoresItsLateResponses() {
        val h = Harness()
        h.image(key = "first")
        h.subscribe()
        val oldId = h.files.single()[0].toInt() and 255
        h.time += 1000
        h.image(50, "second")
        h.link.tick()
        assertEquals(2, h.files[1][1].toInt())
        val newId = h.files.last()[0].toInt() and 255
        assertTrue(oldId != newId)
        val before = h.files.size
        h.response(oldId, 1)
        h.link.tick()
        assertEquals(before, h.files.size)
        h.response(newId, 1)
        h.link.tick()
        assertArrayEquals(h.data!!.artwork, h.files.last().copyOfRange(2, h.files.last().size))
    }

    @Test fun artworkTimeoutOrRejectionClearsTheArtworkIdWhileTextKeepsUpdating() {
        for (reject in listOf(false, true)) {
            val h = Harness()
            h.image()
            h.subscribe()
            if (reject) h.response(h.files.single()[0].toInt() and 255, 6) else h.time = 8000
            h.time += 1000
            h.data = h.data!!.copy(title = "Next title")
            h.link.tick()
            assertArrayEquals(byteArrayOf(0), fields(h.controls.last(), 0).getValue(26).value)
            assertEquals("Next title", fields(h.controls.last(), 0).getValue(1).asString())
        }
    }

    @Test fun missingFileCapabilityAndBackpressureDoNotInventAnArtworkTransfer() {
        val h = Harness()
        h.fileSupported = false
        h.image()
        h.writable = false
        h.subscribe()
        assertTrue(h.controls.isEmpty())
        h.writable = true
        h.link.tick()
        assertEquals(1, h.controls.size)
        assertTrue(h.files.isEmpty())
        assertArrayEquals(byteArrayOf(0), fields(h.controls.single(), 0).getValue(26).value)
        assertNull(NowPlayingLink.encode(h.data!!, null, 99))
    }

    @Test fun unsubscribeAndOptionalTransportFailureStopUpdatesUntilANewSubscription() {
        val h = Harness()
        h.subscribe()
        h.link.transportFailure()
        h.data = h.data!!.copy(title = "Changed")
        h.time += 2000
        h.link.tick()
        assertEquals(1, h.controls.size)
        h.subscribe()
        assertEquals(2, h.controls.size)
        h.link.control(Iap2Csm.Frame(0x5002, ByteArray(0)))
        h.data = null
        h.time += 2000
        h.link.tick()
        assertEquals(2, h.controls.size)
    }
}
