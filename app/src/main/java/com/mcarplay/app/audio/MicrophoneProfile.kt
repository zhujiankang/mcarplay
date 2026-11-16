package com.mcarplay.app.audio

/** Only negotiate LPCM that the receiver explicitly advertises for speech input. */
internal data class MicrophoneProfile(
    val format: Long,
    val sampleRate: Int,
) {
    companion object {
        fun select(info: Map<*, *>?): MicrophoneProfile? {
            val formats = info?.get("audioFormats") as? List<*> ?: return null
            val bits =
                formats
                    .mapNotNull { it as? Map<*, *> }
                    .filter {
                        (it["type"] as? Number)?.toLong() == 100L && it["audioType"] == "speechRecognition"
                    }.fold(0L) { mask, item -> mask or ((item["audioInputFormats"] as? Number)?.toLong() ?: 0L) }
            // AirPlay LPCM s16 mono: bit4=16kHz, bit6=24kHz. No Opus/AAC guessing.
            return when {
                bits and 0x40L != 0L -> MicrophoneProfile(0x40, 24_000)
                bits and 0x10L != 0L -> MicrophoneProfile(0x10, 16_000)
                else -> null
            }
        }
    }
}
