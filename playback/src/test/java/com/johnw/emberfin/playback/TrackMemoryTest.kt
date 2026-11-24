package com.johnw.emberfin.playback

import com.johnw.emberfin.playback.Fixtures.audio
import com.johnw.emberfin.playback.Fixtures.sub
import com.johnw.emberfin.playback.Fixtures.video
import org.jellyfin.sdk.model.api.MediaStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackMemoryTest {
    private val policy = AudioTrackPolicy()

    /** Episode A: English AC-3 + Japanese AAC (default), English and Spanish subtitles. */
    private val episodeA = listOf(
        video(0),
        audio(1, "aac", "jpn", channels = 2, isDefault = true),
        audio(2, "ac3", "eng", channels = 6),
        sub(3, "subrip", "eng"),
        sub(4, "subrip", "spa"),
    )

    /** Episode B: same tracks in another order, plus a TrueHD English track. */
    private val episodeB = listOf(
        video(0),
        audio(1, "truehd", "eng", channels = 8),
        audio(2, "ac3", "eng", channels = 6),
        audio(3, "aac", "jpn", channels = 2, isDefault = true),
        sub(4, "subrip", "spa"),
        sub(5, "subrip", "eng"),
    )

    @Test fun `remembered language finds the same track in another episode`() {
        val pref = TrackPreference(TrackMemory.audioFrom(episodeA, 2), TrackMemory.subtitleFrom(episodeA, 4))
        assertEquals(ResolvedTracks(audioIndex = 2, subtitleIndex = 4), TrackMemory.resolve(pref, episodeB, policy))
    }

    @Test fun `a remembered choice never lands on TrueHD or DTS`() {
        val pref = TrackPreference(audio = AudioPreference("eng", title = "eng truehd"))
        assertEquals(2, TrackMemory.resolve(pref, episodeB, policy).audioIndex)
    }

    @Test fun `a banned track is never remembered`() {
        assertNull(TrackMemory.audioFrom(episodeB, 1))
    }

    @Test fun `subtitles off is remembered as off`() {
        val pref = TrackPreference(subtitle = TrackMemory.subtitleFrom(episodeA, null))
        assertEquals(SubtitlePreference.Off, pref.subtitle)
        assertEquals(-1, TrackMemory.resolve(pref, episodeB, policy).subtitleIndex)
    }

    @Test fun `a missing language falls back to the usual choice`() {
        val pref = TrackPreference(
            audio = AudioPreference("fra", null),
            subtitle = SubtitlePreference.On("fra", forced = false, title = null, codec = "subrip"),
        )
        assertEquals(ResolvedTracks(null, null), TrackMemory.resolve(pref, episodeB, policy))
    }

    @Test fun `forced subtitles stay forced`() {
        val streams: List<MediaStream> = listOf(
            video(0),
            audio(1, "eac3", "eng"),
            sub(2, "subrip", "eng"),
            sub(3, "subrip", "eng").copy(isForced = true),
        )
        val pref = TrackPreference(subtitle = SubtitlePreference.On("eng", forced = true, title = null, codec = "subrip"))
        assertEquals(3, TrackMemory.resolve(pref, streams, policy).subtitleIndex)
    }

    @Test fun `no preference changes nothing`() {
        assertEquals(ResolvedTracks(null, null), TrackMemory.resolve(null, episodeA, policy))
    }
}
