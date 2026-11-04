package us.marreros.haandroidmediaplayer

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackDeliveryTest {
    private fun player(service: PlaybackService) = PlaybackService::class.java.getDeclaredField("player")
        .apply { isAccessible = true }.get(service) as ExoPlayer
    private fun play(service: PlaybackService, url: String, announce: Boolean = false) {
        service.onStartCommand(InternalPlayback.authorize(Intent().setAction(PlaybackService.CMD_PLAY_MEDIA)
            .putExtra(PlaybackService.EXTRA_URL, url)
            .putExtra(PlaybackService.EXTRA_CONTENT_TYPE, "audio/mpeg")
            .putExtra(PlaybackService.EXTRA_ANNOUNCE, announce)), 0, 1)
    }
    @Test fun announcementFailureRestoresMusicAndKeepsSanitizedDiagnostic() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        try {
            play(service, "https://public.example/music.mp3")
            player(service).seekTo(12_345L)
            play(service, "http://10.1.0.195:8123/api/tts_proxy/a.mp3?secret=hidden", true)
            assertEquals("audio/mpeg", player(service).currentMediaItem!!.localConfiguration!!.mimeType)
            service.playbackListener.onPlayerError(PlaybackException("secret signed URL", null,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
            assertEquals("https://public.example/music.mp3", player(service).currentMediaItem!!.localConfiguration!!.uri.toString())
            assertTrue(player(service).playWhenReady)
            assertEquals(12_345L, player(service).currentPosition)
            val diagnostic = AppPrefs.prefs(service).getString("playback_error", "")!!
            assertTrue(diagnostic.contains("external HTTPS"))
            assertFalse(diagnostic.contains("secret"))
            play(service, "https://public.example/next.mp3")
            assertNull(AppPrefs.prefs(service).getString("playback_error", null))
        } finally { controller.destroy() }
    }
    @Test fun stalledBufferingStopsAndPublishesIdleWithDiagnostic() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        var snapshot: PlayerSnapshot? = null
        PlayerStateBus.listener = { snapshot = it }
        try {
            play(service, "https://unreachable.invalid/tts.mp3")
            // ExoPlayer's synchronous prepare enters BUFFERING; the Looper owns the deadline.
            assertEquals(Player.STATE_BUFFERING, player(service).playbackState)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PlaybackFailure.BUFFER_TIMEOUT_MS))
            assertEquals(Player.STATE_IDLE, player(service).playbackState)
            assertEquals("idle", snapshot!!.state)
            assertTrue(snapshot!!.title!!.contains("timed out"))
        } finally { PlayerStateBus.listener = null; controller.destroy() }
    }
    @Test fun dndSuppressesTtsAndSuccessfulAnnouncementRestoresPausedMusic() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        val manager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            shadowOf(manager).setNotificationPolicyAccessGranted(true)
            play(service, "https://public.example/music.mp3")
            player(service).pause()
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            play(service, "http://ha.local/api/tts_proxy/a.mp3", true)
            assertEquals("https://public.example/music.mp3", player(service).currentMediaItem!!.localConfiguration!!.uri.toString())
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            play(service, "https://remote.ui.nabu.casa/api/tts_proxy/a.mp3", true)
            service.playbackListener.onPlaybackStateChanged(Player.STATE_READY)
            service.playbackListener.onPlaybackStateChanged(Player.STATE_ENDED)
            assertEquals("https://public.example/music.mp3", player(service).currentMediaItem!!.localConfiguration!!.uri.toString())
            assertFalse(player(service).playWhenReady)
        } finally { controller.destroy() }
    }
}
