package com.example.plohoystream.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import com.example.plohoystream.stream.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

private object SettingsSerializer : Serializer<Settings> {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    override val defaultValue = Settings()
    override suspend fun readFrom(input: InputStream): Settings =
        runCatching { json.decodeFromString(Settings.serializer(), input.readBytes().decodeToString()) }
            .getOrDefault(Settings())
    override suspend fun writeTo(t: Settings, output: OutputStream) {
        output.write(json.encodeToString(Settings.serializer(), t).encodeToByteArray())
    }
}

/** DataStore-backed [SettingsStore]; persists across app updates (app-private dir). */
class DataStoreSettingsStore(context: Context) : SettingsStore {
    private val store: DataStore<Settings> = DataStoreFactory.create(
        serializer = SettingsSerializer,
        produceFile = { context.applicationContext.dataStoreFile("settings.json") },
    )
    override val data: Flow<Settings> = store.data.catch { emit(Settings()) }
    override suspend fun update(transform: (Settings) -> Settings) { store.updateData(transform) }
}
