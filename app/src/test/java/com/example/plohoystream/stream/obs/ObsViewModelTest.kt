package com.example.plohoystream.stream.obs

import com.example.plohoystream.MainDispatcherRule
import com.example.plohoystream.data.FakeSettingsStore
import com.example.plohoystream.stream.FakeStreamEngine
import com.example.plohoystream.stream.StreamViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObsViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    @Test fun obsSwitchScene_delegatesToRemote() = runTest {
        val fakeObs = FakeObsRemote()
        val vm = StreamViewModel(FakeStreamEngine(), store = FakeSettingsStore(), obs = fakeObs)
        vm.obsSwitchScene("Main")
        advanceUntilIdle()
        assertEquals(listOf("Main"), fakeObs.switchedScenes)
    }

    @Test fun setObsAutoSwitch_persistsViaStore() = runTest {
        val store = FakeSettingsStore()
        val vm = StreamViewModel(FakeStreamEngine(), store = store, obs = FakeObsRemote())
        vm.setObsAutoSwitch(true)
        advanceUntilIdle()
        assertTrue(store.state.value.obsAutoSwitchEnabled)
    }

    @Test fun obsConnectedState_propagatesToUiState() = runTest {
        val fakeObs = FakeObsRemote()
        val vm = StreamViewModel(FakeStreamEngine(), store = FakeSettingsStore(), obs = fakeObs)
        advanceUntilIdle()
        fakeObs.connected.value = true
        advanceUntilIdle()
        assertTrue(vm.uiState.value.obsConnected)
    }
}
