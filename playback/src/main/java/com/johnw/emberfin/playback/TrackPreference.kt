package com.johnw.emberfin.playback

import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType

/**
 * The audio/subtitle choice remembered for a series. Track indexes differ between episodes, so
 * this stores what the track *is* (language, title, forced flag) and [resolve] finds the matching
 * track in each new episode. Audio always goes through [AudioTrackPolicy], so a remembered choice
 * can never pick TrueHD/MLP/DTS.
 */
data class TrackPreference(
    /** null = no audio preference (the policy's usual choice). */
    val audio: AudioPreference? = null,
    /** null = no subtitle preference (the server's default). */
    val subtitle: SubtitlePreference? = null,
) {
    val isEmpty: Boolean get() = audio == null && subtitle == null
}

data class AudioPreference(val language: String?, val title: String?)

sealed interface SubtitlePreference {
    data object Off : SubtitlePreference
    data class On(val language: String?, val forced: Boolean, val title: String?, val codec: String?) : SubtitlePreference
}

/** Indexes for one episode; null = no preference, the negotiator decides as usual. */
data class ResolvedTracks(val audioIndex: Int?, val subtitleIndex: Int?)

object TrackMemory {
    /** What to remember after the user picked audio track [index] in [streams]. */
    fun audioFrom(streams: List<MediaStream>, index: Int): AudioPreference? {
        val s = streams.firstOrNull { it.type == MediaStreamType.AUDIO && it.index == index } ?: return null
        if (AudioTrackPolicy.isBanned(s.codec)) return null
        return AudioPreference(s.language.normalized(), s.displayTitle)
    }

    /** What to remember after the user picked subtitle [index] (null = off). */
    fun subtitleFrom(streams: List<MediaStream>, index: Int?): SubtitlePreference? {
        if (index == null || index < 0) return SubtitlePreference.Off
        val s = streams.firstOrNull { it.type == MediaStreamType.SUBTITLE && it.index == index } ?: return null
        return SubtitlePreference.On(s.language.normalized(), s.isForced, s.displayTitle, s.codec?.lowercase())
    }

    fun resolve(pref: TrackPreference?, streams: List<MediaStream>, policy: AudioTrackPolicy): ResolvedTracks {
        if (pref == null) return ResolvedTracks(null, null)
        return ResolvedTracks(pref.audio?.let { audio(it, streams, policy) }, pref.subtitle?.let { subtitle(it, streams) })
    }

    private fun audio(pref: AudioPreference, streams: List<MediaStream>, policy: AudioTrackPolicy): Int? {
        val allowed = policy.menuTracks(streams)
        if (allowed.isEmpty()) return null
        val inLanguage = allowed.filter { pref.language == null || it.language.normalized() == pref.language }
        if (inLanguage.isEmpty()) return null // That language isn't in this episode: the usual choice.
        // The same kind of track as last time (episodes of one release share titles like "Dolby Digital 5.1").
        inLanguage.firstOrNull { pref.title != null && it.displayTitle == pref.title }?.let { return it.index }
        return (policy.choose(inLanguage, pref.language) as? AudioChoice.Track)?.index
    }

    private fun subtitle(pref: SubtitlePreference, streams: List<MediaStream>): Int? = when (pref) {
        SubtitlePreference.Off -> -1
        is SubtitlePreference.On -> {
            val subs = streams.filter { it.type == MediaStreamType.SUBTITLE }
            val sameLanguage = subs.filter { pref.language == null || it.language.normalized() == pref.language }
            sameLanguage.sortedWith(
                compareBy<MediaStream>(
                    { if (it.isForced == pref.forced) 0 else 1 },
                    { if (pref.title != null && it.displayTitle == pref.title) 0 else 1 },
                    { if (pref.codec != null && it.codec?.lowercase() == pref.codec) 0 else 1 },
                    { it.index },
                ),
            ).firstOrNull()?.index
        }
    }

    private fun String?.normalized(): String? = this?.lowercase()?.takeUnless { it.isBlank() || it == "und" }
}
