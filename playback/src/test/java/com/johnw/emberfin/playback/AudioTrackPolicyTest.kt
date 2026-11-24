package com.johnw.emberfin.playback

import com.johnw.emberfin.playback.Fixtures.audio
import com.johnw.emberfin.playback.Fixtures.dvMovieStreams
import org.jellyfin.sdk.model.api.AudioSpatialFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTrackPolicyTest {
    private val policy = AudioTrackPolicy()

    @Test fun `TrueHD default with AC-3 and DTS picks AC-3`() {
        assertEquals(AudioChoice.Track(2), policy.choose(dvMovieStreams, "eng"))
    }

    @Test fun `menu hides TrueHD and DTS`() {
        val menu = policy.menuTracks(dvMovieStreams).map { it.index }
        assertEquals(listOf(2), menu)
    }

    @Test fun `banned codecs`() {
        listOf("truehd", "TrueHD", "mlp", "dts", "DTS", "dts-hd", "dtshd", "dca").forEach { assertTrue(it, AudioTrackPolicy.isBanned(it)) }
        listOf("eac3", "ac3", "aac", "flac", "opus", null).forEach { assertFalse("$it", AudioTrackPolicy.isBanned(it)) }
    }

    @Test fun `DD+ Atmos beats DD+ beats AC-3 beats AAC`() {
        val streams = listOf(
            audio(1, "aac", channels = 2),
            audio(2, "ac3"),
            audio(3, "eac3"),
            audio(4, "eac3", spatial = AudioSpatialFormat.DOLBY_ATMOS, channels = 8),
        )
        assertEquals(AudioChoice.Track(4), policy.choose(streams, "eng"))
        assertEquals(AudioChoice.Track(3), policy.choose(streams.filter { it.index != 4 }, "eng"))
        assertEquals(AudioChoice.Track(2), policy.choose(streams.filter { it.index <= 2 }, "eng"))
    }

    @Test fun `Atmos preference off treats DD+ tracks alike`() {
        val streams = listOf(
            audio(1, "eac3", channels = 6),
            audio(2, "eac3", profile = "Dolby Digital Plus + Dolby Atmos", channels = 6),
        )
        assertEquals(AudioChoice.Track(2), policy.choose(streams, "eng"))
        assertEquals(AudioChoice.Track(1), AudioTrackPolicy(preferAtmos = false).choose(streams, "eng"))
    }

    @Test fun `preferred language wins over codec`() {
        val streams = listOf(audio(1, "eac3", language = "fra"), audio(2, "ac3", language = "eng"))
        assertEquals(AudioChoice.Track(2), policy.choose(streams, "eng"))
        assertEquals(AudioChoice.Track(1), policy.choose(streams, "fra"))
    }

    @Test fun `unknown language falls back to all tracks`() {
        val streams = listOf(audio(1, "aac", language = "fra"), audio(2, "ac3", language = "deu"))
        assertEquals(AudioChoice.Track(2), policy.choose(streams, "jpn"))
    }

    @Test fun `commentary loses to the main track`() {
        val streams = listOf(audio(1, "eac3", channels = 2, title = "Director's Commentary"), audio(2, "ac3"))
        assertEquals(AudioChoice.Track(2), policy.choose(streams, "eng"))
    }

    @Test fun `TrueHD or DTS only asks the server to transcode the best one`() {
        val streams = listOf(audio(1, "dts", channels = 6), audio(2, "truehd", channels = 8, isDefault = true))
        assertEquals(AudioChoice.TranscodeFrom(2), policy.choose(streams, "eng"))
        assertTrue(policy.menuTracks(streams).isEmpty())
    }

    @Test fun `no audio`() {
        assertEquals(AudioChoice.None, policy.choose(listOf(Fixtures.video()), "eng"))
    }

    @Test fun `direct play codecs drop banned ones and keep Dolby first`() {
        assertEquals(
            listOf("eac3", "ac3", "aac", "flac"),
            policy.directPlayCodecs(setOf("flac", "truehd", "aac", "dts", "ac3", "eac3", "mlp", "dca")),
        )
    }
}
