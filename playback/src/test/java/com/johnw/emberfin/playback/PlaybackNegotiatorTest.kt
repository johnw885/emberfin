package com.johnw.emberfin.playback

import com.johnw.emberfin.playback.Fixtures.source
import com.johnw.emberfin.playback.Fixtures.sub
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackErrorCode
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackNegotiatorTest {
    private val itemId = UUID(0L, 1L)
    private val request = PlaybackRequest(itemId, UUID(0L, 2L), 120_000_000, audioStreamIndex = 2, subtitleStreamIndex = -1)
    private val base = "http://server.test:8096"
    private val staticUrl: (org.jellyfin.sdk.model.api.MediaSourceInfo, String?) -> String = { s, ps -> "$base/Videos/x/stream.${s.container}?static=true&PlaySessionId=$ps" }

    private fun response(vararg sources: org.jellyfin.sdk.model.api.MediaSourceInfo, error: PlaybackErrorCode? = null) =
        PlaybackInfoResponse(mediaSources = sources.toList(), playSessionId = "ps1", errorCode = error)

    @Test fun `direct play uses the static URL`() {
        val plan = PlaybackNegotiator.decide(response(source()), request, base, staticUrl)
        assertEquals(PlayMethod.DIRECT_PLAY, plan.playMethod)
        assertEquals(PlaybackTier.DIRECT_PLAY, plan.tier)
        assertTrue(plan.url.startsWith("$base/Videos/x/stream.mkv"))
        assertEquals(2, plan.audioStreamIndex)
        assertEquals("ps1", plan.playSessionId)
    }

    @Test fun `server refusing direct play gives the HLS transcode URL`() {
        val plan = PlaybackNegotiator.decide(response(source(directPlay = false, transcodingUrl = "/videos/x/master.m3u8?a=1")), request, "$base/", staticUrl)
        assertEquals(PlayMethod.TRANSCODE, plan.playMethod)
        assertEquals("$base/videos/x/master.m3u8?a=1", plan.url)
        assertEquals(PlaybackTier.DIRECT_STREAM, plan.tier)
        assertTrue(plan.isTranscoding)
    }

    @Test fun `fallback tier ignores direct play support`() {
        val src = source(directPlay = true, transcodingUrl = "/videos/x/master.m3u8")
        val plan = PlaybackNegotiator.decide(response(src), request.copy(tier = PlaybackTier.TRANSCODE), base, staticUrl)
        assertEquals(PlayMethod.TRANSCODE, plan.playMethod)
        assertEquals(PlaybackTier.TRANSCODE, plan.tier)
    }

    @Test(expected = PlaybackException::class)
    fun `server error code throws`() {
        PlaybackNegotiator.decide(response(source(), error = PlaybackErrorCode.NOT_ALLOWED), request, base, staticUrl)
    }

    @Test(expected = PlaybackException::class)
    fun `nothing playable throws`() {
        PlaybackNegotiator.decide(response(source(directPlay = false)), request, base, staticUrl)
    }

    @Test fun `picks the requested media source`() {
        val a = source(id = "a".repeat(32))
        val b = source(id = "b".repeat(32))
        val plan = PlaybackNegotiator.decide(response(a, b), request.copy(mediaSourceId = b.id), base, staticUrl)
        assertEquals(b.id, plan.mediaSourceId)
    }

    @Test fun `external subtitles get absolute URLs and MIME types`() {
        val streams = listOf(
            sub(3, "subrip", deliveryMethod = SubtitleDeliveryMethod.EXTERNAL, deliveryUrl = "/Videos/x/Subtitles/3/0/Stream.subrip"),
            sub(4, "ass", "fra", deliveryMethod = SubtitleDeliveryMethod.EXTERNAL, deliveryUrl = "/Videos/x/Subtitles/4/0/Stream.ass"),
            sub(5, "PGSSUB", deliveryMethod = SubtitleDeliveryMethod.EMBED),
            sub(6, "dvb_teletext", deliveryMethod = SubtitleDeliveryMethod.EXTERNAL, deliveryUrl = "/x"),
        )
        val subs = PlaybackNegotiator.externalSubtitles(streams, base)
        assertEquals(listOf(3, 4), subs.map { it.index })
        assertEquals("$base/Videos/x/Subtitles/3/0/Stream.subrip", subs[0].url)
        assertEquals("application/x-subrip", subs[0].mimeType)
        assertEquals("text/x-ssa", subs[1].mimeType)
    }

    @Test fun `preferred language follows the server's default audio track`() {
        assertEquals("eng", PlaybackNegotiator.preferredLanguage(source(defaultAudio = 1)))
        assertNull(PlaybackNegotiator.preferredLanguage(source(defaultAudio = null)))
    }
}
