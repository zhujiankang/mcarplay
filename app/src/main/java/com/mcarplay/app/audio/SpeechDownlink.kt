package com.mcarplay.app.audio

import com.mcarplay.app.airplay.TimingServer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/** Lease-scoped mono assistant PCM, with silence when idle, for a negotiated duplex speech stream. */
internal class SpeechDownlink(
    peer: InetAddress,
    dataPort: Int,
    controlPort: Int,
    private val key: ByteArray,
    sampleRate: Int,
    private val allowed: () -> Boolean,
    log: (String) -> Unit,
    private val pcm: SpeechPcmBuffer = SpeechPcmBuffer(sampleRate),
) : AutoCloseable {
    private val frames =
        (sampleRate / 50).also {
            require(sampleRate in listOf(16000, 24000) && dataPort in 1..65535 && controlPort in 0..65535)
        }
    private val socket = DatagramSocket()

    @Volatile private var closed = false
    private val worker: Thread

    init {
        val packetizer = AudioSender(peer.hostAddress!!, dataPort, key, {}, sampleRate = sampleRate, streamType = 100)
        worker =
            thread(name = "speech-downlink", isDaemon = true) {
                var count = 0L
                var next = System.nanoTime()
                try {
                    while (!closed && allowed()) {
                        val stamp = count * frames
                        val packet = packetizer.sealPacket(pcm.frame(frames), (count and 65535).toInt(), stamp, count, count == 0L)
                        if (closed || !allowed()) break
                        socket.send(DatagramPacket(packet, packet.size, peer, dataPort))
                        if (controlPort > 0 && count % 50 == 0L) {
                            val sync =
                                AudioSyncPacket.build(
                                    (count / 50).toInt(),
                                    stamp,
                                    stamp + frames,
                                    TimingServer.ntpFromNanos(System.nanoTime()),
                                    count == 0L,
                                )
                            if (closed || !allowed()) break
                            socket.send(DatagramPacket(sync, sync.size, peer, controlPort))
                        }
                        count++
                        next += 20_000_000L
                        val wait = next - System.nanoTime()
                        if (wait > 0) Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt()) else next = System.nanoTime()
                    }
                } catch (_: Exception) {
                    if (!closed && allowed()) log("MIC: speech downlink stopped unexpectedly")
                } finally {
                    socket.close()
                    key.fill(0)
                    pcm.clear()
                    log("MIC: speech downlink stopped packets=$count")
                }
            }
    }

    override fun close() {
        closed = true
        socket.close()
        worker.interrupt()
    }
}
