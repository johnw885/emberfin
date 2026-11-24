package com.johnw.reelfin.ui

import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.jellyfin.sdk.model.api.VideoRangeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemFormatTest {
    private val id = UUID(0L, 1L)

    private fun stream(type: MediaStreamType, index: Int, codec: String? = null, width: Int? = null, range: VideoRangeType? = null,
                       channels: Int? = null, title: String? = null) = MediaStream(
        type = type, index = index, codec = codec, width = width, videoRangeType = range ?: VideoRangeType.UNKNOWN,
        channels = channels, displayTitle = title, isOriginal = false, isInterlaced = false, isDefault = false, isForced = false, isHearingImpaired = false, isExternal = false,
        isTextSubtitleStream = type == MediaStreamType.SUBTITLE, supportsExternalStream = false,
    )

    private fun withStreams(vararg streams: MediaStream) = BaseItemDto(
        id = id, type = BaseItemKind.MOVIE,
        mediaSources = listOf(
            MediaSourceInfo(
                protocol = MediaProtocol.FILE, type = MediaSourceType.DEFAULT, isRemote = false, readAtNativeFramerate = false,
                ignoreDts = false, ignoreIndex = false, genPtsInput = false, supportsTranscoding = true, supportsDirectStream = true,
                supportsDirectPlay = true, isInfiniteStream = false, requiresOpening = false, requiresClosing = false,
                requiresLooping = false, supportsProbing = true, mediaStreams = streams.toList(), transcodingSubProtocol = MediaStreamProtocol.HTTP,
                hasSegments = false, useMostCompatibleTranscodingProfile = false,
            ),
        ),
    )

    @Test fun badgesFor4kDolbyVisionAtmos() {
        val item = withStreams(
            stream(MediaStreamType.VIDEO, 0, "hevc", width = 3840, range = VideoRangeType.DOVI_WITH_HDR10),
            stream(MediaStreamType.AUDIO, 1, "eac3", channels = 6, title = "English - Dolby Digital+ Atmos"),
            stream(MediaStreamType.SUBTITLE, 2, "srt"),
        )
        assertEquals(listOf("4K", "Dolby Vision", "Atmos", "5.1", "CC"), ItemFormat.badges(item))
    }

    @Test fun trueHdAtmosAloneEarnsNoAtmosBadge() {
        // Reelfin never plays TrueHD, so its Atmos doesn't count.
        val item = withStreams(
            stream(MediaStreamType.VIDEO, 0, "hevc", width = 1920, range = VideoRangeType.SDR),
            stream(MediaStreamType.AUDIO, 1, "truehd", channels = 8, title = "TrueHD Atmos 7.1"),
            stream(MediaStreamType.AUDIO, 2, "ac3", channels = 2, title = "Stereo"),
        )
        assertEquals(listOf("HD"), ItemFormat.badges(item))
    }

    @Test fun dolbyVisionWithEnhancementLayerShowsHdr10() {
        // P7 FEL plays as its HDR10 base layer.
        val item = withStreams(stream(MediaStreamType.VIDEO, 0, "hevc", width = 3840, range = VideoRangeType.DOVI_WITH_EL))
        assertEquals(listOf("4K", "HDR10"), ItemFormat.badges(item))
    }

    @Test fun runtimeFormatting() {
        assertEquals("2h 14m", ItemFormat.runtime(134L * 600_000_000L))
        assertEquals("45m", ItemFormat.runtime(45L * 600_000_000L))
        assertNull(ItemFormat.runtime(null))
        assertNull(ItemFormat.runtime(0))
    }

    @Test fun episodeLineAndTitle() {
        val ep = BaseItemDto(id = id, type = BaseItemKind.EPISODE, name = "Dulcinea", seriesName = "Show", parentIndexNumber = 1, indexNumber = 1)
        assertEquals("S1:E1  ·  Dulcinea", ItemFormat.episodeLine(ep))
        assertEquals("Show", ItemFormat.title(ep))
        assertNull(ItemFormat.episodeLine(BaseItemDto(id = id, type = BaseItemKind.MOVIE, name = "Film")))
    }

    @Test fun progressIgnoresUntouchedAndFinished() {
        fun at(pct: Double) = BaseItemDto(
            id = id, type = BaseItemKind.MOVIE,
            userData = UserItemDataDto(playedPercentage = pct, playbackPositionTicks = 1, playCount = 0, isFavorite = false, played = false, key = "k", itemId = id),
        )
        assertNull(ItemFormat.progress(at(0.0)))
        assertNull(ItemFormat.progress(at(99.9)))
        assertTrue((ItemFormat.progress(at(50.0)) ?: 0f) in 0.49f..0.51f)
    }
}
