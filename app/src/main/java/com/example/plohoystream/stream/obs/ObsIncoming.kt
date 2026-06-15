package com.example.plohoystream.stream.obs

import kotlinx.serialization.json.JsonObject

sealed interface ObsIncoming {
    data class Hello(val salt: String?, val challenge: String?) : ObsIncoming
    data object Identified : ObsIncoming
    data class Response(
        val requestId: String,
        val requestType: String,
        val ok: Boolean,
        val data: JsonObject?,
    ) : ObsIncoming
    data class SceneChanged(val sceneName: String) : ObsIncoming
    data class StreamStateChanged(val active: Boolean) : ObsIncoming
    data object Unknown : ObsIncoming
}
