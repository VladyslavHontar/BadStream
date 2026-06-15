package com.example.plohoystream.stream.obs

import com.example.plohoystream.stream.Settings
import com.example.plohoystream.stream.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ObsWebSocketController(
    private val streamState: StateFlow<StreamState>,
    private val settings: Flow<Settings>,
    private val clientFactory: () -> OkHttpClient = {
        OkHttpClient.Builder().pingInterval(10, TimeUnit.SECONDS).build()
    },
) : ObsRemote {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _scenes = MutableStateFlow<List<String>>(emptyList())
    override val scenes: StateFlow<List<String>> = _scenes.asStateFlow()

    private val _currentScene = MutableStateFlow<String?>(null)
    override val currentScene: StateFlow<String?> = _currentScene.asStateFlow()

    private val _obsStreaming = MutableStateFlow(false)
    override val obsStreaming: StateFlow<Boolean> = _obsStreaming.asStateFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var latestSettings: Settings = Settings()
    @Volatile private var enabled = false
    // Connection-relevant params only; a change here (not any Settings field) triggers reconnect.
    @Volatile private var connParams: Triple<String, Int, String> = Triple("", 4455, "")

    private val requestCounter = AtomicInteger(0)
    private fun nextRequestId() = "r${requestCounter.incrementAndGet()}"

    private val reconnectDelay = AtomicLong(500L)
    private var reconnectJob: Job? = null

    init {
        // Watch settings for connect/reconnect
        scope.launch {
            settings.collect { cfg ->
                latestSettings = cfg
                val shouldBeEnabled = cfg.obsHost.isNotBlank()
                val newParams = Triple(cfg.obsHost, cfg.obsPort, cfg.obsPassword)
                when {
                    shouldBeEnabled != enabled -> {
                        enabled = shouldBeEnabled
                        connParams = newParams
                        if (enabled) connectToObs(cfg) else disconnectFromObs()
                    }
                    // Reconnect ONLY when connection params change — not on unrelated settings
                    // (scene names, auto-switch, quality, …); those are read live via latestSettings.
                    enabled && newParams != connParams -> {
                        connParams = newParams
                        disconnectFromObs()
                        delay(200)
                        connectToObs(cfg)
                    }
                }
            }
        }
        // BRB auto-switch collector
        scope.launch {
            streamState.collect { st ->
                val cfg = latestSettings
                BrbSwitch.decide(
                    state = st,
                    autoEnabled = cfg.obsAutoSwitchEnabled,
                    connected = _connected.value,
                    currentScene = _currentScene.value,
                    mainScene = cfg.obsMainSceneName,
                    brbScene = cfg.obsBrbSceneName,
                )?.let { targetScene -> switchScene(targetScene) }
            }
        }
    }

    private fun connectToObs(cfg: Settings) {
        reconnectJob?.cancel()
        socket?.cancel()
        socket = null
        val client = clientFactory()
        val url = "ws://${cfg.obsHost}:${cfg.obsPort}"
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, ObsListener())
    }

    private fun disconnectFromObs() {
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, "disabled")
        socket = null
        _connected.value = false
        _scenes.value = emptyList()
        _currentScene.value = null
        _obsStreaming.value = false
    }

    private fun scheduleReconnect() {
        if (!enabled) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val backoff = reconnectDelay.get()
            delay(backoff)
            reconnectDelay.set(minOf(backoff * 2, 10_000L))
            if (enabled) connectToObs(latestSettings)
        }
    }

    private fun sendRaw(text: String) {
        socket?.send(text)
    }

    override fun switchScene(name: String) {
        if (!_connected.value) return
        val data = buildJsonObject { put("sceneName", name) }
        sendRaw(ObsMessages.buildRequest("SetCurrentProgramScene", nextRequestId(), data))
    }

    override fun startObsStream() {
        if (!_connected.value) return
        sendRaw(ObsMessages.buildRequest("StartStream", nextRequestId()))
    }

    override fun stopObsStream() {
        if (!_connected.value) return
        sendRaw(ObsMessages.buildRequest("StopStream", nextRequestId()))
    }

    fun refreshScenes() {
        if (!_connected.value) return
        sendRaw(ObsMessages.buildRequest("GetSceneList", nextRequestId()))
    }

    fun dispose() {
        scope.coroutineContext[Job]?.cancel()
        socket?.cancel()
        socket = null
    }

    private inner class ObsListener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val msg = ObsMessages.parse(text)) {
                is ObsIncoming.Hello -> {
                    val cfg = latestSettings
                    val auth = if (msg.salt != null && msg.challenge != null) {
                        ObsAuth.compute(cfg.obsPassword, msg.salt, msg.challenge)
                    } else null
                    sendRaw(ObsMessages.buildIdentify(auth))
                }
                is ObsIncoming.Identified -> {
                    reconnectDelay.set(500L)
                    _connected.value = true
                    sendRaw(ObsMessages.buildRequest("GetSceneList", nextRequestId()))
                    sendRaw(ObsMessages.buildRequest("GetStreamStatus", nextRequestId()))
                }
                is ObsIncoming.Response -> {
                    if (msg.ok && msg.data != null) {
                        when (msg.requestType) {
                            "GetSceneList" -> {
                                _scenes.value = ObsMessages.scenesFrom(msg.data)
                                ObsMessages.currentSceneFrom(msg.data)?.let { _currentScene.value = it }
                            }
                            "GetStreamStatus" -> {
                                val active = msg.data["outputActive"]
                                    ?.let { (it as? JsonPrimitive)?.boolean }
                                    ?: false
                                _obsStreaming.value = active
                            }
                        }
                    }
                }
                is ObsIncoming.SceneChanged -> _currentScene.value = msg.sceneName
                is ObsIncoming.StreamStateChanged -> _obsStreaming.value = msg.active
                is ObsIncoming.Unknown -> { /* ignore */ }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== socket) return   // stale socket from a deliberate reconnect — ignore
            _connected.value = false
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== socket) return   // stale socket (we already opened a new one) — ignore
            _connected.value = false
            if (enabled) scheduleReconnect()
        }
    }
}
