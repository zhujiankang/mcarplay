package com.mcarplay.app.iap2

import com.mcarplay.app.media.CarMediaSnapshot

/** Per-connection subscription and artwork transfer. All methods run under the owning transport lock. */
internal class NowPlayingLink(
    private val provider: () -> CarMediaSnapshot?,
    private val sendControl: (ByteArray) -> Boolean,
    private val sendFile: (ByteArray) -> Boolean,
    private val maximumPayload: () -> Int,
    private val fileSupported: () -> Boolean,
    private val ready: () -> Boolean,
    private val now: () -> Long,
    private val log: (String) -> Unit,
) {
    private var subscribed = false
    private var failed = false
    private var lastPoll: Long? = null
    private var lastSent: ByteArray? = null
    private var imageKey: String? = null
    private var imageId: Int? = null
    private var nextId = 1
    private var pending: ByteArray? = null
    private var setupSent = false
    private var started = false
    private var delivered = false
    private var offset = 0
    private var deadline = 0L
    private var artworkUnavailable = false
    private var artworkRequests = 0
    private var updates = 0L

    fun control(frame: Iap2Csm.Frame): Boolean =
        when (frame.messageId) {
            0x5000 -> {
                subscribed = true
                failed = false
                cancelArtwork()
                imageKey = null
                lastSent = null
                lastPoll = null
                log("MEDIA-BRIDGE: subscribed fileTransfer=${fileSupported()}")
                tick()
                true
            }
            0x5002 -> {
                subscribed = false
                cancelArtwork()
                lastSent = null
                log("MEDIA-BRIDGE: unsubscribed")
                true
            }
            0x5003 -> {
                // Artwork size/format attributes are receipt evidence; current output is bounded JPEG.
                artworkRequests++
                log("MEDIA-BRIDGE: artworkAttributesReceived=$artworkRequests")
                true
            }
            else -> false
        }

    fun transportFailure() {
        if (failed) return
        failed = true
        pending = null
        log("MEDIA-BRIDGE: optional packet awaiting ACK; new media suspended, link retries retained")
    }

    fun tick() {
        if (!subscribed || failed || !ready()) return
        val time = now()
        if (pending != null && setupSent && time >= deadline) {
            cancelArtwork()
            artworkUnavailable = true
            lastSent = null
            log("MEDIA-BRIDGE: artwork response timeout; text updates continue")
        }
        if (lastPoll == null || time - lastPoll!! >= 1_000) {
            lastPoll = time
            val snapshot = provider() ?: CarMediaSnapshot()
            val key = snapshot.artworkKey.takeIf { snapshot.artwork?.size in 1..MAX_ARTWORK }
            if (key != imageKey) {
                cancelArtwork()
                imageKey = key
                imageId = if (key != null && fileSupported() && !artworkUnavailable && nextId <= 255) nextId++ else null
                pending = snapshot.artwork?.takeIf { imageId != null }
                delivered = false
            }
            val id = imageId.takeIf { !artworkUnavailable && fileSupported() }
            val payload = encode(snapshot, id, maximumPayload()) ?: return
            if (lastSent?.contentEquals(payload) != true) {
                if (!sendControl(payload)) {
                    lastPoll = null
                    return
                }
                lastSent = payload
                updates++
                if (updates <= 3 || updates and (updates - 1) == 0L) {
                    log(
                        "MEDIA-BRIDGE: updatesQueued=$updates artworkDeclared=${id != null} lyricCompatibility=${snapshot.lyricCompatibility}",
                    )
                }
            }
        }
        val bytes = pending ?: return
        val id = imageId ?: return
        if (!setupSent) {
            val setup = ByteArray(12)
            setup[0] = id.toByte()
            setup[1] = 4
            for (index in 0..7) setup[2 + index] = (bytes.size.toLong() ushr (56 - 8 * index)).toByte()
            setup[11] = 2 // Now Playing Artwork
            if (!sendFile(setup)) return
            setupSent = true
            deadline = time + 8_000
            log("MEDIA-BRIDGE: artworkSetup id=$id bytes=${bytes.size}")
        }
        if (!started || delivered) return
        // At most four chunks per tick, plus the transport's bounded optional window.
        repeat(4) {
            if (offset >= bytes.size) return
            val size = minOf(1_000, maximumPayload() - 2, bytes.size - offset)
            if (size <= 0) return
            val end = offset + size
            val kind = (if (offset == 0) 0x80 else 0) or (if (end == bytes.size) 0x40 else 0)
            val chunk = byteArrayOf(id.toByte(), kind.toByte()) + bytes.copyOfRange(offset, end)
            if (!sendFile(chunk)) return
            offset = end
            deadline = time + 8_000
        }
    }

    fun file(datagram: ByteArray) {
        if (!subscribed || failed || delivered || datagram.size != 2 || !setupSent || datagram[0].toInt() and 255 != imageId) return
        when (datagram[1].toInt() and 255) {
            1 ->
                if (!started) {
                    started = true
                    deadline = now() + 8_000
                }
            5 ->
                if (offset == 0 || offset == pending?.size) {
                    delivered = true
                    pending = null
                    log("MEDIA-BRIDGE: artworkSuccess id=$imageId")
                }
            2, 3, 6 -> {
                pending = null
                artworkUnavailable = true
                lastSent = null
                log("MEDIA-BRIDGE: artwork declined/paused/cancelled; text updates continue")
            }
        }
    }

    private fun cancelArtwork() {
        if (pending != null && setupSent) sendFile(byteArrayOf((imageId ?: 0).toByte(), 2))
        pending = null
        setupSent = false
        started = false
        offset = 0
    }

    companion object {
        const val MAX_ARTWORK = 131_072

        fun encode(
            value: CarMediaSnapshot,
            artworkId: Int?,
            maxPayload: Int,
        ): ByteArray? {
            if (maxPayload < 100) return null
            val budget = ((maxPayload - 100) / 4).coerceIn(0, 384)

            fun text(
                id: Int,
                value: String,
            ): ByteArray {
                var clean = value.filter { !it.isISOControl() }.take(128)
                while (clean.encodeToByteArray().size > budget) clean = clean.dropLast(1)
                return Iap2Csm.string(id, clean)
            }

            fun time(
                id: Int,
                value: Long,
            ) = Iap2Csm.u32(id, value.coerceIn(0, 0xFFFFFFFFL))
            var item = text(1, value.title) + time(4, value.durationMs) + text(6, value.album) + text(12, value.artist)
            item += Iap2Csm.u8(26, artworkId?.takeIf { it in 1..255 } ?: 0)
            val playback =
                Iap2Csm.u8(0, value.status.coerceIn(0, 2)) + time(1, value.elapsedMs) +
                    Iap2Csm.u8(5, 0) + Iap2Csm.u8(6, 0) + text(7, value.app)
            return Iap2Csm.encode(0x5001, Iap2Csm.param(0, item) + Iap2Csm.param(1, playback)).takeIf { it.size <= maxPayload }
        }
    }
}
