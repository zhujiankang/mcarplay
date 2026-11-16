package com.mcarplay.app.carlife

/** Local-only metadata. Never put titles, artists, lyrics or artwork bytes in diagnostics. */
data class CarLifeMediaInfo(
    val source: String,
    val song: String,
    val artist: String,
    val album: String,
    val artwork: ByteArray?,
    val durationMs: Long,
    val songId: String,
)

/** Baidu CarlifeMediaInfo schema; accepts direct messages (Xiaomi 8.8.5) and bounded list wrappers. */
object CarLifeMediaParser {
    private const val MAX_PAYLOAD = 1_048_576
    private const val MAX_ART = 524_288
    private const val MAX_TEXT = 4096

    private data class Field(
        val id: Int,
        val wire: Int,
        val number: Long = 0,
        val bytes: ByteArray? = null,
    )

    fun parse(payload: ByteArray): List<CarLifeMediaInfo> =
        runCatching {
            require(payload.size in 1..MAX_PAYLOAD)
            val fields = fields(payload)
            if (fields.any { it.id == 1 && it.wire == 2 }) {
                listOfNotNull(info(fields))
            } else {
                val count = fields.singleOrNull { it.id == 1 && it.wire == 0 }?.number ?: error("Missing count")
                val entries = fields.filter { it.id == 2 && it.wire == 2 }
                require(count in 0..16 && entries.size.toLong() == count)
                entries.mapNotNull { info(fields(it.bytes!!)) }
            }
        }.getOrDefault(emptyList())

    private fun fields(payload: ByteArray): List<Field> {
        val result = ArrayList<Field>()
        var offset = 0

        fun number(): Long {
            val (n, length) = CarLifeProtocol.readVarint(payload, offset)
            offset += length
            return n
        }
        while (offset < payload.size) {
            require(result.size < 64)
            val tag = number()
            val id = (tag ushr 3).toInt()
            require(id > 0)
            val wire = (tag and 7).toInt()
            when (wire) {
                0 -> result += Field(id, wire, number())
                2 -> {
                    val length = number()
                    require(length in 0..(payload.size - offset).toLong())
                    val end = offset + length.toInt()
                    result += Field(id, wire, bytes = payload.copyOfRange(offset, end))
                    offset = end
                }
                1, 5 -> {
                    offset += if (wire == 1) 8 else 4
                    require(offset <= payload.size)
                }
                else -> error("Invalid wire type")
            }
        }
        return result
    }

    private fun info(fields: List<Field>): CarLifeMediaInfo? {
        require(
            fields
                .groupingBy { it.id }
                .eachCount()
                .values
                .all { it == 1 },
        )

        fun text(id: Int): String {
            val bytes = fields.singleOrNull { it.id == id && it.wire == 2 }?.bytes ?: return ""
            require(bytes.size <= MAX_TEXT)
            return bytes.decodeToString(throwOnInvalidSequence = true).replace("\u0000", "").trim()
        }
        val source = text(1)
        val song = text(2)
        if (source.isBlank() || song.isBlank()) return null
        val art = fields.singleOrNull { it.id == 5 && it.wire == 2 }?.bytes
        require((art?.size ?: 0) <= MAX_ART)
        val duration = fields.singleOrNull { it.id == 6 && it.wire == 0 }?.number ?: 0
        return CarLifeMediaInfo(source, song, text(3), text(4), art?.takeIf { it.isNotEmpty() }, duration.coerceIn(0, 86_400_000), text(8))
    }
}
