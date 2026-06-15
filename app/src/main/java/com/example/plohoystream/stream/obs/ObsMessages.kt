package com.example.plohoystream.stream.obs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object ObsMessages {
    private val json = Json { ignoreUnknownKeys = true }

    fun buildIdentify(auth: String?): String = buildJsonObject {
        put("op", 1)
        putJsonObject("d") {
            put("rpcVersion", 1)
            if (auth != null) put("authentication", auth)
            put("eventSubscriptions", 68)
        }
    }.toString()

    fun buildRequest(
        requestType: String,
        requestId: String,
        requestData: JsonObject? = null,
    ): String = buildJsonObject {
        put("op", 6)
        putJsonObject("d") {
            put("requestType", requestType)
            put("requestId", requestId)
            if (requestData != null) put("requestData", requestData)
        }
    }.toString()

    fun parse(text: String): ObsIncoming {
        return try {
            val root = json.parseToJsonElement(text).jsonObject
            val op = root["op"]?.jsonPrimitive?.int ?: return ObsIncoming.Unknown
            val d = root["d"]?.jsonObject ?: return ObsIncoming.Unknown
            when (op) {
                0 -> {
                    val auth = d["authentication"]?.jsonObject
                    ObsIncoming.Hello(
                        salt = auth?.get("salt")?.jsonPrimitive?.contentOrNull,
                        challenge = auth?.get("challenge")?.jsonPrimitive?.contentOrNull,
                    )
                }
                2 -> ObsIncoming.Identified
                5 -> {
                    val eventType = d["eventType"]?.jsonPrimitive?.contentOrNull ?: return ObsIncoming.Unknown
                    val eventData = d["eventData"]?.jsonObject
                    when (eventType) {
                        "CurrentProgramSceneChanged" -> {
                            val sceneName = eventData?.get("sceneName")?.jsonPrimitive?.contentOrNull
                                ?: return ObsIncoming.Unknown
                            ObsIncoming.SceneChanged(sceneName)
                        }
                        "StreamStateChanged" -> {
                            val active = eventData?.get("outputActive")?.jsonPrimitive?.boolean
                                ?: return ObsIncoming.Unknown
                            ObsIncoming.StreamStateChanged(active)
                        }
                        else -> ObsIncoming.Unknown
                    }
                }
                7 -> {
                    val requestId = d["requestId"]?.jsonPrimitive?.contentOrNull ?: return ObsIncoming.Unknown
                    val requestType = d["requestType"]?.jsonPrimitive?.contentOrNull ?: return ObsIncoming.Unknown
                    val status = d["requestStatus"]?.jsonObject
                    val ok = status?.get("result")?.jsonPrimitive?.boolean ?: false
                    val responseData = d["responseData"]?.jsonObject
                    ObsIncoming.Response(requestId, requestType, ok, responseData)
                }
                else -> ObsIncoming.Unknown
            }
        } catch (e: Exception) {
            ObsIncoming.Unknown
        }
    }

    fun scenesFrom(data: JsonObject): List<String> =
        data["scenes"]?.jsonArray?.mapNotNull {
            it.jsonObject["sceneName"]?.jsonPrimitive?.contentOrNull
        } ?: emptyList()

    fun currentSceneFrom(data: JsonObject): String? =
        data["currentProgramSceneName"]?.jsonPrimitive?.contentOrNull
}
