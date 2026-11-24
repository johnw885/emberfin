package com.johnw.emberfin.playback

import org.jellyfin.sdk.model.api.AudioSpatialFormat
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.VideoRangeType

/** Synthetic test data only: no real server, item or library details. */
object Fixtures {
    fun stream(
        index: Int,
        type: MediaStreamType,
        codec: String?,
        language: String? = null,
        channels: Int? = null,
        profile: String? = null,
        title: String? = null,
        isDefault: Boolean = false,
        isExternal: Boolean = false,
        deliveryMethod: SubtitleDeliveryMethod? = null,
        deliveryUrl: String? = null,
        spatial: AudioSpatialFormat = AudioSpatialFormat.NONE,
        rangeType: VideoRangeType = VideoRangeType.SDR,
    ) = MediaStream(
        index = index,
        type = type,
        codec = codec,
        language = language,
        channels = channels,
        profile = profile,
        title = title,
        displayTitle = listOfNotNull(language, codec, title).joinToString(" "),
        isDefault = isDefault,
        isExternal = isExternal,
        isForced = false,
        isHearingImpaired = false,
        isInterlaced = false,
        isOriginal = false,
        isTextSubtitleStream = type == MediaStreamType.SUBTITLE && codec in listOf("subrip", "srt", "ass", "ssa", "webvtt"),
        supportsExternalStream = false,
        deliveryMethod = deliveryMethod,
        deliveryUrl = deliveryUrl,
        audioSpatialFormat = spatial,
        videoRangeType = rangeType,
    )

    fun audio(index: Int, codec: String, language: String? = "eng", channels: Int = 6, isDefault: Boolean = false, profile: String? = null, title: String? = null, spatial: AudioSpatialFormat = AudioSpatialFormat.NONE) =
        stream(index, MediaStreamType.AUDIO, codec, language, channels, profile, title, isDefault, spatial = spatial)

    fun sub(index: Int, codec: String, language: String? = "eng", isExternal: Boolean = false, deliveryMethod: SubtitleDeliveryMethod? = null, deliveryUrl: String? = null) =
        stream(index, MediaStreamType.SUBTITLE, codec, language, isExternal = isExternal, deliveryMethod = deliveryMethod, deliveryUrl = deliveryUrl)

    fun video(index: Int = 0, codec: String = "hevc", range: VideoRangeType = VideoRangeType.HDR10) =
        stream(index, MediaStreamType.VIDEO, codec, rangeType = range)

    /** A 4K DV P8.1 movie whose default audio is TrueHD, with AC-3 and DTS:X alternatives. */
    val dvMovieStreams = listOf(
        video(0, range = VideoRangeType.DOVI_WITH_HDR10),
        audio(1, "truehd", channels = 8, isDefault = true, profile = "Dolby TrueHD + Dolby Atmos"),
        audio(2, "ac3", channels = 6),
        audio(3, "dts", channels = 8, profile = "DTS-HD MA + DTS:X"),
        sub(4, "subrip"),
        sub(5, "PGSSUB"),
        sub(6, "subrip", "fra"),
    )

    fun source(
        id: String = "0123456789abcdef0123456789abcdef",
        streams: List<MediaStream> = dvMovieStreams,
        directPlay: Boolean = true,
        transcodingUrl: String? = null,
        defaultAudio: Int? = 1,
        defaultSubtitle: Int? = -1,
    ) = MediaSourceInfo(
        protocol = MediaProtocol.FILE,
        id = id,
        type = MediaSourceType.DEFAULT,
        container = "mkv",
        isRemote = false,
        readAtNativeFramerate = false,
        ignoreDts = false,
        ignoreIndex = false,
        genPtsInput = false,
        supportsTranscoding = true,
        supportsDirectStream = directPlay,
        supportsDirectPlay = directPlay,
        isInfiniteStream = false,
        useMostCompatibleTranscodingProfile = false,
        requiresOpening = false,
        requiresClosing = false,
        requiresLooping = false,
        supportsProbing = true,
        mediaStreams = streams,
        transcodingUrl = transcodingUrl,
        transcodingSubProtocol = if (transcodingUrl != null) MediaStreamProtocol.HLS else MediaStreamProtocol.HTTP,
        defaultAudioStreamIndex = defaultAudio,
        defaultSubtitleStreamIndex = defaultSubtitle,
        hasSegments = false,
        runTimeTicks = 7_620_000_000_0L,
    )
}
