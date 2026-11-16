package com.mcarplay.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.mcarplay.app.carlife.CarLifeMediaInfo
import com.mcarplay.app.settings.MediaPrefs
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Bounded local media bridge. Image decode/encode never runs on CarLife/BT/event/UI threads. */
internal class MediaMetadataRelay(
    private val log: (String) -> Unit,
) {
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "media-metadata").apply { isDaemon = true } }
    private var task: ScheduledFuture<*>? = null
    private var owner: Any? = null
    private var epoch = 0L
    private var state = CarLifeMediaState()
    private var lastPcm = Long.MIN_VALUE
    private var coverInput: String? = null
    private var coverOutput: ByteArray? = null
    private var metadataReceived = 0L
    private var imageErrors = 0

    @Synchronized
    fun start(
        context: Context,
        source: Any,
    ) {
        if (owner === source) return
        stop()
        owner = source
        val generation = epoch
        task =
            worker.scheduleWithFixedDelay({
                try {
                    refresh(context.applicationContext, generation)
                } catch (_: Exception) {
                    if (imageErrors++ < 3) log("MEDIA-BRIDGE: local metadata refresh unavailable")
                }
            }, 0, 500, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun stop(source: Any? = null) {
        if (source != null && owner !== source) return
        epoch++
        owner = null
        task?.cancel(false)
        task = null
        state = CarLifeMediaState()
        coverInput = null
        coverOutput = null
        lastPcm = Long.MIN_VALUE
        metadataReceived = 0
        imageErrors = 0
        CarMediaState.current = null
    }

    @Synchronized
    fun media(
        source: Any,
        info: CarLifeMediaInfo,
    ) {
        if (owner !== source) return
        state.update(info, SystemClock.elapsedRealtime())
        metadataReceived++
        if (metadataReceived <= 3 || metadataReceived and (metadataReceived - 1) == 0L) {
            log("MEDIA-BRIDGE: carlifeInfoReceived=$metadataReceived artworkBytes=${info.artwork?.size ?: 0}")
        }
    }

    @Synchronized
    fun playback(
        source: Any,
        playing: Boolean,
    ) {
        if (owner === source) lastPcm = if (playing) SystemClock.elapsedRealtime() else Long.MIN_VALUE
    }

    private fun refresh(
        context: Context,
        generation: Long,
    ) {
        val time = SystemClock.elapsedRealtime()
        val session = MediaSessionsState.nowPlaying.takeIf { MediaSessionsState.accessGranted && !it?.title.isNullOrBlank() }
        val data =
            synchronized(this) {
                if (owner == null || epoch != generation) return
                val song = state.song
                val playing = session?.playing ?: (lastPcm >= 0 && time - lastPcm in 0..3_000)
                val lyric = state.lyricTitle(time, playing && MediaPrefs.clusterLyrics(context), session?.title, session?.artist)
                Triple(song, playing, lyric)
            }
        val song = data.first
        val matching =
            session == null ||
                (
                    song != null &&
                        session.title?.trim() == song.song.trim() &&
                        (session.artist.isNullOrBlank() || song.artist.isBlank() || session.artist.trim() == song.artist.trim())
                )
        if (song == null && session == null) {
            synchronized(this) { if (epoch == generation) CarMediaState.current = null }
            return
        }
        val raw = song?.artwork.takeIf { matching }
        val bitmap = session?.art
        val inputKey =
            when {
                bitmap != null -> "bitmap:${bitmap.generationId}:${bitmap.width}:${bitmap.height}"
                raw != null -> "raw:${hash(raw)}"
                else -> null
            }
        val cached = synchronized(this) { Pair(inputKey == coverInput, coverOutput) }
        val cover =
            if (inputKey == null) {
                null
            } else {
                if (cached.first) {
                    cached.second
                } else {
                    try {
                        encodeCover(raw, bitmap)
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        if (!cached.first && inputKey != null) log("MEDIA-BRIDGE: artworkNormalized=${cover != null} bytes=${cover?.size ?: 0}")
        val snapshot =
            CarMediaSnapshot(
                title = data.third ?: session?.title ?: song?.song.orEmpty(),
                artist = session?.artist ?: song?.artist.orEmpty(),
                album = session?.album ?: song?.album.orEmpty(),
                app = session?.pkg ?: song?.source.orEmpty(),
                durationMs = session?.durationMs ?: song?.durationMs ?: 0,
                elapsedMs = session?.displayPositionAt(time) ?: 0,
                status = if (data.second) 1 else 2,
                artwork = cover,
                artworkKey = cover?.let(::hash),
                lyricCompatibility = data.third != null,
            )
        synchronized(this) {
            if (epoch != generation || owner == null || state.song !== song) return
            coverInput = inputKey
            coverOutput = cover
            CarMediaState.current = snapshot
        }
    }

    private fun encodeCover(
        raw: ByteArray?,
        bitmap: Bitmap?,
    ): ByteArray? {
        var owned: Bitmap? = null
        val source =
            bitmap ?: raw?.let {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
                if (bounds.outWidth !in 1..8192 ||
                    bounds.outHeight !in 1..8192 ||
                    bounds.outWidth.toLong() * bounds.outHeight > 16_777_216
                ) {
                    return null
                }
                val options =
                    BitmapFactory.Options().apply {
                        inSampleSize =
                            maxOf(
                                bounds.outWidth,
                                bounds.outHeight,
                            ).div(512).coerceAtLeast(1)
                    }
                BitmapFactory.decodeByteArray(it, 0, it.size, options)?.also { image -> owned = image }
            } ?: return null
        var scaled: Bitmap? = null
        return try {
            val size = maxOf(source.width, source.height)
            val image =
                if (size > 512) {
                    Bitmap
                        .createScaledBitmap(
                            source,
                            (source.width * 512 / size).coerceAtLeast(1),
                            (source.height * 512 / size).coerceAtLeast(1),
                            true,
                        ).also { scaled = it }
                } else {
                    source
                }
            val output = ByteArrayOutputStream()
            if (!image.compress(Bitmap.CompressFormat.JPEG, 80, output)) return null
            output.toByteArray().takeIf { it.size in 1..131_072 }
        } finally {
            scaled?.takeIf { it !== source }?.recycle()
            owned?.recycle()
        }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
