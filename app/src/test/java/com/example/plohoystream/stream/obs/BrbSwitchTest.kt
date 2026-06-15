package com.example.plohoystream.stream.obs

import com.example.plohoystream.stream.StreamState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrbSwitchTest {
    private val main = "Main"
    private val brb = "BRB"

    @Test fun reconnecting_fromMain_enabled_connected_returnsBrb() {
        assertEquals(brb, BrbSwitch.decide(StreamState.Reconnecting, true, true, main, main, brb))
    }

    @Test fun live_fromBrb_enabled_connected_returnsMain() {
        assertEquals(main, BrbSwitch.decide(StreamState.Live, true, true, brb, main, brb))
    }

    @Test fun reconnecting_alreadyOnBrb_returnsNull_noRedundant() {
        assertNull(BrbSwitch.decide(StreamState.Reconnecting, true, true, brb, main, brb))
    }

    @Test fun live_alreadyOnMain_returnsNull_noRedundant() {
        assertNull(BrbSwitch.decide(StreamState.Live, true, true, main, main, brb))
    }

    @Test fun notEnabled_returnsNull() {
        assertNull(BrbSwitch.decide(StreamState.Reconnecting, false, true, main, main, brb))
    }

    @Test fun notConnected_returnsNull() {
        assertNull(BrbSwitch.decide(StreamState.Reconnecting, true, false, main, main, brb))
    }

    @Test fun blankBrbScene_returnsNull() {
        assertNull(BrbSwitch.decide(StreamState.Reconnecting, true, true, main, main, ""))
    }

    @Test fun blankMainScene_onLive_returnsNull() {
        assertNull(BrbSwitch.decide(StreamState.Live, true, true, brb, "", brb))
    }

    @Test fun idle_returnsNull() {
        assertNull(BrbSwitch.decide(StreamState.Idle, true, true, main, main, brb))
    }

    @Test fun error_returnsNull() {
        assertNull(BrbSwitch.decide(StreamState.Error("err"), true, true, main, main, brb))
    }

    @Test fun connecting_returnsNull() {
        assertNull(BrbSwitch.decide(StreamState.Connecting, true, true, main, main, brb))
    }

    @Test fun stopping_returnsNull() {
        assertNull(BrbSwitch.decide(StreamState.Stopping, true, true, main, main, brb))
    }

    @Test fun reconnecting_currentIsNull_returnsNull() {
        assertNull(BrbSwitch.decide(StreamState.Reconnecting, true, true, null, main, brb))
    }
}
