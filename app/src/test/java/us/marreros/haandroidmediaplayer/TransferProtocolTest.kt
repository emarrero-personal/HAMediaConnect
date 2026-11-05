package us.marreros.haandroidmediaplayer

import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** Client side of feature negotiation, chunk results, and reports against a scripted HA socket. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransferProtocolTest {
    private class Harness(features: Any? = JSONArray(listOf("media_transfer_v1", "playback_report_v1", "future_v9"))) {
        val events = mutableListOf<ConnectionEvent>()
        val commands = mutableListOf<HaCommand>()
        val sent = mutableListOf<JSONObject>()
        var cancelled = false
        val identity: DeviceIdentity
        val client: HaWebSocketClient
        val socket: WebSocket = Proxy.newProxyInstance(WebSocket::class.java.classLoader, arrayOf(WebSocket::class.java)) { _, method, args ->
            when (method.name) {
                "send" -> { sent += JSONObject(args!![0] as String); true }
                "queueSize" -> 0L
                "cancel" -> { cancelled = true; null }
                else -> null
            }
        } as WebSocket
        val listener: WebSocketListener
        init {
            val context = RuntimeEnvironment.getApplication() as android.content.Context
            AppPrefs.prefs(context).edit().clear().commit()
            identity = DeviceIdentity(context, TestKeys().apply { create() })
            client = HaWebSocketClient("https://ha.example", "SECRET_TOKEN", "Pixel", identity, {}, commands::add, onConnectionEvent = events::add)
            HaWebSocketClient::class.java.getDeclaredField("stage").apply { isAccessible = true }.set(client, "ha_required")
            HaWebSocketClient::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(client, socket)
            val type = Class.forName("us.marreros.haandroidmediaplayer.HaWebSocketClient\$Listener")
            listener = type.getDeclaredConstructor(HaWebSocketClient::class.java).apply { isAccessible = true }.newInstance(client) as WebSocketListener
            message("{\"type\":\"auth_required\"}"); message("{\"type\":\"auth_ok\"}")
            result(1, JSONObject().put("device_id", identity.deviceId).put("expires_in", 30)
                .put("payload", "HAMediaConnect/v1\n${"a".repeat(43)}\nowner\n${identity.deviceId}"))
            val proof = JSONObject().put("device_id", identity.deviceId)
            features?.let { proof.put("features", it) }
            result(2, proof)
            result(3)
        }
        fun message(text: String) = listener.onMessage(socket, text)
        fun result(id: Int, result: JSONObject = JSONObject()) = message(JSONObject().put("type", "result").put("id", id).put("success", true).put("result", result).toString())
        fun error(id: Int, code: String) = message(JSONObject().put("type", "result").put("id", id).put("success", false)
            .put("error", JSONObject().put("code", code).put("message", "secret detail")).toString())
        fun event(data: JSONObject) = message(JSONObject().put("type", "event").put("id", 3).put("event", data).toString())
        val failed get() = events.any { it.failure != null }
    }
    private val id = "T".repeat(22)
    private val offerFields get() = JSONObject().put("action", "play_media").put("transfer_id", id).put("media_size", 100)
        .put("media_sha256", "a".repeat(64)).put("chunk_size", TransferOffer.CHUNK_SIZE)
        .put("media_content_type", "audio/mpeg").put("announce", true).put("request_id", "R".repeat(16))

    @Test fun negotiatesOnlyMutuallySupportedFeatures() {
        val h = Harness()
        assertEquals(JSONArray(listOf("media_transfer_v1", "playback_report_v1")).toString(), h.sent[3].getJSONArray("features").toString())
        assertTrue(h.client.supports(HaWebSocketClient.FEATURE_TRANSFER))
        assertEquals(ConnectionPhase.CONNECTED, h.events.last().phase)
        val legacy = Harness(null)
        assertFalse(legacy.sent[3].has("features")) // Older integrations reject unknown subscribe fields.
        assertFalse(legacy.client.supports(HaWebSocketClient.FEATURE_TRANSFER))
        assertFalse(legacy.client.requestChunk(id, 0) {})
        assertFalse(legacy.client.reportDelivery("R".repeat(16), "playing", null))
        assertFalse(Harness(JSONArray()).sent[3].has("features"))
        for (bad in listOf(JSONArray(List(9) { "x" }), JSONArray(listOf(1)), JSONArray(listOf("x".repeat(33))), "media_transfer_v1")) {
            val malformed = Harness(bad)
            assertEquals(ConnectionFailureKind.PROTOCOL, malformed.events.last().failure!!.kind)
            assertEquals(3, malformed.sent.size) // Never subscribes after a malformed proof result.
        }
    }

    @Test fun parsesTransferOfferAndRequestId() {
        val h = Harness()
        h.event(offerFields)
        val command = h.commands.single()
        assertEquals(PlaybackService.CMD_PLAY_MEDIA, command.command)
        assertNull(command.url)
        assertEquals(id, command.transfer!!.transferId)
        assertEquals(100, command.transfer!!.size)
        assertEquals("R".repeat(16), command.requestId)
        assertTrue(command.announce)
        h.event(JSONObject().put("action", "play_media").put("media_content_id", "https://e/a.mp3").put("request_id", "Q".repeat(16)))
        assertEquals("Q".repeat(16), h.commands.last().requestId)
        assertFalse(h.failed)
    }

    @Test fun malformedOrUnnegotiatedOffersAreProtocolErrors() {
        val cases = listOf<(JSONObject) -> JSONObject>(
            { it.put("media_content_id", "https://e/a.mp3") },           // Two delivery methods.
            { it.apply { remove("media_sha256") } },                     // Incomplete offer.
            { it.put("media_size", TransferOffer.MAX_BYTES + 1) },       // Oversized payload.
            { it.put("media_size", "100") },
            { it.put("chunk_size", 1024) },
            { it.put("media_content_type", "text/html") },
            { it.put("transfer_id", "../../etc/passwd/xxxxxx") },
            { it.put("request_id", "short") },
            { it.put("action", "pause") }
        )
        for (mutate in cases) {
            val h = Harness()
            h.event(mutate(offerFields))
            assertTrue(h.commands.isEmpty())
            assertEquals(ConnectionFailureKind.PROTOCOL, h.events.last().failure!!.kind)
        }
        val legacy = Harness(null)
        legacy.event(offerFields)
        assertTrue(legacy.commands.isEmpty())
        assertEquals(ConnectionFailureKind.PROTOCOL, legacy.events.last().failure!!.kind)
        val stray = Harness()
        stray.event(JSONObject().put("action", "play_media").put("media_content_id", "https://e/a.mp3").put("media_size", 10))
        assertEquals(ConnectionFailureKind.PROTOCOL, stray.events.last().failure!!.kind)
    }

    @Test fun chunkResultsUseTheirOwnSizeBoundAndAreStrictlyValidated() {
        val h = Harness()
        val results = mutableListOf<ChunkResult>()
        assertTrue(h.client.requestChunk(id, 0) { results += it })
        val request = h.sent.last()
        assertEquals("hamediaconnect/media_chunk", request.getString("type"))
        assertEquals(h.identity.deviceId, request.getString("device_id"))
        assertEquals(id, request.getString("transfer_id"))
        assertEquals(0, request.getInt("offset"))
        val bytes = ByteArray(TransferOffer.CHUNK_SIZE) { it.toByte() }
        val full = java.util.Base64.getEncoder().encodeToString(bytes)
        h.result(request.getInt("id"), JSONObject().put("offset", 0).put("data", full))
        assertArrayEquals(bytes, (results.single() as ChunkResult.Data).bytes)
        assertFalse(h.failed)
        // The larger bound applies only to an outstanding chunk request.
        h.message(JSONObject().put("type", "result").put("id", 99).put("success", true)
            .put("result", JSONObject().put("data", full)).toString())
        assertEquals(ConnectionFailureKind.PROTOCOL, h.events.last().failure!!.kind)

        for (bad in listOf<(Int) -> JSONObject>(
            { JSONObject().put("offset", 49152).put("data", "AAAA") },                // Different offset.
            { JSONObject().put("offset", 0).put("data", "AAAA").put("extra", 1) },     // Extra field.
            { JSONObject().put("offset", 0).put("data", "A*A=") },                     // Invalid Base64.
            { JSONObject().put("offset", 0).put("data", "AAA") },                      // Unpadded.
            { JSONObject().put("offset", 0).put("data", "A".repeat(TransferOffer.MAX_ENCODED_CHUNK + 4)) },
            { JSONObject().put("offset", 0).put("data", 5) }
        )) {
            val c = Harness()
            assertTrue(c.client.requestChunk(id, 0) {})
            c.result(c.sent.last().getInt("id"), bad(0))
            assertEquals(ConnectionFailureKind.PROTOCOL, c.events.last().failure!!.kind)
        }
    }

    @Test fun transferErrorsStayTransferScopedAndDisconnectAbandonsRequests() {
        val h = Harness()
        val results = mutableListOf<ChunkResult>()
        h.client.requestChunk(id, 0) { results += it }
        h.error(h.sent.last().getInt("id"), "media_unavailable")
        h.client.requestChunk(id, 0) { results += it }
        h.error(h.sent.last().getInt("id"), "temporarily_unavailable")
        assertSame(ChunkResult.Unavailable, results[0])
        assertSame(ChunkResult.Retry, results[1])
        assertFalse(h.failed)
        assertTrue(h.client.supports(HaWebSocketClient.FEATURE_TRANSFER))
        h.client.requestChunk(id, 49152) { results += it }
        h.listener.onFailure(h.socket, java.io.IOException("secret"), null)
        assertSame(ChunkResult.Disconnected, results.last())
        assertFalse(h.client.requestChunk(id, 0) {})
        val closed = Harness()
        closed.client.requestChunk(id, 0) { results += it }
        closed.client.close()
        assertSame(ChunkResult.Disconnected, results.last())
    }

    @Test fun reportsAndReleasesAreBestEffortAndBounded() {
        val h = Harness()
        assertTrue(h.client.reportDelivery("R".repeat(16), "failed", "unreachable"))
        val report = h.sent.last()
        assertEquals("hamediaconnect/playback_report", report.getString("type"))
        assertEquals("unreachable", report.getString("reason"))
        h.error(report.getInt("id"), "device_auth_failed")
        assertFalse(h.failed) // A rejected report never tears down the device connection.
        assertFalse(h.client.reportDelivery("bad", "playing", null))
        h.client.releaseTransfer(id)
        assertEquals("hamediaconnect/media_release", h.sent.last().getString("type"))
        h.client.releaseTransfer("../bad")
        assertEquals("hamediaconnect/media_release", h.sent.last().getString("type"))
        assertFalse(h.client.requestChunk("bad", 0) {})
        assertFalse(h.client.requestChunk(id, -1) {})
        var accepted = 0
        repeat(HaWebSocketClient.MAX_AUX_REQUESTS + 4) { if (h.client.requestChunk(id, 0) {}) accepted++ }
        assertEquals(HaWebSocketClient.MAX_AUX_REQUESTS - 1, accepted) // One release is still outstanding.
    }

    @Test fun stateAcknowledgmentsStillWorkAlongsideAuxiliaryRequests() {
        val h = Harness()
        h.client.requestChunk(id, 0) {}
        val chunkId = h.sent.last().getInt("id")
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(2))
        h.client.fireState(JSONObject().put("state", "playing").put("volume_level", 0.5))
        val stateId = h.sent.last().getInt("id")
        assertNotEquals(chunkId, stateId)
        h.result(stateId)
        h.result(chunkId, JSONObject().put("offset", 0).put("data", "AAAA"))
        assertFalse(h.failed)
    }
}
