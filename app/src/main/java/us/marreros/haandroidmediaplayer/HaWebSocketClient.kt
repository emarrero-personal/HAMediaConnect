package us.marreros.haandroidmediaplayer

import android.os.Handler
import android.os.Looper
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One HA connection, one proof, one command subscription; no application message queue. */
class HaWebSocketClient(
    private val baseUrl: String,
    private val token: String,
    private val deviceName: String,
    private val identity: DeviceSigner,
    private val onStatus: (String) -> Unit,
    private val onCommand: (HaCommand) -> Unit,
    private val allowHttp: Boolean = false
) {
    companion object {
        const val MAX_MESSAGE = 16384
        fun boundedJson(text: String): JSONObject {
            require(text.length <= MAX_MESSAGE && text.toByteArray().size <= MAX_MESSAGE)
            android.util.JsonReader(java.io.StringReader(text)).use { reader ->
                reader.isLenient = false
                fun value(depth: Int) {
                    require(depth <= 8)
                    when (reader.peek()) {
                        android.util.JsonToken.BEGIN_OBJECT -> {
                            reader.beginObject()
                            val names = mutableSetOf<String>()
                            while (reader.hasNext()) { require(names.add(reader.nextName())); value(depth + 1) }
                            reader.endObject()
                        }
                        android.util.JsonToken.BEGIN_ARRAY -> {
                            reader.beginArray(); while (reader.hasNext()) value(depth + 1); reader.endArray()
                        }
                        android.util.JsonToken.STRING -> reader.nextString()
                        android.util.JsonToken.NUMBER -> require(reader.nextString().toDouble().isFinite())
                        android.util.JsonToken.BOOLEAN -> reader.nextBoolean()
                        android.util.JsonToken.NULL -> reader.nextNull()
                        else -> error("Invalid JSON")
                    }
                }
                require(reader.peek() == android.util.JsonToken.BEGIN_OBJECT)
                value(0)
                require(reader.peek() == android.util.JsonToken.END_DOCUMENT)
            }
            return JSONObject(text)
        }
    }
    private val client = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { fail("Authentication timed out") }
    private var socket: WebSocket? = null
    private var stage = "new"
    private var nextId = 1
    private var requestId = -1
    private var subscriptionId = -1
    private var stateId = -1
    private var lastStateAt = 0L
    private var commandWindow = 0L
    private var commandCount = 0
    private val deviceId get() = identity.deviceId

    @Synchronized fun connect() {
        require(stage == "new")
        require(token.length in 1..8192 && deviceName.length in 1..128)
        stage = "ha_required"
        handler.postDelayed(timeout, 45000)
        socket = client.newWebSocket(Request.Builder().url(toWebSocketUrl(baseUrl)).build(), Listener())
        onStatus("Connecting…")
    }
    @Synchronized fun close() {
        stage = "closed"; handler.removeCallbacks(timeout)
        socket?.cancel(); socket = null
    }
    @Synchronized private fun fail(status: String) {
        if (stage == "closed") return
        stage = "closed"; handler.removeCallbacks(timeout)
        socket?.cancel(); socket = null
        onStatus(status)
    }
    private fun send(ws: WebSocket, msg: JSONObject) {
        val text = msg.toString()
        require(text.toByteArray().size <= MAX_MESSAGE && ws.queueSize() <= MAX_MESSAGE)
        check(ws.send(text))
    }
    private fun request(ws: WebSocket, type: String, fields: JSONObject) {
        requestId = nextId++
        send(ws, fields.put("id", requestId).put("type", "hamediaconnect/$type"))
    }
    @Synchronized fun fireState(event: JSONObject) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (stage != "ready" || stateId != -1 || now - lastStateAt < 1000) return
        val ws = socket ?: return
        try {
            val volume = event.optDouble("volume_level", 0.5)
            require(volume.isFinite() && volume in 0.0..1.0)
            val state = JSONObject().put("state", event.optString("state", "idle").let { if (it == "error") "idle" else it })
                .put("volume_level", volume).put("is_volume_muted", event.optBoolean("is_volume_muted"))
            for ((key, max) in listOf("media_title" to 512, "media_content_id" to 2048)) {
                val value = event.optString(key, "")
                if (value.isNotBlank() && value != "null") state.put(key, value.take(max))
            }
            stateId = nextId++
            send(ws, JSONObject().put("id", stateId).put("type", "hamediaconnect/state")
                .put("device_id", deviceId).put("state", state))
            lastStateAt = now
        } catch (_: Exception) { fail("Protocol rejected") }
    }
    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) = synchronized(this@HaWebSocketClient) {
            if (stage == "closed") return@synchronized
            try {
                val json = boundedJson(text)
                val type = json.get("type")
                require(type is String)
                val fields = when (type) {
                    "auth_required", "auth_ok" -> setOf("type", "ha_version")
                    "auth_invalid" -> setOf("type", "message")
                    "result" -> setOf("type", "id", "success", "result", "error")
                    "event" -> setOf("type", "id", "event")
                    else -> emptySet()
                }
                require(json.keys().asSequence().all { it in fields })
                if (json.has("id")) require(json.get("id") is Int)
                when (type) {
                    "auth_required" -> {
                        check(stage == "ha_required")
                        stage = "ha_auth"
                        send(webSocket, JSONObject().put("type", "auth").put("access_token", token))
                    }
                    "auth_ok" -> {
                        check(stage == "ha_auth")
                        stage = "challenge"
                        request(webSocket, "challenge", JSONObject().put("public_key", identity.publicKey).put("name", deviceName))
                    }
                    "auth_invalid" -> fail("Authentication failed")
                    "result" -> {
                        val id = json.getInt("id")
                        val success = json.get("success")
                        require(success is Boolean)
                        if (id == stateId && stage == "ready") {
                            stateId = -1
                            check(success)
                        } else {
                            check(id == requestId)
                            if (!success) { fail("Device authentication rejected; review registration"); return@synchronized }
                            when (stage) {
                                "challenge" -> {
                                    val result = json.getJSONObject("result")
                                    check(result.getString("device_id") == deviceId)
                                    val payload = result.getString("payload")
                                    check(payload.matches(Regex("HAMediaConnect/v1\\n[A-Za-z0-9_-]{43}\\n[A-Za-z0-9_-]{1,128}\\n$deviceId")))
                                    check(result.get("expires_in") is Int && result.getInt("expires_in") in 1..30)
                                    stage = "proof"
                                    val signature = try { identity.sign(payload) } catch (_: Exception) {
                                        fail("Device key unavailable; reset identity in Settings")
                                        return@synchronized
                                    }
                                    request(webSocket, "register", JSONObject().put("signature", signature))
                                }
                                "proof" -> {
                                    check(json.getJSONObject("result").getString("device_id") == deviceId)
                                    stage = "subscription"
                                    request(webSocket, "subscribe_commands", JSONObject().put("device_id", deviceId))
                                    subscriptionId = requestId
                                }
                                "subscription" -> {
                                    stage = "ready"; handler.removeCallbacks(timeout); onStatus("Connected")
                                }
                                else -> error("Unexpected response")
                            }
                        }
                    }
                    "event" -> {
                        check(stage == "ready" && json.getInt("id") == subscriptionId)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - commandWindow >= 60000) { commandWindow = now; commandCount = 0 }
                        check(++commandCount <= 120)
                        onCommand(parseCommand(json.getJSONObject("event")))
                    }
                    else -> error("Unexpected message")
                }
            } catch (_: Exception) { fail("Protocol rejected") }
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { fail("Protocol rejected") }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { fail("Disconnected") }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { fail("Disconnected") }
    }
    private fun toWebSocketUrl(base: String): String = TransportPolicy.base(base, allowHttp).newBuilder()
        .addPathSegments("api/websocket").build().toString().let {
            if (it.startsWith("https:")) "wss:" + it.removePrefix("https:") else "ws:" + it.removePrefix("http:")
        }
    private fun parseCommand(data: JSONObject): HaCommand {
        val allowed = setOf("action", "media_content_id", "media_title", "media_content_type", "volume_level", "is_volume_muted", "announce")
        require(data.keys().asSequence().all { it in allowed })
        val action = data.getString("action")
        require(action in setOf("play", "pause", "stop", "play_media", "volume_set", "volume_mute"))
        fun text(key: String, max: Int): String? = if (!data.has(key)) null else data.get(key).let {
            require(it is String && it.length in 1..max); it
        }
        fun flag(key: String): Boolean? = if (!data.has(key)) null else data.get(key).let { require(it is Boolean); it }
        val url = text("media_content_id", 2048)
        require(action != "play_media" || url != null)
        val volume = if (data.has("volume_level")) data.get("volume_level").let {
            require(it is Number && it.toDouble().isFinite() && it.toDouble() in 0.0..1.0); it.toFloat()
        } else null
        require(action != "volume_set" || volume != null)
        val muted = flag("is_volume_muted")
        require(action != "volume_mute" || muted != null)
        return HaCommand(if (action == "volume_set") PlaybackService.CMD_SET_VOLUME else action,
            url, text("media_title", 512), text("media_content_type", 128), volume, null, muted, flag("announce") ?: false)
    }
}
