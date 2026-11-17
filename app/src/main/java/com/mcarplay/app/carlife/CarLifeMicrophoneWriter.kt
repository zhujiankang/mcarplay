package com.mcarplay.app.carlife

import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 16kHz mono s16LE, bounded to 160ms. Never block the AirPlay event/UDP reader. */
internal class CarLifeMicrophoneWriter(
    private val socket: () -> Socket?,
    private val cipher: () -> CarLifePayloadCipher?,
    private val log: (String) -> Unit,
) : AutoCloseable {
    private data class Chunk(
        val generation: Long,
        val timestamp: Int,
        val pcm: ByteArray,
        val recording: Recording,
    )

    private class Recording(
        val generation: Long,
    ) {
        val queued =
            java.util.concurrent.atomic
                .AtomicLong()
        val written =
            java.util.concurrent.atomic
                .AtomicLong()
        val bytes =
            java.util.concurrent.atomic
                .AtomicLong()
        val dropped =
            java.util.concurrent.atomic
                .AtomicLong()
        val discarded =
            java.util.concurrent.atomic
                .AtomicLong()
        val failed =
            java.util.concurrent.atomic
                .AtomicLong()
        val inFlight =
            java.util.concurrent.atomic
                .AtomicInteger()
        val ended =
            java.util.concurrent.atomic
                .AtomicBoolean()
        val firstWriteMs =
            java.util.concurrent.atomic
                .AtomicLong(-1)
        val lastWriteMs =
            java.util.concurrent.atomic
                .AtomicLong(-1)
        private val started = System.nanoTime()

        fun wrote(size: Int): Long {
            val elapsed = (System.nanoTime() - started) / 1_000_000
            firstWriteMs.compareAndSet(-1, elapsed)
            lastWriteMs.set(elapsed)
            bytes.addAndGet(size.toLong())
            return written.incrementAndGet()
        }

        fun summary(phase: String) =
            "MIC: carlifeRecording generation=$generation phase=$phase queued=${queued.get()} written=${written.get()} " +
                "bytes=${bytes.get()} dropped=${dropped.get()} discarded=${discarded.get()} " +
                "failed=${failed.get()} inFlight=${inFlight.get()} " +
                "firstWriteMs=${firstWriteMs.get()} lastWriteMs=${lastWriteMs.get()}"
    }

    private val queue = ArrayBlockingQueue<Chunk>(8)
    private var generation = 0L
    private var active = false
    private var pending = ByteArray(640)
    private var filled = 0
    private var timestamp = 0
    private var recording: Recording? = null

    @Volatile private var closed = false
    private var worker: Thread? = null

    @Synchronized
    fun begin() {
        if (closed) return
        end()
        active = true
        recording = Recording(generation)
        timestamp = 0
        if (worker != null) return
        worker =
            thread(name = "carlife-mic-write", isDaemon = true) {
                while (!closed) {
                    val chunk =
                        try {
                            queue.poll(500, TimeUnit.MILLISECONDS)
                        } catch (_: InterruptedException) {
                            break
                        } ?: continue
                    val stats = chunk.recording
                    stats.inFlight.incrementAndGet()
                    try {
                        if (!valid(chunk.generation)) {
                            stats.discarded.incrementAndGet()
                            continue
                        }
                        val target = socket()
                        val encryption = cipher()
                        if (target == null || encryption == null) {
                            stats.discarded.incrementAndGet()
                            continue
                        }
                        val body = encryption.encrypt(chunk.pcm)
                        synchronized(target) {
                            if (valid(chunk.generation)) {
                                target.getOutputStream().write(CarLifeProtocol.encodeVideo(MIC_DATA, chunk.timestamp, body))
                                target.getOutputStream().flush()
                                val count = stats.wrote(chunk.pcm.size)
                                if (count == 1L || count % 250L == 0L) {
                                    log("MIC: carlifeWritten=$count dropped=${stats.dropped.get()} kind=0x58001 rate=16000 channels=1")
                                }
                            } else {
                                stats.discarded.incrementAndGet()
                            }
                        }
                    } catch (_: Exception) {
                        stats.failed.incrementAndGet()
                        val stopped =
                            synchronized(this@CarLifeMicrophoneWriter) {
                                if (generation == chunk.generation) {
                                    end()
                                    true
                                } else {
                                    false
                                }
                            }
                        log("MIC: CarLife write failed generation=${chunk.generation} stoppedMatchingRecording=$stopped")
                    } finally {
                        chunk.pcm.fill(0)
                        if (stats.inFlight.decrementAndGet() == 0 && stats.ended.get()) log(stats.summary("drained"))
                    }
                }
            }
    }

    @Synchronized
    fun offer(pcm: ByteArray): Boolean {
        if (!active || closed || pcm.size % 2 != 0) return false
        var offset = 0
        while (offset < pcm.size) {
            val size = minOf(pcm.size - offset, pending.size - filled)
            pcm.copyInto(pending, filled, offset, offset + size)
            offset += size
            filled += size
            if (filled == pending.size) {
                val stats = recording ?: return false
                val chunk = Chunk(generation, timestamp, pending, stats)
                if (!queue.offer(chunk)) {
                    chunk.pcm.fill(0)
                    stats.dropped.incrementAndGet()
                } else {
                    stats.queued.incrementAndGet()
                }
                pending = ByteArray(640)
                filled = 0
                timestamp += 20
            }
        }
        return true
    }

    @Synchronized
    private fun valid(value: Long) = !closed && active && generation == value

    @Synchronized
    fun end() {
        val previous = recording
        recording = null
        active = false
        generation++
        while (true) {
            val chunk = queue.poll() ?: break
            chunk.recording.discarded.incrementAndGet()
            chunk.pcm.fill(0)
        }
        pending.fill(0)
        filled = 0
        previous?.let {
            it.ended.set(true)
            log(it.summary("ended"))
        }
    }

    override fun close() {
        end()
        closed = true
        worker?.interrupt()
    }

    companion object {
        const val MIC_DATA = 0x58001
    }
}
