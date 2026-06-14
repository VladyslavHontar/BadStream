package com.example.plohoystream.stream

import com.example.plohoystream.MainDispatcherRule
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CameraStreamEngineTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun engine(
        streamer: FakeRtmpStreamer,
        hevcEncoder: Boolean = false,
        hevcMain10: Boolean = false,
        cameraHdr: Boolean = false,
        startMedia: (RtmpStreamer, VideoFormat, VideoQuality) -> Unit = { _, _, _ -> },
    ) = CameraStreamEngine(
        streamerFactory = { streamer },
        startMedia = startMedia,
        stopMedia = {},
        pollIntervalMs = 100,
        hevcEncoder = hevcEncoder,
        hevcMain10 = hevcMain10,
        cameraHdr = cameraHdr,
    )

    @Test fun start_movesToConnecting_thenLiveWhenNativeReports() = runTest {
        val streamer = FakeRtmpStreamer()
        val e = engine(streamer)
        e.start(StreamConfig("rtmp://live.twitch.tv/app", "key"))
        runCurrent()
        assertTrue(e.state.value is StreamState.Connecting)
        streamer.emitState(2) // Live
        advanceTimeBy(150); runCurrent()
        assertEquals(StreamState.Live, e.state.value)
        e.stop()
    }

    @Test fun liveThenNativeDrop_entersReconnecting() = runTest {
        val streamer = FakeRtmpStreamer()
        val e = engine(streamer)
        e.start(StreamConfig("rtmp://h/app", "key")); runCurrent()
        streamer.emitState(2); advanceTimeBy(150); runCurrent()
        assertEquals(StreamState.Live, e.state.value)
        streamer.emitState(3); advanceTimeBy(150); runCurrent()   // 3 == native Dropped
        assertEquals(StreamState.Reconnecting, e.state.value)
        e.stop()
    }

    @Test fun start_withBadUrl_goesToError() = runTest {
        val e = engine(FakeRtmpStreamer())
        e.start(StreamConfig("not-a-url", "key"))
        runCurrent()
        assertTrue(e.state.value is StreamState.Error)
    }

    @Test fun nativeRejected_surfacesAsError() = runTest {
        val streamer = FakeRtmpStreamer()
        val e = engine(streamer)
        e.start(StreamConfig("rtmp://h/app", "key")); runCurrent()
        streamer.emitState(4)                                     // 4 == native Rejected
        advanceTimeBy(150); runCurrent()
        assertTrue(e.state.value is StreamState.Error)
    }

    @Test fun stop_returnsToIdle_andStopsStreamer() = runTest {
        val streamer = FakeRtmpStreamer()
        val e = engine(streamer)
        e.start(StreamConfig("rtmp://h/app", "key")); runCurrent()
        e.stop(); runCurrent()
        assertEquals(StreamState.Idle, e.state.value)
        assertTrue(streamer.stopped)
    }

    @Test fun requestedHevc_butServerDowngrades_startsMediaWithAvc() = runTest {
        val streamer = FakeRtmpStreamer()
        var captured: VideoFormat? = null
        var capturedQuality: VideoQuality? = null
        val e = engine(
            streamer, hevcEncoder = true, hevcMain10 = true, cameraHdr = true,
            startMedia = { _, fmt, q -> captured = fmt; capturedQuality = q },
        )
        val cfg = StreamConfig("rtmp://h/app", "key", hdrEnabled = true)
        e.start(cfg)
        runCurrent()
        streamer.negotiatedCodecValue = VideoCodecType.AVC // server has no HEVC -> downgrade
        streamer.emitState(2); advanceTimeBy(150); runCurrent()
        assertNotNull(captured)
        assertEquals(cfg.quality, capturedQuality)
        assertEquals(VideoCodecType.AVC, captured?.codec)
        assertEquals(DynamicRange.SDR, captured?.dynamicRange)
        assertEquals(false, captured?.main10)
        assertEquals(false, e.activeHdr.value)
        e.stop()
    }

    @Test fun requestedHdr_serverKeepsHevc_activeHdrTrue() = runTest {
        val streamer = FakeRtmpStreamer()
        var captured: VideoFormat? = null
        val e = engine(
            streamer, hevcEncoder = true, hevcMain10 = true, cameraHdr = true,
            startMedia = { _, fmt, _ -> captured = fmt },
        )
        e.start(StreamConfig("rtmp://h/app", "key", hdrEnabled = true))
        runCurrent()
        // FakeRtmpStreamer.start sets negotiatedCodecValue = requested (HEVC); leave it.
        streamer.emitState(2); advanceTimeBy(150); runCurrent()
        assertEquals(VideoCodecType.HEVC, captured?.codec)
        assertEquals(DynamicRange.HLG10, captured?.dynamicRange)
        assertEquals(true, e.activeHdr.value)
        e.stop()
        assertEquals(false, e.activeHdr.value)
    }

    @Test fun live_pollsBytesSentAndQueueDepth_intoStats() = runTest {
        val streamer = FakeRtmpStreamer()
        val e = engine(streamer)
        // Default StreamConfig.quality has a 6 Mbps videoBitrate -> health target.
        e.start(StreamConfig("rtmp://h/app", "key")); runCurrent()
        streamer.emitState(2); advanceTimeBy(150); runCurrent()
        assertEquals(StreamState.Live, e.state.value)
        // Simulate ~125 KB sent over the next poll tick at the default 6 Mbps target.
        streamer.bytesSentValue = 125_000L
        streamer.queueDepthValue = 200      // > 60% of 256 -> Bad
        advanceTimeBy(300); runCurrent()
        assertTrue(e.bitrateKbps.value >= 0)
        assertEquals(ConnectionHealth.Bad, e.health.value)
        e.stop()
    }

    @Test fun forceAvcOverride_requestsAvc_evenWithHevcEncoder() = runTest {
        val streamer = FakeRtmpStreamer()
        val e = engine(streamer, hevcEncoder = true, hevcMain10 = true, cameraHdr = true)
        e.start(StreamConfig("rtmp://h/app", "key", codecOverride = CodecOverride.ForceAvc))
        runCurrent()
        assertEquals(VideoCodecType.AVC, streamer.requestedCodec)
        e.stop()
    }

    @Test fun drop_thenReconnects_reachesLiveAgain() = runTest {
        val streamer = FakeRtmpStreamer()
        val e = engine(streamer)
        e.start(StreamConfig("rtmp://h/app", "key")); runCurrent()
        streamer.emitState(2); advanceTimeBy(150); runCurrent()
        assertEquals(StreamState.Live, e.state.value)
        streamer.emitState(3); advanceTimeBy(150); runCurrent()   // drop
        assertEquals(StreamState.Reconnecting, e.state.value)
        advanceTimeBy(5000); runCurrent()                         // 5s backoff elapses -> new attempt
        streamer.emitState(2); advanceTimeBy(150); runCurrent()   // reconnected
        assertEquals(StreamState.Live, e.state.value)
        e.stop()
    }

    @Test fun stop_duringReconnectWait_endsIdle_noReconnect() = runTest {
        val streamer = FakeRtmpStreamer()
        val e = engine(streamer)
        e.start(StreamConfig("rtmp://h/app", "key")); runCurrent()
        streamer.emitState(2); advanceTimeBy(150); runCurrent()
        streamer.emitState(3); advanceTimeBy(150); runCurrent()
        assertEquals(StreamState.Reconnecting, e.state.value)
        e.stop(); runCurrent()
        assertEquals(StreamState.Idle, e.state.value)
        advanceTimeBy(6000); runCurrent()
        assertEquals(StreamState.Idle, e.state.value)             // did not reconnect
    }

    @Test fun rejected_isTerminal_noReconnect() = runTest {
        val streamer = FakeRtmpStreamer()
        val e = engine(streamer)
        e.start(StreamConfig("rtmp://h/app", "key")); runCurrent()
        streamer.emitState(2); advanceTimeBy(150); runCurrent()
        streamer.emitState(4); advanceTimeBy(150); runCurrent()   // server rejection
        assertTrue(e.state.value is StreamState.Error)
        advanceTimeBy(6000); runCurrent()
        assertTrue(e.state.value is StreamState.Error)            // stays terminal
    }
}
