package com.mcarplay.app.audio

import com.mcarplay.app.airplay.AirPlayCrypto

/** Authenticated AirPlay input RTP. No payload, speech or coordinates are logged/persisted. */
internal class MicrophoneRtpDecoder(
    private val key: ByteArray,
) {
    private var previousNonce: Long? = null
    private var previousSequence: Int? = null
    private var ssrc: ByteArray? = null

    fun decode(packet: ByteArray): ByteArray? {
        if (packet.size !in 38..8192 || packet[0].toInt() and 0xC0 != 0x80) return null
        // Padding is not part of the supported input format.
        if (packet[0].toInt() and 0x20 != 0) return null
        var header = 12 + 4 * (packet[0].toInt() and 0x0F)
        if (header > packet.size - 24) return null
        if (packet[0].toInt() and 0x10 != 0) {
            if (header + 4 > packet.size - 24) return null
            val words = ((packet[header + 2].toInt() and 255) shl 8) or (packet[header + 3].toInt() and 255)
            header += 4 + 4 * words
        }
        if (header > packet.size - 26 || (packet.size - header - 24) % 2 != 0) return null
        val tail = packet.copyOfRange(packet.size - 8, packet.size)
        var counter = 0L
        for (i in 0..7) counter = counter or ((tail[i].toLong() and 255) shl (8 * i))
        if (previousNonce?.let { java.lang.Long.compareUnsigned(counter, it) <= 0 } == true) return null
        val sequence = ((packet[2].toInt() and 255) shl 8) or (packet[3].toInt() and 255)
        if (previousSequence?.let { ((sequence - it) and 0xFFFF) !in 1..32767 } == true) return null
        val source = packet.copyOfRange(8, 12)
        if (ssrc?.contentEquals(source) == false) return null
        val nonce = ByteArray(12).also { tail.copyInto(it, 4) }
        val pcm =
            runCatching {
                AirPlayCrypto.chachaOpen(key, nonce, packet.copyOfRange(header, packet.size - 8), packet.copyOfRange(4, 12))
            }.getOrNull() ?: return null
        previousNonce = counter
        previousSequence = sequence
        ssrc = source
        // Network LPCM is s16 BE, CarLife microphone is s16 LE mono.
        for (i in pcm.indices step 2) {
            val first = pcm[i]
            pcm[i] = pcm[i + 1]
            pcm[i + 1] = first
        }
        return pcm
    }
}
