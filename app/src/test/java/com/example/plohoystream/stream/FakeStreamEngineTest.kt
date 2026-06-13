package com.example.plohoystream.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class FakeStreamEngineTest {
    @Test fun start_movesToConnecting_andRecordsConfig() {
        val e = FakeStreamEngine()
        e.start(StreamConfig("rtmp://h/app", "k"))
        assertEquals(StreamState.Connecting, e.state.value)
        assertEquals(StreamConfig("rtmp://h/app", "k"), e.lastConfig)
    }
    @Test fun emitLive_thenStop_goesStoppingThenIdle() {
        val e = FakeStreamEngine()
        e.start(StreamConfig("rtmp://h/app", "k"))
        e.emitLive()
        assertEquals(StreamState.Live, e.state.value)
        e.stop()
        assertEquals(StreamState.Idle, e.state.value)
    }
    @Test fun emitError_setsErrorWithReason() {
        val e = FakeStreamEngine()
        e.start(StreamConfig("rtmp://h/app", "k"))
        e.emitError("connect failed")
        assertEquals(StreamState.Error("connect failed"), e.state.value)
    }
}
