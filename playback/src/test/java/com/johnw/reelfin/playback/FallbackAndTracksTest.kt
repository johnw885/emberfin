package com.johnw.reelfin.playback

import androidx.media3.common.PlaybackException as Media3Error
import com.johnw.reelfin.playback.Fixtures.dvMovieStreams
import com.johnw.reelfin.playback.Fixtures.source
import com.johnw.reelfin.playback.Fixtures.sub
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FallbackAndTracksTest {
    @Test fun `decoder errors walk the chain`() {
        assertEquals(PlaybackTier.DIRECT_STREAM, FallbackPolicy.onError(Media3Error.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackTier.DIRECT_PLAY))
        assertEquals(PlaybackTier.TRANSCODE, FallbackPolicy.onError(Media3Error.ERROR_CODE_DECODER_INIT_FAILED, PlaybackTier.DIRECT_STREAM))
        assertNull(FallbackPolicy.onError(Media3Error.ERROR_CODE_DECODING_FAILED, PlaybackTier.TRANSCODE))
        assertEquals(PlaybackTier.DIRECT_STREAM, FallbackPolicy.onError(Media3Error.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, PlaybackTier.DIRECT_PLAY))
    }

    @Test fun `network errors don't fall back`() {
        assertNull(FallbackPolicy.onError(Media3Error.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackTier.DIRECT_PLAY))
        assertNull(FallbackPolicy.onError(Media3Error.ERROR_CODE_IO_BAD_HTTP_STATUS, PlaybackTier.DIRECT_PLAY))
    }

    @Test fun `copied error codes match Media3`() {
        assertEquals(Media3Error.ERROR_CODE_PARSING_CONTAINER_MALFORMED, FallbackPolicy.ERROR_PARSING_CONTAINER_MALFORMED)
        assertEquals(Media3Error.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, FallbackPolicy.ERROR_PARSING_CONTAINER_UNSUPPORTED)
        assertEquals(Media3Error.ERROR_CODE_DECODER_QUERY_FAILED, FallbackPolicy.ERROR_DECODER_QUERY_FAILED)
        assertEquals(Media3Error.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, FallbackPolicy.ERROR_DECODING_FORMAT_EXCEEDS_CAPABILITIES)
        assertEquals(Media3Error.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED, FallbackPolicy.ERROR_AUDIO_TRACK_WRITE_FAILED)
    }

    private fun plan(transcoding: Boolean, streams: List<org.jellyfin.sdk.model.api.MediaStream> = dvMovieStreams): StreamPlan {
        val src = source(streams = streams, directPlay = !transcoding, transcodingUrl = if (transcoding) "/master.m3u8" else null)
        return PlaybackNegotiator.decide(
            PlaybackInfoResponse(listOf(src), "ps", null),
            PlaybackRequest(UUID(0, 1), UUID(0, 2), 1, audioStreamIndex = 2),
            "http://s.test",
        ) { _, _ -> "http://s.test/static" }
    }

    @Test fun `direct play audio maps to the n-th embedded audio track`() {
        val p = plan(transcoding = false)
        assertEquals(TrackTarget.Embedded(1), TrackMapper.audio(p, 2))
        assertEquals(TrackTarget.Embedded(0), TrackMapper.audio(p, 1))
        assertEquals(TrackTarget.Renegotiate, TrackMapper.audio(p, 99))
    }

    @Test fun `transcoded audio change needs a new stream`() {
        val p = plan(transcoding = true)
        assertEquals(TrackTarget.Embedded(0), TrackMapper.audio(p, 2))
        assertEquals(TrackTarget.Renegotiate, TrackMapper.audio(p, 1))
    }

    @Test fun `embedded subtitles map by order, off is off`() {
        val p = plan(transcoding = false)
        assertEquals(TrackTarget.Embedded(0), TrackMapper.subtitle(p, 4))
        assertEquals(TrackTarget.Embedded(1), TrackMapper.subtitle(p, 5))
        assertEquals(TrackTarget.Off, TrackMapper.subtitle(p, -1))
        assertEquals(TrackTarget.Off, TrackMapper.subtitle(p, null))
    }

    @Test fun `external subtitles are side-loaded, unknown formats renegotiate`() {
        val streams = listOf(
            Fixtures.video(),
            Fixtures.audio(1, "ac3"),
            sub(2, "subrip", isExternal = true, deliveryMethod = SubtitleDeliveryMethod.EXTERNAL, deliveryUrl = "/s/2.srt"),
            sub(3, "subrip"),
            sub(4, "dvb_teletext"),
        )
        val p = plan(transcoding = false, streams = streams)
        assertEquals(TrackTarget.SideLoaded("jf-sub-2"), TrackMapper.subtitle(p, 2))
        // The external stream doesn't count when numbering embedded ones.
        assertEquals(TrackTarget.Embedded(0), TrackMapper.subtitle(p, 3))
        assertEquals(TrackTarget.Renegotiate, TrackMapper.subtitle(p, 4))
    }

    @Test fun `embedded subtitle in an HLS stream needs a new stream`() {
        assertEquals(TrackTarget.Renegotiate, TrackMapper.subtitle(plan(transcoding = true), 5))
    }

    @Test fun `ordinal ignores other track types`() {
        assertEquals(2, TrackMapper.embeddedOrdinal(dvMovieStreams, MediaStreamType.AUDIO, 3))
        assertNull(TrackMapper.embeddedOrdinal(dvMovieStreams, MediaStreamType.AUDIO, 4))
    }
}
