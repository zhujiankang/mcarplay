package com.mcarplay.app.carlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class CarlinkRtspTest {
    private fun request(
        method: String,
        id: String = "1",
        body: String = "",
    ) = CarlinkRtsp("$method * RTSP/1.0", mapOf("cseq" to id), body)

    @Test
    fun backToBackMessagesUseByteContentLength() {
        val first = request("SET_PARAMETER", body = "测试")
        val second = request("OPTIONS", "2")
        val input = ByteArrayInputStream(first.encode() + second.encode())
        assertEquals(first.body, CarlinkRtsp.read(input).body)
        assertEquals(second.line, CarlinkRtsp.read(input).line)
        assertEquals(-1, input.read())
    }

    @Test
    fun malformedLengthDuplicateHeaderAndOversizeAreRejected() {
        listOf(
            "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\ncseq: 2\r\n\r\n",
            "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 65537\r\n\r\n",
            "OPTIONS * RTSP/1.0\r\nCSeq: -1\r\n\r\n",
            "X".repeat(16385),
        ).forEach { wire ->
            assertThrows(IllegalArgumentException::class.java) { CarlinkRtsp.read(ByteArrayInputStream(wire.toByteArray())) }
        }
    }

    @Test
    fun negotiatesCapabilitiesSetupAndPlayBeforeIdrRequest() {
        val sink = CarlinkRtspSink(15550)
        assertEquals(null, sink.requestKeyFrame())
        assertEquals(1, sink.receive(request("OPTIONS")).size)
        val caps = sink.receive(request("GET_PARAMETER", body = "wfd_video_formats\r\nwfd_client_rtp_ports\r\n")).single().body
        assertTrue(caps.contains("00000020"))
        assertTrue(caps.contains("15550"))
        val setup = sink.receive(request("SET_PARAMETER", body = "wfd_trigger_method: SETUP\r\n")).last()
        assertTrue(setup.line.startsWith("SETUP "))
        assertFalse(sink.playing)
        val play =
            sink
                .receive(
                    CarlinkRtsp(
                        "RTSP/1.0 200 OK",
                        mapOf(
                            "cseq" to setup.headers.getValue("cseq"),
                            "session" to "test;timeout=30",
                        ),
                    ),
                ).single()
        assertTrue(play.line.startsWith("PLAY "))
        assertEquals("test", play.headers["session"])
        sink.receive(CarlinkRtsp("RTSP/1.0 200 OK", mapOf("cseq" to play.headers.getValue("cseq"))))
        assertTrue(sink.playing)
        assertTrue(sink.requestKeyFrame()!!.body.contains("wfd_idr_request"))
        sink.receive(request("TEARDOWN"))
        assertFalse(sink.playing)
    }

    @Test
    fun hdcpAndSetupFailureCannotStartVideo() {
        val sink = CarlinkRtspSink(15550)
        assertThrows(IllegalArgumentException::class.java) {
            sink.receive(request("SET_PARAMETER", body = "wfd_content_protection: HDCP2.1 port=1024\r\n"))
        }
        val setup = sink.receive(request("SET_PARAMETER", body = "wfd_trigger_method: SETUP\r\n")).last()
        assertThrows(IllegalArgumentException::class.java) {
            sink.receive(CarlinkRtsp("RTSP/1.0 403 Forbidden", mapOf("cseq" to setup.headers.getValue("cseq"))))
        }
        assertFalse(sink.playing)
    }
}
