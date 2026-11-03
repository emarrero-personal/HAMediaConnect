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
    private val deviceName: String,
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
    private var subscribed = false
    private var subscriptionId = -1
    private var registrationId = -1

    fun connect() {
        authenticated = false
        subscribed = false
        subscriptionId = -1
        registrationId = -1
        val wsUrl = toWebSocketUrl(baseUrl)
        onStatus("Connecting…")
        socket = client.newWebSocket(Request.Builder().url(wsUrl).build(), Listener())
    }

    fun close() {
        authenticated = false
        subscribed = false
        socket?.close(1000, "Service stopping")
        socket = null
    }

    fun fireState(eventData: JSONObject) {
        if (!authenticated || !subscribed) return
        val state = JSONObject().put("state", eventData.optString("state", "idle").let { if (it == "error") "idle" else it })
            .put("volume_level", eventData.optDouble("volume_level", 0.5))
            .put("is_volume_muted", eventData.optBoolean("is_volume_muted", false))
        for (key in listOf("media_title", "media_content_id")) {
            val value = eventData.optString(key, "")
            if (value.isNotBlank() && value != "null") state.put(key, value)
        }
        val msg = JSONObject()
            .put("id", ids.getAndIncrement())
            .put("type", "hamediaconnect/state")
            .put("device_id", deviceId)
            .put("state", state)
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
                        register(webSocket)
                        onStatus("Authenticated; registering…")
                    }
                    "auth_invalid" -> {
                        authenticated = false
                        subscribed = false
                        onStatus("Authentication failed")
                    }
                    "event" -> if (subscribed && json.optInt("id") == subscriptionId) handleNativeEvent(json)
                    "result" -> {
                        val id = json.optInt("id", -1)
                        val success = json.optBoolean("success", false)
                        if (id == registrationId) {
                            if (success) {
                                subscribe(webSocket)
                                onStatus("Registered; subscribing…")
                            } else {
                                onStatus("Registration rejected: ${json.optJSONObject("error")?.optString("code")}")
                            }
                        } else if (id == subscriptionId) {
                            subscribed = success
                            if (success) {
                                onStatus("Connected")
                            } else {
                                val error = json.optJSONObject("error")
                                val code = error?.optString("code", "unknown") ?: "unknown"
                                onStatus("Subscription rejected: $code")
                                Log.w(TAG, "Command subscription rejected: $code")
                            }
                        } else if (!success) {
                            val code = json.optJSONObject("error")?.optString("code", "unknown") ?: "unknown"
                            Log.w(TAG, "HA command failed (id=$id): $code")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Bad websocket message: $text", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            authenticated = false
            subscribed = false
            onStatus("Disconnected: ${t.message ?: "network error"}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            authenticated = false
            subscribed = false
            onStatus("Disconnected")
        }
    }

    private fun register(webSocket: WebSocket) {
        registrationId = ids.getAndIncrement()
        webSocket.send(JSONObject()
            .put("id", registrationId)
            .put("type", "hamediaconnect/register")
            .put("device_id", deviceId)
            .put("name", deviceName)
            .toString())
    }

    private fun subscribe(webSocket: WebSocket) {
        subscriptionId = ids.getAndIncrement()
        val msg = JSONObject()
            .put("id", subscriptionId)
            .put("type", "hamediaconnect/subscribe_commands")
            .put("device_id", deviceId)
        webSocket.send(msg.toString())
    }

    private fun handleNativeEvent(root: JSONObject) {
        val data = root.optJSONObject("event") ?: return
        val action = data.optString("action")
        val mapped = when (action) {
            "volume_set" -> PlaybackService.CMD_SET_VOLUME
            "volume_mute" -> PlaybackService.CMD_MUTE
            "play", "pause", "stop", "play_media" -> action
            else -> return
        }
        onCommand(HaCommand(
            command = mapped,
            url = data.optString("media_content_id").takeIf { it.isNotBlank() },
            title = data.optString("media_title").takeIf { it.isNotBlank() },
            contentType = data.optString("media_content_type").takeIf { it.isNotBlank() },
            volume = if (data.has("volume_level")) data.optDouble("volume_level").toFloat() else null,
            position = null,
            muted = if (data.has("is_volume_muted")) data.optBoolean("is_volume_muted") else null,
            announce = data.optBoolean("announce", false)
        ))
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
