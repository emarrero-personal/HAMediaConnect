package us.marreros.haandroidmediaplayer

import android.os.Handler
import android.os.Looper
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

interface HaConnection : ChunkSource {
    fun connect()
    fun close()
    fun fireState(event: JSONObject)
    /** Features both sides negotiated for the current, ready connection. */
    fun supports(feature: String): Boolean = false
    override fun requestChunk(transferId: String, offset: Int, callback: (ChunkResult) -> Unit): Boolean = false
    /** Best effort; returns whether the report was sent. Never affects connection state. */
    fun reportDelivery(requestId: String, phase: String, reason: String?): Boolean = false
}

/** One HA connection, one proof, one command subscription; no application message queue. */
class HaWebSocketClient(
    private val baseUrl: String,
    private val token: String,
    private val deviceName: String,
    private val identity: DeviceSigner,
    private val onStatus: (String) -> Unit,
    private val onCommand: (HaCommand) -> Unit,
    private val allowHttp: Boolean = false,
    private val onConnectionEvent: (ConnectionEvent) -> Unit = {}
) : HaConnection {
    companion object {
        const val MAX_MESSAGE = 16384
        const val STATE_ACK_TIMEOUT_MS = 15000L
        const val STATE_ACK_TIMEOUT_STATUS = "State acknowledgment timed out"
        /**
         * Only a result for an outstanding chunk request may use this larger frame bound. JSON
         * encoders may legally escape every Base64 '/' as "\/", so the raw frame allows twice the
         * encoded chunk; the decoded `data` string itself is still capped at 64 KiB.
         */
        const val MAX_CHUNK_MESSAGE = TransferOffer.MAX_ENCODED_CHUNK * 2 + 1024
        const val MAX_AUX_REQUESTS = 16
        const val FEATURE_TRANSFER = "media_transfer_v1"
        const val FEATURE_REPORT = "playback_report_v1"
        val SUPPORTED_FEATURES = setOf(FEATURE_TRANSFER, FEATURE_REPORT)
        private val REQUEST_ID = Regex("[A-Za-z0-9_-]{16}")
        fun boundedJson(text: String, limit: Int = MAX_MESSAGE): JSONObject {
            require(text.length <= limit && text.toByteArray().size <= limit)
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
    private val timeout = Runnable { fail(ConnectionFailure(ConnectionFailureKind.HANDSHAKE_TIMEOUT)) }
    private var socket: WebSocket? = null
    private var stage = "new"
    private var nextId = 1
    private var requestId = -1
    private var subscriptionId = -1
    private var stateId = -1
    private var stateTimeout: Runnable? = null
    private var lastStateAt = 0L
    private var commandWindow = 0L
    private var commandCount = 0
    private val deviceId get() = identity.deviceId
    private var negotiated: Set<String> = emptySet()
    private val auxRequests = mutableMapOf<Int, (Boolean, JSONObject) -> Unit>()
    private val chunkRequests = mutableMapOf<Int, (ChunkResult) -> Unit>()

    @Synchronized override fun connect() {
        require(stage == "new")
        require(token.length in 1..8192 && deviceName.length in 1..128)
        stage = "ha_required"
        handler.postDelayed(timeout, 45000)
        socket = client.newWebSocket(Request.Builder().url(toWebSocketUrl(baseUrl)).build(), Listener())
        emit(ConnectionEvent(ConnectionPhase.CONNECTING))
    }
    @Synchronized override fun close() {
        stage = "closed"; handler.removeCallbacks(timeout); clearStateDeadline()
        socket?.cancel(); socket = null
        abandonRequests()
    }
    @Synchronized private fun fail(failure: ConnectionFailure) {
        if (stage == "closed") return
        stage = "closed"; handler.removeCallbacks(timeout); clearStateDeadline()
        socket?.cancel(); socket = null
        abandonRequests()
        emit(ConnectionEvent.failed(failure))
    }
    private fun abandonRequests() {
        val callbacks = chunkRequests.values.toList()
        chunkRequests.clear(); auxRequests.clear()
        callbacks.forEach { it(ChunkResult.Disconnected) }
    }
    @Synchronized override fun supports(feature: String) = stage == "ready" && feature in negotiated
    private fun auxiliary(type: String, fields: JSONObject, handler: (Boolean, JSONObject) -> Unit): Int? {
        val ws = socket ?: return null
        if (stage != "ready" || auxRequests.size >= MAX_AUX_REQUESTS) return null
        val id = nextId++
        return try {
            send(ws, fields.put("id", id).put("type", "hamediaconnect/$type").put("device_id", deviceId))
            auxRequests[id] = handler
            id
        } catch (_: Exception) { null } // Full send buffer: the caller retries later.
    }
    @Synchronized override fun requestChunk(transferId: String, offset: Int, callback: (ChunkResult) -> Unit): Boolean {
        if (!supports(FEATURE_TRANSFER) || !transferId.matches(TransferOffer.ID) || offset < 0) return false
        val id = auxiliary("media_chunk", JSONObject().put("transfer_id", transferId).put("offset", offset)) { success, json ->
            chunkRequests.remove(json.getInt("id"))
            callback(if (success) chunkData(json, offset) else when (errorCode(json)) {
                "temporarily_unavailable" -> ChunkResult.Retry
                else -> ChunkResult.Unavailable
            })
        } ?: return false
        chunkRequests[id] = callback
        return true
    }
    @Synchronized override fun releaseTransfer(transferId: String) {
        if (supports(FEATURE_TRANSFER) && transferId.matches(TransferOffer.ID))
            auxiliary("media_release", JSONObject().put("transfer_id", transferId)) { _, _ -> }
    }
    @Synchronized override fun reportDelivery(requestId: String, phase: String, reason: String?): Boolean {
        if (!supports(FEATURE_REPORT) || !requestId.matches(REQUEST_ID)) return false
        val fields = JSONObject().put("request_id", requestId).put("phase", phase)
        reason?.let { fields.put("reason", it) }
        // A rejected report is informational only; it never closes the device connection.
        return auxiliary("playback_report", fields) { _, _ -> } != null
    }
    private fun errorCode(json: JSONObject): String? {
        val error = json.optJSONObject("error") ?: return null
        if (error.has("code")) require(error.get("code") is String && error.getString("code").length in 1..128)
        return error.optString("code")
    }
    private fun chunkData(json: JSONObject, offset: Int): ChunkResult {
        val result = json.getJSONObject("result")
        require(result.keys().asSequence().toSet() == setOf("offset", "data"))
        require(result.get("offset") == offset)
        val data = result.get("data")
        require(data is String && data.length in 4..TransferOffer.MAX_ENCODED_CHUNK && data.length % 4 == 0)
        return ChunkResult.Data(offset, java.util.Base64.getDecoder().decode(data))
    }
    private fun emit(event: ConnectionEvent) {
        onConnectionEvent(event)
        onStatus(event.message)
    }
    private fun failureStage() = when (stage) {
        "challenge", "proof" -> FailureStage.REGISTRATION
        "subscription" -> FailureStage.SUBSCRIPTION
        "ready" -> FailureStage.STATE
        else -> FailureStage.HANDSHAKE
    }
    private fun clearStateDeadline() {
        stateTimeout?.let(handler::removeCallbacks)
        stateTimeout = null
        stateId = -1
    }
    private fun awaitStateAcknowledgment(id: Int) {
        val deadline = Runnable {
            synchronized(this) {
                if (stage == "ready" && stateId == id) fail(ConnectionFailure(ConnectionFailureKind.STATE_TIMEOUT))
            }
        }
        stateTimeout = deadline
        handler.postDelayed(deadline, STATE_ACK_TIMEOUT_MS)
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
    @Synchronized override fun fireState(event: JSONObject) {
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
            awaitStateAcknowledgment(stateId)
            lastStateAt = now
        } catch (_: Exception) { fail(ConnectionFailure(ConnectionFailureKind.PROTOCOL)) }
    }
    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) = synchronized(this@HaWebSocketClient) {
            if (stage == "closed") return@synchronized
            try {
                val json = boundedJson(text, if (chunkRequests.isEmpty()) MAX_MESSAGE else MAX_CHUNK_MESSAGE)
                val type = json.get("type")
                require(type is String)
                if (text.length > MAX_MESSAGE || text.toByteArray().size > MAX_MESSAGE)
                    require(type == "result" && json.opt("id").let { it is Int && it in chunkRequests })
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
                    "auth_invalid" -> fail(ConnectionFailure(ConnectionFailureKind.HA_REJECTED))
                    "result" -> {
                        val id = json.getInt("id")
                        val success = json.get("success")
                        require(success is Boolean)
                        val aux = auxRequests.remove(id)
                        if (aux != null) {
                            check(stage == "ready")
                            aux(success, json)
                        } else if (id == stateId && stage == "ready") {
                            clearStateDeadline()
                            if (!success) {
                                fail(ConnectionFailure.response(json.optJSONObject("error")?.optString("code"), FailureStage.STATE))
                                return@synchronized
                            }
                        } else {
                            check(id == requestId)
                            if (!success) {
                                val error = json.optJSONObject("error")
                                if (error?.has("code") == true) require(error.get("code") is String && error.getString("code").length in 1..128)
                                val code = error?.optString("code")
                                fail(ConnectionFailure.response(code, failureStage()))
                                return@synchronized
                            }
                            when (stage) {
                                "challenge" -> {
                                    val result = json.getJSONObject("result")
                                    check(result.getString("device_id") == deviceId)
                                    val payload = result.getString("payload")
                                    check(payload.matches(Regex("HAMediaConnect/v1\\n[A-Za-z0-9_-]{43}\\n[A-Za-z0-9_-]{1,128}\\n$deviceId")))
                                    check(result.get("expires_in") is Int && result.getInt("expires_in") in 1..30)
                                    stage = "proof"
                                    val signature = try { identity.sign(payload) } catch (_: Exception) {
                                        fail(ConnectionFailure(ConnectionFailureKind.KEY_UNAVAILABLE))
                                        return@synchronized
                                    }
                                    request(webSocket, "register", JSONObject().put("signature", signature))
                                }
                                "proof" -> {
                                    val result = json.getJSONObject("result")
                                    check(result.getString("device_id") == deviceId)
                                    // Older integrations advertise nothing; never send fields they would reject.
                                    val offered = result.opt("features")?.let { list ->
                                        require(list is org.json.JSONArray && list.length() <= 8)
                                        (0 until list.length()).map { index ->
                                            list.get(index).also { require(it is String && it.length in 1..32) } as String
                                        }.toSet()
                                    } ?: emptySet()
                                    negotiated = offered intersect SUPPORTED_FEATURES
                                    stage = "subscription"
                                    val fields = JSONObject().put("device_id", deviceId)
                                    if (negotiated.isNotEmpty()) fields.put("features", org.json.JSONArray(negotiated.sorted()))
                                    request(webSocket, "subscribe_commands", fields)
                                    subscriptionId = requestId
                                }
                                "subscription" -> {
                                    stage = "ready"; handler.removeCallbacks(timeout); emit(ConnectionEvent(ConnectionPhase.CONNECTED))
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
            } catch (_: Exception) { fail(ConnectionFailure(ConnectionFailureKind.PROTOCOL)) }
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { fail(ConnectionFailure(ConnectionFailureKind.PROTOCOL)) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { fail(ConnectionFailure.transport(t)) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { fail(ConnectionFailure(ConnectionFailureKind.TRANSPORT)) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { fail(ConnectionFailure(ConnectionFailureKind.TRANSPORT)) }
    }
    private fun toWebSocketUrl(base: String): String = TransportPolicy.base(base, allowHttp).newBuilder()
        .addPathSegments("api/websocket").build().toString().let {
            if (it.startsWith("https:")) "wss:" + it.removePrefix("https:") else "ws:" + it.removePrefix("http:")
        }
    private fun parseCommand(data: JSONObject): HaCommand {
        val allowed = setOf("action", "media_content_id", "media_title", "media_content_type", "volume_level", "is_volume_muted", "announce") +
            (if (FEATURE_REPORT in negotiated) setOf("request_id") else emptySet()) +
            (if (FEATURE_TRANSFER in negotiated) setOf("transfer_id", "media_size", "media_sha256", "chunk_size") else emptySet())
        require(data.keys().asSequence().all { it in allowed })
        val action = data.getString("action")
        require(action in setOf("play", "pause", "stop", "play_media", "volume_set", "volume_mute"))
        fun text(key: String, max: Int): String? = if (!data.has(key)) null else data.get(key).let {
            require(it is String && it.length in 1..max); it
        }
        fun flag(key: String): Boolean? = if (!data.has(key)) null else data.get(key).let { require(it is Boolean); it }
        fun integer(key: String): Int = data.get(key).let { require(it is Int); it }
        val url = text("media_content_id", 2048)
        val requestId = text("request_id", 16)?.also { require(it.matches(REQUEST_ID)) }
        val transfer = if (!data.has("transfer_id")) null else {
            require(action == "play_media" && url == null)
            TransferOffer(text("transfer_id", 22)!!, integer("media_size"), text("media_sha256", 64)!!,
                integer("chunk_size"), text("media_content_type", 128) ?: "")
        }
        require(transfer == null || setOf("media_size", "media_sha256", "chunk_size").all(data::has))
        require(transfer != null || setOf("media_size", "media_sha256", "chunk_size").none(data::has))
        require(action == "play_media" || requestId == null)
        require(action != "play_media" || (url != null) != (transfer != null))
        val volume = if (data.has("volume_level")) data.get("volume_level").let {
            require(it is Number && it.toDouble().isFinite() && it.toDouble() in 0.0..1.0); it.toFloat()
        } else null
        require(action != "volume_set" || volume != null)
        val muted = flag("is_volume_muted")
        require(action != "volume_mute" || muted != null)
        return HaCommand(if (action == "volume_set") PlaybackService.CMD_SET_VOLUME else action,
            url, text("media_title", 512), text("media_content_type", 128), volume, null, muted, flag("announce") ?: false,
            requestId, transfer)
    }
}
