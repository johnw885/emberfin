package com.johnw.reelfin.playback

/**
 * Decides whether a player error should move down the fallback chain
 * (direct play -> direct stream -> transcode). Only decoder, audio-output and container errors
 * qualify; network and server errors would fail the same way at every tier.
 */
object FallbackPolicy {
    // Media3 PlaybackException error codes (copied so this stays plain Kotlin).
    const val ERROR_PARSING_CONTAINER_MALFORMED = 3001
    const val ERROR_PARSING_CONTAINER_UNSUPPORTED = 3003
    const val ERROR_DECODER_INIT_FAILED = 4001
    const val ERROR_DECODER_QUERY_FAILED = 4002
    const val ERROR_DECODING_FAILED = 4003
    const val ERROR_DECODING_FORMAT_EXCEEDS_CAPABILITIES = 4004
    const val ERROR_DECODING_FORMAT_UNSUPPORTED = 4005
    const val ERROR_AUDIO_TRACK_INIT_FAILED = 5001
    const val ERROR_AUDIO_TRACK_WRITE_FAILED = 5002

    private val FALLBACK_ERRORS = setOf(
        ERROR_PARSING_CONTAINER_MALFORMED, ERROR_PARSING_CONTAINER_UNSUPPORTED,
        ERROR_DECODER_INIT_FAILED, ERROR_DECODER_QUERY_FAILED, ERROR_DECODING_FAILED,
        ERROR_DECODING_FORMAT_EXCEEDS_CAPABILITIES, ERROR_DECODING_FORMAT_UNSUPPORTED,
        ERROR_AUDIO_TRACK_INIT_FAILED, ERROR_AUDIO_TRACK_WRITE_FAILED,
    )

    fun next(tier: PlaybackTier): PlaybackTier? = when (tier) {
        PlaybackTier.DIRECT_PLAY -> PlaybackTier.DIRECT_STREAM
        PlaybackTier.DIRECT_STREAM -> PlaybackTier.TRANSCODE
        PlaybackTier.TRANSCODE -> null
    }

    /** The tier to retry at, or null to give up and show the error. */
    fun onError(errorCode: Int, current: PlaybackTier): PlaybackTier? =
        if (errorCode in FALLBACK_ERRORS) next(current) else null
}
