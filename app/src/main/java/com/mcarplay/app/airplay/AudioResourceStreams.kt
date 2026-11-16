package com.mcarplay.app.airplay

/** Adapted from CarPToCarL applyAudio. Call only on the session's serial control worker. */
internal class AudioResourceStreams(
    private val current: () -> Boolean,
    private val modes: ModeArbiter,
    private val intent: CarPlayAudioIntent,
    private val enabled: () -> Boolean,
    private val acquire: (priority: Int, temporary: Boolean) -> Boolean,
    private val release: () -> Boolean,
    private val switchOutput: (Boolean) -> Boolean,
    private val mediaCommand: (CarPlayAudioIntent.MediaCommand) -> Unit,
    private val now: () -> Long,
    private val log: (String) -> Unit,
) {
    private var active = false

    @Volatile private var closed = false
    private var nextAttempt = 0L
    private var stopping = false

    fun close() {
        closed = true
    }

    fun reconcile() {
        if (closed || !current()) return
        while (true) {
            val transfer = modes.consumeAudioTransfer() ?: break
            mediaCommand(intent.onCarAudioTransfer(transfer))
        }
        val time = now()
        val media = intent.media && enabled()
        val prompt = intent.hasPrompt(time) && enabled()
        if (stopping && time >= nextAttempt) {
            nextAttempt = time + 2000
            active = switchOutput(false)
            stopping = active
            if (!active) nextAttempt = time
        }
        if (stopping) return
        if (modes.recognizingSpeech) {
            if (active) {
                active = switchOutput(false)
                stopping = active
                if (stopping) nextAttempt = time + 2000
            }
            return
        }
        if ((media || prompt) && (!active || !modes.phoneOwnsAudio || (media && modes.phoneBorrowsAudio)) && time >= nextAttempt) {
            nextAttempt = time + 2000 // refuse/retry is bounded; repeated PCM cannot flood modesChanged
            val acquired = acquire(if (media) 500 else 100, !media)
            log("RESOURCE-AUDIO: acquire media=$media prompt=$prompt temporary=${!media} accepted=$acquired")
            if (acquired) nextAttempt = time
        }
        if (!current()) return
        val wanted = modes.phoneOwnsAudio && (media || prompt)
        if (!wanted && active) {
            active = switchOutput(false)
            stopping = active
            if (stopping) {
                nextAttempt = time + 2000
                return
            }
        }
        if (!media && !prompt && !active && modes.phoneBorrowsAudio && time >= nextAttempt) {
            nextAttempt = time + 2000
            val released = release()
            log("RESOURCE-AUDIO: releaseTemporary accepted=$released")
            if (released) nextAttempt = time
        }
        if (wanted && !active && time >= nextAttempt) {
            nextAttempt = time + 2000
            active = switchOutput(true)
            if (!current()) return
            // A car event may have taken ownership while SETUP/RECORD was in flight.
            if (active && (!modes.phoneOwnsAudio || modes.recognizingSpeech)) {
                active = switchOutput(false)
                stopping = active
            }
            if (active) nextAttempt = time
        }
    }
}
