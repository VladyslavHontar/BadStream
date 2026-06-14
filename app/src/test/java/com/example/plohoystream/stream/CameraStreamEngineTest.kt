package com.example.plohoystream.stream

import com.example.plohoystream.MainDispatcherRule
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CameraStreamEngineTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun engine(streamer: FakeRtmpStreamer) = CameraStreamEngine(
        streamerFactory = { streamer },
        startMedia = { _ -> },   // no-op: do not touch Android media classes in unit tests
        stopMedia = {},
        pollIntervalMs = 100,
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
}
