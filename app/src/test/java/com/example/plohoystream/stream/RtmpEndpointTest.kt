package com.example.plohoystream.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class RtmpEndpointTest {
    @Test fun parsesHostAppAndKey() {
        val e = RtmpEndpoint.parse("rtmp://live.twitch.tv/app", "live_123_abc")
        assertEquals("live.twitch.tv", e.host)
        assertEquals("app", e.app)
        assertEquals(1935, e.port)
        assertEquals("live_123_abc", e.streamKey)
        assertEquals("rtmp://live.twitch.tv/app", e.tcUrl)
    }

    @Test fun parsesExplicitPort() {
        val e = RtmpEndpoint.parse("rtmp://127.0.0.1:1936/live", "k")
        assertEquals("127.0.0.1", e.host)
        assertEquals(1936, e.port)
        assertEquals("live", e.app)
    }

    @Test fun trimsTrailingSlashOnApp() {
        val e = RtmpEndpoint.parse("rtmp://a.b/app/", "k")
        assertEquals("app", e.app)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonRtmpScheme() {
        RtmpEndpoint.parse("https://x/y", "k")
    }
}
