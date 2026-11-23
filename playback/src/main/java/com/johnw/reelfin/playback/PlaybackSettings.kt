package com.johnw.reelfin.playback

import org.jellyfin.sdk.model.api.MediaSegmentType

/** What the player does when playback enters a media segment of a given type. */
enum class SegmentAction { SKIP_BUTTON, AUTO_SKIP, NEXT_UP, IGNORE }

/**
 * Player preferences. Session 4 adds the settings screen; until then the defaults apply and
 * debug builds can override the bitrate cap from adb.
 */
data class PlaybackSettings(
    /** Quality cap in bits/s. Files above it are transcoded down. */
    val maxStreamingBitrate: Int = DEFAULT_MAX_BITRATE,
    /** Rank DD+ Atmos above plain DD+. Off = treat them the same. */
    val preferAtmos: Boolean = true,
    val autoplayNext: Boolean = true,
    val nextUpCountdownMs: Long = 10_000,
    /** When an episode has no Outro segment, the countdown starts this long before the end. */
    val nextUpBeforeEndMs: Long = 30_000,
    val segmentActions: Map<MediaSegmentType, SegmentAction> = DEFAULT_SEGMENT_ACTIONS,
    /** Off = never switch the display refresh rate; on = ExoPlayer's seamless-only strategy. */
    val frameRateMatching: Boolean = true,
    val subtitleScale: Float = 1f,
    val subtitleBackground: Boolean = true,
) {
    companion object {
        const val DEFAULT_MAX_BITRATE = 120_000_000

        /** Options for the settings screen (bits/s). */
        val BITRATE_OPTIONS = listOf(120_000_000, 80_000_000, 40_000_000, 20_000_000, 10_000_000, 4_000_000)

        val DEFAULT_SEGMENT_ACTIONS = mapOf(
            MediaSegmentType.INTRO to SegmentAction.SKIP_BUTTON,
            MediaSegmentType.RECAP to SegmentAction.SKIP_BUTTON,
            MediaSegmentType.PREVIEW to SegmentAction.SKIP_BUTTON,
            MediaSegmentType.COMMERCIAL to SegmentAction.AUTO_SKIP,
            MediaSegmentType.OUTRO to SegmentAction.NEXT_UP,
        )
    }
}
