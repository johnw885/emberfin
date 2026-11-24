package com.johnw.emberfin.playback

import org.jellyfin.sdk.model.api.AudioSpatialFormat
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType

/** The audio track to play, or the track the server must transcode because only banned codecs exist. */
sealed interface AudioChoice {
    data class Track(val index: Int) : AudioChoice
    data class TranscodeFrom(val index: Int) : AudioChoice
    data object None : AudioChoice
}

/**
 * Audio rules: TrueHD/MLP/DTS are never played or offered (the owner's rule). Preference order
 * is E-AC-3 (Atmos first) > AC-3 > AAC > anything else, inside the preferred language.
 */
class AudioTrackPolicy(private val preferAtmos: Boolean = true) {

    /** Codecs the device may direct-play, with banned ones removed, in preference order. */
    fun directPlayCodecs(available: Set<String>): List<String> =
        available.map { it.lowercase() }.filterNot(::isBanned).distinct()
            .sortedWith(compareBy({ CODEC_ORDER.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it }))

    /** Audio tracks the menu may show: everything except banned codecs, in file order. */
    fun menuTracks(streams: List<MediaStream>): List<MediaStream> =
        streams.filter { it.type == MediaStreamType.AUDIO && !isBanned(it.codec) }.sortedBy { it.index }

    fun choose(streams: List<MediaStream>, preferredLanguage: String?): AudioChoice {
        val audio = streams.filter { it.type == MediaStreamType.AUDIO }
        if (audio.isEmpty()) return AudioChoice.None
        val allowed = audio.filterNot { isBanned(it.codec) }
        if (allowed.isEmpty()) {
            return AudioChoice.TranscodeFrom(inLanguage(audio, preferredLanguage).sortedWith(byQuality).first().index)
        }
        return AudioChoice.Track(inLanguage(allowed, preferredLanguage).sortedWith(byPreference).first().index)
    }

    /** Rank of a track: lower is better. */
    fun rank(stream: MediaStream): Int = when (stream.codec?.lowercase()) {
        "eac3" -> if (preferAtmos && isAtmos(stream)) 0 else 1
        "ac3" -> 2
        "aac" -> 3
        else -> 4
    }

    private val byPreference: Comparator<MediaStream> = compareBy<MediaStream>(
        { if (isCommentary(it)) 1 else 0 },
        { rank(it) },
        { -(it.channels ?: 0) },
        { if (it.isDefault) 0 else 1 },
        { it.index },
    )

    private val byQuality: Comparator<MediaStream> = compareBy<MediaStream>(
        { if (isCommentary(it)) 1 else 0 },
        { -(it.channels ?: 0) },
        { if (it.isDefault) 0 else 1 },
        { it.index },
    )

    private fun inLanguage(streams: List<MediaStream>, language: String?): List<MediaStream> {
        if (language.isNullOrBlank()) return streams
        val matching = streams.filter { it.language.equals(language, ignoreCase = true) }
        return matching.ifEmpty { streams }
    }

    companion object {
        private val CODEC_ORDER = listOf("eac3", "ac3", "aac", "mp3", "opus", "flac", "vorbis", "alac")

        /** TrueHD, MLP and every DTS flavour. */
        fun isBanned(codec: String?): Boolean {
            val c = codec?.lowercase()?.trim() ?: return false
            return c == "truehd" || c == "mlp" || c == "dca" || c.startsWith("dts")
        }

        fun isAtmos(stream: MediaStream): Boolean =
            stream.audioSpatialFormat == AudioSpatialFormat.DOLBY_ATMOS ||
                stream.profile?.contains("atmos", ignoreCase = true) == true ||
                stream.displayTitle?.contains("atmos", ignoreCase = true) == true

        private fun isCommentary(stream: MediaStream): Boolean =
            stream.title?.contains("commentary", ignoreCase = true) == true
    }
}
