package com.example.plohoystream.stream

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class StreamViewModel(private val engine: StreamEngine) : ViewModel() {
    private val _uiState = MutableStateFlow(StreamUiState())
    val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

    /** Encoder input surface when the engine is a video engine; always-null flow otherwise (Fake). */
    val encoderSurface: StateFlow<Surface?> =
        (engine as? VideoStreamEngine)?.encoderSurface ?: MutableStateFlow<Surface?>(null)

    init {
        viewModelScope.launch {
            engine.state.collect { s -> _uiState.update { it.copy(stream = s) } }
        }
    }

    fun setUrl(value: String) = _uiState.update { it.copy(url = value) }
    fun setKey(value: String) = _uiState.update { it.copy(key = value) }

    fun goLive() {
        val s = _uiState.value
        if (s.canGoLive) engine.start(StreamConfig(s.url, s.key))
    }

    fun stop() = engine.stop()
}
