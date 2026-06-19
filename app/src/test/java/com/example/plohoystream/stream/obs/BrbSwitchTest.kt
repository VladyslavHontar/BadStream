package com.example.plohoystream.stream.obs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrbSwitchTest {
    private val main = "Main"
    private val brb = "BRB"

    // decide(broken, working, autoEnabled, connected, currentScene, mainScene, brbScene)

    @Test fun broken_fromMain_enabled_connected_returnsBrb() {
        assertEquals(brb, BrbSwitch.decide(broken = true, working = false, true, true, main, main, brb))
    }

    @Test fun working_fromBrb_enabled_connected_returnsMain() {
        assertEquals(main, BrbSwitch.decide(broken = false, working = true, true, true, brb, main, brb))
    }

    @Test fun broken_alreadyOnBrb_returnsNull_noRedundant() {
        assertNull(BrbSwitch.decide(broken = true, working = false, true, true, brb, main, brb))
    }

    @Test fun working_alreadyOnMain_returnsNull_noRedundant() {
        assertNull(BrbSwitch.decide(broken = false, working = true, true, true, main, main, brb))
    }

    @Test fun broken_fromOtherScene_returnsNull_doesNotYank() {
        assertNull(BrbSwitch.decide(broken = true, working = false, true, true, "Starting", main, brb))
    }

    @Test fun notEnabled_returnsNull() {
        assertNull(BrbSwitch.decide(broken = true, working = false, false, true, main, main, brb))
    }

    @Test fun notConnected_returnsNull() {
        assertNull(BrbSwitch.decide(broken = true, working = false, true, false, main, main, brb))
    }

    @Test fun blankBrbScene_returnsNull() {
        assertNull(BrbSwitch.decide(broken = true, working = false, true, true, main, main, ""))
    }

    @Test fun blankMainScene_returnsNull() {
        assertNull(BrbSwitch.decide(broken = false, working = true, true, true, brb, "", brb))
    }

    @Test fun neitherBrokenNorWorking_returnsNull() {
        assertNull(BrbSwitch.decide(broken = false, working = false, true, true, main, main, brb))
    }

    @Test fun currentSceneNull_returnsNull() {
        assertNull(BrbSwitch.decide(broken = true, working = false, true, true, null, main, brb))
    }
}
