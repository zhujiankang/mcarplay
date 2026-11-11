package com.mcarplay.app.carlink

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

data class CarlinkRtsp(
    val line: String,
    val headers: Map<String, String>,
    val body: String = "",
) {
    fun encode(): ByteArray {
        require(!line.contains('\r') && !line.contains('\n'))
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val output = StringBuilder(line).append("\r\n")
        headers.filterKeys { it != "content-length" }.forEach { (key, value) ->
            require(key.matches(Regex("[A-Za-z0-9-]+")) && !value.contains('\r') && !value.contains('\n'))
            output
                .append(key)
                .append(": ")
                .append(value)
                .append("\r\n")
        }
        output.append("Content-Length: ").append(bytes.size).append("\r\n\r\n")
        return output.toString().toByteArray(StandardCharsets.US_ASCII) + bytes
    }

    companion object {
        fun read(input: InputStream): CarlinkRtsp {
            val header = ByteArrayOutputStream()
            var ending = 0
            while (true) {
                require(header.size() < 16384) { "carlink_rtsp_header_limit" }
                val next = input.read()
                check(next >= 0) { "carlink_rtsp_eof" }
                header.write(next)
                ending = (ending shl 8) or next
                if (ending == 0x0d0a0d0a) break
            }
            val lines = header.toString("US-ASCII").removeSuffix("\r\n\r\n").split("\r\n")
            require(lines.first().startsWith("RTSP/") || lines.first().matches(Regex("[A-Z_]+ [^\\r\\n]+ RTSP/1\\.0"))) {
                "carlink_rtsp_start_line"
            }
            val values = linkedMapOf<String, String>()
            lines.drop(1).forEach { line ->
                val at = line.indexOf(':')
                require(at > 0) { "carlink_rtsp_header" }
                val key = line.substring(0, at).lowercase()
                require(key !in values) { "carlink_rtsp_duplicate_header" }
                values[key] = line.substring(at + 1).trim()
            }
            val size = values["content-length"]?.toIntOrNull() ?: if ("content-length" !in values) 0 else error("carlink_rtsp_length")
            require(size in 0..65536 && values["cseq"]?.toLongOrNull() in 0..0xffffffffL) { "carlink_rtsp_length_or_sequence" }
            val data = ByteArray(size)
            java.io.DataInputStream(input).readFully(data)
            return CarlinkRtsp(lines.first(), values, data.toString(StandardCharsets.UTF_8))
        }
    }
}

/** WFD sink state machine. Only 720p30 H.264/AAC is advertised; no HEVC, HDCP or fusion modes. */
class CarlinkRtspSink(
    private val rtpPort: Int,
) {
    var playing = false
        private set
    private var requestId = 1000
    private var setupId: String? = null
    private var playId: String? = null
    private var session: String? = null
    private var presentation = "rtsp://127.0.0.1/wfd1.0/streamid=0"
    private var triggered = false
    private val capabilities =
        linkedMapOf(
            "wfd_video_formats" to "28 00 01 01 00000020 00000000 00000000 00 0000 0000 00 none none",
            "wfd_audio_codecs" to "AAC 00000001 00",
            "wfd_client_rtp_ports" to "RTP/AVP/TCP;unicast $rtpPort 0 mode=play",
            "wfd_content_protection" to "none",
            "wfd_uibc_capability" to "none",
        )

    init {
        require(rtpPort in 1024..65535)
    }

    fun receive(message: CarlinkRtsp): List<CarlinkRtsp> {
        val id = checkNotNull(message.headers["cseq"])
        val result = arrayListOf<CarlinkRtsp>()
        if (message.line.startsWith("RTSP/")) {
            if (id == setupId) {
                require(message.line.startsWith("RTSP/1.0 200 ")) { "carlink_setup_refused" }
                session = message.headers["session"]?.substringBefore(';')?.takeIf { it.isNotBlank() && it.length <= 128 }
                    ?: error("carlink_rtsp_session")
                playId = (++requestId).toString()
                result += CarlinkRtsp("PLAY $presentation RTSP/1.0", mapOf("cseq" to playId!!, "session" to session!!))
                setupId = null
            } else if (id == playId) {
                require(message.line.startsWith("RTSP/1.0 200 ")) { "carlink_play_refused" }
                playing = true
                playId = null
            }
            return result
        }
        val method = message.line.substringBefore(' ')
        val params =
            message.body.split("\r\n").filter { it.isNotBlank() }.associate { line ->
                val parts = line.split(':', limit = 2)
                parts[0].trim() to parts.getOrElse(1) { "" }.trim()
            }
        var body = ""
        val headers = linkedMapOf("cseq" to id)
        when (method) {
            "OPTIONS" -> headers["public"] = "org.wfa.wfd1.0, OPTIONS, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, TEARDOWN"
            "GET_PARAMETER" -> body = capabilities.filterKeys { it in params }.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
            "SET_PARAMETER" -> {
                params["wfd_presentation_URL"]?.split(' ')?.firstOrNull()?.let { url ->
                    require(url.startsWith("rtsp://") && url.length <= 1024 && url.none(Char::isISOControl)) { "carlink_rtsp_url" }
                    presentation = url
                }
                params["wfd_content_protection"]?.let { require(it == "none") { "carlink_hdcp_not_supported" } }
            }
            "TEARDOWN" -> playing = false
            else -> return listOf(CarlinkRtsp("RTSP/1.0 405 Method Not Allowed", headers))
        }
        result += CarlinkRtsp("RTSP/1.0 200 OK", headers, body)
        if (method == "SET_PARAMETER" && params["wfd_trigger_method"] == "SETUP" && !triggered) {
            triggered = true
            setupId = (++requestId).toString()
            result +=
                CarlinkRtsp(
                    "SETUP $presentation RTSP/1.0",
                    mapOf(
                        "cseq" to setupId!!,
                        "transport" to "RTP/AVP/TCP;unicast;client_port=$rtpPort",
                    ),
                )
        }
        return result
    }

    fun requestKeyFrame(): CarlinkRtsp? {
        if (!playing) return null
        return CarlinkRtsp(
            "SET_PARAMETER * RTSP/1.0",
            mapOf("cseq" to (++requestId).toString(), "session" to session!!),
            "wfd_idr_request\r\n",
        )
    }
}
