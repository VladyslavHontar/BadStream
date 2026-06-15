package com.example.plohoystream.stream

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRoundTripTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun encodesAndDecodes_roundTrip() {
        val original = Settings(
            rtmpUrl = "rtmp://h/app",
            streamKey = "k",
            quality = VideoQuality(1280, 720, 30, 3_500_000, 128_000),
            codecOverride = CodecOverride.ForceHevc,
            hdrEnabled = true,
            recordWhileStreaming = true,
        )
        val encoded = json.encodeToString(Settings.serializer(), original)
        val decoded = json.decodeFromString(Settings.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test fun missingField_fallsBackToDefault_forwardCompat() {
        // JSON written by an older app version that lacked hdrEnabled/codecOverride.
        val partial = """{"rtmpUrl":"rtmp://h/app","streamKey":"k"}"""
        val decoded = json.decodeFromString(Settings.serializer(), partial)
        assertEquals("rtmp://h/app", decoded.rtmpUrl)
        assertEquals("k", decoded.streamKey)
        assertEquals(VideoQuality.Default, decoded.quality)
        assertEquals(CodecOverride.Auto, decoded.codecOverride)
        assertEquals(false, decoded.hdrEnabled)
        assertEquals(false, decoded.recordWhileStreaming)
    }

    @Test fun unknownField_isIgnored_forwardCompat() {
        // JSON written by a NEWER app version with a field this version doesn't know.
        val withExtra = """{"rtmpUrl":"rtmp://h/app","streamKey":"k","futureField":42}"""
        val decoded = json.decodeFromString(Settings.serializer(), withExtra)
        assertEquals("rtmp://h/app", decoded.rtmpUrl)
        assertEquals("k", decoded.streamKey)
    }
}
