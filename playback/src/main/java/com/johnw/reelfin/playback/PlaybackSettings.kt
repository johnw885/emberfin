package com.johnw.reelfin.playback

import org.jellyfin.sdk.model.api.MediaSegmentType

/** What the player does when playback enters a media segment of a given type. */
enum class SegmentAction { SKIP_BUTTON, AUTO_SKIP, NEXT_UP, IGNORE }

/**
 * How the display refresh rate follows the video.
 * - OFF: never switch.
 * - SEAMLESS: ExoPlayer's strategy, only when the display can switch without a blank (default).
 * - ALWAYS: ask for the video's rate even if the TV blanks for a moment (needed for 29.97/59.94 fps
 *   on a 60 Hz output); done with `Surface.setFrameRate(…, CHANGE_FRAME_RATE_ALWAYS)`.
 */
enum class FrameRateMode { OFF, SEAMLESS, ALWAYS }

/** Player preferences, written by the settings screen (the app's `SettingsStore`). */
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
    val frameRateMode: FrameRateMode = FrameRateMode.SEAMLESS,
    val subtitleScale: Float = 1f,
    val subtitleBackground: Boolean = true,
) {
    companion object {
        const val DEFAULT_MAX_BITRATE = 120_000_000

        /** Options for the settings screen (bits/s). */
        val BITRATE_OPTIONS = listOf(120_000_000, 80_000_000, 40_000_000, 20_000_000, 10_000_000, 4_000_000)

        /** Subtitle size choices (label to scale). */
        val SUBTITLE_SCALES = listOf("Small" to 0.8f, "Medium" to 1f, "Large" to 1.25f, "Extra large" to 1.5f)

        val DEFAULT_SEGMENT_ACTIONS = mapOf(
            MediaSegmentType.INTRO to SegmentAction.SKIP_BUTTON,
            MediaSegmentType.RECAP to SegmentAction.SKIP_BUTTON,
            MediaSegmentType.PREVIEW to SegmentAction.SKIP_BUTTON,
            MediaSegmentType.COMMERCIAL to SegmentAction.AUTO_SKIP,
            MediaSegmentType.OUTRO to SegmentAction.NEXT_UP,
        )
    }
}
