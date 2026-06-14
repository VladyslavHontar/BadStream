package com.example.plohoystream.data

import com.example.plohoystream.stream.Settings
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSettingsStore(initial: Settings = Settings()) : SettingsStore {
    val state = MutableStateFlow(initial)
    override val data = state
    override suspend fun update(transform: (Settings) -> Settings) { state.value = transform(state.value) }
}
