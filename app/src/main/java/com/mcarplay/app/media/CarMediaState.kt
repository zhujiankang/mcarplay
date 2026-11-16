package com.mcarplay.app.media

/** Immutable normalized snapshot shared by independent Bluetooth/network iAP2 subscriptions. */
data class CarMediaSnapshot(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val app: String = "",
    val durationMs: Long = 0,
    val elapsedMs: Long = 0,
    val status: Int = 0,
    val artwork: ByteArray? = null,
    val artworkKey: String? = null,
    val lyricCompatibility: Boolean = false,
)

object CarMediaState {
    @Volatile var current: CarMediaSnapshot? = null
        internal set
}
