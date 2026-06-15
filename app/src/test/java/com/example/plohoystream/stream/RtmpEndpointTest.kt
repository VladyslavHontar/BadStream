package com.example.plohoystream.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // ---- scheme detection (RTMP vs SRT) ----

    @Test fun rtmpUrlHasRtmpScheme() {
        assertEquals(EndpointScheme.RTMP, Endpoint.schemeOf("rtmp://live.twitch.tv/app"))
    }

    @Test fun rtmpsUrlHasRtmpScheme() {
        assertEquals(EndpointScheme.RTMP, Endpoint.schemeOf("rtmps://live.twitch.tv/app"))
    }

    @Test fun srtUrlHasSrtScheme() {
        assertEquals(EndpointScheme.SRT, Endpoint.schemeOf("srt://relay.example.com:8890"))
    }

    // ---- Endpoint.parse: RTMP still works exactly as before ----

    @Test fun endpointParsesRtmp() {
        val e = Endpoint.parse("rtmp://live.twitch.tv/app", "live_123")
        assertTrue(e is Endpoint.Rtmp)
        e as Endpoint.Rtmp
        assertEquals("live.twitch.tv", e.endpoint.host)
        assertEquals("app", e.endpoint.app)
        assertEquals(1935, e.endpoint.port)
        assertEquals("live_123", e.endpoint.streamKey)
    }

    @Test fun endpointParsesRtmps() {
        val e = Endpoint.parse("rtmps://live.twitch.tv/app", "k")
        assertTrue(e is Endpoint.Rtmp)
    }

    // ---- Endpoint.parse: SRT ----

    @Test fun endpointParsesSrtWithStreamidAndLatency() {
        val e = Endpoint.parse("srt://host.example.com:8890?streamid=publish/abc&latency=3000", "")
        assertTrue(e is Endpoint.Srt)
        e as Endpoint.Srt
        assertEquals("host.example.com", e.host)
        assertEquals(8890, e.port)
        assertEquals("publish/abc", e.streamid)
        assertEquals(3000, e.latencyMs)
    }

    @Test fun srtDefaultsWhenQueryAbsent() {
        val e = Endpoint.parse("srt://1.2.3.4:9000", "") as Endpoint.Srt
        assertEquals("1.2.3.4", e.host)
        assertEquals(9000, e.port)
        assertEquals("", e.streamid)
        assertEquals(2000, e.latencyMs)   // default latency
    }

    @Test fun srtUrlDecodesStreamid() {
        val e = Endpoint.parse("srt://h:1234?streamid=foo%2Fbar%3Abaz", "") as Endpoint.Srt
        assertEquals("foo/bar:baz", e.streamid)
    }

    @Test(expected = IllegalArgumentException::class)
    fun srtRequiresPort() {
        Endpoint.parse("srt://hostonly", "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun endpointRejectsUnknownScheme() {
        Endpoint.parse("http://x/y", "")
    }
}
