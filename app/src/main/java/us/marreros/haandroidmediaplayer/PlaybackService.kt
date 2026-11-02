package us.marreros.haandroidmediaplayer

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
    private lateinit var mediaSession: MediaSession
    private val handler = Handler(Looper.getMainLooper())
    private var currentUrl: String? = null
    private var currentTitle: String? = null
    private data class SavedMedia(
        val item: MediaItem,
        val positionMs: Long,
        val wasPlaying: Boolean,
        val url: String?,
        val title: String?
    )
    private var savedMedia: SavedMedia? = null
    private var announcing = false
    private var announcementReady = false
    private var muted = false

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
            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(TAG, "state=$playbackState announcing=$announcing saved=${savedMedia != null}")
                if (announcing && playbackState == Player.STATE_READY) announcementReady = true
                if (announcing && announcementReady && playbackState == Player.STATE_ENDED) {
                    restoreMedia()
                }
                publishState()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) = publishState()
            override fun onPlayerError(error: PlaybackException) {
                if (announcing) restoreMedia() else publishState("error")
            }
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
                intent.getStringExtra(EXTRA_TITLE),
                intent.getBooleanExtra(EXTRA_ANNOUNCE, false)
            )
            CMD_PLAY -> player.play()
            CMD_PAUSE -> player.pause()
            CMD_STOP -> {
                announcing = false
                announcementReady = false
                savedMedia = null
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

    private fun playMedia(url: String?, title: String?, announce: Boolean) {
        if (url.isNullOrBlank()) return
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
            announcementReady = false
        } else {
            announcing = false
            announcementReady = false
            savedMedia = null
        }
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

    private fun restoreMedia() {
        val previous = savedMedia
        Log.i(TAG, "restoreMedia saved=${previous != null}")
        announcing = false
        announcementReady = false
        savedMedia = null
        if (previous == null) {
            currentUrl = null
            currentTitle = null
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
        mediaSession.release()
        player.release()
        super.onDestroy()
    }
}
