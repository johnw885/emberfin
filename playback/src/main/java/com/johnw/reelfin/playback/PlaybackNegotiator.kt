package com.johnw.reelfin.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.videoApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.serializer.toUUID

/** What the player needs to start one item. */
data class StreamPlan(
    val itemId: UUID,
    val url: String,
    val mediaSourceId: String?,
    val playSessionId: String?,
    val playMethod: PlayMethod,
)

class NotDirectPlayableException(message: String) : Exception(message)

/**
 * Spike version: asks the server for PlaybackInfo with a fixed direct-play-only profile and
 * returns a static stream URL. Session 2 replaces the profile with `DeviceProfileBuilder`
 * (real codec/HDR capabilities) and adds the direct stream -> transcode fallback chain.
 */
class PlaybackNegotiator {
    suspend fun plan(api: ApiClient, userId: String, itemId: UUID): StreamPlan = withContext(Dispatchers.IO) {
        val info = api.mediaInfoApi.getPostedPlaybackInfo(
            itemId = itemId,
            data = PlaybackInfoDto(
                userId = userId.toUUID(),
                maxStreamingBitrate = MAX_BITRATE,
                deviceProfile = SPIKE_PROFILE,
                enableDirectPlay = true,
                enableDirectStream = false,
                enableTranscoding = false,
            ),
        ).content
        info.errorCode?.let { throw NotDirectPlayableException("Server refused playback: $it") }
        val source = info.mediaSources.firstOrNull()
            ?: throw NotDirectPlayableException("Item has no media sources")
        if (!source.supportsDirectPlay) {
            throw NotDirectPlayableException("Not direct-playable with the spike profile (container ${source.container})")
        }
        val url = api.videoApi.getVideoStreamUrl(
            itemId = itemId,
            container = source.container,
            static = true,
            mediaSourceId = source.id,
            playSessionId = info.playSessionId,
            tag = source.eTag,
        )
        StreamPlan(itemId, url, source.id, info.playSessionId, PlayMethod.DIRECT_PLAY)
    }

    private companion object {
        const val MAX_BITRATE = 200_000_000

        /** TrueHD/MLP/DTS are deliberately absent (John's audio rule). */
        val SPIKE_PROFILE = DeviceProfile(
            name = "Reelfin spike",
            maxStreamingBitrate = MAX_BITRATE,
            maxStaticBitrate = MAX_BITRATE,
            directPlayProfiles = listOf(
                DirectPlayProfile(
                    container = "mkv,mp4,m4v,mov,webm",
                    videoCodec = "hevc,h264,av1,vp9",
                    audioCodec = "eac3,ac3,aac,mp3,opus,flac",
                    type = DlnaProfileType.VIDEO,
                ),
            ),
            transcodingProfiles = emptyList(),
            containerProfiles = emptyList(),
            codecProfiles = emptyList(),
            subtitleProfiles = emptyList(),
        )
    }
}
