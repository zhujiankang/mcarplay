package com.mcarplay.app.carlink

import com.mcarplay.app.carlife.CarLifeVideoParser
import com.mcarplay.app.video.ExternalVideoFrame
import java.io.ByteArrayOutputStream

data class CarlinkAacFrame(
    val data: ByteArray,
    val sampleRate: Int,
    val channels: Int,
    val timestampUs: Long,
)

/** MPEG-TS over RFC4571 RTP. No CarLife framing, socket, launcher or authentication is used here. */
class CarlinkMedia(
    private val video: (ExternalVideoFrame) -> Unit,
    private val audio: (CarlinkAacFrame) -> Unit,
    private val discontinuity: () -> Unit,
) {
    private var sequence: Int? = null
    private var pmtPid: Int? = null
    private var videoPid: Int? = null
    private var audioPid: Int? = null
    private val counters = mutableMapOf<Int, Int>()
    private val sections = mutableMapOf<Int, ByteArray>()

    private class PesBuffer {
        val data = ByteArrayOutputStream()
        var length: Int? = null
    }

    private val pending = mutableMapOf<Int, PesBuffer>()
    private val clocks = mutableMapOf<Int, Long>()
    private var aacPending = byteArrayOf()
    private var aacStamp = 0L
    private var h264 = CarLifeVideoParser()
    private var bytesWithoutPicture = 0L

    fun rtp(packet: ByteArray) {
        require(packet.size >= 12 && packet[0].toInt() ushr 6 and 3 == 2) { "carlink_rtp_header" }
        var offset = 12 + (packet[0].toInt() and 15) * 4
        require(offset <= packet.size) { "carlink_rtp_csrc" }
        if (packet[0].toInt() and 16 != 0) {
            require(offset + 4 <= packet.size) { "carlink_rtp_extension" }
            offset += 4 + uint16(packet, offset + 2) * 4
        }
        val padding = if (packet[0].toInt() and 32 != 0) packet.last().toInt() and 255 else 0
        require((packet[0].toInt() and 32 == 0 || padding > 0) && offset <= packet.size - padding) { "carlink_rtp_padding" }
        require(packet[1].toInt() and 127 == 33) { "carlink_rtp_payload_type" }
        val next = uint16(packet, 2)
        val previous = sequence
        if (previous == next) return
        if (previous != null && next != ((previous + 1) and 65535)) resetMedia()
        sequence = next
        val end = packet.size - padding
        require((end - offset) % 188 == 0) { "carlink_ts_alignment" }
        while (offset < end) {
            ts(packet.copyOfRange(offset, offset + 188))
            offset += 188
        }
    }

    private fun resetMedia() {
        pending.clear()
        sections.clear()
        counters.clear()
        h264 = CarLifeVideoParser()
        clocks.clear()
        aacPending = byteArrayOf()
        bytesWithoutPicture = 0
        discontinuity()
    }

    private fun ts(data: ByteArray) {
        require(data[0] == 0x47.toByte() && data[1].toInt() and 128 == 0) { "carlink_ts_header" }
        val pid = ((data[1].toInt() and 31) shl 8) or (data[2].toInt() and 255)
        val start = data[1].toInt() and 64 != 0
        val mode = (data[3].toInt() ushr 4) and 3
        require(mode != 0 && data[3].toInt() and 192 == 0) { "carlink_ts_scrambling" }
        var offset = 4
        if (mode and 2 != 0) {
            val size = data[4].toInt() and 255
            require(size <= 183) { "carlink_ts_adaptation" }
            if (size > 0 && data[5].toInt() and 128 != 0) {
                pending.remove(pid)
                counters.remove(pid)
                sections.remove(pid)
                if (pid == videoPid) {
                    h264 = CarLifeVideoParser()
                    discontinuity()
                }
                if (pid == audioPid) aacPending = byteArrayOf()
            }
            offset += size + 1
        }
        if (mode and 1 == 0 || offset == data.size) return
        require(offset < data.size) { "carlink_ts_payload" }
        val counter = data[3].toInt() and 15
        val previous = counters.put(pid, counter)
        if (previous == counter) return
        if (previous != null && counter != ((previous + 1) and 15)) {
            pending.remove(pid)
            sections.remove(pid)
            if (pid == videoPid) {
                h264 = CarLifeVideoParser()
                discontinuity()
            }
            if (pid == audioPid) aacPending = byteArrayOf()
            if (!start) return
        }
        val body = data.copyOfRange(offset, data.size)
        when (pid) {
            0, pmtPid -> section(pid, start, body)
            videoPid, audioPid -> pes(pid, start, body)
        }
    }

    private fun section(
        pid: Int,
        start: Boolean,
        body: ByteArray,
    ) {
        var part = body
        if (start) {
            val pointer = body[0].toInt() and 255
            require(pointer + 1 <= body.size) { "carlink_psi_pointer" }
            if (pointer > 0 && sections[pid] != null) section(pid, false, body.copyOfRange(1, pointer + 1))
            sections.remove(pid)
            part = body.copyOfRange(pointer + 1, body.size)
        } else if (sections[pid] == null) {
            return
        }
        var data = sections.getOrDefault(pid, byteArrayOf()) + part
        require(data.size <= 8192) { "carlink_psi_limit" }
        while (data.size >= 3 && data[0] != 0xff.toByte()) {
            val size = 3 + (((data[1].toInt() and 15) shl 8) or (data[2].toInt() and 255))
            require(size in 12..4096) { "carlink_psi_length" }
            if (data.size < size) break
            val table = data.copyOfRange(0, size)
            require(psiCrc(table) == 0) { "carlink_psi_crc" }
            if (table[5].toInt() and 1 == 0) {
                data = data.copyOfRange(size, data.size)
                continue
            }
            if (pid == 0) {
                require(table[0] == 0.toByte()) { "carlink_pat_table" }
                var offset = 8
                while (offset + 4 <= size - 4) {
                    if (uint16(table, offset) != 0) {
                        val next = uint16(table, offset + 2) and 8191
                        if (pmtPid != null && pmtPid != next) {
                            resetMedia()
                            videoPid = null
                            audioPid = null
                        }
                        pmtPid = next
                        break
                    }
                    offset += 4
                }
            } else {
                require(table[0] == 2.toByte() && size >= 16) { "carlink_pmt_table" }
                var offset = 12 + (uint16(table, 10) and 4095)
                require(offset <= size - 4) { "carlink_pmt_program_info" }
                var nextVideo: Int? = null
                var nextAudio: Int? = null
                while (offset + 5 <= size - 4) {
                    val stream = table[offset].toInt() and 255
                    val streamPid = uint16(table, offset + 1) and 8191
                    if (stream == 0x1b && nextVideo == null) nextVideo = streamPid
                    if (stream == 0x0f && nextAudio == null) nextAudio = streamPid
                    offset += 5 + (uint16(table, offset + 3) and 4095)
                    require(offset <= size - 4) { "carlink_pmt_es_info" }
                }
                if (nextVideo != videoPid || nextAudio != audioPid) resetMedia()
                videoPid = nextVideo
                audioPid = nextAudio
            }
            data = data.copyOfRange(size, data.size)
        }
        if (data.isEmpty() || data[0] == 0xff.toByte()) sections.remove(pid) else sections[pid] = data
    }

    private fun pes(
        pid: Int,
        start: Boolean,
        body: ByteArray,
    ) {
        if (start) {
            pending.remove(pid)?.let { deliver(pid, it.data.toByteArray(), allowUnbounded = true) }
            pending[pid] = PesBuffer()
        }
        val output = pending[pid] ?: return
        require(output.data.size() + body.size <= CarlinkPacket.MAX_SIZE) { "carlink_pes_limit" }
        output.data.write(body)
        if (output.length == null && output.data.size() >= 6) output.length = uint16(output.data.toByteArray(), 4)
        output.length?.let { size ->
            if (size > 0 && output.data.size() >= size + 6) {
                pending.remove(pid)
                deliver(pid, output.data.toByteArray().copyOfRange(0, size + 6), allowUnbounded = false)
            }
        }
    }

    private fun deliver(
        pid: Int,
        bytes: ByteArray,
        allowUnbounded: Boolean,
    ) {
        require(bytes.size >= 9 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 1.toByte()) { "carlink_pes_header" }
        val declared = uint16(bytes, 4)
        if (declared > 0 && bytes.size < declared + 6) {
            discontinuity()
            return
        }
        require(declared > 0 || allowUnbounded) { "carlink_pes_boundary" }
        val headerSize = bytes[8].toInt() and 255
        require(bytes[6].toInt() and 192 == 128 && headerSize >= 5 && bytes[7].toInt() and 128 != 0 && 9 + headerSize <= bytes.size) {
            "carlink_pes_pts"
        }
        val raw = pts(bytes, 9)
        val last = clocks[pid]
        val modulus = 1L shl 33
        var clock = if (last == null) raw else (last and -modulus) + raw
        if (last != null && clock - last < -modulus / 2) clock += modulus
        if (last != null && clock - last > modulus / 2) clock -= modulus
        clocks[pid] = clock
        val stamp = clock * 1_000_000L / 90_000
        val payload = bytes.copyOfRange(9 + headerSize, if (declared > 0) declared + 6 else bytes.size)
        if (pid == videoPid) {
            bytesWithoutPicture += payload.size
            require(bytesWithoutPicture <= 4L * CarlinkPacket.MAX_SIZE) { "carlink_video_no_picture_limit" }
            h264.push(stamp, payload).forEach { unit ->
                bytesWithoutPicture = 0
                video(ExternalVideoFrame(unit.data, unit.keyFrame, unit.timestamp, unit.sps, unit.pps))
            }
        } else {
            if (aacPending.isEmpty()) aacStamp = stamp
            val adts = aacPending + payload
            require(adts.size <= CarlinkPacket.MAX_SIZE) { "carlink_adts_limit" }
            var offset = 0
            while (adts.size - offset >= 7) {
                require(
                    adts[offset].toInt() and 255 == 255 &&
                        adts[offset + 1].toInt() and 0xf6 == 0xf0,
                ) { "carlink_adts_sync" }
                val b2 = adts[offset + 2].toInt() and 255
                val b3 = adts[offset + 3].toInt() and 255
                val rateIndex = (b2 ushr 2) and 15
                val channels = ((b2 and 1) shl 2) or (b3 ushr 6)
                val size =
                    ((b3 and 3) shl 11) or ((adts[offset + 4].toInt() and 255) shl 3) or
                        ((adts[offset + 5].toInt() ushr 5) and 7)
                val header = if (adts[offset + 1].toInt() and 1 == 1) 7 else 9
                require(
                    b2 ushr 6 == 1 &&
                        rateIndex in rates.indices &&
                        channels in 1..2 &&
                        size >= header &&
                        adts[offset + 6].toInt() and 3 == 0,
                ) { "carlink_adts_format" }
                if (offset + size > adts.size) break
                val rate = rates[rateIndex]
                audio(CarlinkAacFrame(adts.copyOfRange(offset + header, offset + size), rate, channels, aacStamp))
                aacStamp += 1024_000_000L / rate
                offset += size
            }
            aacPending = adts.copyOfRange(offset, adts.size)
        }
    }

    companion object {
        private val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

        internal fun uint16(
            data: ByteArray,
            at: Int,
        ): Int = ((data[at].toInt() and 255) shl 8) or (data[at + 1].toInt() and 255)

        internal fun psiCrc(data: ByteArray): Int {
            var crc = -1
            data.forEach { byte ->
                crc = crc xor (byte.toInt() shl 24)
                repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04c11db7 else crc shl 1 }
            }
            return crc
        }

        private fun pts(
            data: ByteArray,
            at: Int,
        ): Long {
            require((0..4 step 2).all { data[at + it].toInt() and 1 == 1 }) { "carlink_pts_marker" }
            return ((data[at].toLong() and 14) shl 29) or ((data[at + 1].toLong() and 255) shl 22) or
                ((data[at + 2].toLong() and 254) shl 14) or ((data[at + 3].toLong() and 255) shl 7) or
                ((data[at + 4].toLong() and 254) ushr 1)
        }
    }
}
