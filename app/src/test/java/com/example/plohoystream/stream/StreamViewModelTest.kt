package com.example.plohoystream.stream

import com.example.plohoystream.MainDispatcherRule
import com.example.plohoystream.data.FakeSettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    @Test fun setUrlKey_thenGoLive_startsEngineWithConfig() = runTest {
        val engine = FakeStreamEngine()
        val vm = StreamViewModel(engine, store = FakeSettingsStore())
        vm.setUrl("rtmp://h/app"); vm.setKey("k")
        vm.goLive()
        advanceUntilIdle()
        assertEquals(StreamConfig("rtmp://h/app", "k"), engine.lastConfig)
        assertEquals(StreamState.Connecting, vm.uiState.value.stream)
    }

    @Test fun goLive_ignoredWhenUrlOrKeyBlank() = runTest {
        val engine = FakeStreamEngine()
        val vm = StreamViewModel(engine, store = FakeSettingsStore())
        vm.setUrl("rtmp://h/app")   // no key
        vm.goLive()
        advanceUntilIdle()
        assertEquals(null, engine.lastConfig)
        assertEquals(StreamState.Idle, vm.uiState.value.stream)
    }

    @Test fun engineStateChanges_propagateToUiState() = runTest {
        val engine = FakeStreamEngine()
        val vm = StreamViewModel(engine, store = FakeSettingsStore())
        advanceUntilIdle()
        engine.start(StreamConfig("rtmp://h/app", "k"))
        engine.emitLive()
        advanceUntilIdle()
        assertEquals(StreamState.Live, vm.uiState.value.stream)
        assertTrue(vm.uiState.value.isActive)
    }

    @Test fun stop_delegatesToEngine() = runTest {
        val engine = FakeStreamEngine()
        val vm = StreamViewModel(engine, store = FakeSettingsStore())
        vm.setUrl("rtmp://h/app"); vm.setKey("k"); vm.goLive(); engine.emitLive()
        advanceUntilIdle()
        vm.stop()
        advanceUntilIdle()
        assertEquals(StreamState.Idle, vm.uiState.value.stream)
    }

    @Test fun setHdr_thenGoLive_passesHdrEnabledInConfig() = runTest {
        val engine = FakeStreamEngine()
        val vm = StreamViewModel(engine, hdrAvailable = true, store = FakeSettingsStore())
        vm.setUrl("rtmp://h/app"); vm.setKey("k")
        vm.setHdr(true)
        vm.goLive()
        advanceUntilIdle()
        assertEquals(StreamConfig("rtmp://h/app", "k", hdrEnabled = true), engine.lastConfig)
        assertTrue(vm.uiState.value.hdrAvailable)
    }

    @Test fun setQualityAndCodec_thenGoLive_buildsRicherConfig() = runTest {
        val engine = FakeStreamEngine()
        val vm = StreamViewModel(engine, store = FakeSettingsStore())
        vm.setUrl("rtmp://h/app"); vm.setKey("k")
        vm.setQuality(VideoQuality(1280, 720, 30, 3_500_000, 128_000))
        vm.setCodecOverride(CodecOverride.ForceHevc)
        vm.goLive()
        advanceUntilIdle()
        assertEquals(
            StreamConfig("rtmp://h/app", "k", quality = VideoQuality(1280, 720, 30, 3_500_000, 128_000), codecOverride = CodecOverride.ForceHevc),
            engine.lastConfig,
        )
    }

    @Test fun setUrl_thenKey_persistToStore() = runTest {
        val store = FakeSettingsStore()
        val vm = StreamViewModel(FakeStreamEngine(), store = store)
        vm.setUrl("rtmp://h/app"); vm.setKey("k")
        advanceUntilIdle()
        assertEquals("rtmp://h/app", store.state.value.rtmpUrl)
        assertEquals("k", store.state.value.streamKey)
    }

    @Test fun loadsPersistedSettingsOnInit() = runTest {
        val store = FakeSettingsStore(Settings(rtmpUrl = "rtmp://saved/app", streamKey = "sk"))
        val vm = StreamViewModel(FakeStreamEngine(), store = store)
        advanceUntilIdle()
        assertEquals("rtmp://saved/app", vm.uiState.value.settings.rtmpUrl)
        assertEquals("sk", vm.uiState.value.settings.streamKey)
    }

    @Test fun panelOpenClose_andRoute_updateUiState() = runTest {
        val vm = StreamViewModel(FakeStreamEngine(), store = FakeSettingsStore())
        vm.openSettings()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.panelOpen)
        vm.navigateSettings(com.example.plohoystream.ui.settings.SettingsRoute.Video)
        assertEquals(com.example.plohoystream.ui.settings.SettingsRoute.Video, vm.uiState.value.settingsRoute)
        vm.closeSettings()
        assertFalse(vm.uiState.value.panelOpen)
    }

    @Test fun engineLiveStats_propagateToUiState() = runTest {
        val engine = FakeStreamEngine()
        val vm = StreamViewModel(engine, store = FakeSettingsStore())
        advanceUntilIdle()
        engine.emitBitrate(5500)
        engine.emitHealth(ConnectionHealth.Warn)
        advanceUntilIdle()
        assertEquals(5500, vm.uiState.value.bitrateKbps)
        assertEquals(ConnectionHealth.Warn, vm.uiState.value.health)
    }

@Test fun reconnecting_propagatesToUiState_andIsActive() = runTest {
    val engine = FakeStreamEngine()
    val vm = StreamViewModel(engine, store = FakeSettingsStore())
    advanceUntilIdle()
    engine.start(StreamConfig("rtmp://h/app", "k"))
    engine.emitLive()
    advanceUntilIdle()
    engine.emitReconnecting()
    advanceUntilIdle()
    assertEquals(StreamState.Reconnecting, vm.uiState.value.stream)
    assertTrue(vm.uiState.value.isActive)
}
}
