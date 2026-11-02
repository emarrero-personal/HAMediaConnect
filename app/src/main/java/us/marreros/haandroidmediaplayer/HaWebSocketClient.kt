package us.marreros.haandroidmediaplayer

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HaWebSocketClient(
    private val baseUrl: String,
    private val token: String,
    private val deviceId: String,
    private val onStatus: (String) -> Unit,
    private val onCommand: (HaCommand) -> Unit
) {
    companion object {
        const val COMMAND_EVENT = "ha_android_media_player_command"
        const val STATE_EVENT = "ha_android_media_player_state"
        private const val TAG = "HaWebSocketClient"
    }

    private val ids = AtomicInteger(1)
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var socket: WebSocket? = null
    private var authenticated = false

    fun connect() {
        authenticated = false
        val wsUrl = toWebSocketUrl(baseUrl)
        onStatus("Connecting…")
        socket = client.newWebSocket(Request.Builder().url(wsUrl).build(), Listener())
    }

    fun close() {
        authenticated = false
        socket?.close(1000, "Service stopping")
        socket = null
    }

    fun fireState(eventData: JSONObject) {
        if (!authenticated) return
        val msg = JSONObject()
            .put("id", ids.getAndIncrement())
            .put("type", "fire_event")
            .put("event_type", STATE_EVENT)
            .put("event_data", eventData)
        socket?.send(msg.toString())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            onStatus("Socket open; authenticating…")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = JSONObject(text)
                when (json.optString("type")) {
                    "auth_required" -> {
                        webSocket.send(JSONObject()
                            .put("type", "auth")
                            .put("access_token", token)
                            .toString())
                    }
                    "auth_ok" -> {
                        authenticated = true
                        subscribe(webSocket)
                        onStatus("Connected")
                    }
                    "auth_invalid" -> {
                        authenticated = false
                        onStatus("Authentication failed")
                    }
                    "event" -> handleEvent(json)
                    "result" -> {
                        if (!json.optBoolean("success", true)) {
                            Log.w(TAG, "HA command failed: $text")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Bad websocket message: $text", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            authenticated = false
            onStatus("Disconnected: ${t.message ?: "network error"}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            authenticated = false
            onStatus("Disconnected")
        }
    }

    private fun subscribe(webSocket: WebSocket) {
        val msg = JSONObject()
            .put("id", ids.getAndIncrement())
            .put("type", "subscribe_events")
            .put("event_type", COMMAND_EVENT)
        webSocket.send(msg.toString())
    }

    private fun handleEvent(root: JSONObject) {
        val event = root.optJSONObject("event") ?: return
        val data = event.optJSONObject("data") ?: return
        val target = data.optString("target", "*")
        if (target != "*" && target != deviceId) return

        val cmd = data.optString("command")
        if (cmd.isBlank()) return
        onCommand(
            HaCommand(
                command = cmd,
                url = data.optString("url").takeIf { it.isNotBlank() },
                title = data.optString("title").takeIf { it.isNotBlank() },
                contentType = data.optString("content_type").takeIf { it.isNotBlank() },
                volume = if (data.has("volume")) data.optDouble("volume").toFloat() else null,
                position = if (data.has("position")) data.optLong("position") else null
            )
        )
    }

    private fun toWebSocketUrl(base: String): String {
        val normalized = base.trim().trimEnd('/')
        return when {
            normalized.startsWith("https://") -> "wss://${normalized.removePrefix("https://")}/api/websocket"
            normalized.startsWith("http://") -> "ws://${normalized.removePrefix("http://")}/api/websocket"
            normalized.startsWith("wss://") || normalized.startsWith("ws://") -> "$normalized/api/websocket"
            else -> "wss://$normalized/api/websocket"
        }
    }
}
