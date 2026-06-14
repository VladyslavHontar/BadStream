package com.example.plohoystream.stream

import org.junit.Assert.assertEquals
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

    @Test fun newFields_haveSaneDefaults() {
        val s = StreamUiState()
        assertEquals(0, s.bitrateKbps)
        assertEquals(ConnectionHealth.Good, s.health)
        assertEquals(0f, s.audioLevel, 0f)
        assertEquals("00:00", s.elapsed)
        assertEquals(com.example.plohoystream.ui.settings.SettingsRoute.Root, s.settingsRoute)
        assertFalse(s.settingsOpen)
    }

    @Test fun settingsOpen_trueWhenPanelOpen() {
        assertTrue(StreamUiState(panelOpen = true).settingsOpen)
    }
}
