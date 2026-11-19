package com.mcarplay.app.video

import com.mcarplay.app.airplay.AirPlayCrypto
import com.mcarplay.app.airplay.ModeArbiter
import com.mcarplay.app.airplay.ScreenResourceStreams
import com.mcarplay.app.airplay.StreamFrameCipher
import com.mcarplay.app.compat.MercedesHomeVideoExperiment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class HomeVideoContinuityTest {
    @Test fun realEncryptedTcpSurvivesHomeCameraAndReturnWithoutResettingNonce() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            val modes = ModeArbiter()
            val policy = MercedesHomeVideoExperiment(true, true, modes)
            val key = ByteArray(32) { (it + 9).toByte() }
            val clock = AtomicLong()
            val sender =
                VideoSender(
                    "127.0.0.1",
                    receiver.localPort,
                    StreamFrameCipher.ChaCha(key),
                    640,
                    480,
                    log = {},
                    phoneOwnsScreen = { modes.phoneOwnsScreen },
                    allowVideoWhileHidden = policy::allowHiddenFrames,
                    uptimeMs = clock::get,
                    externalVideoSource =
                        object : ExternalVideoSource {
                            override fun requestKeyFrame() = true
                        },
                )
            val streams =
                ScreenResourceStreams(
                    isCurrent = { sender.isRunning },
                    phoneOwnsScreen = { modes.phoneOwnsScreen },
                    retireScreen = { error("Home/camera must not TEARDOWN") },
                    restoreScreen = { error("Return must not SETUP/RECORD") },
                    onFailure = { error(it) },
                    retainScreenStream = { policy.retainStream },
                    keepSendingWhenHidden = policy::allowHiddenFrames,
                    onRetainedScreenVisibility = { visible ->
                        if (!visible && !policy.allowHiddenFrames()) sender.pauseScreenOutput()
                    },
                    onClose = sender::stop,
                )
            try {
                assertTrue(sender.start {})
                receiver.accept().use { socket ->
                    socket.soTimeout = 2000
                    val input = DataInputStream(socket.getInputStream())
                    var nonce = 0L

                    fun sendAndVerify(idr: Boolean) {
                        assertTrue(sender.submitExternalFrame(frame(idr)))
                        var header: ByteArray
                        var body: ByteArray
                        do {
                            header = ByteArray(128)
                            input.readFully(header)
                            val size = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
                            require(size in 0..65536)
                            body = ByteArray(size)
                            input.readFully(body)
                        } while (header[4].toInt() != 0)
                        val plain = AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(nonce++), body, header)
                        assertTrue(plain.isNotEmpty())
                    }
                    sendAndVerify(true)
                    change(modes, 1)
                    streams.reconcile()
                    assertFalse(modes.phoneOwnsScreen)
                    assertFalse(streams.outputPaused)
                    repeat(3) {
                        clock.addAndGet(30_000) // Simulated elapsed time, not a vehicle watchdog test.
                        sendAndVerify(false)
                    }
                    change(modes, 3)
                    streams.reconcile()
                    assertTrue(streams.outputPaused)
                    assertTrue(sender.submitExternalFrame(frame(true)))
                    awaitSkipped(sender, 1)
                    change(modes, 4)
                    streams.reconcile()
                    assertFalse(modes.phoneOwnsScreen) // Camera returns to Home, not CarPlay.
                    assertFalse(streams.outputPaused)
                    assertTrue(sender.submitExternalFrame(frame(false)))
                    awaitSkipped(sender, 2)
                    sendAndVerify(true)
                    assertTrue(modes.acquireScreen(true) { true })
                    streams.reconcile(forceRebuild = true)
                    sendAndVerify(false)
                    assertEquals(6L, nonce)
                    assertTrue(streams.attached)
                    assertTrue(sender.isRunning)
                    assertTrue(modes.phoneOwnsAudio)
                    streams.close()
                    assertEquals(-1, input.read())
                    assertFalse(sender.isRunning)
                }
            } finally {
                streams.close()
                sender.stop()
            }
        }
    }

    private fun change(
        modes: ModeArbiter,
        kind: Long,
    ) {
        modes.changeResponse(mapOf("resources" to listOf(mapOf("resourceID" to 1L, "transferType" to kind))))
    }

    private fun awaitSkipped(
        sender: VideoSender,
        count: Long,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (sender.screenFramesSkippedTotal < count && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(count, sender.screenFramesSkippedTotal)
    }

    private fun frame(idr: Boolean): ExternalVideoFrame {
        val sps = byteArrayOf(0x67, 0x42, 0, 0x1f)
        val pps = byteArrayOf(0x68, 0xce.toByte(), 6, 0xe2.toByte())
        val slice = byteArrayOf(if (idr) 0x65 else 0x41, 0x22, 0x33)
        val data = listOf(sps, pps, slice).fold(ByteArray(0)) { bytes, nal -> bytes + byteArrayOf(0, 0, 0, 1) + nal }
        return ExternalVideoFrame(data, idr, 0, sps, pps)
    }
}
