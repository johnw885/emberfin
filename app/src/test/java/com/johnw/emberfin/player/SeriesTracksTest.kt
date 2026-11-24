package com.johnw.emberfin.player

import com.johnw.emberfin.data.db.SeriesTracksEntity
import com.johnw.emberfin.playback.AudioPreference
import com.johnw.emberfin.playback.SubtitlePreference
import com.johnw.emberfin.playback.TrackPreference
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesTracksTest {
    @Test fun `an empty row means no preference`() {
        assertEquals(TrackPreference(), SeriesTracks.toPreference(SeriesTracksEntity("p/s")))
    }

    @Test fun `audio and subtitles map back`() {
        val e = SeriesTracksEntity(
            "p/s", audioLanguage = "eng", audioTitle = "English AC3 5.1", audioSet = true,
            subtitleOff = false, subtitleLanguage = "spa", subtitleForced = true, subtitleTitle = "Spanish Forced", subtitleCodec = "subrip",
        )
        assertEquals(
            TrackPreference(
                AudioPreference("eng", "English AC3 5.1"),
                SubtitlePreference.On("spa", forced = true, title = "Spanish Forced", codec = "subrip"),
            ),
            SeriesTracks.toPreference(e),
        )
    }

    @Test fun `subtitles off maps to off`() {
        assertEquals(SubtitlePreference.Off, SeriesTracks.toPreference(SeriesTracksEntity("p/s", subtitleOff = true)).subtitle)
    }

    @Test fun `keys are per profile and series`() {
        assertEquals("server/user/series", SeriesTracks.key("server/user", "series"))
    }
}
