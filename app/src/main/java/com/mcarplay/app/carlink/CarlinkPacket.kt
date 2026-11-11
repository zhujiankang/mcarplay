package com.mcarplay.app.carlink

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer

/** Independently implemented UCar framing, checked against CarWith 4.0.9 DEX. */
data class CarlinkPacket(
    val sequence: Long,
    val seconds: Long,
    val source: Int,
    val format: Int,
    val kind: Int,
    val category: Int,
    val method: Int,
    val payload: ByteArray,
) {
    fun encode(): ByteArray {
        require(sequence in 0..0xffffffffL && seconds in 0..0xffffffffL)
        require(source in 0..1 && format in 0..3 && kind in 0..3)
        require(category in 0..255 && method in 0..65535 && payload.size <= MAX_SIZE - HEADER_SIZE)
        val data = ByteArray(HEADER_SIZE + payload.size)
        val buffer = ByteBuffer.wrap(data)
        buffer.putInt(data.size).putInt(sequence.toInt()).putInt(seconds.toInt())
        buffer.put((source or (format shl 1) or (kind shl 3)).toByte())
        buffer.put(category.toByte()).putShort(method.toShort()).putShort(0)
        buffer.putShort(crc(data, 18).toShort()).put(payload)
        return data
    }

    companion object {
        const val HEADER_SIZE = 20
        const val MAX_SIZE = 1024 * 1024

        fun read(input: InputStream): CarlinkPacket {
            val stream = DataInputStream(input)
            val size = stream.readInt()
            require(size in HEADER_SIZE..MAX_SIZE) { "ucar_length" }
            val data = ByteArray(size)
            ByteBuffer.wrap(data).putInt(size)
            stream.readFully(data, 4, size - 4)
            return decode(data)
        }

        fun decode(data: ByteArray): CarlinkPacket {
            require(data.size in HEADER_SIZE..MAX_SIZE) { "ucar_length" }
            val buffer = ByteBuffer.wrap(data)
            require(buffer.int == data.size) { "ucar_length" }
            val sequence = buffer.int.toLong() and 0xffffffffL
            val time = buffer.int.toLong() and 0xffffffffL
            val flags = buffer.get().toInt() and 255
            val category = buffer.get().toInt() and 255
            val method = buffer.short.toInt() and 65535
            require(flags and 0xe0 == 0 && buffer.short.toInt() == 0) { "ucar_header" }
            require(buffer.short.toInt() and 65535 == crc(data, 18)) { "ucar_crc" }
            return CarlinkPacket(
                sequence,
                time,
                flags and 1,
                (flags ushr 1) and 3,
                (flags ushr 3) and 3,
                category,
                method,
                data.copyOfRange(HEADER_SIZE, data.size),
            )
        }

        internal fun crc(
            data: ByteArray,
            size: Int,
        ): Int {
            var value = 0
            for (i in 0 until size) {
                value = value xor (data[i].toInt() and 255)
                repeat(8) { value = if (value and 1 == 0) value ushr 1 else (value ushr 1) xor 0xa001 }
            }
            return value
        }
    }
}
