package com.mcarplay.app.audio

import com.mcarplay.app.airplay.AirPlayClient
import com.mcarplay.app.carlife.CarLifeSession
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Serializes input SETUP/TEARDOWN. Late results can never activate a newer recognition/session. */
internal class CarLifeMicrophoneBridge(
    private val log: (String) -> Unit,
    private val recognitionTimeoutMs: Long = 30_000,
    private val firstPacketTimeoutMs: Long = 3_000,
) {
    private val worker =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "voice-control").apply { isDaemon = true }
        }
    private val watchdog =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "voice-timeout").apply { isDaemon = true }
        }

    private class Lease(
        val client: AirPlayClient,
        val source: CarLifeSession,
        val epoch: Long,
        val id: Long,
    ) {
        @Volatile var receiver: CarMicrophoneReceiver? = null

        @Volatile var cancelled = false

        @Volatile var setupAccepted = false
        var timeout: java.util.concurrent.ScheduledFuture<*>? = null
        var inputTimeout: java.util.concurrent.ScheduledFuture<*>? = null
        val cleaned =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
    }

    private var active: Lease? = null

    @Synchronized
    fun request(
        client: AirPlayClient,
        source: CarLifeSession,
        secret: ByteArray,
        outputType: Long?,
    ) {
        if (active?.let { it.source === source && it.client === client && !it.cancelled } == true) return
        stop()
        // A second type100 stream can replace a receiver's active type100 output. Preserve working playback.
        if (outputType != 102L) {
            log("MIC: skipped; dedicated input currently requires active media type102")
            return
        }
        val profile =
            client.microphoneProfile() ?: run {
                log("MIC: skipped; no advertised speechRecognition PCM input")
                return
            }
        val lease = Lease(client, source, client.connectionGeneration, (SecureRandom().nextLong() and Long.MAX_VALUE).coerceAtLeast(1))
        active = lease
        val privateSecret = secret.copyOf()
        source.beginMicrophone()
        worker.execute {
            var started = false
            try {
                if (!valid(lease)) return@execute
                val host = client.activeHost ?: return@execute
                val receiver = CarMicrophoneReceiver(InetAddress.getByName(host), source::offerMicrophone, log)
                lease.receiver = receiver
                if (!valid(lease)) {
                    receiver.close()
                    return@execute
                }
                if (!client.setSpeechRecognitionState(true, lease.epoch)) return@execute
                if (!client.assertModes(expectedEpoch = lease.epoch) || !valid(lease)) return@execute
                lease.setupAccepted = client.setupMicrophone(profile, lease.id, receiver.localPort, lease.epoch, receiver.controlPort)
                if (!lease.setupAccepted) return@execute
                if (!valid(lease)) return@execute
                // Reuse the session's activation guard. Never repeat a Mercedes RECORD after startup.
                if (!client.record(timeoutMillis = 3_000, expectedEpoch = lease.epoch) || !valid(lease)) return@execute
                receiver.start(client.microphoneKey(privateSecret, lease.id), profile.sampleRate)
                lease.inputTimeout =
                    watchdog.schedule({
                        if (valid(lease) && receiver.authenticatedPackets == 0L) {
                            log("MIC: no authenticated input before deadline; stop input, preserve video/control")
                            cancel(lease)
                        }
                    }, firstPacketTimeoutMs, TimeUnit.MILLISECONDS)
                log("MIC: input started rate=${profile.sampleRate} targetRate=16000; awaiting authenticated packets")
                started = true
            } catch (_: Exception) {
                log("MIC: input negotiation failed; preserving media/video session")
                cancel(lease)
            } finally {
                privateSecret.fill(0)
                if (!started) cancel(lease)
            }
        }
        lease.timeout =
            watchdog.schedule({
                cancel(lease)
                log("MIC: recognition timeout; input stopped")
            }, recognitionTimeoutMs, TimeUnit.MILLISECONDS)
    }

    @Synchronized private fun valid(lease: Lease): Boolean =
        active === lease && !lease.cancelled && lease.client.isConnectionCurrent(lease.epoch) && lease.source.isRunning

    @Synchronized
    fun stop(source: CarLifeSession? = null) {
        val lease = active ?: return
        if (source != null && lease.source !== source) return
        active = null
        lease.cancelled = true
        lease.timeout?.cancel(false)
        lease.inputTimeout?.cancel(false)
        lease.source.endMicrophone()
        lease.receiver?.close()
        lease.client.setSpeechRecognitionState(false, lease.epoch)
        worker.execute { cleanup(lease) }
    }

    @Synchronized private fun cancel(lease: Lease) {
        if (active === lease) {
            stop()
        } else {
            lease.cancelled = true
            lease.receiver?.close()
        }
    }

    private fun cleanup(lease: Lease) {
        if (!lease.cleaned.compareAndSet(false, true)) return
        lease.receiver?.close()
        // A rejected SETUP never created a stream. Some receivers treat an unknown type100 teardown broadly.
        if (lease.setupAccepted) {
            runCatching { lease.client.teardownMicrophone(lease.id, lease.epoch) }
        } else {
            log("MIC: input SETUP not accepted; no TEARDOWN for uncreated stream")
        }
        if (lease.client.setSpeechRecognitionState(false, lease.epoch)) {
            runCatching { lease.client.assertModes(expectedEpoch = lease.epoch) }
        }
    }
}
