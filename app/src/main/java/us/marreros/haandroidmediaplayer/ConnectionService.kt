package us.marreros.haandroidmediaplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class ConnectionService : Service() {
    companion object {
        private const val CHANNEL_ID = "ha_connection"
        private const val NOTIFICATION_ID = 1001
    }

    internal var tokenStoreFactory: () -> SecretStore = { SecureTokenStore(this) }
    internal var refreshCredentials: (String, String) -> JSONObject = { origin, refresh -> HaOAuth(origin).refresh(refresh) }
    internal var identityFactory: () -> DeviceSigner = { DeviceIdentity(this) }
    internal var createConnection: (String, String, String, DeviceSigner, (String) -> Unit, (HaCommand) -> Unit, Boolean) -> HaConnection =
        { url, token, name, signer, status, command, allowHttp ->
            HaWebSocketClient(url, token, name, signer, status, command, allowHttp)
        }
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var reconnectFuture: ScheduledFuture<*>? = null
    @Volatile private var client: HaConnection? = null
    @Volatile private var stopped = false
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private var retryAttempt = 0
    @Volatile private var authenticationBlocked = false
    private lateinit var connectivity: ConnectivityManager
    private var defaultNetwork: Network? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networkChanged(network)
        override fun onLost(network: Network) {
            if (network == defaultNetwork) networkChanged(null)
        }
    }

    @Synchronized private fun networkChanged(network: Network?) {
        if (stopped || defaultNetwork == network) return
        defaultNetwork = network
        generation.incrementAndGet()
        client?.close()
        client = null
        scheduler.execute {
            reconnectFuture?.cancel(false)
            reconnectFuture = null
            if (stopped || authenticationBlocked) return@execute
            retryAttempt = 0
            updateStatus(if (network == null) "Disconnected: waiting for network" else "Reconnecting")
            if (network != null) scheduleReconnect()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        connectivity = getSystemService(ConnectivityManager::class.java)
        defaultNetwork = connectivity.activeNetwork
        connectivity.registerDefaultNetworkCallback(networkCallback)
        PlayerStateBus.listener = { snapshot ->
            publishPlayerState(
                snapshot.state, snapshot.volume, snapshot.muted, snapshot.positionMs, snapshot.durationMs,
                snapshot.title, snapshot.contentId
            )
        }
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopped = false
        scheduler.execute { if (reconnectFuture == null && client == null) connect() }
        return START_STICKY
    }

    private fun connect() {
        if (stopped || authenticationBlocked) return
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        val attempt = generation.incrementAndGet()
        updateStatus("Connecting")
        val prefs = AppPrefs.prefs(this)
        val baseUrl = prefs.getString(AppPrefs.KEY_URL, "")?.trim().orEmpty()
        if (!AppPrefs.isAllowedUrl(baseUrl, prefs.getBoolean(AppPrefs.KEY_ALLOW_HTTP, false))) {
            updateStatus("Connection blocked: HTTPS required unless insecure HTTP is enabled")
            return
        }
        val secure = tokenStoreFactory()
        if (secure.get("credential_origin") != baseUrl.trimEnd('/')) {
            updateStatus("Sign in or enter a token for this Home Assistant URL")
            return
        }
        val tokenMode = secure.get("auth_method") == "token"
        var token = if (tokenMode) secure.get("long_lived_token").orEmpty() else secure.get("access_token").orEmpty()
        val refreshToken = if (tokenMode) null else secure.get("refresh_token")
        if (!refreshToken.isNullOrBlank() && baseUrl.startsWith("https://") &&
            (token.isBlank() || System.currentTimeMillis() >= prefs.getLong("oauth_expiry", 0) - 60_000)) {
            try {
                val refreshed = refreshCredentials(baseUrl, refreshToken)
                if (stopped || attempt != generation.get()) return
                token = refreshed.getString("access_token")
                secure.put("access_token", token)
                if (refreshed.has("refresh_token")) secure.put("refresh_token", refreshed.getString("refresh_token"))
                prefs.edit().putLong("oauth_expiry",
                    System.currentTimeMillis() + refreshed.optLong("expires_in", 1800) * 1000).apply()
            } catch (e: Exception) {
                if (stopped || attempt != generation.get()) return
                if (e is OAuthRejected) { authenticationBlocked = true; updateStatus("Authentication failed; sign in again") }
                else { updateStatus("Token refresh unavailable; retrying"); scheduleReconnect() }
                return
            }
        }

        val identity = try { identityFactory() } catch (_: Exception) {
            updateStatus("Device key unavailable; reset identity in Settings")
            return
        }
        if (baseUrl.isBlank() || token.isBlank()) {
            updateStatus("Configure URL and sign in")
            stopSelf()
            return
        }

        synchronized(this) {
            if (stopped || attempt != generation.get()) return
            client?.close()
            val statusCallback: (String) -> Unit = { status ->
                try { if (!stopped) scheduler.execute {
                    if (stopped || attempt != generation.get()) return@execute
                    if (status.startsWith("Authentication failed") || status.startsWith("Device authentication rejected") ||
                        status == "Protocol rejected" || status.startsWith("Device key unavailable")) authenticationBlocked = true
                    updateStatus(status)
                    if (status == "Connected") retryAttempt = 0
                    else if (TransportPolicy.shouldReconnect(status)) scheduleReconnect()
                } } catch (_: java.util.concurrent.RejectedExecutionException) {
                    // The socket callback raced service destruction.
                }
            }
            client = createConnection(baseUrl, token,
                (prefs.getString(AppPrefs.KEY_NAME, android.os.Build.MODEL) ?: android.os.Build.MODEL).take(128),
                identity, statusCallback,
                { command -> if (!stopped && attempt == generation.get()) dispatchCommand(command) },
                prefs.getBoolean(AppPrefs.KEY_ALLOW_HTTP, false)).also { it.connect() }
        }
    }

    private fun scheduleReconnect() {
        if (stopped || (reconnectFuture != null && !reconnectFuture!!.isDone)) return
        updateStatus("Reconnecting — ${AppPrefs.prefs(this).getString(AppPrefs.KEY_STATUS, "transport unavailable")?.substringAfter("— ")}")
        val delay = TransportPolicy.reconnectDelay(retryAttempt)
        retryAttempt = (retryAttempt + 1).coerceAtMost(6)
        reconnectFuture = scheduler.schedule({
            reconnectFuture = null
            if (!stopped) connect()
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun dispatchCommand(command: HaCommand) {
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = command.command
            command.url?.let { putExtra(PlaybackService.EXTRA_URL, resolveUrl(it)) }
            command.title?.let { putExtra(PlaybackService.EXTRA_TITLE, it) }
            command.contentType?.let { putExtra(PlaybackService.EXTRA_CONTENT_TYPE, it) }
            command.volume?.let { putExtra(PlaybackService.EXTRA_VOLUME, it) }
            command.position?.let { putExtra(PlaybackService.EXTRA_POSITION, it) }
            putExtra(PlaybackService.EXTRA_ANNOUNCE, command.announce)
            command.muted?.let { putExtra(PlaybackService.EXTRA_MUTED, it) }
        }
        startService(InternalPlayback.authorize(intent))
    }

    private fun resolveUrl(url: String): String = TransportPolicy.resolveMedia(
        AppPrefs.prefs(this).getString(AppPrefs.KEY_URL, "")!!, url
    )

    fun publishPlayerState(
        state: String,
        volume: Float,
        muted: Boolean,
        positionMs: Long,
        durationMs: Long,
        title: String?,
        contentId: String?
    ) {
        val prefs = AppPrefs.prefs(this)
        val payload = JSONObject()
            .put("name", prefs.getString(AppPrefs.KEY_NAME, android.os.Build.MODEL))
            .put("state", state)
            .put("volume_level", volume)
            .put("is_volume_muted", muted)
            .put("position_ms", positionMs)
            .put("duration_ms", durationMs)
            .put("media_title", title ?: JSONObject.NULL)
            .put("media_content_id", contentId ?: JSONObject.NULL)
            .put("connected", true)
            .put("app_version", BuildConfig.VERSION_NAME)
        client?.fireState(payload)
    }

    private fun updateStatus(status: String) {
        if (stopped) return
        AppPrefs.prefs(this).edit().putString(AppPrefs.KEY_STATUS, status).apply()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String): android.app.Notification {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("HAMediaConnect")
            .setContentText(status)
            .setContentIntent(pending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.connection_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.connection_channel_description) }
        )
    }

    @Synchronized override fun onDestroy() {
        stopped = true
        generation.incrementAndGet()
        connectivity.unregisterNetworkCallback(networkCallback)
        PlayerStateBus.listener = null
        reconnectFuture?.cancel(true)
        client?.close()
        scheduler.shutdownNow()
        AppPrefs.prefs(this).edit().putString(AppPrefs.KEY_STATUS, "Stopped").apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
