package com.mcarplay.app.audio

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Bounded network microphone input; closing the socket unblocks receive immediately. */
internal class CarMicrophoneReceiver(
    private val peer: InetAddress,
    private val onPcm: (ByteArray) -> Boolean,
    private val log: (String) -> Unit,
) : AutoCloseable {
    private val socket =
        DatagramSocket(0).apply {
            soTimeout = 500
            receiveBufferSize = 128 * 1024
        }
    val localPort: Int get() = socket.localPort
    private val received = AtomicLong()
    private val decoded = AtomicLong()
    private val delivered = AtomicLong()

    @Volatile private var closed = false

    fun start(
        key: ByteArray,
        sampleRate: Int,
    ) {
        val decoder = MicrophoneRtpDecoder(key)
        val resampler = PcmResampler(sampleRate, 1, 16_000)
        thread(name = "car-mic-input", isDaemon = true) {
            val buffer = ByteArray(8193)
            try {
                while (!closed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    if (closed || packet.address != peer) continue
                    received.incrementAndGet()
                    val pcm = decoder.decode(buffer.copyOf(packet.length)) ?: continue
                    val count = decoded.incrementAndGet()
                    val stereo = resampler.convert(pcm)
                    val mono = ByteArray(stereo.size / 2)
                    for (i in mono.indices step 2) {
                        mono[i] = stereo[i * 2]
                        mono[i + 1] = stereo[i * 2 + 1]
                    }
                    if (onPcm(mono)) delivered.incrementAndGet()
                    if (count == 1L || count % 250L == 0L) summary()
                }
            } catch (_: Exception) {
                if (!closed) log("MIC: input receiver stopped unexpectedly")
            } finally {
                socket.close()
                summary()
                key.fill(0)
            }
        }
    }

    private fun summary() = log("MIC: received=${received.get()} authenticated=${decoded.get()} queued=${delivered.get()}")

    override fun close() {
        closed = true
        socket.close()
    }
}
