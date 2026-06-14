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
        startMedia: (RtmpStreamer, VideoFormat) -> Unit = { _, _ -> },
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

    @Test fun liveThenNativeDrop_surfacesAsError() = runTest {
        val streamer = FakeRtmpStreamer()
        val e = engine(streamer)
        e.start(StreamConfig("rtmp://h/app", "key")); runCurrent()
        streamer.emitState(2); advanceTimeBy(150); runCurrent()
        assertEquals(StreamState.Live, e.state.value)
        streamer.emitState(3); advanceTimeBy(150); runCurrent()
        assertTrue(e.state.value is StreamState.Error)
    }

    @Test fun start_withBadUrl_goesToError() = runTest {
        val e = engine(FakeRtmpStreamer())
        e.start(StreamConfig("not-a-url", "key"))
        runCurrent()
        assertTrue(e.state.value is StreamState.Error)
    }

    @Test fun nativeError_surfacesAsError() = runTest {
        val streamer = FakeRtmpStreamer()
        val e = engine(streamer)
        e.start(StreamConfig("rtmp://h/app", "key")); runCurrent()
        streamer.emitState(3) // Error
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
        val e = engine(
            streamer, hevcEncoder = true, hevcMain10 = true, cameraHdr = true,
            startMedia = { _, fmt -> captured = fmt },
        )
        e.start(StreamConfig("rtmp://h/app", "key", hdrEnabled = true))
        runCurrent()
        streamer.negotiatedCodecValue = VideoCodecType.AVC // server has no HEVC -> downgrade
        streamer.emitState(2); advanceTimeBy(150); runCurrent()
        assertNotNull(captured)
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
            startMedia = { _, fmt -> captured = fmt },
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
}
