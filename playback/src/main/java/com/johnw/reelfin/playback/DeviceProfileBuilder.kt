package com.johnw.reelfin.playback

import org.jellyfin.sdk.model.api.CodecProfile
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileCondition
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile
import org.jellyfin.sdk.model.api.TranscodeSeekInfo
import org.jellyfin.sdk.model.api.TranscodingProfile
import org.jellyfin.sdk.model.api.VideoRangeType

/**
 * Turns [DeviceCapabilities] into the Jellyfin `DeviceProfile` sent with every PlaybackInfo request.
 *
 * - Direct play only lists codecs the device decodes; audio never lists TrueHD/MLP/DTS.
 * - Video range types: Dolby Vision profiles with an enhancement layer (P7 FEL) and invalid DV
 *   are never declared, so the server strips them to their HDR10 base layer.
 * - Transcodes are HLS fMP4 with HEVC/H.264 video and E-AC-3/AC-3/AAC audio.
 */
class DeviceProfileBuilder(
    private val caps: DeviceCapabilities,
    private val audioPolicy: AudioTrackPolicy = AudioTrackPolicy(),
) {
    fun build(maxBitrate: Int): DeviceProfile {
        val videoCodecs = VIDEO_ORDER.filter { c -> caps.videoDecoders.any { it.codec == c } }
        val audioCodecs = audioPolicy.directPlayCodecs(caps.audioCodecs)
        val transcodeVideo = listOf("hevc", "h264").filter { it in videoCodecs }.ifEmpty { listOf("h264") }
        val transcodeAudio = listOf("eac3", "ac3", "aac").filter { it in audioCodecs }.ifEmpty { listOf("aac") }

        return DeviceProfile(
            name = "Reelfin",
            maxStreamingBitrate = maxBitrate,
            maxStaticBitrate = maxBitrate,
            musicStreamingTranscodingBitrate = 320_000,
            directPlayProfiles = listOf(
                DirectPlayProfile(
                    container = DIRECT_PLAY_CONTAINERS,
                    videoCodec = videoCodecs.joinToString(","),
                    audioCodec = audioCodecs.joinToString(","),
                    type = DlnaProfileType.VIDEO,
                ),
            ),
            transcodingProfiles = listOf(
                TranscodingProfile(
                    container = "mp4",
                    type = DlnaProfileType.VIDEO,
                    videoCodec = transcodeVideo.joinToString(","),
                    audioCodec = transcodeAudio.joinToString(","),
                    protocol = MediaStreamProtocol.HLS,
                    estimateContentLength = false,
                    enableMpegtsM2TsMode = false,
                    transcodeSeekInfo = TranscodeSeekInfo.AUTO,
                    copyTimestamps = false,
                    context = EncodingContext.STREAMING,
                    enableSubtitlesInManifest = false,
                    maxAudioChannels = caps.maxAudioChannels.toString(),
                    minSegments = 1,
                    segmentLength = 0,
                    breakOnNonKeyFrames = true,
                    conditions = emptyList(),
                    enableAudioVbrEncoding = true,
                ),
            ),
            containerProfiles = emptyList(),
            codecProfiles = caps.videoDecoders.filter { it.codec in videoCodecs }.map(::videoCodecProfile),
            subtitleProfiles = subtitleProfiles(),
        )
    }

    /** Range types each codec may direct-play, as Jellyfin serial names. */
    fun rangeTypes(codec: String): List<VideoRangeType> = buildList {
        add(VideoRangeType.SDR)
        if (codec == "h264") return@buildList
        val hdr10 = HdrFormat.HDR10 in caps.hdr
        val dv = HdrFormat.DOLBY_VISION in caps.hdr && codec == "hevc"
        if (hdr10) {
            add(VideoRangeType.HDR10)
            // HDR10+ always carries an HDR10 base, so an HDR10 display can show it.
            add(VideoRangeType.HDR10_PLUS)
        }
        if (HdrFormat.HLG in caps.hdr) add(VideoRangeType.HLG)
        if (codec == "hevc") {
            // Profile 8 with a backwards-compatible base layer: plays as DV or as its base.
            add(VideoRangeType.DOVI_WITH_SDR)
            if (hdr10) {
                add(VideoRangeType.DOVI_WITH_HDR10)
                add(VideoRangeType.DOVI_WITH_HDR10_PLUS)
            }
            if (HdrFormat.HLG in caps.hdr) add(VideoRangeType.DOVI_WITH_HLG)
            // Profile 5 has no fallback layer, so it needs real DV.
            if (dv) add(VideoRangeType.DOVI)
        }
        // Never: DOVI_WITH_EL, DOVI_WITH_ELHDR10_PLUS (P7 FEL) and DOVI_INVALID.
    }

    private fun videoCodecProfile(decoder: VideoDecoder): CodecProfile {
        val conditions = buildList {
            add(cond(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.WIDTH, decoder.maxWidth.toString()))
            add(cond(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.HEIGHT, decoder.maxHeight.toString()))
            decoder.maxLevel?.let { add(cond(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.VIDEO_LEVEL, it.toString())) }
            add(cond(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.VIDEO_BIT_DEPTH, if (decoder.supports10Bit) "10" else "8"))
            add(
                cond(
                    ProfileConditionType.EQUALS_ANY,
                    ProfileConditionValue.VIDEO_RANGE_TYPE,
                    rangeTypes(decoder.codec).joinToString("|") { it.serialName },
                ),
            )
            when (decoder.codec) {
                "h264" -> add(cond(ProfileConditionType.EQUALS_ANY, ProfileConditionValue.VIDEO_PROFILE, "high|main|baseline|constrained baseline"))
                "hevc" -> add(cond(ProfileConditionType.EQUALS_ANY, ProfileConditionValue.VIDEO_PROFILE, if (decoder.supports10Bit) "main|main 10" else "main"))
            }
        }
        return CodecProfile(type = CodecType.VIDEO, conditions = conditions, applyConditions = emptyList(), codec = decoder.codec, container = null, subContainer = null)
    }

    private fun subtitleProfiles(): List<SubtitleProfile> =
        EMBEDDED_SUBTITLES.map { SubtitleProfile(format = it, method = SubtitleDeliveryMethod.EMBED, didlMode = null, language = null, container = null) } +
            EXTERNAL_SUBTITLES.map { SubtitleProfile(format = it, method = SubtitleDeliveryMethod.EXTERNAL, didlMode = null, language = null, container = null) }

    private fun cond(type: ProfileConditionType, property: ProfileConditionValue, value: String) =
        ProfileCondition(condition = type, property = property, value = value, isRequired = false)

    companion object {
        private val VIDEO_ORDER = listOf("hevc", "h264", "av1", "vp9")
        const val DIRECT_PLAY_CONTAINERS = "mkv,webm,mp4,m4v,mov,ts,mpegts,m2ts"

        /** Formats Media3 renders from inside the container. Anything else is burned in by the server. */
        val EMBEDDED_SUBTITLES = listOf("srt", "subrip", "ass", "ssa", "pgs", "pgssub", "dvdsub", "vobsub", "dvbsub", "mov_text", "vtt", "webvtt")

        /** Text formats fetched as separate files. */
        val EXTERNAL_SUBTITLES = listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt")
    }
}
