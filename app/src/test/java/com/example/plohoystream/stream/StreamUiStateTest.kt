package com.example.plohoystream.stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUiStateTest {
    @Test fun canGoLive_requiresUrlKeyAndIdleOrError() {
        assertFalse(StreamUiState("", "", StreamState.Idle).canGoLive)         // no url/key
        assertFalse(StreamUiState("rtmp://h/app", "", StreamState.Idle).canGoLive) // no key
        assertTrue(StreamUiState("rtmp://h/app", "k", StreamState.Idle).canGoLive)
        assertTrue(StreamUiState("rtmp://h/app", "k", StreamState.Error("x")).canGoLive)
        assertFalse(StreamUiState("rtmp://h/app", "k", StreamState.Live).canGoLive) // already live
    }
    @Test fun isActive_trueWhileConnectingLiveStopping() {
        assertTrue(StreamUiState(stream = StreamState.Connecting).isActive)
        assertTrue(StreamUiState(stream = StreamState.Live).isActive)
        assertTrue(StreamUiState(stream = StreamState.Stopping).isActive)
        assertFalse(StreamUiState(stream = StreamState.Idle).isActive)
        assertFalse(StreamUiState(stream = StreamState.Error("x")).isActive)
    }
}
