package com.example.plohoystream.data

import com.example.plohoystream.stream.Settings
import kotlinx.coroutines.flow.Flow

/** Generic persistence seam. Reads the whole [Settings] bundle and applies copy-transforms. */
interface SettingsStore {
    val data: Flow<Settings>
    suspend fun update(transform: (Settings) -> Settings)
}
