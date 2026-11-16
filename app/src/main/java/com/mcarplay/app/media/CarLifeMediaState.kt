package com.mcarplay.app.media

import com.mcarplay.app.carlife.CarLifeMediaInfo

/** Prevent lyric updates from destroying the real song identity/cover; discard cross-track stale lyrics. */
internal class CarLifeMediaState {
    var song: CarLifeMediaInfo? = null
        private set
    private var lyric: CarLifeMediaInfo? = null
    private var lyricAt = 0L

    fun update(
        info: CarLifeMediaInfo,
        now: Long,
    ) {
        val base = song
        val lyricCandidate = base != null && info.album.isNotBlank() && info.artist == "${base.album}-${base.artist}"
        if (lyricCandidate) {
            if (base != null && sameTrack(base, info) && info.artist == "${base.album}-${base.artist}") {
                lyric = info
                lyricAt = now
            } else {
                lyric = null
            }
            return
        }
        val changed =
            base == null ||
                base.source != info.source ||
                base.song != info.song ||
                base.artist != info.artist ||
                base.songId != info.songId
        song = if (!changed && info.artwork == null) info.copy(artwork = base?.artwork) else info
        if (changed) lyric = null
    }

    fun lyricTitle(
        now: Long,
        enabled: Boolean,
        sessionTitle: String?,
        sessionArtist: String?,
    ): String? {
        val base = song ?: return null
        val candidate = lyric ?: return null
        if (!enabled || now - lyricAt !in 0..5_000) return null
        if (!sessionTitle.isNullOrBlank() && sessionTitle.trim() != base.song.trim()) return null
        if (!sessionArtist.isNullOrBlank() && sessionArtist.trim() != base.artist.trim()) return null
        return candidate.song
    }

    private fun sameTrack(
        base: CarLifeMediaInfo,
        candidate: CarLifeMediaInfo,
    ): Boolean =
        base.source == candidate.source &&
            base.album == candidate.album &&
            (base.songId.isEmpty() || candidate.songId.isEmpty() || base.songId == candidate.songId)
}
