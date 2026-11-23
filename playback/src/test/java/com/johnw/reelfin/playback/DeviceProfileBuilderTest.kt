package com.johnw.reelfin.playback

import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.VideoRangeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileBuilderTest {
    /** Roughly a 4K HDR streamer whose platform also has TrueHD/DTS decoders (which must still be ignored). */
    private val streamer = DeviceCapabilities(
        videoDecoders = listOf(
            VideoDecoder("hevc", 153, true, 3840, 2160),
            VideoDecoder("h264", 52, false, 3840, 2160),
            VideoDecoder("av1", null, true, 3840, 2160),
            VideoDecoder("vp9", null, true, 3840, 2160),
        ),
        hdr = setOf(HdrFormat.HDR10, HdrFormat.HDR10_PLUS, HdrFormat.HLG, HdrFormat.DOLBY_VISION),
        audioCodecs = setOf("eac3", "ac3", "aac", "truehd", "dts", "mlp", "opus"),
        maxAudioChannels = 8,
        atmosPassthrough = true,
    )

    private fun DeviceProfile.rangeTypesFor(codec: String): List<String> =
        codecProfiles!!.first { it.codec == codec && it.type == CodecType.VIDEO }
            .conditions!!.first { it.property == ProfileConditionValue.VIDEO_RANGE_TYPE }.value!!.split("|")

    @Test fun `direct play audio never lists TrueHD, MLP or DTS`() {
        val audio = DeviceProfileBuilder(streamer).build(120_000_000).directPlayProfiles!!.single().audioCodec!!.split(",")
        assertEquals(listOf("eac3", "ac3", "aac", "opus"), audio)
    }

    @Test fun `video codecs and containers`() {
        val dp = DeviceProfileBuilder(streamer).build(120_000_000).directPlayProfiles!!.single()
        assertEquals("hevc,h264,av1,vp9", dp.videoCodec)
        assertTrue("mkv" in dp.container.split(","))
    }

    @Test fun `DV P5 and P8 declared, P7 FEL and invalid DV left out`() {
        val ranges = DeviceProfileBuilder(streamer).build(120_000_000).rangeTypesFor("hevc")
        listOf(VideoRangeType.SDR, VideoRangeType.HDR10, VideoRangeType.HDR10_PLUS, VideoRangeType.HLG, VideoRangeType.DOVI,
            VideoRangeType.DOVI_WITH_HDR10, VideoRangeType.DOVI_WITH_HDR10_PLUS, VideoRangeType.DOVI_WITH_HLG, VideoRangeType.DOVI_WITH_SDR)
            .forEach { assertTrue(it.serialName, it.serialName in ranges) }
        listOf(VideoRangeType.DOVI_WITH_EL, VideoRangeType.DOVI_WITH_ELHDR10_PLUS, VideoRangeType.DOVI_INVALID)
            .forEach { assertFalse(it.serialName, it.serialName in ranges) }
    }

    @Test fun `range type names match the server's spelling`() {
        val ranges = DeviceProfileBuilder(streamer).build(120_000_000).rangeTypesFor("hevc")
        assertTrue("DOVIWithHDR10" in ranges)
        assertTrue("HDR10Plus" in ranges)
    }

    @Test fun `HDR10-only display keeps DV profile 8 via its base layer but drops profile 5`() {
        val caps = streamer.copy(hdr = setOf(HdrFormat.HDR10))
        val ranges = DeviceProfileBuilder(caps).build(120_000_000).rangeTypesFor("hevc")
        assertTrue("DOVIWithHDR10" in ranges)
        assertFalse("DOVI" in ranges)
        assertFalse("HLG" in ranges)
    }

    @Test fun `SDR display declares no HDR so the server tone-maps`() {
        val ranges = DeviceProfileBuilder(streamer.copy(hdr = emptySet())).build(120_000_000).rangeTypesFor("hevc")
        assertEquals(listOf("SDR", "DOVIWithSDR"), ranges)
    }

    @Test fun `h264 is SDR 8-bit only`() {
        val profile = DeviceProfileBuilder(streamer).build(120_000_000)
        assertEquals(listOf("SDR"), profile.rangeTypesFor("h264"))
        val depth = profile.codecProfiles!!.first { it.codec == "h264" }.conditions!!.first { it.property == ProfileConditionValue.VIDEO_BIT_DEPTH }
        assertEquals("8", depth.value)
    }

    @Test fun `hevc level comes from the decoder`() {
        val level = DeviceProfileBuilder(streamer).build(120_000_000).codecProfiles!!.first { it.codec == "hevc" }
            .conditions!!.first { it.property == ProfileConditionValue.VIDEO_LEVEL }
        assertEquals("153", level.value)
    }

    @Test fun `transcodes are HLS fMP4 with Dolby audio`() {
        val t = DeviceProfileBuilder(streamer).build(120_000_000).transcodingProfiles!!.single()
        assertEquals(MediaStreamProtocol.HLS, t.protocol)
        assertEquals("mp4", t.container)
        assertEquals("hevc,h264", t.videoCodec)
        assertEquals("eac3,ac3,aac", t.audioCodec)
        assertEquals("8", t.maxAudioChannels)
    }

    @Test fun `quality cap sets both bitrate limits`() {
        val p = DeviceProfileBuilder(streamer).build(20_000_000)
        assertEquals(20_000_000, p.maxStreamingBitrate)
        assertEquals(20_000_000, p.maxStaticBitrate)
    }

    @Test fun `subtitles - bitmap formats embedded, text formats external`() {
        val subs = DeviceProfileBuilder(streamer).build(120_000_000).subtitleProfiles!!
        assertTrue(subs.any { it.format == "pgssub" && it.method == SubtitleDeliveryMethod.EMBED })
        assertTrue(subs.any { it.format == "ass" && it.method == SubtitleDeliveryMethod.EXTERNAL })
        assertFalse(subs.any { it.format == "pgssub" && it.method == SubtitleDeliveryMethod.EXTERNAL })
    }

    @Test fun `device without HEVC transcodes to h264`() {
        val caps = streamer.copy(videoDecoders = listOf(VideoDecoder("h264", 42, false, 1920, 1080)), audioCodecs = setOf("aac"))
        val t = DeviceProfileBuilder(caps).build(10_000_000).transcodingProfiles!!.single()
        assertEquals("h264", t.videoCodec)
        assertEquals("aac", t.audioCodec)
    }
}
