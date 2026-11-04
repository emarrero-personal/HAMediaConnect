package us.marreros.haandroidmediaplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.media.AudioManager
import android.app.NotificationManager
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    companion object {
        private const val TAG = "HARemotePlayback"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CONTENT_TYPE = "content_type"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_POSITION = "position"
        const val EXTRA_ANNOUNCE = "announce"
        const val EXTRA_MUTED = "muted"

        const val CMD_PLAY_MEDIA = "play_media"
        const val CMD_PLAY = "play"
        const val CMD_PAUSE = "pause"
        const val CMD_STOP = "stop"
        const val CMD_SET_VOLUME = "set_volume"
        const val CMD_MUTE = "volume_mute"
        const val CMD_SEEK = "seek"
    }

    private lateinit var player: ExoPlayer
    internal lateinit var mediaDataSourceFactory: OkHttpDataSource.Factory
    private lateinit var mediaSession: MediaSession
    private val handler = Handler(Looper.getMainLooper())
    private var currentUrl: String? = null
    private var currentTitle: String? = null
    private var playbackFailed = false
    private val bufferingTimeout = Runnable {
        if (player.playbackState == Player.STATE_BUFFERING) failPlayback(PlaybackFailure.TIMEOUT)
    }

    private fun failPlayback(reason: String) {
        handler.removeCallbacks(bufferingTimeout)
        Log.w(TAG, reason) // Never attach the Throwable or its message/URL.
        AppPrefs.prefs(this).edit().putString("playback_error", reason).apply()
        playbackFailed = true
        if (announcing) restoreMedia() else {
            replacePlaybackListener()
            player.stop()
            currentTitle = reason
            publishState("idle")
        }
    }
    private data class SavedMedia(
        val item: MediaItem,
        val positionMs: Long,
        val wasPlaying: Boolean,
        val url: String?,
        val title: String?
    )
    private var savedMedia: SavedMedia? = null
    private var announcing = false
    private var muted = false
    private val dndReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isDndActive() && announcing) {
                Log.i(TAG, "DND enabled; ending announcement and restoring media")
                restoreMedia()
            }
        }
    }

    private fun isDndActive(): Boolean {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    private val ticker = object : Runnable {
        override fun run() {
            publishState()
            handler.postDelayed(this, 1000)
        }
    }

    private var playbackGeneration = 0L
    internal lateinit var playbackListener: Player.Listener
        private set

    private fun replacePlaybackListener() {
        // Media3 drops queued events for removed listeners. The generation also
        // rejects an old callback already captured by a caller during replacement.
        if (::playbackListener.isInitialized) player.removeListener(playbackListener)
        val generation = ++playbackGeneration
        playbackListener = createPlaybackListener(generation)
        player.addListener(playbackListener)
    }

    private fun createPlaybackListener(generation: Long) = object : Player.Listener {
        private var announcementReady = false
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (generation != playbackGeneration) return
            Log.d(TAG, "state=$playbackState announcing=$announcing saved=${savedMedia != null}")
            handler.removeCallbacks(bufferingTimeout)
            if (playbackState == Player.STATE_BUFFERING) {
                handler.postDelayed(bufferingTimeout, PlaybackFailure.BUFFER_TIMEOUT_MS)
            }
            if (announcing && playbackState == Player.STATE_READY) announcementReady = true
            if (announcing && playbackState == Player.STATE_ENDED) {
                if (announcementReady) {
                    restoreMedia()
                } else if (player.playbackState == Player.STATE_ENDED) {
                    // A real empty/failed stream can end without reaching READY.
                    // A stale ENDED while the new item loads must not restore it.
                    failPlayback("Announcement ended without playable audio; regenerate TTS")
                }
            }
            publishState()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (generation == playbackGeneration) publishState()
        }
        override fun onPlayerError(error: PlaybackException) {
            if (generation != playbackGeneration) return
            failPlayback(PlaybackFailure.describe(error))
        }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            if (generation != playbackGeneration) return
            currentTitle = mediaMetadata.title?.toString() ?: currentTitle
            publishState()
        }
    }

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(PlaybackNotifications(this))
        // Separate from authenticated HA control: never attach bearer tokens.
        // Refuse redirects so signatures cannot cross origins or downgrade TLS.
        val mediaHttp = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .build()
        mediaDataSourceFactory = OkHttpDataSource.Factory(mediaHttp)
            .setContentTypePredicate { type ->
                val mime = type.substringBefore(';').trim().lowercase()
                mime != "text/html" && mime != "application/json"
            }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this)
                .setDataSourceFactory(mediaDataSourceFactory))
            .build()
        replacePlaybackListener()
        mediaSession = MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo) =
                ControllerPolicy.result(session, controller)
        }).build()
        ContextCompat.registerReceiver(this, dndReceiver,
            IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        handler.post(ticker)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (ControllerPolicy.allowed(controllerInfo.uid, controllerInfo.isTrusted)) mediaSession else null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Never forward caller-supplied media-button/custom intents to Media3: its
        // fallback has no verified Binder identity. Live system controls use the session.
        if (!InternalPlayback.allowed(intent)) return START_STICKY
        if (intent == null) return super.onStartCommand(intent, flags, startId)
        when (intent.action) {
            CMD_PLAY_MEDIA -> playMedia(
                intent.getStringExtra(EXTRA_URL),
                intent.getStringExtra(EXTRA_TITLE),
                intent.getBooleanExtra(EXTRA_ANNOUNCE, false),
                intent.getStringExtra(EXTRA_CONTENT_TYPE)
            )
            "notification_toggle" -> if (player.isPlaying) player.pause() else player.play()
            CMD_PLAY -> player.play()
            CMD_PAUSE -> player.pause()
            CMD_STOP -> {
                announcing = false
                savedMedia = null
                replacePlaybackListener()
                player.stop()
                player.clearMediaItems()
                currentUrl = null
                currentTitle = null
            }
            CMD_MUTE -> {
                if (intent.hasExtra(EXTRA_MUTED)) {
                    muted = intent.getBooleanExtra(EXTRA_MUTED, false)
                    // Mute only this ExoPlayer, not other Android media apps.
                    player.volume = if (muted) 0f else 1f
                }
            }
            CMD_SET_VOLUME -> {
                if (intent.hasExtra(EXTRA_VOLUME)) setSystemVolume(intent.getFloatExtra(EXTRA_VOLUME, 0.5f))
            }
            CMD_SEEK -> {
                if (intent.hasExtra(EXTRA_POSITION)) player.seekTo(intent.getLongExtra(EXTRA_POSITION, 0L))
            }
        }
        publishState()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun playMedia(url: String?, title: String?, announce: Boolean, contentType: String?) {
        if (url.isNullOrBlank() || !TransportPolicy.media(url)) return
        if (announce && isDndActive()) {
            Log.i(TAG, "Suppressing announcement while DND is active")
            return
        }
        handler.removeCallbacks(bufferingTimeout)
        playbackFailed = false
        AppPrefs.prefs(this).edit().remove("playback_error").apply()
        Log.i(TAG, "playMedia announce=$announce items=${player.mediaItemCount}")
        if (announce) {
            if (!announcing && player.mediaItemCount > 0) {
                player.currentMediaItem?.let {
                    Log.i(TAG, "saving position=${player.currentPosition} playing=${player.playWhenReady}")
                    savedMedia = SavedMedia(
                        it,
                        player.currentPosition.coerceAtLeast(0L),
                        player.playWhenReady,
                        currentUrl,
                        currentTitle
                    )
                }
            }
            announcing = true
        } else {
            announcing = false
            savedMedia = null
        }
        currentUrl = url
        currentTitle = title
        val metadata = MediaMetadata.Builder()
            .setTitle(title ?: "Home Assistant")
            .build()
        val item = MediaItem.Builder()
            .setUri(url)
            .setMimeType(contentType?.takeIf { it.startsWith("audio/") || it.startsWith("video/") || it == "application/x-mpegURL" })
            .setMediaMetadata(metadata)
            .build()
        replacePlaybackListener()
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    private fun restoreMedia() {
        val previous = savedMedia
        Log.i(TAG, "restoreMedia saved=${previous != null}")
        announcing = false
        savedMedia = null
        handler.removeCallbacks(bufferingTimeout)
        replacePlaybackListener()
        if (previous == null) {
            currentUrl = null
            currentTitle = if (playbackFailed) AppPrefs.prefs(this).getString("playback_error", null) else null
            player.clearMediaItems()
        } else {
            currentUrl = previous.url
            currentTitle = previous.title
            player.setMediaItem(previous.item, previous.positionMs)
            player.prepare()
            if (previous.wasPlaying) player.play() else player.pause()
        }
        publishState()
    }

    private fun setSystemVolume(level: Float) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val desired = (level.coerceIn(0f, 1f) * max).toInt()
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, desired, 0)
    }

    private fun systemVolume(): Float {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max.toFloat()
    }

    private fun publishState(forced: String? = null) {
        val state = forced ?: when {
            player.playbackState == Player.STATE_BUFFERING -> "buffering"
            player.isPlaying -> "playing"
            player.playbackState == Player.STATE_READY && !player.playWhenReady -> "paused"
            else -> "idle"
        }
        PlayerStateBus.listener?.invoke(
            PlayerSnapshot(
                state = state,
                volume = systemVolume(),
                muted = muted,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration.takeIf { it > 0 } ?: 0L,
                title = currentTitle,
                contentId = currentUrl
            )
        )
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(bufferingTimeout)
        unregisterReceiver(dndReceiver)
        playbackGeneration++
        player.removeListener(playbackListener)
        mediaSession.release()
        player.release()
        super.onDestroy()
    }
}
