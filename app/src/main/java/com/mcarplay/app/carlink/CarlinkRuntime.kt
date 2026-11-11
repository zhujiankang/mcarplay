package com.mcarplay.app.carlink

import com.mcarplay.app.carlife.spsDimensions
import com.mcarplay.app.video.ExternalVideoFrame
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Source lifetime is independent of the CarPlay connection and of any vendor's package names. */
object CarlinkRuntime {
    data class Status(
        val running: Boolean,
        val stage: String,
        val videoFrames: Long,
        val audioFrames: Long,
        val width: Int,
        val height: Int,
        val ready: Boolean,
    )

    private class Run(
        val pin: String,
        val log: (String) -> Unit,
    ) {
        var receiver: CarlinkReceiver? = null
        var stage = "STARTING"
        var videoFrames = 0L
        var audioFrames = 0L
        var width = 0
        var height = 0
        var ready = false
    }

    private class Output(
        val video: (ExternalVideoFrame) -> Boolean,
        val ended: () -> Unit,
        pcm: ((ByteArray, Int, Int) -> Unit)?,
        failed: () -> Unit,
    ) {
        val closed = AtomicBoolean()
        val gate = CarlinkFrameGate()
        val decoder = pcm?.let { CarlinkAacDecoder(it, failed) }
    }

    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "carlink-runtime").apply { isDaemon = true } }
    private val keyframePending = AtomicBoolean()
    private var run: Run? = null
    private var output: Output? = null

    fun status(): Status =
        synchronized(lock) {
            run?.let { Status(true, it.stage, it.videoFrames, it.audioFrames, it.width, it.height, it.ready) }
                ?: Status(false, "STOPPED", 0, 0, 0, 0, false)
        }

    fun pin(): String = synchronized(lock) { run?.pin.orEmpty() }

    fun start(
        pin: String,
        log: (String) -> Unit,
    ) {
        val current =
            synchronized(lock) {
                if (run != null) return
                Run(pin, log).also { run = it }
            }
        worker.execute {
            try {
                val receiver =
                    CarlinkReceiver(
                        pin,
                        event = { onEvent(current, it) },
                        video = { onVideo(current, it) },
                        audio = { onAudio(current, it) },
                        lost = {
                            synchronized(lock) {
                                if (run === current) {
                                    current.ready = false
                                    output?.gate?.reset()
                                }
                            }
                        },
                    )
                val keep =
                    synchronized(lock) {
                        if (run === current) {
                            current.receiver = receiver
                            true
                        } else {
                            false
                        }
                    }
                if (!keep) receiver.close()
            } catch (error: Exception) {
                synchronized(lock) {
                    if (run === current) {
                        current.stage = "START_FAILED_${error.javaClass.simpleName}"
                        current.ready = false
                    }
                }
                current.log("CARLINK: START_FAILED_${error.javaClass.simpleName}")
            }
        }
    }

    private fun onEvent(
        current: Run,
        event: String,
    ) {
        val ended =
            synchronized(lock) {
                if (run !== current) return
                current.stage = event
                if (event == "AUTH_CONFIRMED_PEER_APP_UNVERIFIED" || event == "SOURCE_DISCONNECTED") {
                    current.ready = false
                    current.width = 0
                    current.height = 0
                    output?.gate?.reset()
                }
                output.takeIf { event == "SOURCE_DISCONNECTED" }
            }
        current.log("CARLINK: $event")
        ended?.takeUnless { it.closed.get() }?.ended?.invoke()
    }

    private fun onVideo(
        current: Run,
        frame: ExternalVideoFrame,
    ) {
        val target =
            synchronized(lock) {
                if (run !== current) return
                current.videoFrames++
                val size = frame.sps?.let(::spsDimensions)
                if (size != null) {
                    current.width = size.first
                    current.height = size.second
                }
                val configuredIdr = frame.keyFrame && size != null && frame.pps != null
                if (configuredIdr && current.width > 0 && current.height > 0) current.ready = true
                output
            } ?: return
        if (target.closed.get()) return
        val admitted = target.gate.admit(frame)
        if (admitted != null && !target.video(admitted)) {
            target.gate.reset()
            requestKeyFrame()
        }
    }

    private fun onAudio(
        current: Run,
        frame: CarlinkAacFrame,
    ) {
        val target =
            synchronized(lock) {
                if (run !== current) return
                current.audioFrames++
                output
            }
        if (target != null && !target.closed.get() && target.decoder?.submit(frame) == false) {
            current.log("CARLINK: AAC_QUEUE_FULL")
        }
    }

    fun stop() {
        val previous =
            synchronized(lock) {
                (run to output).also {
                    run = null
                    output = null
                }
            }
        previous.second?.let {
            dispose(it)
            it.ended()
        }
        worker.execute { previous.first?.receiver?.close() }
    }

    fun attach(
        video: (ExternalVideoFrame) -> Boolean,
        pcm: ((ByteArray, Int, Int) -> Unit)?,
        ended: () -> Unit,
    ): AutoCloseable {
        val pair =
            synchronized(lock) {
                check(run?.ready == true) { "carlink_source_lost_before_attach" }
                val next =
                    Output(video, ended, pcm) {
                        synchronized(lock) { run?.log?.invoke("CARLINK: AAC_DECODER_FAILED") }
                    }
                (next to output).also { output = next }
            }
        val next = pair.first
        pair.second?.let(::dispose)
        requestKeyFrame()
        return AutoCloseable {
            synchronized(lock) { if (output === next) output = null }
            dispose(next)
        }
    }

    private fun dispose(target: Output) {
        if (target.closed.compareAndSet(false, true)) worker.execute { target.decoder?.close() }
    }

    fun requestKeyFrame(): Boolean {
        val current = synchronized(lock) { run } ?: return false
        if (!keyframePending.compareAndSet(false, true)) return true
        worker.execute {
            try {
                if (synchronized(lock) { run === current }) current.receiver?.requestKeyFrame()
            } finally {
                keyframePending.set(false)
            }
        }
        return true
    }
}
