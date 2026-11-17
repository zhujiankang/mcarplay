package com.mcarplay.app.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CarLifeMicrophoneWriterTest {
    @Test fun shortRecordingReportsWrittenBytesAtEndWithoutWaitingFor250Blocks() {
        val output = ByteArrayOutputStream()
        val socket =
            object : Socket() {
                override fun getOutputStream(): OutputStream = output
            }
        val logs = LinkedBlockingQueue<String>()
        val cipher = CarLifePayloadCipher(ByteArray(16))
        val writer = CarLifeMicrophoneWriter({ socket }, { cipher }, { logs.offer(it) })
        try {
            writer.begin()
            writer.offer(ByteArray(640) { 1 })
            assertTrue(logs.poll(2, TimeUnit.SECONDS)?.contains("carlifeWritten=1") == true)
            writer.end()
            val summary = logs.poll(2, TimeUnit.SECONDS) ?: error("missing end summary")
            assertTrue(summary.contains("phase=ended queued=1 written=1 bytes=640"))
            assertTrue(summary.contains("dropped=0 discarded=0 failed=0"))
        } finally {
            writer.close()
        }
    }

    @Test fun lateFailureOfOldWriteCannotStopNewRecognition() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val output = ByteArrayOutputStream()
        val old =
            object : Socket() {
                override fun getOutputStream(): OutputStream =
                    object : OutputStream() {
                        override fun write(value: Int) {
                            entered.countDown()
                            release.await(2, TimeUnit.SECONDS)
                            throw java.io.IOException("old recording failed")
                        }
                    }
            }
        val fresh =
            object : Socket() {
                override fun getOutputStream(): OutputStream = output
            }
        val target = AtomicReference<Socket>(old)
        val logs = LinkedBlockingQueue<String>()
        val writer = CarLifeMicrophoneWriter({ target.get() }, { CarLifePayloadCipher(ByteArray(16)) }, { logs.offer(it) })
        try {
            writer.begin()
            writer.offer(ByteArray(640))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            writer.end()
            writer.begin()
            target.set(fresh)
            assertTrue(writer.offer(ByteArray(640) { 2 }))
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var failureIsolated = false
            var written = false
            while (System.nanoTime() < deadline && !(failureIsolated && written)) {
                val line = logs.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (line.contains("stoppedMatchingRecording=false")) failureIsolated = true
                if (line.contains("carlifeWritten=1")) written = true
            }
            assertTrue(failureIsolated)
            assertTrue(written)
            assertTrue(writer.offer(ByteArray(0)))
            assertTrue(output.size() > 640)
        } finally {
            release.countDown()
            writer.close()
        }
    }

    @Test fun encryptedMicPacketsUseVoiceUplinkKindAndStopClearsIncompleteAudio() {
        val cipher = CarLifePayloadCipher(ByteArray(16) { it.toByte() })
        ServerSocket(0).use { server ->
            Socket("127.0.0.1", server.localPort).use { sender ->
                server.accept().use { peer ->
                    peer.soTimeout = 2000
                    val writer = CarLifeMicrophoneWriter({ sender }, { cipher }, {})
                    try {
                        assertFalse(writer.offer(ByteArray(640)))
                        writer.begin()
                        assertTrue(writer.offer(ByteArray(320) { 1 }))
                        writer.end()
                        writer.begin()
                        val expected = ByteArray(640) { (it % 127).toByte() }
                        assertTrue(writer.offer(expected))
                        val frame = readCarLifeSocket(peer.getInputStream(), CarLifeProtocol.PORT_VOICE)
                        assertEquals(0x58001, frame.kind)
                        assertEquals(0, frame.timestamp)
                        assertArrayEquals(expected, cipher.decrypt(frame.payload))
                        writer.end()
                        assertFalse(writer.offer(expected))
                        peer.soTimeout = 100
                        assertThrows(java.net.SocketTimeoutException::class.java) { peer.getInputStream().read() }
                    } finally {
                        writer.close()
                    }
                }
            }
        }
    }
}
