package us.marreros.haandroidmediaplayer

import okhttp3.WebSocket
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HaWebSocketClientTest {
    private fun socket(sent: MutableList<String>): WebSocket = Proxy.newProxyInstance(
        WebSocket::class.java.classLoader,
        arrayOf(WebSocket::class.java)
    ) { _, method, args ->
        if (method.name == "send") {
            sent += args!![0] as String
            true
        } else if (method.returnType == Boolean::class.javaPrimitiveType) {
            true
        } else {
            null
        }
    } as WebSocket

    private fun listener(client: HaWebSocketClient): Any {
        val type = Class.forName("us.marreros.haandroidmediaplayer.HaWebSocketClient\$Listener")
        return type.getDeclaredConstructor(HaWebSocketClient::class.java).apply {
            isAccessible = true
        }.newInstance(client)
    }

    private fun message(listener: Any, socket: WebSocket, text: String) {
        listener.javaClass.getMethod("onMessage", WebSocket::class.java, String::class.java)
            .invoke(listener, socket, text)
    }

    @Test
    fun authenticatesRegistersSubscribesAndMapsCommands() {
        val statuses = mutableListOf<String>()
        val commands = mutableListOf<HaCommand>()
        val client = HaWebSocketClient(
            "https://ha.example", "token", "pixel", "Pixel", statuses::add, commands::add
        )
        val sent = mutableListOf<String>()
        val socket = socket(sent)
        val listener = listener(client)

        message(listener, socket, "{\"type\":\"auth_required\"}")
        assertEquals("auth", JSONObject(sent[0]).getString("type"))
        message(listener, socket, "{\"type\":\"auth_ok\"}")
        assertEquals("ha_remote_media_player/register", JSONObject(sent[1]).getString("type"))
        message(listener, socket, "{\"type\":\"result\",\"id\":1,\"success\":true}")
        assertEquals("ha_remote_media_player/subscribe_commands", JSONObject(sent[2]).getString("type"))
        message(listener, socket, "{\"type\":\"result\",\"id\":2,\"success\":true}")
        message(listener, socket, """
            {"type":"event","id":2,"event":{"action":"play_media",
            "media_content_id":"/song.mp3","media_title":"Song",
            "media_content_type":"audio/mpeg","volume_level":0.4,
            "is_volume_muted":false,"announce":true}}
        """.trimIndent())

        assertEquals("Connected", statuses.last())
        assertEquals(HaCommand("play_media", "/song.mp3", "Song", "audio/mpeg", 0.4f, null, false, true), commands.single())
    }

    @Test
    fun publishesSanitizedStateAndNormalizesUrls() {
        val client = HaWebSocketClient("https://ha.example/", "token", "pixel", "Pixel", {}, {})
        val sent = mutableListOf<String>()
        val field = HaWebSocketClient::class.java.getDeclaredField("socket").apply { isAccessible = true }
        field.set(client, socket(sent))
        val authenticated = HaWebSocketClient::class.java.getDeclaredField("authenticated").apply { isAccessible = true }
        val subscribed = HaWebSocketClient::class.java.getDeclaredField("subscribed").apply { isAccessible = true }
        authenticated.set(client, true)
        subscribed.set(client, true)

        client.fireState(JSONObject().put("state", "error").put("volume_level", 0.7).put("media_title", "Title"))
        val state = JSONObject(sent.single()).getJSONObject("state")
        assertEquals("idle", state.getString("state"))
        assertEquals("Title", state.getString("media_title"))

        val method = HaWebSocketClient::class.java.getDeclaredMethod("toWebSocketUrl", String::class.java)
            .apply { isAccessible = true }
        assertEquals("wss://ha.example/api/websocket", method.invoke(client, "https://ha.example"))
        assertEquals("ws://ha.example/api/websocket", method.invoke(client, "ws://ha.example"))
    }
}
