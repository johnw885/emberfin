package com.johnw.reelfin.playback

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities

/** Reads [DeviceCapabilities] from MediaCodecList, the display's HDR types and the audio output. */
object AndroidDeviceCapabilities {

    @OptIn(UnstableApi::class)
    fun read(context: Context): DeviceCapabilities {
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        fun forMime(mime: String) = decoders.filter { info ->
            info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }

        val video = listOfNotNull(
            videoDecoder(forMime("video/hevc"), "hevc", "video/hevc"),
            videoDecoder(forMime("video/avc"), "h264", "video/avc"),
            videoDecoder(forMime("video/av01"), "av1", "video/av01"),
            videoDecoder(forMime("video/x-vnd.on2.vp9"), "vp9", "video/x-vnd.on2.vp9"),
        )

        val displayHdr = displayHdrTypes(context)
        val hdr = buildSet {
            if (Display.HdrCapabilities.HDR_TYPE_HDR10 in displayHdr) add(HdrFormat.HDR10)
            if (Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in displayHdr) add(HdrFormat.HDR10_PLUS)
            if (Display.HdrCapabilities.HDR_TYPE_HLG in displayHdr) add(HdrFormat.HLG)
            if (Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in displayHdr && forMime("video/dolby-vision").isNotEmpty()) {
                add(HdrFormat.DOLBY_VISION)
            }
        }

        val audioCaps = runCatching {
            AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
        }.getOrNull()
        val audio = buildSet {
            AUDIO_MIMES.forEach { (mime, codec) -> if (forMime(mime).isNotEmpty()) add(codec) }
            if (audioCaps?.supportsEncoding(C.ENCODING_AC3) == true) add("ac3")
            if (audioCaps?.supportsEncoding(C.ENCODING_E_AC3) == true) add("eac3")
            add("aac")
        }
        return DeviceCapabilities(
            videoDecoders = video,
            hdr = hdr,
            audioCodecs = audio,
            maxAudioChannels = (audioCaps?.maxChannelCount ?: 6).coerceIn(2, 8),
            atmosPassthrough = audioCaps?.supportsEncoding(C.ENCODING_E_AC3_JOC) == true,
        )
    }

    private fun videoDecoder(infos: List<MediaCodecInfo>, codec: String, mime: String): VideoDecoder? {
        if (infos.isEmpty()) return null
        // Prefer hardware decoders; software ones can't keep up with 4K.
        val hw = infos.filter { Build.VERSION.SDK_INT < 29 || it.isHardwareAccelerated }.ifEmpty { infos }
        val caps = hw.mapNotNull { runCatching { it.getCapabilitiesForType(mime) }.getOrNull() }
        if (caps.isEmpty()) return null
        val levels = caps.flatMap { it.profileLevels.toList() }
        val maxLevel = levels.mapNotNull { jellyfinLevel(codec, it.level) }.maxOrNull()
        val tenBit = levels.any { pl ->
            when (codec) {
                "hevc" -> pl.profile == CodecProfileLevel.HEVCProfileMain10 || pl.profile == CodecProfileLevel.HEVCProfileMain10HDR10 ||
                    pl.profile == CodecProfileLevel.HEVCProfileMain10HDR10Plus
                "av1" -> pl.profile == CodecProfileLevel.AV1ProfileMain10 || pl.profile == CodecProfileLevel.AV1ProfileMain10HDR10 ||
                    pl.profile == CodecProfileLevel.AV1ProfileMain10HDR10Plus
                "vp9" -> pl.profile == CodecProfileLevel.VP9Profile2 || pl.profile == CodecProfileLevel.VP9Profile2HDR ||
                    pl.profile == CodecProfileLevel.VP9Profile2HDR10Plus
                else -> false
            }
        }
        val width = caps.mapNotNull { it.videoCapabilities?.supportedWidths?.upper }.maxOrNull() ?: 1920
        val height = caps.mapNotNull { it.videoCapabilities?.supportedHeights?.upper }.maxOrNull() ?: 1080
        return VideoDecoder(codec, maxLevel, tenBit, width, height)
    }

    /** Android level constant -> Jellyfin's level number. */
    internal fun jellyfinLevel(codec: String, level: Int): Int? = when (codec) {
        "h264" -> AVC_LEVELS[level]
        "hevc" -> HEVC_LEVELS[level]
        else -> null
    }

    private fun displayHdrTypes(context: Context): Set<Int> {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptySet()
        val types = mutableSetOf<Int>()
        @Suppress("DEPRECATION")
        display.hdrCapabilities?.supportedHdrTypes?.let { types += it.toList() }
        if (Build.VERSION.SDK_INT >= 34) {
            display.supportedModes.forEach { types += it.supportedHdrTypes.toList() }
        }
        return types
    }

    private val AUDIO_MIMES = listOf(
        "audio/eac3" to "eac3",
        "audio/eac3-joc" to "eac3",
        "audio/ac3" to "ac3",
        "audio/mp4a-latm" to "aac",
        "audio/mpeg" to "mp3",
        "audio/opus" to "opus",
        "audio/flac" to "flac",
        "audio/vorbis" to "vorbis",
    )

    private val AVC_LEVELS = mapOf(
        CodecProfileLevel.AVCLevel1 to 10, CodecProfileLevel.AVCLevel1b to 10, CodecProfileLevel.AVCLevel11 to 11,
        CodecProfileLevel.AVCLevel12 to 12, CodecProfileLevel.AVCLevel13 to 13, CodecProfileLevel.AVCLevel2 to 20,
        CodecProfileLevel.AVCLevel21 to 21, CodecProfileLevel.AVCLevel22 to 22, CodecProfileLevel.AVCLevel3 to 30,
        CodecProfileLevel.AVCLevel31 to 31, CodecProfileLevel.AVCLevel32 to 32, CodecProfileLevel.AVCLevel4 to 40,
        CodecProfileLevel.AVCLevel41 to 41, CodecProfileLevel.AVCLevel42 to 42, CodecProfileLevel.AVCLevel5 to 50,
        CodecProfileLevel.AVCLevel51 to 51, CodecProfileLevel.AVCLevel52 to 52, CodecProfileLevel.AVCLevel6 to 60,
        CodecProfileLevel.AVCLevel61 to 61, CodecProfileLevel.AVCLevel62 to 62,
    )

    private val HEVC_LEVELS = buildMap {
        listOf(
            CodecProfileLevel.HEVCMainTierLevel1 to 30, CodecProfileLevel.HEVCHighTierLevel1 to 30,
            CodecProfileLevel.HEVCMainTierLevel2 to 60, CodecProfileLevel.HEVCHighTierLevel2 to 60,
            CodecProfileLevel.HEVCMainTierLevel21 to 63, CodecProfileLevel.HEVCHighTierLevel21 to 63,
            CodecProfileLevel.HEVCMainTierLevel3 to 90, CodecProfileLevel.HEVCHighTierLevel3 to 90,
            CodecProfileLevel.HEVCMainTierLevel31 to 93, CodecProfileLevel.HEVCHighTierLevel31 to 93,
            CodecProfileLevel.HEVCMainTierLevel4 to 120, CodecProfileLevel.HEVCHighTierLevel4 to 120,
            CodecProfileLevel.HEVCMainTierLevel41 to 123, CodecProfileLevel.HEVCHighTierLevel41 to 123,
            CodecProfileLevel.HEVCMainTierLevel5 to 150, CodecProfileLevel.HEVCHighTierLevel5 to 150,
            CodecProfileLevel.HEVCMainTierLevel51 to 153, CodecProfileLevel.HEVCHighTierLevel51 to 153,
            CodecProfileLevel.HEVCMainTierLevel52 to 156, CodecProfileLevel.HEVCHighTierLevel52 to 156,
            CodecProfileLevel.HEVCMainTierLevel6 to 180, CodecProfileLevel.HEVCHighTierLevel6 to 180,
            CodecProfileLevel.HEVCMainTierLevel61 to 183, CodecProfileLevel.HEVCHighTierLevel61 to 183,
            CodecProfileLevel.HEVCMainTierLevel62 to 186, CodecProfileLevel.HEVCHighTierLevel62 to 186,
        ).forEach { (k, v) -> put(k, v) }
    }
}
