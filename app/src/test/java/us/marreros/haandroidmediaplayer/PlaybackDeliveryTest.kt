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
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    @Test fun failureRemainsLiveUntilStopWithoutChangingConnectionStatus() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        val prefs = AppPrefs.prefs(service)
        prefs.edit().putString(AppPrefs.KEY_STATUS, "Connected").commit()
        try {
            play(service, "https://remote.ui.nabu.casa/api/tts_proxy/opaque?token=secret", true)
            assertEquals("buffering", PlayerStateBus.latest!!.state)
            val old = service.playbackListener
            old.onPlayerError(PlaybackException("secret", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
            assertTrue(PlaybackDiagnostic.summary(PlayerStateBus.latest!!).startsWith("Failed"))
            assertTrue(PlaybackDiagnostic.summary(PlayerStateBus.latest!!).contains("HTTPS · Nabu Casa"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertEquals(PlaybackFailure.TIMEOUT, PlayerStateBus.latest!!.error)
            assertEquals("Connected", prefs.getString(AppPrefs.KEY_STATUS, ""))
            service.onStartCommand(InternalPlayback.authorize(Intent().setAction(PlaybackService.CMD_STOP)), 0, 1)
            old.onPlayerError(PlaybackException("late secret", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
            assertNull(PlayerStateBus.latest!!.error)
            assertNull(prefs.getString("playback_error", null))
            assertNull(PlayerStateBus.latest!!.contentId)
            assertTrue(PlaybackDiagnostic.summary(PlayerStateBus.latest!!).startsWith("Ready"))
        } finally { controller.destroy() }
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
        // Stall a real local connection; external DNS failures can race the timer.
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val connected = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val request = executor.submit {
                server.accept().use {
                    connected.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
            val controller = Robolectric.buildService(PlaybackService::class.java).create()
            val service = controller.get()
            var snapshot: PlayerSnapshot? = null
            PlayerStateBus.listener = { snapshot = it }
            try {
                play(service, "http://127.0.0.1:${server.localPort}/stalled.mp3")
                assertEquals(Player.STATE_BUFFERING, player(service).playbackState)
                assertTrue("Media3 must connect to the stalled endpoint", connected.await(5, TimeUnit.SECONDS))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PlaybackFailure.BUFFER_TIMEOUT_MS))
                assertEquals(Player.STATE_IDLE, player(service).playbackState)
                assertEquals("idle", snapshot!!.state)
                assertEquals(PlaybackFailure.TIMEOUT, snapshot!!.error)
            } finally {
                release.countDown()
                PlayerStateBus.listener = null
                controller.destroy()
                executor.shutdownNow()
                request.get(5, TimeUnit.SECONDS)
            }
        }
    }
    @Test fun dndSuppressesTtsAndSuccessfulAnnouncementRestoresPausedMusic() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        val manager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            shadowOf(manager).setNotificationPolicyAccessGranted(true)
            play(service, "https://public.example/music.mp3")
            player(service).seekTo(6_543L)
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
    @Test fun staleEndedAndReadyFromReplacedMusicCannotCompleteAnnouncement() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        try {
            play(service, "https://public.example/music.mp3")
            val musicListener = service.playbackListener
            play(service, "https://ha.example/api/tts_proxy/first", true)
            val announcementListener = service.playbackListener
            musicListener.onPlaybackStateChanged(Player.STATE_READY)
            musicListener.onPlaybackStateChanged(Player.STATE_ENDED)
            announcementListener.onPlaybackStateChanged(Player.STATE_ENDED)
            assertEquals("https://ha.example/api/tts_proxy/first", player(service).currentMediaItem!!.localConfiguration!!.uri.toString())
            announcementListener.onPlaybackStateChanged(Player.STATE_READY)
            musicListener.onPlaybackStateChanged(Player.STATE_ENDED)
            assertEquals("https://ha.example/api/tts_proxy/first", player(service).currentMediaItem!!.localConfiguration!!.uri.toString())
            announcementListener.onPlaybackStateChanged(Player.STATE_ENDED)
            assertEquals("https://public.example/music.mp3", player(service).currentMediaItem!!.localConfiguration!!.uri.toString())
        } finally { controller.destroy() }
    }

    @Test fun repeatedSameUrlAnnouncementsResetReadyAndRestoreOriginalMusicOnce() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        try {
            play(service, "https://public.example/music.mp3")
            player(service).seekTo(42_000L)
            val url = "https://ha.example/api/tts_proxy/repeated"
            play(service, url, true)
            val first = service.playbackListener
            first.onPlaybackStateChanged(Player.STATE_READY)
            play(service, url, true)
            val second = service.playbackListener
            second.onPlaybackStateChanged(Player.STATE_ENDED)
            first.onPlaybackStateChanged(Player.STATE_ENDED)
            first.onPlayerError(PlaybackException("old secret", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
            assertEquals(url, player(service).currentMediaItem!!.localConfiguration!!.uri.toString())
            assertNull(AppPrefs.prefs(service).getString("playback_error", null))
            second.onPlaybackStateChanged(Player.STATE_READY)
            first.onPlaybackStateChanged(Player.STATE_ENDED)
            assertEquals(url, player(service).currentMediaItem!!.localConfiguration!!.uri.toString())
            second.onPlaybackStateChanged(Player.STATE_ENDED)
            second.onPlaybackStateChanged(Player.STATE_ENDED)
            assertEquals("https://public.example/music.mp3", player(service).currentMediaItem!!.localConfiguration!!.uri.toString())
            assertEquals(42_000L, player(service).currentPosition)
            assertTrue(player(service).playWhenReady)
        } finally { controller.destroy() }
    }

    @Test fun rapidReplacementWithMusicDiscardsOldAnnouncementCompletionAndSavedMedia() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        try {
            play(service, "https://public.example/original.mp3")
            play(service, "https://ha.example/api/tts_proxy/first", true)
            val old = service.playbackListener
            old.onPlaybackStateChanged(Player.STATE_READY)
            play(service, "https://public.example/replacement.mp3")
            old.onPlaybackStateChanged(Player.STATE_ENDED)
            old.onPlayerError(PlaybackException("old secret", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
            assertEquals("https://public.example/replacement.mp3", player(service).currentMediaItem!!.localConfiguration!!.uri.toString())
            play(service, "https://ha.example/api/tts_proxy/second", true)
            val current = service.playbackListener
            current.onPlaybackStateChanged(Player.STATE_READY)
            current.onPlaybackStateChanged(Player.STATE_ENDED)
            assertEquals("https://public.example/replacement.mp3", player(service).currentMediaItem!!.localConfiguration!!.uri.toString())
        } finally { controller.destroy() }
    }

    @Test fun announcementErrorsBeforeAndAfterReadyRestorePausedPositionAndIgnoreLateCallbacks() {
        for (ready in listOf(false, true)) {
            val controller = Robolectric.buildService(PlaybackService::class.java).create()
            val service = controller.get()
            var snapshot: PlayerSnapshot? = null
            PlayerStateBus.listener = { snapshot = it }
            try {
                play(service, "https://public.example/paused.mp3")
                player(service).seekTo(9_876L)
                player(service).pause()
                play(service, "https://ha.example/api/tts_proxy/failed", true)
                val failed = service.playbackListener
                if (ready) failed.onPlaybackStateChanged(Player.STATE_READY)
                failed.onPlayerError(PlaybackException("signed secret", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
                failed.onPlaybackStateChanged(Player.STATE_READY)
                failed.onPlaybackStateChanged(Player.STATE_ENDED)
                failed.onPlayerError(PlaybackException("duplicate secret", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
                assertEquals("https://public.example/paused.mp3", player(service).currentMediaItem!!.localConfiguration!!.uri.toString())
                assertEquals(9_876L, player(service).currentPosition)
                assertFalse(player(service).playWhenReady)
                val error = AppPrefs.prefs(service).getString("playback_error", "")!!
                assertTrue(error.contains("decoding failed"))
                assertFalse(error.contains("secret"))
                // HA receives the restored media snapshot, with no independent error field.
                assertEquals("https://public.example/paused.mp3", snapshot!!.contentId)
                assertFalse(snapshot!!.title.orEmpty().contains("decoding failed"))
                assertTrue(snapshot!!.notice!!.contains("Announcement failed"))
                assertFalse(snapshot!!.notice!!.contains("secret"))
            } finally { PlayerStateBus.listener = null; controller.destroy() }
        }
    }

    @Test fun stopDuringAnnouncementInvalidatesCompletionAndErrorCallbacks() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        try {
            play(service, "https://public.example/music.mp3")
            play(service, "https://ha.example/api/tts_proxy/stop", true)
            val stopped = service.playbackListener
            stopped.onPlaybackStateChanged(Player.STATE_READY)
            service.onStartCommand(InternalPlayback.authorize(Intent().setAction(PlaybackService.CMD_STOP)), 0, 1)
            stopped.onPlaybackStateChanged(Player.STATE_ENDED)
            stopped.onPlayerError(PlaybackException("old secret", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
            assertEquals(0, player(service).mediaItemCount)
            assertNull(AppPrefs.prefs(service).getString("playback_error", null))
        } finally { controller.destroy() }
    }

    @Test fun emptyAnnouncementEndingBeforeReadyReportsFailureAndRestoresMusic() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        try {
            play(service, "https://public.example/music.mp3")
            play(service, "https://ha.example/api/tts_proxy/empty", true)
            // An empty timeline reaches real ENDED without any READY callback.
            player(service).clearMediaItems()
            assertEquals("https://public.example/music.mp3", player(service).currentMediaItem!!.localConfiguration!!.uri.toString())
            assertTrue(AppPrefs.prefs(service).getString("playback_error", "")!!.contains("without playable audio"))
        } finally { controller.destroy() }
    }

}
