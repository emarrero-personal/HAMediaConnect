package us.marreros.haandroidmediaplayer

import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.lang.reflect.Proxy
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HaWebSocketClientTest {
    private class Harness {
        val statuses = mutableListOf<String>()
        val commands = mutableListOf<HaCommand>()
        val sent = mutableListOf<JSONObject>()
        var queued = 0L
        var cancelled = false
        val keys = TestKeys().apply { create() }
        val identity: DeviceIdentity
        val client: HaWebSocketClient
        val socket: WebSocket = Proxy.newProxyInstance(WebSocket::class.java.classLoader, arrayOf(WebSocket::class.java)) { _, method, args ->
            when (method.name) {
                "send" -> { sent += JSONObject(args!![0] as String); true }
                "queueSize" -> queued
                "cancel" -> { cancelled = true; null }
                "close" -> true
                else -> null
            }
        } as WebSocket
        val listener: WebSocketListener
        init {
            val context = RuntimeEnvironment.getApplication() as android.content.Context
            AppPrefs.prefs(context).edit().clear().commit()
            identity = DeviceIdentity(context, keys)
            client = HaWebSocketClient("https://ha.example", "SECRET_TOKEN", "Pixel", identity, statuses::add, commands::add)
            set("stage", "ha_required"); set("socket", socket)
            val type = Class.forName("us.marreros.haandroidmediaplayer.HaWebSocketClient\$Listener")
            listener = type.getDeclaredConstructor(HaWebSocketClient::class.java).apply { isAccessible = true }.newInstance(client) as WebSocketListener
        }
        fun set(name: String, value: Any) = HaWebSocketClient::class.java.getDeclaredField(name).apply { isAccessible = true }.set(client, value)
        fun message(text: String) = listener.onMessage(socket, text)
        fun result(id: Int, result: JSONObject = JSONObject()) = message(JSONObject().put("type", "result").put("id", id).put("success", true).put("result", result).toString())
        fun start() { message("{\"type\":\"auth_required\"}"); message("{\"type\":\"auth_ok\"}") }
        fun ready() {
            start()
            result(1, JSONObject().put("device_id", identity.deviceId).put("expires_in", 30)
                .put("payload", "HAMediaConnect/v1\n${"a".repeat(43)}\nowner\n${identity.deviceId}"))
            result(2, JSONObject().put("device_id", identity.deviceId)); result(3)
        }
        fun event(data: JSONObject) = message(JSONObject().put("type", "event").put("id", 3).put("event", data).toString())
    }
    @Test fun signsFreshChallengeBeforeSubscribingAndMapsCommands() {
        val h = Harness(); h.ready()
        assertEquals("auth", h.sent[0].getString("type"))
        assertEquals("hamediaconnect/challenge", h.sent[1].getString("type"))
        assertEquals(h.identity.publicKey, h.sent[1].getString("public_key"))
        assertEquals("hamediaconnect/register", h.sent[2].getString("type"))
        val signed = android.util.Base64.decode(h.sent[2].getString("signature"), 0)
        assertTrue(java.security.Signature.getInstance("SHA256withECDSA").run {
            initVerify(h.keys.pair!!.public); update("HAMediaConnect/v1\n${"a".repeat(43)}\nowner\n${h.identity.deviceId}".toByteArray()); verify(signed)
        })
        assertEquals("hamediaconnect/subscribe_commands", h.sent[3].getString("type"))
        assertEquals("Connected", h.statuses.last())
        h.event(JSONObject().put("action", "play_media").put("media_content_id", "/song.mp3").put("media_title", "Song")
            .put("media_content_type", "audio/mpeg").put("volume_level", .4).put("is_volume_muted", false).put("announce", true))
        assertEquals(HaCommand("play_media", "/song.mp3", "Song", "audio/mpeg", .4f, null, false, true), h.commands.single())
        h.event(JSONObject().put("action", "volume_set").put("volume_level", .5))
        assertEquals(PlaybackService.CMD_SET_VOLUME, h.commands.last().command)
        h.event(JSONObject().put("action", "pause"))
        assertEquals("pause", h.commands.last().command)
    }
    @Test fun boundsStateQueueAndTruncatesMetadata() {
        val h = Harness(); h.ready(); ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        val event = JSONObject().put("state", "error").put("volume_level", .7).put("media_title", "a".repeat(600))
        h.client.fireState(event)
        val state = h.sent.last().getJSONObject("state")
        assertEquals("idle", state.getString("state")); assertEquals(512, state.getString("media_title").length)
        val count = h.sent.size
        for (i in 0..100) h.client.fireState(event)
        assertEquals(count, h.sent.size)
        h.result(4)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2)); h.client.fireState(event)
        assertEquals(count + 1, h.sent.size)
        h.result(5); h.queued = 20000
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2)); h.client.fireState(event)
        assertEquals("Protocol rejected", h.statuses.last()); assertTrue(h.cancelled)
    }
    @Test fun malformedUnexpectedAndOversizedMessagesFailClosedWithoutBodies() {
        for (message in listOf("not-json SECRET_TOKEN", "{}", "{'type':'auth_required'}", "{type:auth_required}",
            "{\"type\":\"auth_required\",\"type\":\"auth_required\"}", "{} trailing", "{\"type\":\"unexpected\"}", " ".repeat(20000),
            "{\"type\":\"auth_ok\"}", "{\"type\":\"result\",\"id\":99,\"success\":true}",
            "{\"nested\":".repeat(9) + "0" + "}".repeat(9))) {
            val h = Harness(); h.message(message)
            assertEquals("Protocol rejected", h.statuses.last())
            assertTrue(h.cancelled); assertTrue(h.commands.isEmpty())
            assertTrue(h.statuses.none { it.contains("SECRET_TOKEN") })
        }
        val binary = Harness(); binary.listener.onMessage(binary.socket, okio.ByteString.EMPTY)
        assertEquals("Protocol rejected", binary.statuses.last())
    }
    @Test fun invalidProofChallengesCannotBeUsedAsSigningOracle() {
        for (payload in listOf("arbitrary", "HAMediaConnect/v1\n${"a".repeat(43)}\nowner\nwrong")) {
            val h = Harness(); h.start()
            h.result(1, JSONObject().put("device_id", h.identity.deviceId).put("expires_in", 30).put("payload", payload))
            assertEquals(2, h.sent.size); assertEquals("Protocol rejected", h.statuses.last())
        }
        val h = Harness(); h.start(); h.keys.invalidated = true
        h.result(1, JSONObject().put("device_id", h.identity.deviceId).put("expires_in", 30)
            .put("payload", "HAMediaConnect/v1\n${"a".repeat(43)}\nowner\n${h.identity.deviceId}"))
        assertTrue(h.cancelled); assertEquals(2, h.sent.size)
    }
    @Test fun strictCommandSchemasRejectWrongTypesAndUnknownFields() {
        val invalid = listOf(
            JSONObject().put("action", "unknown"), JSONObject().put("action", "play_media"),
            JSONObject().put("action", "volume_set").put("volume_level", 2),
            JSONObject().put("action", "volume_set").put("volume_level", "0.5"),
            JSONObject().put("action", "volume_mute").put("is_volume_muted", "true"),
            JSONObject().put("action", "volume_mute"), JSONObject().put("action", "pause").put("extra", "sensitive"),
            JSONObject().put("action", "play_media").put("media_content_id", "a".repeat(2049)),
            JSONObject().put("action", "play_media").put("media_content_id", "/a").put("announce", 1)
        )
        for (data in invalid) {
            val h = Harness(); h.ready(); h.event(data)
            assertTrue(h.commands.isEmpty()); assertEquals("Protocol rejected", h.statuses.last())
        }
    }
    @Test fun authRejectionStopsConnectionAndLateMessagesAreIgnored() {
        val h = Harness(); h.message("{\"type\":\"auth_invalid\"}")
        assertEquals("Authentication failed", h.statuses.last())
        h.listener.onFailure(h.socket, RuntimeException("SECRET_TOKEN"), null)
        h.message("{\"type\":\"auth_ok\"}")
        assertEquals(listOf("Authentication failed"), h.statuses)
        assertTrue(h.sent.isEmpty())
        val rejected = Harness(); rejected.start()
        rejected.message("{\"type\":\"result\",\"id\":1,\"success\":false,\"error\":{\"message\":\"SECRET_TOKEN\"}}")
        assertEquals("Device authentication rejected; review registration", rejected.statuses.last())
        val disconnected = Harness(); disconnected.listener.onClosed(disconnected.socket, 1000, "SECRET_TOKEN")
        assertEquals("Disconnected", disconnected.statuses.last())
        val timedOut = Harness()
        (HaWebSocketClient::class.java.getDeclaredField("timeout").apply { isAccessible = true }.get(timedOut.client) as Runnable).run()
        assertEquals("Authentication timed out", timedOut.statuses.last())
        timedOut.client.close()
    }
    @Test fun boundsInboundCommandRateAndAllowsNextWindow() {
        val h = Harness(); h.ready()
        repeat(120) { h.event(JSONObject().put("action", "pause")) }
        assertEquals(120, h.commands.size)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        h.event(JSONObject().put("action", "pause"))
        repeat(120) { h.event(JSONObject().put("action", "pause")) }
        assertEquals(240, h.commands.size)
        assertEquals("Protocol rejected", h.statuses.last())
    }
    @Test fun missingStateAckClosesOnceAndRequestsBackoffReconnect() {
        val h = Harness(); h.ready()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        h.client.fireState(JSONObject().put("state", "idle"))
        val count = h.sent.size
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(HaWebSocketClient.STATE_ACK_TIMEOUT_MS - 1))
        assertFalse(h.cancelled)
        h.client.fireState(JSONObject().put("state", "playing"))
        assertEquals(count, h.sent.size) // Backpressure never queues intermediate states.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertTrue(h.cancelled)
        assertEquals(HaWebSocketClient.STATE_ACK_TIMEOUT_STATUS, h.statuses.last())
        assertTrue(TransportPolicy.shouldReconnect(h.statuses.last()))
        h.result(4) // A late acknowledgment cannot revive a closed client.
        h.listener.onFailure(h.socket, RuntimeException(), null)
        assertEquals(1, h.statuses.count { it == HaWebSocketClient.STATE_ACK_TIMEOUT_STATUS })
        val replacement = Harness(); replacement.ready()
        replacement.client.fireState(JSONObject().put("state", "idle"))
        assertEquals("hamediaconnect/state", replacement.sent.last().getString("type"))
        h.client.close(); replacement.client.close()
    }
    @Test fun matchingAcksCancelTheirDeadlineAndNewRequestsGetFreshDeadlines() {
        val h = Harness(); h.ready()
        val loop = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        loop.idleFor(Duration.ofSeconds(2))
        h.client.fireState(JSONObject().put("state", "idle"))
        loop.idleFor(Duration.ofSeconds(10)); h.result(4)
        h.client.fireState(JSONObject().put("state", "playing"))
        loop.idleFor(Duration.ofSeconds(5)) // Old deadline must not cancel the new request.
        assertFalse(h.cancelled)
        h.result(5)
        loop.idleFor(Duration.ofSeconds(20))
        assertFalse(h.cancelled)
        h.client.close()
    }
    @Test fun closeAndFailureCancelPendingStateDeadlines() {
        for (close in listOf(true, false)) {
            val h = Harness(); h.ready()
            val loop = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            loop.idleFor(Duration.ofSeconds(2)); h.client.fireState(JSONObject().put("state", "idle"))
            if (close) h.client.close() else h.listener.onFailure(h.socket, RuntimeException(), null)
            val statuses = h.statuses.toList()
            loop.idleFor(Duration.ofSeconds(20))
            assertEquals(statuses, h.statuses)
        }
        assertFalse(TransportPolicy.shouldReconnect("Authentication failed"))
        assertFalse(TransportPolicy.shouldReconnect("Protocol rejected"))
        assertTrue(TransportPolicy.shouldReconnect("Disconnected"))
        assertTrue(TransportPolicy.shouldReconnect("Authentication timed out"))
    }
    @Test fun controlUrlsUseExplicitHttpPolicyAndDisableRedirects() {
        val h = Harness()
        val method = HaWebSocketClient::class.java.getDeclaredMethod("toWebSocketUrl", String::class.java).apply { isAccessible = true }
        assertEquals("wss://ha.example/api/websocket", method.invoke(h.client, "https://ha.example/"))
        try { method.invoke(h.client, "http://ha.example"); fail("HTTP must require opt-in") } catch (_: java.lang.reflect.InvocationTargetException) { }
        val httpClient = HaWebSocketClient("http://ha.local:8123", "token", "Pixel", h.identity, {}, {}, true)
        assertEquals("ws://ha.local:8123/api/websocket", method.invoke(httpClient, "http://ha.local:8123"))
        val transport = HaWebSocketClient::class.java.getDeclaredField("client").apply { isAccessible = true }.get(h.client) as okhttp3.OkHttpClient
        assertFalse(transport.followRedirects); assertFalse(transport.followSslRedirects)
    }
}
