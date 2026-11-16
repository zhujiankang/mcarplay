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
    )

    private val queue = ArrayBlockingQueue<Chunk>(8)
    private var generation = 0L
    private var active = false
    private var pending = ByteArray(640)
    private var filled = 0
    private var timestamp = 0
    private var sent = 0L
    private var dropped = 0L

    @Volatile private var closed = false
    private var worker: Thread? = null

    @Synchronized
    fun begin() {
        if (closed) return
        end()
        active = true
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
                    if (!valid(chunk.generation)) continue
                    val target = socket() ?: continue
                    val encryption = cipher() ?: continue
                    try {
                        val body = encryption.encrypt(chunk.pcm)
                        synchronized(target) {
                            if (valid(chunk.generation)) {
                                target.getOutputStream().write(CarLifeProtocol.encodeVideo(MIC_DATA, chunk.timestamp, body))
                                target.getOutputStream().flush()
                                sent++
                                if (sent == 1L || sent % 250L == 0L) summary()
                            }
                        }
                    } catch (_: Exception) {
                        end()
                        log("MIC: CarLife write failed; voice input stopped")
                    } finally {
                        chunk.pcm.fill(0)
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
                val chunk = Chunk(generation, timestamp, pending)
                if (!queue.offer(chunk)) {
                    chunk.pcm.fill(0)
                    dropped++
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
        active = false
        generation++
        while (true) {
            (queue.poll() ?: break).pcm.fill(0)
        }
        pending.fill(0)
        filled = 0
    }

    private fun summary() = log("MIC: carlifeWritten=$sent dropped=$dropped kind=0x58001 rate=16000 channels=1")

    override fun close() {
        end()
        closed = true
        worker?.interrupt()
        summary()
    }

    companion object {
        const val MIC_DATA = 0x58001
    }
}
