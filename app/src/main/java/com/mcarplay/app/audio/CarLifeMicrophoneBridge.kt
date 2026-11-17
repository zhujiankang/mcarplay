package com.mcarplay.app.audio

import com.mcarplay.app.airplay.AirPlayClient
import com.mcarplay.app.carlife.CarLifeAudioEvent
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
    private val worker: java.util.concurrent.Executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "voice-control").apply { isDaemon = true }
        },
    private val prepareAudio: ((AirPlayClient, Long) -> Boolean)? = null,
    private val onStateChanged: () -> Unit = {},
) {
    private val watchdog =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "voice-timeout").apply { isDaemon = true }
        }

    private class Lease(
        val client: AirPlayClient,
        val source: CarLifeSession,
        val epoch: Long,
        val id: Long,
        val speech: SpeechPcmBuffer,
    ) {
        var borrowedAudio = false

        @Volatile var receiver: CarMicrophoneReceiver? = null

        @Volatile var downlink: SpeechDownlink? = null

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
        if (outputType != null && outputType != 102L && prepareAudio == null) {
            log("MIC: skipped; input type100 would conflict with active output; keep existing audio")
            return
        }
        val profile =
            client.microphoneProfile() ?: run {
                log("MIC: skipped; no advertised speechRecognition PCM input")
                return
            }
        val lease =
            Lease(
                client,
                source,
                client.connectionGeneration,
                (SecureRandom().nextLong() and Long.MAX_VALUE).coerceAtLeast(1),
                SpeechPcmBuffer(profile.sampleRate),
            )
        active = lease
        val privateSecret = secret.copyOf()
        source.beginMicrophone()
        worker.execute {
            var started = false
            try {
                if (!valid(lease, requireAudio = false)) return@execute
                if (!client.modes.phoneOwnsAudio) {
                    if (!client.acquireAudioResource(500, temporary = true, lease.epoch)) {
                        log("MIC: speech audio borrow denied; retain car resource constraints")
                        return@execute
                    }
                    lease.borrowedAudio = true
                }
                if (!valid(lease)) return@execute
                val host = client.activeHost ?: return@execute
                val receiver = CarMicrophoneReceiver(InetAddress.getByName(host), source::offerMicrophone, log)
                lease.receiver = receiver
                if (!valid(lease)) {
                    receiver.close()
                    return@execute
                }
                if (!client.setSpeechRecognitionState(true, lease.epoch)) return@execute
                if (prepareAudio?.invoke(client, lease.epoch) == false || !valid(lease)) {
                    log("MIC: output retirement unconfirmed; no overlapping speech SETUP")
                    return@execute
                }
                onStateChanged()
                if (!client.assertModes(expectedEpoch = lease.epoch) || !valid(lease)) return@execute
                val stream =
                    client.openMicrophone(
                        profile,
                        lease.id,
                        receiver.localPort,
                        lease.epoch,
                        receiver.controlPort,
                        allowPortFallback = true,
                        current = {
                            !lease.cancelled && client.isConnectionCurrent(lease.epoch) && source.isRunning && client.modes.phoneOwnsAudio
                        },
                    )
                lease.setupAccepted = stream.accepted
                if (!stream.accepted || !stream.usable) return@execute
                if (!valid(lease)) return@execute
                // Reuse the session's activation guard. Never repeat a Mercedes RECORD after startup.
                if (!client.record(timeoutMillis = 3_000, expectedEpoch = lease.epoch) || !valid(lease)) return@execute
                receiver.start(client.microphoneKey(privateSecret, lease.id), profile.sampleRate)
                if (stream.dataPort > 0) {
                    val outputKey =
                        com.mcarplay.app.airplay.AirPlayCrypto.hkdfSha512(
                            privateSecret,
                            "DataStream-Salt${lease.id}".toByteArray(Charsets.US_ASCII),
                            "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
                        )
                    lease.downlink =
                        SpeechDownlink(
                            InetAddress.getByName(host),
                            stream.dataPort,
                            stream.controlPort,
                            outputKey,
                            profile.sampleRate,
                            allowed = { !lease.cancelled && client.isConnectionCurrent(lease.epoch) && client.modes.phoneOwnsAudio },
                            log = log,
                            pcm = lease.speech,
                        )
                }
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

    /** Source callback never performs network I/O; bounded mono PCM is drained by the speech worker. */
    @Synchronized fun offerSpeech(
        source: CarLifeSession,
        event: CarLifeAudioEvent,
    ): Boolean {
        val lease = active ?: return false
        if (lease.source !== source || lease.cancelled) return false
        lease.speech.offer(event)
        return true
    }

    @Synchronized private fun valid(
        lease: Lease,
        requireAudio: Boolean = true,
    ): Boolean =
        active === lease &&
            !lease.cancelled &&
            lease.client.isConnectionCurrent(lease.epoch) &&
            lease.source.isRunning &&
            (!requireAudio || lease.client.modes.phoneOwnsAudio)

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
        lease.downlink?.close()
        lease.speech.clear()
        // Keep output suspended until scoped input retirement has been processed on the same control worker.
        worker.execute { cleanup(lease) }
    }

    @Synchronized private fun cancel(lease: Lease) {
        if (active === lease) {
            stop()
        } else {
            lease.cancelled = true
            lease.receiver?.close()
            lease.downlink?.close()
        }
    }

    private fun cleanup(lease: Lease) {
        if (!lease.cleaned.compareAndSet(false, true)) return
        lease.receiver?.close()
        lease.downlink?.close()
        // A rejected SETUP never created a stream. Some receivers treat an unknown type100 teardown broadly.
        val retired =
            if (lease.setupAccepted) {
                runCatching { lease.client.teardownMicrophone(lease.id, lease.epoch) }.getOrDefault(false).also {
                    log("MIC: input retired teardownAccepted=$it")
                }
            } else {
                log("MIC: input SETUP not accepted; no TEARDOWN for uncreated stream")
                true
            }
        if (lease.client.setSpeechRecognitionState(false, lease.epoch)) {
            if (retired && lease.borrowedAudio && lease.client.modes.phoneBorrowsAudio) {
                runCatching { lease.client.releaseAudioBorrow(lease.epoch) }
            } else {
                runCatching { lease.client.assertModes(expectedEpoch = lease.epoch) }
            }
            onStateChanged()
        }
    }
}
