package us.marreros.haandroidmediaplayer

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CONTENT_TYPE = "content_type"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_POSITION = "position"

        const val CMD_PLAY_MEDIA = "play_media"
        const val CMD_PLAY = "play"
        const val CMD_PAUSE = "pause"
        const val CMD_STOP = "stop"
        const val CMD_SET_VOLUME = "set_volume"
        const val CMD_SEEK = "seek"
    }

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private val handler = Handler(Looper.getMainLooper())
    private var currentUrl: String? = null
    private var currentTitle: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            publishState()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = publishState()
            override fun onIsPlayingChanged(isPlaying: Boolean) = publishState()
            override fun onPlayerError(error: PlaybackException) = publishState("error")
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                currentTitle = mediaMetadata.title?.toString() ?: currentTitle
                publishState()
            }
        })
        mediaSession = MediaSession.Builder(this, player).build()
        handler.post(ticker)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            CMD_PLAY_MEDIA -> playMedia(
                intent.getStringExtra(EXTRA_URL),
                intent.getStringExtra(EXTRA_TITLE)
            )
            CMD_PLAY -> player.play()
            CMD_PAUSE -> player.pause()
            CMD_STOP -> {
                player.stop()
                player.clearMediaItems()
                currentUrl = null
                currentTitle = null
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

    private fun playMedia(url: String?, title: String?) {
        if (url.isNullOrBlank()) return
        currentUrl = url
        currentTitle = title
        val metadata = MediaMetadata.Builder()
            .setTitle(title ?: "Home Assistant")
            .build()
        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(metadata)
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
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
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration.takeIf { it > 0 } ?: 0L,
                title = currentTitle,
                contentId = currentUrl
            )
        )
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        mediaSession.release()
        player.release()
        super.onDestroy()
    }
}
