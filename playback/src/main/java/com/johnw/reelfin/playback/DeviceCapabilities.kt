package com.johnw.reelfin.playback

/** HDR formats the display and decoders can both show. */
enum class HdrFormat { HDR10, HDR10_PLUS, HLG, DOLBY_VISION }

/**
 * One video codec the device decodes in hardware.
 * @param codec Jellyfin codec name (`hevc`, `h264`, `av1`, `vp9`).
 * @param maxLevel highest level in Jellyfin's numbering (H.264 5.1 = 51, HEVC 5.1 = 153), null if unknown.
 */
data class VideoDecoder(
    val codec: String,
    val maxLevel: Int?,
    val supports10Bit: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
)

/** What this device can play, read once from Android (see `AndroidDeviceCapabilities`). Plain data so tests can fake it. */
data class DeviceCapabilities(
    val videoDecoders: List<VideoDecoder>,
    val hdr: Set<HdrFormat>,
    /** Jellyfin audio codec names that can be decoded or passed through. */
    val audioCodecs: Set<String>,
    val maxAudioChannels: Int,
    val atmosPassthrough: Boolean,
)
