package com.example.plohoystream.stream.obs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObsMessagesTest {

    // --- buildIdentify ---

    @Test fun buildIdentify_withAuth_containsAuthentication() {
        val msg = ObsMessages.buildIdentify(auth = "authToken123")
        val elem = Json.parseToJsonElement(msg)
        val d = (elem as kotlinx.serialization.json.JsonObject)["d"] as JsonObject
        assertEquals(1, (d["op"]?.let { it } ?: (elem["op"])).let {
            (elem["op"] as kotlinx.serialization.json.JsonPrimitive).int
        })
        assertEquals("authToken123", (d["authentication"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(1, (d["rpcVersion"] as kotlinx.serialization.json.JsonPrimitive).int)
        assertEquals(68, (d["eventSubscriptions"] as kotlinx.serialization.json.JsonPrimitive).int)
    }

    @Test fun buildIdentify_noAuth_omitsAuthentication() {
        val msg = ObsMessages.buildIdentify(auth = null)
        val elem = Json.parseToJsonElement(msg) as JsonObject
        val d = elem["d"] as JsonObject
        assertFalse("authentication should be absent", d.containsKey("authentication"))
        assertEquals(1, (d["rpcVersion"] as kotlinx.serialization.json.JsonPrimitive).int)
        assertEquals(68, (d["eventSubscriptions"] as kotlinx.serialization.json.JsonPrimitive).int)
    }

    // --- buildRequest ---

    @Test fun buildRequest_op6_withoutRequestData() {
        val msg = ObsMessages.buildRequest("GetSceneList", "r1")
        val elem = Json.parseToJsonElement(msg) as JsonObject
        assertEquals(6, (elem["op"] as kotlinx.serialization.json.JsonPrimitive).int)
        val d = elem["d"] as JsonObject
        assertEquals("GetSceneList", (d["requestType"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("r1", (d["requestId"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test fun buildRequest_op6_withRequestData() {
        val data = buildJsonObject { put("sceneName", "Main") }
        val msg = ObsMessages.buildRequest("SetCurrentProgramScene", "r2", data)
        val elem = Json.parseToJsonElement(msg) as JsonObject
        val d = elem["d"] as JsonObject
        val rd = d["requestData"] as JsonObject
        assertEquals("Main", (rd["sceneName"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    // --- parse ---

    @Test fun parse_hello_withAuth() {
        val json = """{"op":0,"d":{"obsWebSocketVersion":"5.0","rpcVersion":1,"authentication":{"salt":"s1","challenge":"c1"}}}"""
        val result = ObsMessages.parse(json)
        assertTrue(result is ObsIncoming.Hello)
        val hello = result as ObsIncoming.Hello
        assertEquals("s1", hello.salt)
        assertEquals("c1", hello.challenge)
    }

    @Test fun parse_hello_withoutAuth() {
        val json = """{"op":0,"d":{"obsWebSocketVersion":"5.0","rpcVersion":1}}"""
        val result = ObsMessages.parse(json)
        assertTrue(result is ObsIncoming.Hello)
        val hello = result as ObsIncoming.Hello
        assertNull(hello.salt)
        assertNull(hello.challenge)
    }

    @Test fun parse_identified_op2() {
        val json = """{"op":2,"d":{"negotiatedRpcVersion":1}}"""
        val result = ObsMessages.parse(json)
        assertTrue(result is ObsIncoming.Identified)
    }

    @Test fun parse_response_success() {
        val json = """{"op":7,"d":{"requestType":"GetSceneList","requestId":"r1","requestStatus":{"result":true,"code":100},"responseData":{"currentProgramSceneName":"Main","scenes":[{"sceneName":"Main","sceneIndex":0}]}}}"""
        val result = ObsMessages.parse(json)
        assertTrue(result is ObsIncoming.Response)
        val r = result as ObsIncoming.Response
        assertEquals("r1", r.requestId)
        assertEquals("GetSceneList", r.requestType)
        assertTrue(r.ok)
    }

    @Test fun parse_response_failure() {
        val json = """{"op":7,"d":{"requestType":"SetCurrentProgramScene","requestId":"r2","requestStatus":{"result":false,"code":604}}}"""
        val result = ObsMessages.parse(json)
        assertTrue(result is ObsIncoming.Response)
        assertFalse((result as ObsIncoming.Response).ok)
    }

    @Test fun parse_event_sceneChanged() {
        val json = """{"op":5,"d":{"eventType":"CurrentProgramSceneChanged","eventIntent":4,"eventData":{"sceneName":"BRB"}}}"""
        val result = ObsMessages.parse(json)
        assertTrue(result is ObsIncoming.SceneChanged)
        assertEquals("BRB", (result as ObsIncoming.SceneChanged).sceneName)
    }

    @Test fun parse_event_streamStateChanged() {
        val json = """{"op":5,"d":{"eventType":"StreamStateChanged","eventIntent":64,"eventData":{"outputActive":true,"outputState":"OBS_WEBSOCKET_OUTPUT_STARTED"}}}"""
        val result = ObsMessages.parse(json)
        assertTrue(result is ObsIncoming.StreamStateChanged)
        assertTrue((result as ObsIncoming.StreamStateChanged).active)
    }

    @Test fun parse_unknown_op() {
        val json = """{"op":9,"d":{}}"""
        val result = ObsMessages.parse(json)
        assertTrue(result is ObsIncoming.Unknown)
    }

    // --- helpers ---

    @Test fun scenesFrom_extractsSceneNames() {
        val data = buildJsonObject {
            put("currentProgramSceneName", "Main")
            put("scenes", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject { put("sceneName", "Main"); put("sceneIndex", 0) })
                add(buildJsonObject { put("sceneName", "BRB"); put("sceneIndex", 1) })
            })
        }
        val scenes = ObsMessages.scenesFrom(data)
        assertEquals(listOf("Main", "BRB"), scenes)
    }

    @Test fun currentSceneFrom_extractsCurrentScene() {
        val data = buildJsonObject { put("currentProgramSceneName", "Main") }
        assertEquals("Main", ObsMessages.currentSceneFrom(data))
    }
}
