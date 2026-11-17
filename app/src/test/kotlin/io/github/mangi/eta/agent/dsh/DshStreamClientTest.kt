package io.github.mangi.eta.agent.dsh

import android.app.Application
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DshStreamClientTest {
    @Test fun actualStreamOwnerAppliesRevisionedDataAndResubscribesRatherThanApplyingAWrongBase() = runBlocking {
        DshHostClient("http://127.0.0.1:3080/", DshCredentials()).use { host ->
            lateinit var socket: FixtureSocket
            DshStreamClient(host) { request, listener -> FixtureSocket(request, listener).also { socket = it; it.open() } }.use { stream ->
                assertEquals("connection-1", stream.connect())
                val sub = stream.subscribe("fixture", "panel", JSONObject())
                socket.emit(JSONObject().put("t", "data").put("sub", sub).put("rev", "1").put("data", JSONObject().put("value", 1).put("remove", true)))
                socket.emit(JSONObject().put("t", "patch").put("sub", sub).put("base", "1").put("rev", "2").put("merge", JSONObject().put("value", 2).put("remove", JSONObject.NULL)))
                val data = stream.feeds.value[sub]!!.data as JSONObject
                assertEquals(2, data.getInt("value")); assertFalse(data.has("remove"))
                val bad = JSONObject().put("t", "patch").put("sub", sub).put("base", "old").put("rev", "3").put("merge", JSONObject().put("value", 999))
                socket.emit(bad); socket.emit(bad)
                assertEquals(2, (stream.feeds.value[sub]!!.data as JSONObject).getInt("value"))
                assertEquals(2, socket.sent.count { it.optString("t") == "sub" })
                stream.unsubscribe(sub)
                assertFalse(stream.feeds.value.containsKey(sub))
            }
            assertTrue(socket.cancelled)
        }
    }

    @Test fun outOfOrderDeferredDeliveryIsConsumedOnceAndCannotCarryNavigationOrPermissions() = runBlocking {
        DshHostClient("http://127.0.0.1:3080/", DshCredentials()).use { host ->
            lateinit var socket: FixtureSocket
            DshStreamClient(host) { request, listener -> FixtureSocket(request, listener).also { socket = it; it.open() } }.use { stream ->
                stream.connect()
                socket.emit(JSONObject().put("t", "done").put("pending", "pending-1").put("effects", JSONArray()
                    .put(JSONObject().put("type", "copy").put("text", "result"))
                    .put(JSONObject().put("type", "link").put("url", "https://example.com"))
                    .put(JSONObject().put("type", "approve").put("text", "no"))))
                val result = stream.awaitPending("pending-1", 1000)
                assertTrue(result.deferred); assertEquals("copy", result.values.single().getString("type"))
                assertTrue(runCatching { stream.awaitPending("pending-1", 1000) }.isFailure)
                val waiting = async { runCatching { stream.awaitPending("pending-2", 2000) } }
                yield(); stream.close()
                assertTrue(withTimeout(1000) { waiting.await() }.isFailure)
            }
        }
    }

    @Test fun protocolRejectsDeepFramesAndResolvesOnlyBoundedDataPaths() {
        val data = JSONObject().put("rows", JSONArray().put(JSONObject().put("value", "bound")))
        assertEquals("bound", DshProtocol.binding(JSONObject().put("$", "data.rows[0].value"), data))
        assertNull(DshProtocol.binding(JSONObject().put("$", "data.__proto__.value"), data))
        assertThrows(IllegalArgumentException::class.java) { DshProtocol.parse("{\"nested\":" + "[".repeat(100) + "0" + "]".repeat(100) + "}", 1000) }
        val effects = DshProtocol.effects(JSONArray().put(JSONObject().put("type", "link").put("url", "javascript:alert(1)"))
            .put(JSONObject().put("type", "open").put("page", "panel:valid")), static = true)
        assertEquals("open", effects.values.single().getString("type"))
        val patch = DshProtocol.mergePatch(JSONObject().put("keep", true), JSONObject().put("__proto__", JSONObject().put("escape", true))) as JSONObject
        assertFalse(patch.has("__proto__")); assertTrue(patch.getBoolean("keep"))
    }

    private class FixtureSocket(private val request: Request, private val listener: WebSocketListener) : WebSocket {
        val sent = mutableListOf<JSONObject>(); var cancelled = false
        fun open() { listener.onOpen(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build()) }
        fun emit(value: JSONObject) { listener.onMessage(this, value.toString()) }
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String): Boolean {
            val value = JSONObject(text); sent += value
            if (value.optString("t") == "hello") emit(JSONObject().put("t", "welcome").put("v", 1).put("conn", "connection-1").put("hb", 25))
            return true
        }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?): Boolean { cancelled = true; return true }
        override fun cancel() { cancelled = true }
    }
}
