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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.File
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
        /** App-cache path of a completed in-band transfer; accepted only inside the transfer directory. */
        const val EXTRA_FILE = "transfer_file"
        const val EXTRA_DELIVERY_KEY = "delivery_key"

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
    private var currentFile: File? = null
    private var currentKey: String? = null
    private val recentKeys = ArrayDeque<String>()
    private var playbackFailed = false
    private var playbackError: String? = null
    private var playbackNotice: String? = null
    private val bufferingTimeout = Runnable {
        if (player.playbackState == Player.STATE_BUFFERING) failPlayback(PlaybackFailure.TIMEOUT, DeliveryReason.TIMEOUT)
    }

    private fun failPlayback(reason: String, kind: DeliveryReason) {
        DeliveryTracker.update(currentKey, DeliveryPhase.FAILED, kind)
        handler.removeCallbacks(bufferingTimeout)
        Log.w(TAG, reason) // Never attach the Throwable or its message/URL.
        AppPrefs.prefs(this).edit().putString("playback_error", reason).apply()
        playbackFailed = true
        playbackError = reason
        if (announcing) {
            if (savedMedia != null) playbackNotice = "Announcement failed — ${endpoint()}\n$reason"
            restoreMedia()
        } else {
            replacePlaybackListener()
            player.stop()
            releaseFile(currentFile.also { currentFile = null })
            publishState("idle")
        }
    }
    private data class SavedMedia(
        val item: MediaItem,
        val positionMs: Long,
        val wasPlaying: Boolean,
        val url: String?,
        val title: String?,
        val file: File?
    )
    private var savedMedia: SavedMedia? = null
    private var announcing = false
    private var muted = false
    private val dndReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isDndActive() && announcing) {
                Log.i(TAG, "DND enabled; ending announcement and restoring media")
                DeliveryTracker.update(currentKey, DeliveryPhase.SUPPRESSED, DeliveryReason.DND)
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
        private var started = false
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (generation != playbackGeneration) return
            Log.d(TAG, "state=$playbackState announcing=$announcing saved=${savedMedia != null}")
            handler.removeCallbacks(bufferingTimeout)
            if (playbackState == Player.STATE_BUFFERING) {
                handler.postDelayed(bufferingTimeout, PlaybackFailure.BUFFER_TIMEOUT_MS)
                DeliveryTracker.update(currentKey, DeliveryPhase.BUFFERING)
            }
            if (playbackState == Player.STATE_READY) {
                if (!started) DeliveryTracker.update(currentKey, if (player.isPlaying) DeliveryPhase.PLAYING else DeliveryPhase.READY)
                if (announcing) announcementReady = true
            }
            if (announcing && playbackState == Player.STATE_ENDED) {
                if (announcementReady) {
                    DeliveryTracker.update(currentKey, DeliveryPhase.COMPLETED)
                    restoreMedia()
                } else if (player.playbackState == Player.STATE_ENDED) {
                    // A real empty/failed stream can end without reaching READY.
                    // A stale ENDED while the new item loads must not restore it.
                    failPlayback("Announcement ended without playable audio; regenerate TTS", DeliveryReason.DECODE)
                }
            }
            if (generation != playbackGeneration) return
            if (!announcing && playbackState == Player.STATE_ENDED && !playbackFailed) {
                DeliveryTracker.update(currentKey, DeliveryPhase.COMPLETED)
                currentUrl = null
                currentTitle = null
                currentKey = null
                releaseFile(currentFile.also { currentFile = null })
                replacePlaybackListener()
                player.clearMediaItems()
            }
            publishState()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (generation != playbackGeneration) return
            if (isPlaying && !started) { started = true; DeliveryTracker.update(currentKey, DeliveryPhase.PLAYING) }
            publishState()
        }
        override fun onPlayerError(error: PlaybackException) {
            if (generation != playbackGeneration) return
            failPlayback(PlaybackFailure.describe(error), PlaybackFailure.reason(error))
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
            .addInterceptor(MediaRedirectPolicy())
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .build()
        mediaDataSourceFactory = OkHttpDataSource.Factory(mediaHttp)
            .setContentTypePredicate { type ->
                val mime = type.substringBefore(';').trim().lowercase()
                mime != "text/html" && mime != "application/json"
            }
        // HTTP(S) still goes through the credential-free OkHttp client; file:// only ever names
        // a verified transfer file because playMedia accepts no other local URI.
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this)
                .setDataSourceFactory(DefaultDataSource.Factory(this, mediaDataSourceFactory)))
            .build()
        replacePlaybackListener()
        mediaSession = MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo) =
                ControllerPolicy.result(session, controller)
        }).build()
        // HA starts this service directly; no controller may call onGetSession.
        // Register explicitly so Media3 manages the playback notification and FGS.
        addSession(mediaSession)
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
                intent.getStringExtra(EXTRA_FILE),
                intent.getStringExtra(EXTRA_TITLE),
                intent.getBooleanExtra(EXTRA_ANNOUNCE, false),
                intent.getStringExtra(EXTRA_CONTENT_TYPE),
                intent.getStringExtra(EXTRA_DELIVERY_KEY)
            )
            "notification_toggle" -> if (player.isPlaying) player.pause() else player.play()
            CMD_PLAY -> player.play()
            CMD_PAUSE -> player.pause()
            CMD_STOP -> {
                DeliveryTracker.update(currentKey, DeliveryPhase.FAILED, DeliveryReason.STOPPED)
                handler.removeCallbacks(bufferingTimeout)
                playbackFailed = false
                playbackError = null
                playbackNotice = null
                AppPrefs.prefs(this).edit().remove("playback_error").apply()
                announcing = false
                val saved = savedMedia?.file
                savedMedia = null
                releaseFile(saved)
                replacePlaybackListener()
                player.stop()
                player.clearMediaItems()
                currentUrl = null
                currentTitle = null
                currentKey = null
                releaseFile(currentFile.also { currentFile = null })
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

    private fun endpoint() = if (currentFile != null) "Direct transfer" else PlaybackDiagnostic.endpoint(currentUrl)

    /** Deletes transferred audio once neither the current nor the saved item refers to it. */
    private fun releaseFile(file: File?) {
        if (file != null && file != currentFile && file != savedMedia?.file) TransferFiles.delete(cacheDir, file)
    }

    private fun playMedia(url: String?, filePath: String?, title: String?, announce: Boolean, contentType: String?, key: String?) {
        val file = filePath?.let(::File)
        val uri = when {
            file != null -> if (TransferFiles.owned(cacheDir, file)) android.net.Uri.fromFile(file).toString() else null
            !url.isNullOrBlank() && TransportPolicy.media(url) -> url
            else -> null
        }
        if (uri == null) {
            DeliveryTracker.update(key, DeliveryPhase.FAILED, DeliveryReason.OTHER)
            return
        }
        if (key != null) {
            if (key in recentKeys) return // Duplicate delivery of a request already played.
            recentKeys.addLast(key)
            while (recentKeys.size > 16) recentKeys.removeFirst()
        }
        if (announce && isDndActive()) {
            Log.i(TAG, "Suppressing announcement while DND is active")
            DeliveryTracker.update(key, DeliveryPhase.SUPPRESSED, DeliveryReason.DND)
            if (file != null) TransferFiles.delete(cacheDir, file)
            return
        }
        if (!BackgroundAudio.allowed(android.os.Build.VERSION.SDK_INT)) {
            DeliveryTracker.update(key, DeliveryPhase.SUPPRESSED, DeliveryReason.UNAVAILABLE)
            playbackNotice = BackgroundAudio.NOTICE
            if (file != null) TransferFiles.delete(cacheDir, file)
            publishState()
            return
        }
        handler.removeCallbacks(bufferingTimeout)
        playbackFailed = false
        playbackError = null
        playbackNotice = null
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
                        currentTitle,
                        currentFile
                    )
                }
            }
            announcing = true
        } else {
            announcing = false
            val saved = savedMedia?.file
            savedMedia = null
            releaseFile(saved)
        }
        val replaced = currentFile
        currentUrl = if (file != null) null else url
        currentTitle = title
        currentFile = file
        currentKey = key
        releaseFile(replaced)
        val metadata = MediaMetadata.Builder()
            .setTitle(title ?: "Home Assistant")
            .build()
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(contentType?.takeIf { it.startsWith("audio/") || it.startsWith("video/") || it == "application/x-mpegURL" })
            .setMediaMetadata(metadata)
            .build()
        replacePlaybackListener()
        // Retrieval starts now (prepare emits BUFFERING synchronously); only later Media3
        // transitions prove that audio actually played.
        if (file == null) DeliveryTracker.update(key, DeliveryPhase.RETRIEVING)
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
        val announcementFile = currentFile
        currentKey = null
        currentFile = previous?.file
        releaseFile(announcementFile)
        if (previous == null) {
            if (!playbackFailed) {
                currentUrl = null
                currentTitle = null
            }
            player.clearMediaItems()
        } else {
            playbackFailed = false
            playbackError = null
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
        PlayerStateBus.publish(
            PlayerSnapshot(
                state = state,
                volume = systemVolume(),
                muted = muted,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration.takeIf { it > 0 } ?: 0L,
                title = currentTitle,
                contentId = currentUrl,
                error = playbackError,
                notice = playbackNotice,
                direct = currentFile != null
            )
        )
    }

    override fun onDestroy() {
        DeliveryTracker.update(currentKey, DeliveryPhase.FAILED, DeliveryReason.STOPPED)
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(bufferingTimeout)
        unregisterReceiver(dndReceiver)
        playbackGeneration++
        player.removeListener(playbackListener)
        player.stop()
        player.clearMediaItems()
        currentUrl = null
        currentTitle = null
        currentKey = null
        savedMedia?.file?.let { TransferFiles.delete(cacheDir, it) }
        currentFile?.let { TransferFiles.delete(cacheDir, it) }
        savedMedia = null
        currentFile = null
        playbackError = null
        playbackNotice = null
        AppPrefs.prefs(this).edit().remove("playback_error").apply()
        publishState("idle")
        mediaSession.release()
        player.release()
        super.onDestroy()
    }
}
