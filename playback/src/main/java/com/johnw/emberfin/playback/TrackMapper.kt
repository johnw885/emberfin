package com.johnw.emberfin.playback

import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType

/** Where a Jellyfin track lives in ExoPlayer, and how a track change must be made. */
sealed interface TrackTarget {
    /** The n-th embedded track of its type, in file order (how ExoPlayer lists a direct-played file's tracks). */
    data class Embedded(val ordinal: Int) : TrackTarget
    /** A side-loaded subtitle; ExoPlayer's format id ends with [id]. */
    data class SideLoaded(val id: String) : TrackTarget
    /** Not in the current stream: ask the server again (new audio for a transcode, or a burn-in). */
    data object Renegotiate : TrackTarget
    data object Off : TrackTarget
}

/** Maps Jellyfin stream indexes to ExoPlayer tracks for a [StreamPlan]. */
object TrackMapper {
    fun sideLoadedId(index: Int) = "jf-sub-$index"

    fun audio(plan: StreamPlan, index: Int): TrackTarget {
        if (plan.isTranscoding) return if (index == plan.audioStreamIndex) TrackTarget.Embedded(0) else TrackTarget.Renegotiate
        return embeddedOrdinal(plan.streams, MediaStreamType.AUDIO, index)?.let { TrackTarget.Embedded(it) } ?: TrackTarget.Renegotiate
    }

    fun subtitle(plan: StreamPlan, index: Int?): TrackTarget {
        if (index == null || index < 0) return TrackTarget.Off
        if (plan.externalSubtitles.any { it.index == index }) return TrackTarget.SideLoaded(sideLoadedId(index))
        val stream = plan.streams.firstOrNull { it.index == index } ?: return TrackTarget.Renegotiate
        // In an HLS stream only side-loaded subtitles exist; an embedded or burned-in one needs a new stream.
        if (plan.isTranscoding || stream.isExternal) return TrackTarget.Renegotiate
        if (stream.codec?.lowercase() !in DeviceProfileBuilder.EMBEDDED_SUBTITLES) return TrackTarget.Renegotiate
        return embeddedOrdinal(plan.streams, MediaStreamType.SUBTITLE, index)?.let { TrackTarget.Embedded(it) } ?: TrackTarget.Renegotiate
    }

    /** Position of stream [index] among the embedded streams of [type], sorted by index. */
    fun embeddedOrdinal(streams: List<MediaStream>, type: MediaStreamType, index: Int): Int? =
        streams.filter { it.type == type && !it.isExternal }.sortedBy { it.index }
            .indexOfFirst { it.index == index }.takeIf { it >= 0 }
}
