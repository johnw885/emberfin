package com.johnw.emberfin.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.videoApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod

/**
 * The fallback chain, best first. DIRECT_PLAY lets the server pick anything; DIRECT_STREAM
 * forbids direct play (the server remuxes into HLS, copying streams where it can); TRANSCODE
 * forbids stream copy too.
 */
enum class PlaybackTier { DIRECT_PLAY, DIRECT_STREAM, TRANSCODE }

/** A subtitle file ExoPlayer loads next to the video (`SubtitleConfiguration`). */
data class ExternalSubtitle(val index: Int, val url: String, val mimeType: String, val language: String?, val label: String)

/** What the player needs to start one item. */
data class StreamPlan(
    val itemId: UUID,
    val url: String,
    val mediaSourceId: String?,
    val playSessionId: String?,
    val playMethod: PlayMethod,
    val tier: PlaybackTier,
    val streams: List<MediaStream>,
    val audioStreamIndex: Int?,
    /** -1 or null = subtitles off. */
    val subtitleStreamIndex: Int?,
    val externalSubtitles: List<ExternalSubtitle>,
    val runTimeTicks: Long?,
) {
    val isTranscoding: Boolean get() = playMethod == PlayMethod.TRANSCODE
}

data class PlaybackRequest(
    val itemId: UUID,
    val userId: UUID,
    val maxBitrate: Int,
    val tier: PlaybackTier = PlaybackTier.DIRECT_PLAY,
    val mediaSourceId: String? = null,
    /** null = let [AudioTrackPolicy] choose. */
    val audioStreamIndex: Int? = null,
    /** null = server default, -1 = off. */
    val subtitleStreamIndex: Int? = null,
    val startPositionMs: Long = 0,
)

class PlaybackException(message: String) : Exception(message)

/**
 * PlaybackInfo -> stream URL. Two calls when needed: the first learns the item's tracks so
 * [AudioTrackPolicy] can pick one (the server's default may be TrueHD), the second asks for
 * that track. The decision itself is the pure [decide] so it can be unit-tested.
 */
class PlaybackNegotiator(
    private val profile: () -> DeviceProfile,
    private val audioPolicy: AudioTrackPolicy,
) {
    suspend fun plan(api: ApiClient, request: PlaybackRequest): StreamPlan = withContext(Dispatchers.IO) {
        var info = fetch(api, request)
        val source = info.mediaSources.firstOrNull { request.mediaSourceId == null || it.id == request.mediaSourceId }
            ?: throw PlaybackException("This item has no playable file")
        val wanted = request.audioStreamIndex ?: when (val choice = audioPolicy.choose(source.mediaStreams.orEmpty(), preferredLanguage(source))) {
            is AudioChoice.Track -> choice.index
            is AudioChoice.TranscodeFrom -> choice.index
            AudioChoice.None -> null
        }
        val subtitle = request.subtitleStreamIndex ?: source.defaultSubtitleStreamIndex ?: -1
        val resolved = request.copy(mediaSourceId = source.id, audioStreamIndex = wanted, subtitleStreamIndex = subtitle)
        if (resolved != request) info = fetch(api, resolved)
        decide(info, resolved, api.baseUrl.orEmpty()) { src, playSessionId ->
            api.videoApi.getVideoStreamUrl(
                itemId = request.itemId,
                container = src.container,
                static = true,
                mediaSourceId = src.id,
                playSessionId = playSessionId,
                tag = src.eTag,
            )
        }
    }

    private suspend fun fetch(api: ApiClient, r: PlaybackRequest): PlaybackInfoResponse {
        val directPlay = r.tier == PlaybackTier.DIRECT_PLAY
        val allowCopy = r.tier != PlaybackTier.TRANSCODE
        return api.mediaInfoApi.getPostedPlaybackInfo(
            itemId = r.itemId,
            data = PlaybackInfoDto(
                userId = r.userId,
                maxStreamingBitrate = r.maxBitrate,
                startTimeTicks = r.startPositionMs * TICKS_PER_MS,
                audioStreamIndex = r.audioStreamIndex,
                subtitleStreamIndex = r.subtitleStreamIndex,
                mediaSourceId = r.mediaSourceId,
                deviceProfile = profile(),
                enableDirectPlay = directPlay,
                enableDirectStream = allowCopy,
                enableTranscoding = true,
                allowVideoStreamCopy = allowCopy,
                allowAudioStreamCopy = allowCopy,
                autoOpenLiveStream = true,
            ),
        ).content
    }

    companion object {
        const val TICKS_PER_MS = 10_000L

        /** The language the server's default audio track uses, which follows the user's Jellyfin settings. */
        fun preferredLanguage(source: MediaSourceInfo): String? =
            source.mediaStreams?.firstOrNull { it.type == MediaStreamType.AUDIO && it.index == source.defaultAudioStreamIndex }?.language
                ?.takeUnless { it.equals("und", true) }

        /** Picks direct play or the server's HLS URL from a PlaybackInfo answer. */
        fun decide(
            info: PlaybackInfoResponse,
            request: PlaybackRequest,
            baseUrl: String,
            staticUrl: (MediaSourceInfo, String?) -> String,
        ): StreamPlan {
            info.errorCode?.let { throw PlaybackException("The server refused playback ($it)") }
            val source = info.mediaSources.firstOrNull { request.mediaSourceId == null || it.id == request.mediaSourceId }
                ?: info.mediaSources.firstOrNull()
                ?: throw PlaybackException("This item has no playable file")
            val streams = source.mediaStreams.orEmpty()
            val base = baseUrl.trimEnd('/')
            val (url, method, tier) = when {
                request.tier == PlaybackTier.DIRECT_PLAY && source.supportsDirectPlay ->
                    Triple(staticUrl(source, info.playSessionId), PlayMethod.DIRECT_PLAY, PlaybackTier.DIRECT_PLAY)
                source.transcodingUrl != null ->
                    Triple(base + source.transcodingUrl, PlayMethod.TRANSCODE, maxOf(request.tier, PlaybackTier.DIRECT_STREAM))
                else -> throw PlaybackException("The server can't stream this item to this device")
            }
            return StreamPlan(
                itemId = request.itemId,
                url = url,
                mediaSourceId = source.id,
                playSessionId = info.playSessionId,
                playMethod = method,
                tier = tier,
                streams = streams,
                audioStreamIndex = request.audioStreamIndex,
                subtitleStreamIndex = request.subtitleStreamIndex,
                externalSubtitles = externalSubtitles(streams, base),
                runTimeTicks = source.runTimeTicks,
            )
        }

        fun externalSubtitles(streams: List<MediaStream>, baseUrl: String): List<ExternalSubtitle> =
            streams.filter {
                it.type == MediaStreamType.SUBTITLE && it.deliveryMethod == SubtitleDeliveryMethod.EXTERNAL && it.deliveryUrl != null
            }.mapNotNull { s ->
                val mime = subtitleMime(s.codec) ?: return@mapNotNull null
                val url = s.deliveryUrl!!.let { if (s.isExternalUrl == true) it else baseUrl + it }
                ExternalSubtitle(s.index, url, mime, s.language, s.displayTitle ?: s.language ?: "Subtitle ${s.index}")
            }

        fun subtitleMime(codec: String?): String? = when (codec?.lowercase()) {
            "srt", "subrip" -> "application/x-subrip"
            "ass", "ssa" -> "text/x-ssa"
            "vtt", "webvtt" -> "text/vtt"
            else -> null
        }
    }
}
