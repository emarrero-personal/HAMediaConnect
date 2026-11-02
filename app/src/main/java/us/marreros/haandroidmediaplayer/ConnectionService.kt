package us.marreros.haandroidmediaplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var client: HaWebSocketClient? = null
    private var stopped = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        PlayerStateBus.listener = { snapshot ->
            publishPlayerState(
                snapshot.state, snapshot.volume, snapshot.positionMs, snapshot.durationMs,
                snapshot.title, snapshot.contentId
            )
        }
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopped = false
        connect()
        return START_STICKY
    }

    private fun connect() {
        val prefs = AppPrefs.prefs(this)
        val baseUrl = prefs.getString(AppPrefs.KEY_URL, "")?.trim().orEmpty()
        val token = prefs.getString(AppPrefs.KEY_TOKEN, "")?.trim().orEmpty()
        val deviceId = AppPrefs.ensureDeviceId(this)
        if (baseUrl.isBlank() || token.isBlank()) {
            updateStatus("Configure URL and token")
            stopSelf()
            return
        }

        reconnectFuture?.cancel(false)
        client?.close()
        client = HaWebSocketClient(
            baseUrl = baseUrl,
            token = token,
            deviceId = deviceId,
            onStatus = { status ->
                updateStatus(status)
                if (status.startsWith("Disconnected") && !stopped) scheduleReconnect()
            },
            onCommand = { command -> dispatchCommand(command) }
        ).also { it.connect() }
    }

    private fun scheduleReconnect() {
        reconnectFuture?.cancel(false)
        reconnectFuture = scheduler.schedule({
            if (!stopped) connect()
        }, 10, TimeUnit.SECONDS)
    }

    private fun dispatchCommand(command: HaCommand) {
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = command.command
            command.url?.let { putExtra(PlaybackService.EXTRA_URL, resolveUrl(it)) }
            command.title?.let { putExtra(PlaybackService.EXTRA_TITLE, it) }
            command.contentType?.let { putExtra(PlaybackService.EXTRA_CONTENT_TYPE, it) }
            command.volume?.let { putExtra(PlaybackService.EXTRA_VOLUME, it) }
            command.position?.let { putExtra(PlaybackService.EXTRA_POSITION, it) }
        }
        startService(intent)
    }

    private fun resolveUrl(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        val base = AppPrefs.prefs(this).getString(AppPrefs.KEY_URL, "")!!.trimEnd('/')
        return if (url.startsWith('/')) "$base$url" else "$base/$url"
    }

    fun publishPlayerState(
        state: String,
        volume: Float,
        positionMs: Long,
        durationMs: Long,
        title: String?,
        contentId: String?
    ) {
        val prefs = AppPrefs.prefs(this)
        val payload = JSONObject()
            .put("device_id", AppPrefs.ensureDeviceId(this))
            .put("name", prefs.getString(AppPrefs.KEY_NAME, android.os.Build.MODEL))
            .put("state", state)
            .put("volume_level", volume)
            .put("position_ms", positionMs)
            .put("duration_ms", durationMs)
            .put("media_title", title ?: JSONObject.NULL)
            .put("media_content_id", contentId ?: JSONObject.NULL)
            .put("connected", true)
            .put("app_version", BuildConfig.VERSION_NAME)
        client?.fireState(payload)
    }

    private fun updateStatus(status: String) {
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
            .setContentTitle("HA Android Media Player")
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

    override fun onDestroy() {
        stopped = true
        PlayerStateBus.listener = null
        reconnectFuture?.cancel(true)
        client?.close()
        scheduler.shutdownNow()
        AppPrefs.prefs(this).edit().putString(AppPrefs.KEY_STATUS, "Stopped").apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
