package us.marreros.haandroidmediaplayer

import android.content.Context
import android.content.Intent
import android.net.Network
import android.os.Looper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectionServiceTest {
    private class FakeConnection(val status: (ConnectionEvent) -> Unit) : HaConnection {
        var closed = false
        override fun connect() { status(ConnectionEvent(ConnectionPhase.CONNECTING)) }
        override fun close() { closed = true }
        override fun fireState(event: JSONObject) {}
        fun connected() = status(ConnectionEvent(ConnectionPhase.CONNECTED))
        fun fail(kind: ConnectionFailureKind) = status(ConnectionEvent.failed(ConnectionFailure(kind)))
    }
    private fun network(id: Int): Network = Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
        .apply { isAccessible = true }.newInstance(id)
    private fun field(service: ConnectionService, name: String) = ConnectionService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)
    private fun drain(service: ConnectionService) {
        val scheduler = field(service, "scheduler") as ScheduledExecutorService
        repeat(2) { scheduler.submit {}.get(5, TimeUnit.SECONDS) }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun change(service: ConnectionService, network: Network?) {
        ConnectionService::class.java.getDeclaredMethod("networkChanged", Network::class.java).apply { isAccessible = true }.invoke(service, network)
        drain(service)
    }
    private fun retryNow(service: ConnectionService) {
        (field(service, "scheduler") as ScheduledExecutorService).submit {
            ConnectionService::class.java.getDeclaredMethod("connect").apply { isAccessible = true }.invoke(service)
        }.get(5, TimeUnit.SECONDS)
        drain(service)
    }
    private inner class Harness(val authMethod: String = "token", resetPrefs: Boolean = true) : AutoCloseable {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = AppPrefs.prefs(context).also {
            if (resetPrefs) it.edit().clear().putString(AppPrefs.KEY_URL, "https://remote.ui.nabu.casa").commit()
        }
        val controller = Robolectric.buildService(ConnectionService::class.java).create()
        val service = controller.get()
        val credentials = MemorySecrets().apply {
            put("credential_origin", "https://remote.ui.nabu.casa"); put("auth_method", authMethod)
            if (authMethod == "token") put("long_lived_token", "fixture-token")
            else { put("access_token", "expired-token"); put("refresh_token", "fixture-refresh") }
        }
        val signer = DeviceIdentity(context, TestKeys().apply { create() })
        val sockets = mutableListOf<FakeConnection>()
        var refreshCount = 0
        init {
            service.tokenStoreFactory = { credentials }
            service.identityFactory = { signer }
            service.refreshCredentials = { origin, refresh ->
                assertEquals("https://remote.ui.nabu.casa", origin)
                assertEquals("fixture-refresh", refresh)
                refreshCount++
                JSONObject().put("access_token", "fixture-token").put("expires_in", 1800)
            }
            service.createConnection = { url, token, _, identity, status, _, allowHttp ->
                assertEquals("https://remote.ui.nabu.casa", url)
                assertEquals(credentials.get(if (authMethod == "token") "long_lived_token" else "access_token"), token)
                assertSame(signer, identity); assertFalse(allowHttp)
                FakeConnection(status).also { sockets += it }
            }
        }
        fun start(action: String? = null) { service.onStartCommand(Intent().setAction(action), 0, 1); drain(service) }
        fun connected() { sockets.last().connected(); drain(service) }
        fun fail(kind: ConnectionFailureKind) { sockets.last().fail(kind); drain(service) }
        override fun close() { controller.destroy() }
    }
    @Test fun tokenNetworkTransitionsReuseCredentialsAndKeyCancelStaleSocketsAndRecover() = transitions("token")
    @Test fun oauthNetworkTransitionsRefreshOnceAndReuseCredentialsAndKey() = transitions("oauth")
    private fun transitions(authMethod: String) {
        Harness(authMethod).use { h ->
            h.start(); h.connected()
            for (network in listOf(network(101), network(102), null, network(103))) {
                val previous = h.sockets.last()
                change(h.service, network)
                assertTrue(previous.closed)
                previous.fail(ConnectionFailureKind.DEVICE_REJECTED); drain(h.service)
                assertFalse(field(h.service, "authenticationBlocked") as Boolean)
                if (network != null) {
                    h.connected()
                    assertEquals("CONNECTED", h.prefs.getString(AppPrefs.KEY_CONNECTION_PHASE, ""))
                    val count = h.sockets.size
                    change(h.service, network)
                    h.start() // Duplicate service/network notifications cannot create sessions.
                    assertEquals(count, h.sockets.size)
                } else assertNull(field(h.service, "reconnectFuture"))
            }
            assertEquals(4, h.sockets.size)
            assertEquals("fixture-token", h.credentials.get(if (authMethod == "token") "long_lived_token" else "access_token"))
            assertEquals(if (authMethod == "oauth") 1 else 0, h.refreshCount)
        }
    }
    @Test fun recoverableFailuresContinueRetryingAndOldCallbacksCannotOverrideNewConnection() {
        Harness().use { h ->
            h.start(); h.connected()
            repeat(12) { index ->
                val kind = listOf(ConnectionFailureKind.DNS, ConnectionFailureKind.TIMEOUT,
                    ConnectionFailureKind.HANDSHAKE_TIMEOUT, ConnectionFailureKind.TEMPORARY,
                    ConnectionFailureKind.SUBSCRIPTION_BUSY, ConnectionFailureKind.STATE_TIMEOUT)[index % 6]
                h.fail(kind)
                assertNotNull(field(h.service, "reconnectFuture"))
                assertEquals("RECONNECTING", h.prefs.getString(AppPrefs.KEY_CONNECTION_PHASE, ""))
                val previous = h.sockets.last()
                retryNow(h.service)
                assertTrue(previous.closed)
                h.connected()
                previous.fail(ConnectionFailureKind.DEVICE_REJECTED); drain(h.service)
                assertEquals("CONNECTED", h.prefs.getString(AppPrefs.KEY_CONNECTION_PHASE, ""))
            }
        }
    }
    @Test fun confirmedPermanentRejectionWaitsForExplicitCredentialCorrection() {
        for (kind in listOf(ConnectionFailureKind.DEVICE_REJECTED, ConnectionFailureKind.HA_REJECTED, ConnectionFailureKind.PROTOCOL)) Harness().use { h ->
            h.start(); h.fail(kind)
            change(h.service, network(200))
            assertTrue(field(h.service, "authenticationBlocked") as Boolean)
            assertNull(field(h.service, "reconnectFuture"))
            assertEquals(1, h.sockets.size)
            h.credentials.put("long_lived_token", "corrected-fixture-token")
            h.start(ConnectionService.ACTION_RECONNECT); h.connected()
            assertEquals(2, h.sockets.size)
            assertFalse(field(h.service, "authenticationBlocked") as Boolean)
        }
    }
    @Test fun transientRefreshBacksOffButRejectedRefreshRequiresCorrection() {
        Harness("oauth").use { h ->
            h.service.refreshCredentials = { _, _ -> throw java.io.IOException("secret") }
            h.start()
            assertNotNull(field(h.service, "reconnectFuture"))
            assertTrue(h.sockets.isEmpty())
            assertFalse(h.prefs.getString(AppPrefs.KEY_STATUS, "")!!.contains("secret"))
            h.service.refreshCredentials = { _, _ -> throw OAuthRejected() }
            retryNow(h.service)
            assertTrue(field(h.service, "authenticationBlocked") as Boolean)
            assertNull(field(h.service, "reconnectFuture"))
            h.credentials.put("access_token", "corrected-fixture-token")
            h.prefs.edit().putLong("oauth_expiry", System.currentTimeMillis() + 3_600_000).commit()
            h.start(ConnectionService.ACTION_RECONNECT); h.connected()
            assertEquals(1, h.sockets.size)
        }
    }
    @Test fun credentialCorrectionDuringRefreshCannotBeOverwrittenByStaleResult() {
        Harness("oauth").use { h ->
            h.service.refreshCredentials = { _, _ ->
                h.credentials.put("refresh_token", "corrected-refresh")
                JSONObject().put("access_token", "stale-token").put("expires_in", 1800)
            }
            h.start()
            assertTrue(h.sockets.isEmpty())
            assertEquals("expired-token", h.credentials.get("access_token"))
            assertEquals("corrected-refresh", h.credentials.get("refresh_token"))
        }
    }
    @Test fun permanentBlockSurvivesServiceRestartUntilExplicitCorrection() {
        Harness().use { h -> h.start(); h.fail(ConnectionFailureKind.DEVICE_REJECTED) }
        Harness(resetPrefs = false).use { h ->
            h.start()
            change(h.service, network(301))
            assertTrue(h.sockets.isEmpty())
            assertTrue(field(h.service, "authenticationBlocked") as Boolean)
            h.credentials.put("long_lived_token", "corrected-fixture-token")
            h.start(ConnectionService.ACTION_RECONNECT); h.connected()
            assertEquals(1, h.sockets.size)
            assertFalse(h.prefs.contains(AppPrefs.KEY_BLOCKED_FAILURE))
        }
    }
    @Test fun serviceRestartPreservesStoredCredentialsAndReconnects() {
        var stored: MemorySecrets? = null
        Harness().use { h -> h.start(); h.connected(); stored = h.credentials }
        Harness().use { h ->
            h.service.tokenStoreFactory = { stored!! }
            h.start(); h.connected()
            assertEquals("fixture-token", stored!!.get("long_lived_token"))
            assertEquals("CONNECTED", h.prefs.getString(AppPrefs.KEY_CONNECTION_PHASE, ""))
        }
    }
}
