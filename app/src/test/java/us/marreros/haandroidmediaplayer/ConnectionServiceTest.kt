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
    private class FakeConnection(val status: (String) -> Unit) : HaConnection {
        var closed = false
        override fun connect() { status("Connecting…") }
        override fun close() { closed = true }
        override fun fireState(event: JSONObject) {}
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
    @Test fun tokenNetworkTransitionsReuseCredentialsAndKeyCancelStaleSocketsAndRecover() = transitionScenario("token")
    @Test fun oauthNetworkTransitionsRefreshOnceAndReuseCredentialsAndKey() = transitionScenario("oauth")
    private fun transitionScenario(authMethod: String) {
        val context = RuntimeEnvironment.getApplication() as Context
        AppPrefs.prefs(context).edit().clear().putString(AppPrefs.KEY_URL, "https://remote.ui.nabu.casa").commit()
        val controller = Robolectric.buildService(ConnectionService::class.java).create()
        val service = controller.get()
        val credentials = MemorySecrets().apply {
            put("credential_origin", "https://remote.ui.nabu.casa")
            put("auth_method", authMethod)
            if (authMethod == "token") put("long_lived_token", "fixture-token")
            else { put("access_token", "expired-token"); put("refresh_token", "fixture-refresh") }
        }
        val signer = DeviceIdentity(context, TestKeys().apply { create() })
        val sockets = mutableListOf<FakeConnection>()
        service.tokenStoreFactory = { credentials }
        service.identityFactory = { signer }
        var refreshCount = 0
        service.refreshCredentials = { origin, refresh ->
            assertEquals("https://remote.ui.nabu.casa", origin)
            assertEquals("fixture-refresh", refresh)
            refreshCount++
            JSONObject().put("access_token", "fixture-token").put("expires_in", 1800)
        }
        service.createConnection = { url, token, _, identity, status, _, allowHttp ->
            assertEquals("https://remote.ui.nabu.casa", url)
            assertEquals("fixture-token", token)
            assertSame(signer, identity)
            assertFalse(allowHttp)
            FakeConnection(status).also { sockets += it }
        }
        try {
            service.onStartCommand(Intent(), 0, 1); drain(service)
            sockets.last().status("Connected"); drain(service)
            // Both directions, and a temporary outage. Android supplies a new Network ID.
            for (network in listOf(network(101), network(102), null, network(103))) {
                val previous = sockets.last()
                change(service, network)
                assertTrue(previous.closed)
                previous.status("Device authentication rejected; review registration")
                drain(service)
                assertFalse(field(service, "authenticationBlocked") as Boolean)
                if (network != null) {
                    retryNow(service)
                    sockets.last().status("Connected"); drain(service)
                    assertEquals("Connected", AppPrefs.prefs(service).getString(AppPrefs.KEY_STATUS, ""))
                } else assertNull(field(service, "reconnectFuture"))
            }
            assertEquals(4, sockets.size)
            assertEquals("fixture-token", credentials.get(if (authMethod == "token") "long_lived_token" else "access_token"))
            assertEquals(if (authMethod == "oauth") 1 else 0, refreshCount)
            // A genuine rejection is terminal, including across subsequent network events.
            sockets.last().status("Device authentication rejected; review registration"); drain(service)
            change(service, network(104))
            assertTrue(field(service, "authenticationBlocked") as Boolean)
            assertNull(field(service, "reconnectFuture"))
            assertEquals(4, sockets.size)
        } finally { controller.destroy() }
    }
}
