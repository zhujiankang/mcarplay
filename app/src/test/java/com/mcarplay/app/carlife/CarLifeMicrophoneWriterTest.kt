package com.mcarplay.app.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

class CarLifeMicrophoneWriterTest {
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
