package com.mcarplay.app.carlink

import java.io.ByteArrayOutputStream

/** Small bounded protobuf codec; only singular values are accepted by this protocol surface. */
class CarlinkFields {
    private val values = sortedMapOf<Int, Any>()

    fun number(field: Int): Long = values[field] as? Long ?: 0L

    fun bytes(field: Int): ByteArray = (values[field] as? ByteArray)?.copyOf() ?: error("ucar_missing_field")

    fun contains(field: Int): Boolean = field in values

    fun put(
        field: Int,
        value: Long,
    ): CarlinkFields =
        apply {
            require(field in 1..0x1fffffff)
            values[field] = value
        }

    fun put(
        field: Int,
        value: ByteArray,
    ): CarlinkFields =
        apply {
            require(field in 1..0x1fffffff)
            values[field] = value.copyOf()
        }

    fun encode(): ByteArray {
        val output = ByteArrayOutputStream()

        fun write(value: Long) {
            var remaining = value
            while (remaining and -128L != 0L) {
                output.write((remaining.toInt() and 127) or 128)
                remaining = remaining ushr 7
            }
            output.write(remaining.toInt())
        }
        values.forEach { (field, value) ->
            if (value is Long) {
                write(field.toLong() shl 3)
                write(value)
            } else {
                val bytes = value as ByteArray
                write((field.toLong() shl 3) or 2)
                write(bytes.size.toLong())
                output.write(bytes)
            }
        }
        return output.toByteArray()
    }

    companion object {
        fun decode(data: ByteArray): CarlinkFields {
            require(data.size <= CarlinkPacket.MAX_SIZE)
            val result = CarlinkFields()
            var offset = 0

            fun read(): Long {
                var value = 0L
                for (i in 0..9) {
                    require(offset < data.size) { "ucar_truncated_varint" }
                    val next = data[offset++].toInt() and 255
                    require(i != 9 || next <= 1) { "ucar_varint_overflow" }
                    value = value or ((next and 127).toLong() shl (7 * i))
                    if (next and 128 == 0) return value
                }
                error("ucar_varint_overflow")
            }
            while (offset < data.size) {
                require(result.values.size < 128) { "ucar_field_limit" }
                val tag = read()
                val field = tag ushr 3
                require(field in 1..0x1fffffff && !result.contains(field.toInt())) { "ucar_field" }
                when ((tag and 7).toInt()) {
                    0 -> result.put(field.toInt(), read())
                    1, 2, 5 -> {
                        val size =
                            when ((tag and 7).toInt()) {
                                1 -> 8L
                                5 -> 4L
                                else -> read()
                            }
                        require(size in 0..(data.size - offset).toLong()) { "ucar_truncated_field" }
                        result.put(field.toInt(), data.copyOfRange(offset, offset + size.toInt()))
                        offset += size.toInt()
                    }
                    else -> error("ucar_wire_type")
                }
            }
            return result
        }
    }
}
