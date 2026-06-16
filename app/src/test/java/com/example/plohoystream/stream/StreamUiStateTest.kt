package com.example.plohoystream.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUiStateTest {
    @Test fun canGoLive_requiresUrlKeyAndIdleOrError() {
        assertFalse(StreamUiState(Settings(), StreamState.Idle).canGoLive)         // no url/key
        assertFalse(StreamUiState(Settings(rtmpUrl = "rtmp://h/app"), StreamState.Idle).canGoLive) // no key
        assertTrue(StreamUiState(Settings(rtmpUrl = "rtmp://h/app", streamKey = "k"), StreamState.Idle).canGoLive)
        assertTrue(StreamUiState(Settings(rtmpUrl = "rtmp://h/app", streamKey = "k"), StreamState.Error("x")).canGoLive)
        assertFalse(StreamUiState(Settings(rtmpUrl = "rtmp://h/app", streamKey = "k"), StreamState.Live).canGoLive) // already live
    }
    @Test fun isActive_trueWhileConnectingLiveReconnectingStopping() {
        assertTrue(StreamUiState(stream = StreamState.Connecting).isActive)
        assertTrue(StreamUiState(stream = StreamState.Live).isActive)
        assertTrue(StreamUiState(stream = StreamState.Reconnecting).isActive)
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

    @Test fun reconnecting_isActive_andNotGoLiveable() {
        val s = StreamUiState(
            settings = Settings(rtmpUrl = "rtmp://h/app", streamKey = "k"),
            stream = StreamState.Reconnecting,
        )
        assertTrue(s.isActive)
        assertFalse(s.canGoLive)
    }

    @Test fun obsSceneSwitcherVisible_requiresConnectedAndNonEmptyScenes() {
        assertFalse(StreamUiState().obsSceneSwitcherVisible)                                  // default: not connected
        assertFalse(StreamUiState(obsConnected = true).obsSceneSwitcherVisible)               // connected, no scenes
        assertFalse(StreamUiState(obsScenes = listOf("Main")).obsSceneSwitcherVisible)        // scenes, not connected
        assertTrue(StreamUiState(obsConnected = true, obsScenes = listOf("Main")).obsSceneSwitcherVisible)
    }
}
