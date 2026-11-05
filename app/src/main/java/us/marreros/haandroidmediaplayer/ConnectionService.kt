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
        const val ACTION_RECONNECT = "reconnect_corrected_configuration"
        private const val CHANNEL_ID = "ha_connection"
        private const val NOTIFICATION_ID = 1001
    }

    private data class CredentialSnapshot(val version: Long, val token: String, val refreshToken: String?)

    internal var tokenStoreFactory: () -> SecretStore = { SecureTokenStore(this) }
    internal var refreshCredentials: (String, String) -> JSONObject = { origin, refresh -> HaOAuth(origin).refresh(refresh) }
    internal var identityFactory: () -> DeviceSigner = { DeviceIdentity(this) }
    internal var createConnection: (String, String, String, DeviceSigner, (ConnectionEvent) -> Unit, (HaCommand) -> Unit, Boolean) -> HaConnection =
        { url, token, name, signer, status, command, allowHttp ->
            HaWebSocketClient(url, token, name, signer, {}, command, allowHttp, status)
        }
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var reconnectFuture: ScheduledFuture<*>? = null
    @Volatile private var client: HaConnection? = null
    @Volatile private var stopped = false
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private var retryAttempt = 0
    private var recovering = false
    @Volatile private var authenticationBlocked = false
    private lateinit var connectivity: ConnectivityManager
    // Scheduler-thread state: one in-band transfer, and which delivery phases HA has acknowledged.
    private lateinit var transfers: TransferController
    private var transferCommand: Pair<String, HaCommand>? = null
    private val sentReports = object : LinkedHashMap<String, MutableSet<DeliveryPhase>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableSet<DeliveryPhase>>?) = size > 16
    }
    private val deliveryObserver: (DeliveryStatus) -> Unit = { status ->
        try { scheduler.execute { report(status) } } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }
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
        val transition = generation.incrementAndGet()
        client?.close()
        client = null
        scheduler.execute {
            reconnectFuture?.cancel(false)
            reconnectFuture = null
            if (stopped || authenticationBlocked || transition != generation.get()) return@execute
            retryAttempt = 0
            recovering = true
            transfers.detach()
            updateStatus(ConnectionEvent(if (network == null) ConnectionPhase.DISCONNECTED else ConnectionPhase.RECONNECTING))
            if (network != null) connect()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val blockedKind = AppPrefs.prefs(this).getString(AppPrefs.KEY_BLOCKED_FAILURE, null)?.let {
            runCatching { ConnectionFailureKind.valueOf(it) }.getOrNull()
        }?.takeUnless { it.retryable }
        authenticationBlocked = blockedKind != null
        TransferFiles.purgeStale(cacheDir)
        transfers = TransferController(cacheDir, scheduler,
            onComplete = { key, _, file -> playTransferred(key, file) },
            onStatus = { key, phase, reason, resuming -> DeliveryTracker.update(key, phase, reason, resuming) })
        DeliveryTracker.observe(deliveryObserver)
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
        blockedKind?.let { updateStatus(ConnectionEvent.failed(ConnectionFailure(it))) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopped = false
        scheduler.execute {
            if (intent?.action == ACTION_RECONNECT) {
                authenticationBlocked = false
                AppPrefs.prefs(this).edit().remove(AppPrefs.KEY_BLOCKED_FAILURE).apply()
                generation.incrementAndGet()
                client?.close()
                client = null
                transfers.detach()
                reconnectFuture?.cancel(false)
                reconnectFuture = null
                retryAttempt = 0
            }
            if (reconnectFuture == null && client == null) connect()
        }
        return START_STICKY
    }

    private fun connect() {
        if (stopped || authenticationBlocked) return
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        val attempt = generation.incrementAndGet()
        updateStatus(ConnectionEvent(if (recovering) ConnectionPhase.RECONNECTING else ConnectionPhase.CONNECTING))
        val prefs = AppPrefs.prefs(this)
        val baseUrl = prefs.getString(AppPrefs.KEY_URL, "")?.trim().orEmpty()
        if (!AppPrefs.isAllowedUrl(baseUrl, prefs.getBoolean(AppPrefs.KEY_ALLOW_HTTP, false))) {
            handleFailure(ConnectionFailure(ConnectionFailureKind.CONFIGURATION))
            return
        }
        val secure = tokenStoreFactory()
        val snapshot = CredentialTransactions.read {
            if (secure.get("credential_origin") != baseUrl.trimEnd('/')) null
            else {
                val tokenMode = secure.get("auth_method") == "token"
                CredentialSnapshot(CredentialTransactions.version(),
                    if (tokenMode) secure.get("long_lived_token").orEmpty() else secure.get("access_token").orEmpty(),
                    if (tokenMode) null else secure.get("refresh_token"))
            }
        }
        if (snapshot == null) {
            handleFailure(ConnectionFailure(ConnectionFailureKind.CONFIGURATION))
            return
        }
        var token = snapshot.token
        val refreshToken = snapshot.refreshToken
        if (!refreshToken.isNullOrBlank() && baseUrl.startsWith("https://") &&
            (token.isBlank() || System.currentTimeMillis() >= prefs.getLong("oauth_expiry", 0) - 60_000)) {
            try {
                val refreshed = refreshCredentials(baseUrl, refreshToken)
                val refreshedToken = refreshed.getString("access_token")
                val committed = CredentialTransactions.commit(snapshot.version, valid = {
                    !stopped && attempt == generation.get() &&
                        prefs.getString(AppPrefs.KEY_URL, "") == baseUrl && secure.get("credential_origin") == baseUrl &&
                        secure.get("auth_method") == "oauth" && secure.get("refresh_token") == refreshToken
                }, write = {
                    secure.put("access_token", refreshedToken)
                    if (refreshed.has("refresh_token")) secure.put("refresh_token", refreshed.getString("refresh_token"))
                    prefs.edit().putLong("oauth_expiry",
                        System.currentTimeMillis() + refreshed.optLong("expires_in", 1800) * 1000).apply()
                })
                if (!committed) return
                token = refreshedToken
            } catch (e: Exception) {
                if (stopped || attempt != generation.get()) return
                handleFailure(ConnectionFailure(if (e is OAuthRejected) ConnectionFailureKind.OAUTH_REJECTED else ConnectionFailureKind.OAUTH_UNAVAILABLE))
                return
            }
        }

        val identity = try { identityFactory() } catch (_: Exception) {
            handleFailure(ConnectionFailure(ConnectionFailureKind.KEY_UNAVAILABLE))
            return
        }
        if (baseUrl.isBlank() || token.isBlank()) {
            handleFailure(ConnectionFailure(ConnectionFailureKind.CONFIGURATION))
            stopSelf()
            return
        }

        synchronized(this) {
            if (stopped || attempt != generation.get()) return
            client?.close()
            val statusCallback: (ConnectionEvent) -> Unit = { event ->
                try { if (!stopped) scheduler.execute {
                    if (stopped || attempt != generation.get()) return@execute
                    if (event.failure != null) handleFailure(event.failure)
                    else {
                        if (event.phase == ConnectionPhase.CONNECTED) {
                            retryAttempt = 0; recovering = false
                            // A transfer interrupted by a network change resumes on the fresh proof.
                            client?.let { transfers.attach(it) }
                            report(DeliveryTracker.latest)
                        }
                        updateStatus(if (recovering && event.phase == ConnectionPhase.CONNECTING)
                            ConnectionEvent(ConnectionPhase.RECONNECTING) else event)
                    }
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

    private fun handleFailure(failure: ConnectionFailure) {
        authenticationBlocked = !failure.retryable
        transfers.detach()
        if (authenticationBlocked) transfers.cancel(DeliveryReason.INTERRUPTED)
        AppPrefs.prefs(this).edit().apply {
            if (authenticationBlocked) putString(AppPrefs.KEY_BLOCKED_FAILURE, failure.kind.name)
            else remove(AppPrefs.KEY_BLOCKED_FAILURE)
        }.apply()
        if (authenticationBlocked) { reconnectFuture?.cancel(false); reconnectFuture = null }
        updateStatus(ConnectionEvent.failed(failure))
        if (failure.retryable) scheduleReconnect(failure)
    }

    private fun scheduleReconnect(failure: ConnectionFailure) {
        if (stopped || (reconnectFuture != null && !reconnectFuture!!.isDone)) return
        recovering = true
        updateStatus(ConnectionEvent(ConnectionPhase.RECONNECTING, failure))
        val delay = TransportPolicy.reconnectDelay(retryAttempt)
        retryAttempt = (retryAttempt + 1).coerceAtMost(6)
        reconnectFuture = scheduler.schedule({
            reconnectFuture = null
            if (!stopped) connect()
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun dispatchCommand(command: HaCommand) {
        var key: String? = null
        if (command.command == PlaybackService.CMD_PLAY_MEDIA) {
            val deliveryKey = command.requestId ?: DeliveryTracker.localKey()
            val offer = command.transfer
            if (offer != null) {
                scheduler.execute {
                    if (!transfers.accepts(offer) || !DeliveryTracker.begin(deliveryKey, command.requestId,
                            DeliveryMethod.DIRECT, command.announce)) return@execute
                    transferCommand = deliveryKey to command
                    transfers.start(deliveryKey, offer)
                }
                return
            }
            if (!DeliveryTracker.begin(deliveryKey, command.requestId, DeliveryMethod.URL, command.announce)) return
            key = deliveryKey
        }
        if (command.command == PlaybackService.CMD_PLAY_MEDIA || command.command == PlaybackService.CMD_STOP) {
            val reason = if (command.command == PlaybackService.CMD_STOP) DeliveryReason.STOPPED else DeliveryReason.SUPERSEDED
            scheduler.execute { transfers.cancel(reason) }
        }
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = command.command
            command.url?.let { putExtra(PlaybackService.EXTRA_URL, resolveUrl(it)) }
            command.title?.let { putExtra(PlaybackService.EXTRA_TITLE, it) }
            command.contentType?.let { putExtra(PlaybackService.EXTRA_CONTENT_TYPE, it) }
            command.volume?.let { putExtra(PlaybackService.EXTRA_VOLUME, it) }
            command.position?.let { putExtra(PlaybackService.EXTRA_POSITION, it) }
            putExtra(PlaybackService.EXTRA_ANNOUNCE, command.announce)
            command.muted?.let { putExtra(PlaybackService.EXTRA_MUTED, it) }
            key?.let { putExtra(PlaybackService.EXTRA_DELIVERY_KEY, it) }
        }
        startService(InternalPlayback.authorize(intent))
    }

    private fun playTransferred(key: String, file: java.io.File) {
        val command = transferCommand?.takeIf { it.first == key }?.second
        transferCommand = null
        // A newer command superseded this transfer while its last chunk was in flight.
        if (command == null || DeliveryTracker.latest?.key != key) return TransferFiles.delete(cacheDir, file)
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.CMD_PLAY_MEDIA
            putExtra(PlaybackService.EXTRA_FILE, file.absolutePath)
            command.title?.let { putExtra(PlaybackService.EXTRA_TITLE, it) }
            putExtra(PlaybackService.EXTRA_CONTENT_TYPE, command.transfer?.mime)
            putExtra(PlaybackService.EXTRA_ANNOUNCE, command.announce)
            putExtra(PlaybackService.EXTRA_DELIVERY_KEY, key)
        }
        startService(InternalPlayback.authorize(intent))
    }

    /** Each phase is reported at most once per request; missed phases are sent after reconnect. */
    private fun report(status: DeliveryStatus?) {
        val requestId = status?.requestId ?: return
        val sent = sentReports.getOrPut(requestId) { mutableSetOf() }
        if (status.phase in sent) return
        if (client?.reportDelivery(requestId, status.phase.wire, status.reason?.wire) == true) sent += status.phase
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

    private fun updateStatus(event: ConnectionEvent) {
        val status = if (event.phase == ConnectionPhase.RECONNECTING && event.failure != null) "Reconnecting — ${event.message}" else event.message
        if (stopped) return
        AppPrefs.prefs(this).edit().putString(AppPrefs.KEY_STATUS, status).putString(AppPrefs.KEY_CONNECTION_PHASE, event.phase.name).apply()
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
        DeliveryTracker.unobserve(deliveryObserver)
        PlayerStateBus.listener = null
        try { scheduler.submit { transfers.cancel(DeliveryReason.STOPPED) }.get(2, TimeUnit.SECONDS) } catch (_: Exception) {}
        reconnectFuture?.cancel(true)
        client?.close()
        scheduler.shutdownNow()
        AppPrefs.prefs(this).edit().putString(AppPrefs.KEY_STATUS, "Stopped").putString(AppPrefs.KEY_CONNECTION_PHASE, ConnectionPhase.DISCONNECTED.name).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
