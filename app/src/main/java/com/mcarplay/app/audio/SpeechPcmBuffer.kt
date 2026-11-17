package com.mcarplay.app.audio

import com.mcarplay.app.carlife.CarLifeAudioEvent

/** At most 300ms of live assistant output. No media replay and no microphone capture. */
internal class SpeechPcmBuffer(
    private val sampleRate: Int,
) {
    private val limit = sampleRate * 2 * 300 / 1000
    private val chunks = ArrayDeque<ByteArray>()
    private var bytes = 0
    private var converter: PcmResampler? = null
    private var headOffset = 0

    @Synchronized fun offer(event: CarLifeAudioEvent) {
        if (event !is CarLifeAudioEvent.Pcm) {
            if (event is CarLifeAudioEvent.Init) clear()
            return
        }
        if (event.sampleRate !in 8_000..192_000 ||
            event.channels !in 1..2 ||
            event.littleEndian.isEmpty() ||
            event.littleEndian.size > 48_000 ||
            event.littleEndian.size % (event.channels * 2) != 0
        ) {
            return
        }
        val resampler =
            converter?.takeIf { it.sourceRate == event.sampleRate && it.sourceChannels == event.channels }
                ?: PcmResampler(event.sampleRate, event.channels, sampleRate).also { converter = it }
        val stereo = resampler.convert(event.littleEndian)
        val mono = ByteArray(stereo.size / 2)
        for (i in mono.indices step 2) {
            val j = i * 2
            val left = ((stereo[j].toInt() and 255) or (stereo[j + 1].toInt() shl 8)).toShort().toInt()
            val right = ((stereo[j + 2].toInt() and 255) or (stereo[j + 3].toInt() shl 8)).toShort().toInt()
            val mixed = (left + right) / 2
            mono[i] = mixed.toByte()
            mono[i + 1] = (mixed shr 8).toByte()
        }
        val recent = if (mono.size > limit) mono.copyOfRange(mono.size - limit, mono.size) else mono
        while (chunks.isNotEmpty() && bytes + recent.size > limit) {
            bytes -= chunks.removeFirst().size - headOffset
            headOffset = 0
        }
        if (recent.isNotEmpty()) {
            chunks.addLast(recent)
            bytes += recent.size
        }
    }

    @Synchronized fun frame(frames: Int): ByteArray {
        val result = ByteArray(frames * 2)
        var offset = 0
        while (offset < result.size && chunks.isNotEmpty()) {
            val head = chunks.first()
            val size = minOf(result.size - offset, head.size - headOffset)
            head.copyInto(result, offset, headOffset, headOffset + size)
            offset += size
            headOffset += size
            bytes -= size
            if (headOffset == head.size) {
                chunks.removeFirst()
                headOffset = 0
            }
        }
        return result
    }

    @Synchronized fun clear() {
        chunks.clear()
        bytes = 0
        headOffset = 0
        converter = null
    }
}
