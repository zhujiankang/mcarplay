package com.mcarplay.app.airplay

/** CarPToCarL audio-intent model, adapted for native CarLife PCM and explicit car-source suppression. */
internal class CarPlayAudioIntent {
    enum class MediaCommand { NONE, PAUSE, RESUME }

    private var observedMedia = false
    private var pendingMediaStart = false
    private var blockedByCar = false
    private var resumeAfterBorrow = false
    private val prompts = mutableMapOf<String, Long>()

    @get:Synchronized var media = false
        private set

    @Synchronized fun reset(playing: Boolean = false) {
        observedMedia = playing
        pendingMediaStart = false
        media = playing
        blockedByCar = false
        resumeAfterBorrow = false
        prompts.clear()
    }

    @Synchronized fun beginMediaStream() {
        pendingMediaStart = !observedMedia
    }

    /** Repeated PCM/state reports cannot undo the car's FM/mute selection. A real stop/start edge can. */
    @Synchronized fun observeMedia(
        playing: Boolean,
        newIntent: Boolean = false,
    ): Boolean {
        val before = media
        if (playing && !observedMedia && (newIntent || pendingMediaStart)) blockedByCar = false
        pendingMediaStart = false
        observedMedia = playing
        media = playing && !blockedByCar
        return before != media
    }

    @Synchronized fun userPlayback(playing: Boolean) {
        blockedByCar = !playing
        pendingMediaStart = false
        resumeAfterBorrow = false
        observedMedia = playing
        media = playing
    }

    @Synchronized fun prompt(
        channel: String,
        now: Long,
        starting: Boolean = false,
    ) {
        val deadline = now + if (starting) 1500 else 750
        prompts[channel] = maxOf(prompts[channel] ?: 0L, deadline)
    }

    @Synchronized fun hasPrompt(now: Long): Boolean {
        prompts.entries.removeAll { it.value <= now }
        return prompts.isNotEmpty()
    }

    @Synchronized fun onCarAudioTransfer(transfer: ModeArbiter.CarAudioTransfer): MediaCommand {
        when (transfer) {
            ModeArbiter.CarAudioTransfer.TAKEN_PERMANENTLY,
            ModeArbiter.CarAudioTransfer.BORROWED_CONSTRAINED,
            -> {
                val playing = media
                resumeAfterBorrow = transfer == ModeArbiter.CarAudioTransfer.BORROWED_CONSTRAINED && (playing || resumeAfterBorrow)
                blockedByCar = true
                media = false
                return if (playing) MediaCommand.PAUSE else MediaCommand.NONE
            }
            ModeArbiter.CarAudioTransfer.UNBORROWED_TO_PHONE -> {
                if (!resumeAfterBorrow) return MediaCommand.NONE
                resumeAfterBorrow = false
                blockedByCar = false
                media = true
                observedMedia = true
                return MediaCommand.RESUME
            }
        }
    }
}
