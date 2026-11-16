package com.mcarplay.app.audio

import com.mcarplay.app.carlife.CarLifeAudioEvent
import com.mcarplay.app.carlife.CarLifeChannel

/** Short prompt preroll while an acknowledged audio borrow/SETUP completes. No disk or unbounded PCM queue. */
internal class CarLifePromptBuffer {
    private data class Entry(
        val channel: CarLifeChannel,
        val event: CarLifeAudioEvent.Pcm,
        val at: Long,
    )

    private val entries = ArrayDeque<Entry>()
    private var bytes = 0

    @Synchronized fun offer(
        channel: CarLifeChannel,
        event: CarLifeAudioEvent.Pcm,
        now: Long,
    ) {
        if (channel == CarLifeChannel.MEDIA || event.littleEndian.size > LIMIT_BYTES) return
        prune(now)
        while (entries.isNotEmpty() && (entries.size >= 32 || bytes + event.littleEndian.size > LIMIT_BYTES)) remove()
        entries.addLast(Entry(channel, event.copy(littleEndian = event.littleEndian.copyOf()), now))
        bytes += event.littleEndian.size
    }

    @Synchronized fun drain(now: Long): List<Pair<CarLifeChannel, CarLifeAudioEvent.Pcm>> {
        prune(now)
        return entries.map { it.channel to it.event }.also {
            entries.clear()
            bytes = 0
        }
    }

    @Synchronized fun clear() {
        entries.clear()
        bytes = 0
    }

    private fun remove() {
        bytes -=
            entries
                .removeFirst()
                .event.littleEndian.size
    }

    private fun prune(now: Long) {
        while (entries.firstOrNull()?.let { now - it.at >= 1500 } == true) remove()
    }

    private companion object {
        const val LIMIT_BYTES = 262144
    }
}
