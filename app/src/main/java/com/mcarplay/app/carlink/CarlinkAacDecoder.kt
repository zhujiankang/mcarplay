package com.mcarplay.app.carlink

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded AAC-LC → PCM decoder; never blocks the Carlink socket or plays audio on the phone. */
class CarlinkAacDecoder(
    private val pcm: (ByteArray, Int, Int) -> Unit,
    private val failed: () -> Unit,
) : AutoCloseable {
    private val queue = ArrayBlockingQueue<CarlinkAacFrame>(64)
    private val closed = AtomicBoolean()
    private val thread =
        Thread({ run() }, "carlink-aac").apply {
            isDaemon = true
            start()
        }

    fun submit(frame: CarlinkAacFrame): Boolean = !closed.get() && queue.offer(frame)

    private fun run() {
        var codec: MediaCodec? = null
        var inputRate = 0
        var inputChannels = 0
        var outputRate = 0
        var outputChannels = 0
        val info = MediaCodec.BufferInfo()
        try {
            while (!closed.get()) {
                val frame = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (frame.sampleRate != inputRate || frame.channels != inputChannels) {
                    codec?.stop()
                    codec?.release()
                    codec = null
                    inputRate = frame.sampleRate
                    inputChannels = frame.channels
                    outputRate = inputRate
                    outputChannels = inputChannels
                    val index = RATES.indexOf(inputRate)
                    require(index >= 0 && inputChannels in 1..2)
                    val config = (2 shl 11) or (index shl 7) or (inputChannels shl 3)
                    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, inputRate, inputChannels)
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf((config ushr 8).toByte(), config.toByte())))
                    format.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                    val created = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    codec = created
                    created.configure(format, null, null, 0)
                    created.start()
                }
                val decoder = checkNotNull(codec)
                val slot = decoder.dequeueInputBuffer(10000)
                if (slot >= 0) {
                    val buffer = checkNotNull(decoder.getInputBuffer(slot))
                    buffer.clear()
                    require(buffer.remaining() >= frame.data.size)
                    buffer.put(frame.data)
                    decoder.queueInputBuffer(slot, 0, frame.data.size, frame.timestampUs, 0)
                }
                while (!closed.get()) {
                    val index = decoder.dequeueOutputBuffer(info, 0)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val format = decoder.outputFormat
                        outputRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(outputChannels in 1..2 && outputRate in 8000..192000)
                    } else if (index >= 0) {
                        try {
                            val buffer = decoder.getOutputBuffer(index)
                            if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size).also(buffer::get)
                                if (!closed.get()) pcm(bytes, outputRate, outputChannels)
                            }
                        } finally {
                            decoder.releaseOutputBuffer(index, false)
                        }
                    } else {
                        break
                    }
                }
            }
        } catch (
            _: InterruptedException,
        ) {
            Thread.currentThread().interrupt()
        } catch (
            _: Exception,
        ) {
            if (!closed.get()) failed()
        } finally {
            closed.set(true)
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            queue.clear()
        }
    }

    override fun close() {
        closed.set(true)
        thread.interrupt()
        if (Thread.currentThread() !== thread) thread.join(1500)
    }

    private companion object {
        val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    }
}
