package com.example.plohoystream.stream

import com.example.plohoystream.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    @Test fun setUrlKey_thenGoLive_startsEngineWithConfig() = runTest {
        val engine = FakeStreamEngine()
        val vm = StreamViewModel(engine)
        vm.setUrl("rtmp://h/app"); vm.setKey("k")
        vm.goLive()
        advanceUntilIdle()
        assertEquals(StreamConfig("rtmp://h/app", "k"), engine.lastConfig)
        assertEquals(StreamState.Connecting, vm.uiState.value.stream)
    }

    @Test fun goLive_ignoredWhenUrlOrKeyBlank() = runTest {
        val engine = FakeStreamEngine()
        val vm = StreamViewModel(engine)
        vm.setUrl("rtmp://h/app")   // no key
        vm.goLive()
        advanceUntilIdle()
        assertEquals(null, engine.lastConfig)
        assertEquals(StreamState.Idle, vm.uiState.value.stream)
    }

    @Test fun engineStateChanges_propagateToUiState() = runTest {
        val engine = FakeStreamEngine()
        val vm = StreamViewModel(engine)
        advanceUntilIdle()
        engine.start(StreamConfig("rtmp://h/app", "k"))
        engine.emitLive()
        advanceUntilIdle()
        assertEquals(StreamState.Live, vm.uiState.value.stream)
        assertTrue(vm.uiState.value.isActive)
    }

    @Test fun stop_delegatesToEngine() = runTest {
        val engine = FakeStreamEngine()
        val vm = StreamViewModel(engine)
        vm.setUrl("rtmp://h/app"); vm.setKey("k"); vm.goLive(); engine.emitLive()
        advanceUntilIdle()
        vm.stop()
        advanceUntilIdle()
        assertEquals(StreamState.Idle, vm.uiState.value.stream)
    }
}
