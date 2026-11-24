package com.johnw.emberfin.player

import com.johnw.emberfin.data.db.SeriesTracksDao
import com.johnw.emberfin.data.db.SeriesTracksEntity
import com.johnw.emberfin.playback.AudioPreference
import com.johnw.emberfin.playback.SubtitlePreference
import com.johnw.emberfin.playback.TrackPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Per-profile, per-series audio/subtitle memory on Room (`series_tracks`). */
class SeriesTracks(private val dao: SeriesTracksDao) {
    suspend fun get(profileKey: String, seriesId: String): TrackPreference? = withContext(Dispatchers.IO) {
        dao.get(key(profileKey, seriesId))?.let(::toPreference)?.takeUnless { it.isEmpty }
    }

    suspend fun saveAudio(profileKey: String, seriesId: String, audio: AudioPreference) = update(profileKey, seriesId) {
        it.copy(audioSet = true, audioLanguage = audio.language, audioTitle = audio.title)
    }

    suspend fun saveSubtitle(profileKey: String, seriesId: String, subtitle: SubtitlePreference) = update(profileKey, seriesId) {
        when (subtitle) {
            SubtitlePreference.Off -> it.copy(subtitleOff = true, subtitleLanguage = null, subtitleForced = false, subtitleTitle = null, subtitleCodec = null)
            is SubtitlePreference.On -> it.copy(
                subtitleOff = false, subtitleLanguage = subtitle.language, subtitleForced = subtitle.forced,
                subtitleTitle = subtitle.title, subtitleCodec = subtitle.codec,
            )
        }
    }

    private suspend fun update(profileKey: String, seriesId: String, change: (SeriesTracksEntity) -> SeriesTracksEntity) =
        withContext(Dispatchers.IO) {
            val key = key(profileKey, seriesId)
            val current = dao.get(key) ?: SeriesTracksEntity(key)
            dao.put(change(current).copy(savedAt = System.currentTimeMillis()))
        }

    companion object {
        fun key(profileKey: String, seriesId: String) = "$profileKey/$seriesId"

        fun toPreference(e: SeriesTracksEntity) = TrackPreference(
            audio = if (e.audioSet) AudioPreference(e.audioLanguage, e.audioTitle) else null,
            subtitle = when (e.subtitleOff) {
                null -> null
                true -> SubtitlePreference.Off
                false -> SubtitlePreference.On(e.subtitleLanguage, e.subtitleForced, e.subtitleTitle, e.subtitleCodec)
            },
        )
    }
}
