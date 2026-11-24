package com.johnw.emberfin.playback

/**
 * "Are you still watching?": before an episode autoplays the next one, ask if [maxEpisodes]
 * episodes have finished in a row, or [maxIdleMs] have passed, without any input from the remote
 * (or from a phone controlling the player). Pure, so it's unit-tested.
 */
class StillWatchingPolicy(
    private val enabled: Boolean,
    private val maxEpisodes: Int = DEFAULT_EPISODES,
    private val maxIdleMs: Long = DEFAULT_IDLE_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var lastInputMs = now()
    private var finishedWithoutInput = 0

    /** A key press, or a command from a remote-control client. */
    fun onInput() {
        lastInputMs = now()
        finishedWithoutInput = 0
    }

    /**
     * An episode is done and the next one would start by itself. True = pause and ask first.
     * Answering the prompt counts as input, which starts the count again.
     */
    fun shouldAskBeforeAutoplay(): Boolean {
        finishedWithoutInput++
        if (!enabled) return false
        return finishedWithoutInput >= maxEpisodes || now() - lastInputMs >= maxIdleMs
    }

    companion object {
        const val DEFAULT_EPISODES = 3
        const val DEFAULT_IDLE_MS = 2 * 60 * 60 * 1000L
    }
}
