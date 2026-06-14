package com.example.plohoystream.stream

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.plohoystream.data.SettingsStore
import com.example.plohoystream.ui.settings.SettingsRoute
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class StreamViewModel(
    private val engine: StreamEngine,
    hdrAvailable: Boolean = false,
    // Persistence seam — NOT defaulted so it can never be forgotten at the call site.
    private val store: SettingsStore,
    // Real-time clock for the 1s elapsed ticker. Kept off the (virtual) Main test dispatcher so
    // unit tests calling advanceUntilIdle() don't chase this perpetually-rescheduling loop forever.
    private val tickerDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _uiState = MutableStateFlow(StreamUiState(hdrAvailable = hdrAvailable))
    val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

    val encoderSurface: StateFlow<Surface?> =
        (engine as? VideoStreamEngine)?.encoderSurface ?: MutableStateFlow<Surface?>(null)

    val activeHdr: StateFlow<Boolean> =
        (engine as? VideoStreamEngine)?.activeHdr ?: MutableStateFlow(false)

    @Volatile private var liveStartMs: Long = 0L

    init {
        // Seed UI state from persisted settings and keep it in sync.
        viewModelScope.launch {
            store.data.collect { s -> _uiState.update { it.copy(settings = s) } }
        }
        viewModelScope.launch {
            engine.state.collect { s ->
                if (s is StreamState.Live && liveStartMs == 0L) liveStartMs = System.currentTimeMillis()
                if (s is StreamState.Idle || s is StreamState.Error) liveStartMs = 0L
                _uiState.update { it.copy(stream = s) }
            }
        }
        (engine as? VideoStreamEngine)?.let { ve ->
            viewModelScope.launch { ve.bitrateKbps.collect { v -> _uiState.update { it.copy(bitrateKbps = v) } } }
            viewModelScope.launch { ve.health.collect { v -> _uiState.update { it.copy(health = v) } } }
            viewModelScope.launch { ve.audioLevel.collect { v -> _uiState.update { it.copy(audioLevel = v) } } }
        }
        // Tick the elapsed timer once per second while live (real-time dispatcher, see ctor).
        viewModelScope.launch(tickerDispatcher) {
            while (isActive) {
                val start = liveStartMs
                val elapsed = if (start > 0L) formatElapsed(System.currentTimeMillis() - start) else "00:00"
                _uiState.update { if (it.elapsed != elapsed) it.copy(elapsed = elapsed) else it }
                delay(1000)
            }
        }
    }

    /** Single write path: applies the transform optimistically to UI state AND persists it. */
    private fun mutate(transform: (Settings) -> Settings) {
        _uiState.update { it.copy(settings = transform(it.settings)) }
        viewModelScope.launch { store.update(transform) }
    }

    fun setUrl(value: String) = mutate { it.copy(rtmpUrl = value) }
    fun setKey(value: String) = mutate { it.copy(streamKey = value) }
    fun setHdr(on: Boolean) = mutate { it.copy(hdrEnabled = on) }
    fun setQuality(q: VideoQuality) = mutate { it.copy(quality = q) }
    fun setCodecOverride(c: CodecOverride) = mutate { it.copy(codecOverride = c) }

    fun openSettings() = _uiState.update { it.copy(panelOpen = true, settingsRoute = SettingsRoute.Root) }
    fun closeSettings() = _uiState.update { it.copy(panelOpen = false, settingsRoute = SettingsRoute.Root) }
    fun navigateSettings(route: SettingsRoute) = _uiState.update { it.copy(settingsRoute = route) }

    fun goLive() {
        val s = _uiState.value
        if (!s.canGoLive) return
        _uiState.update { it.copy(stream = StreamState.Connecting) }   // optimistic: closes double-tap window
        val cfg = s.settings
        engine.start(
            StreamConfig(
                rtmpUrl = cfg.rtmpUrl,
                streamKey = cfg.streamKey,
                hdrEnabled = cfg.hdrEnabled,
                quality = cfg.quality,
                codecOverride = cfg.codecOverride,
            ),
        )
    }

    fun stop() = engine.stop()
}
