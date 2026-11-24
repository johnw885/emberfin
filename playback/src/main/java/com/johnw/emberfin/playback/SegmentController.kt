package com.johnw.emberfin.playback

import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType

data class Segment(val type: MediaSegmentType, val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs
    operator fun contains(positionMs: Long) = positionMs in startMs until endMs

    companion object {
        fun from(dto: MediaSegmentDto) = Segment(dto.type, dto.startTicks / 10_000, dto.endTicks / 10_000)
    }
}

/** What the player shows for segments right now. */
sealed interface SegmentUi {
    data object None : SegmentUi
    data class SkipButton(val segment: Segment) : SegmentUi
    /** Next-episode card. [remainingMs] is null when autoplay is off (the card waits for the user). */
    data class NextUp(val remainingMs: Long?, val totalMs: Long) : SegmentUi
}

/** Something the player must do. */
sealed interface SegmentCommand {
    data class SeekTo(val positionMs: Long) : SegmentCommand
    data object PlayNext : SegmentCommand
}

/**
 * The skip / next-episode state machine, driven by playback position (so pausing freezes the
 * countdown). Per segment type, [PlaybackSettings.segmentActions] decides: a Skip button,
 * auto-skip, or the next-episode countdown. Without an Outro segment the countdown starts
 * [PlaybackSettings.nextUpBeforeEndMs] before the end.
 */
class SegmentController(
    segments: List<Segment>,
    private val durationMs: Long,
    private val hasNext: Boolean,
    private val settings: PlaybackSettings = PlaybackSettings(),
) {
    private val segments = segments.filter { it.lengthMs >= MIN_SEGMENT_MS }.sortedBy { it.startMs }

    var ui: SegmentUi = SegmentUi.None
        private set

    private val dismissed = mutableSetOf<Segment>()
    private val autoSkipped = mutableSetOf<Segment>()
    private var nextUpAnchorMs: Long? = null
    private var nextUpCancelled = false
    private var nextFired = false
    private var lastPositionMs = 0L

    /** Where the next-episode countdown starts, or null when there's no next episode. */
    val nextUpStartMs: Long? = run {
        if (!hasNext) return@run null
        val outro = this.segments.lastOrNull { action(it) == SegmentAction.NEXT_UP }
        outro?.startMs ?: (durationMs - settings.nextUpBeforeEndMs).takeIf { durationMs > 2 * settings.nextUpBeforeEndMs }
    }

    /** Call on every position tick. Returns a command when playback must change. */
    fun update(positionMs: Long, userSeeking: Boolean = false): SegmentCommand? {
        lastPositionMs = positionMs
        dismissed.retainAll { positionMs in it }
        if (userSeeking) {
            ui = SegmentUi.None
            return null
        }

        // Next-episode countdown wins over everything else.
        val start = nextUpStartMs
        if (start != null && positionMs >= start) {
            if (!nextUpCancelled && !nextFired) {
                val anchor = nextUpAnchorMs ?: positionMs.also { nextUpAnchorMs = it }
                if (!settings.autoplayNext) {
                    ui = SegmentUi.NextUp(null, settings.nextUpCountdownMs)
                    return null
                }
                val remaining = settings.nextUpCountdownMs - (positionMs - anchor)
                if (remaining <= 0) return playNext()
                ui = SegmentUi.NextUp(remaining, settings.nextUpCountdownMs)
                return null
            }
        } else {
            // Rewound before the credits: the countdown may start again later.
            nextUpAnchorMs = null
            nextUpCancelled = false
        }

        val current = segments.firstOrNull { positionMs in it && positionMs < it.endMs - END_MARGIN_MS }
        ui = SegmentUi.None
        if (current == null) return null
        return when (action(current)) {
            SegmentAction.AUTO_SKIP -> if (autoSkipped.add(current)) SegmentCommand.SeekTo(current.endMs) else null
            SegmentAction.SKIP_BUTTON -> {
                if (current !in dismissed) ui = SegmentUi.SkipButton(current)
                null
            }
            SegmentAction.NEXT_UP, SegmentAction.IGNORE -> null
        }
    }

    /** OK on the Skip button. */
    fun onSkipPressed(): SegmentCommand? {
        val skip = ui as? SegmentUi.SkipButton ?: return null
        ui = SegmentUi.None
        dismissed += skip.segment
        return SegmentCommand.SeekTo(skip.segment.endMs)
    }

    /** "Next Episode" pressed (on the countdown card or in the overlay). */
    fun onPlayNextPressed(): SegmentCommand? = if (hasNext && !nextFired) playNext() else null

    /** Back while a Skip button or the countdown shows: hide it. Returns false when there was nothing to hide. */
    fun onBack(): Boolean = when (val current = ui) {
        is SegmentUi.SkipButton -> {
            dismissed += current.segment
            ui = SegmentUi.None
            true
        }
        is SegmentUi.NextUp -> {
            nextUpCancelled = true
            ui = SegmentUi.None
            true
        }
        SegmentUi.None -> false
    }

    /** The file played to its end. */
    fun onEnded(): SegmentCommand? =
        if (hasNext && settings.autoplayNext && !nextFired && !nextUpCancelled) playNext() else null

    private fun playNext(): SegmentCommand {
        nextFired = true
        ui = SegmentUi.None
        return SegmentCommand.PlayNext
    }

    private fun action(segment: Segment): SegmentAction =
        settings.segmentActions[segment.type] ?: SegmentAction.IGNORE

    companion object {
        /** Segments shorter than this are detection noise. */
        const val MIN_SEGMENT_MS = 2_000L
        /** Hide the Skip button in a segment's last second; skipping would gain nothing. */
        const val END_MARGIN_MS = 1_000L
    }
}
