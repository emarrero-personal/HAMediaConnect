package us.marreros.haandroidmediaplayer

import android.app.Notification
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import com.google.common.collect.ImmutableList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackAuthorizationTest {
    @Test fun notificationActionsCarryImmutableCapabilities() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
        var wrapped: MediaNotification.ActionFactory? = null
        val delegate = object : MediaNotification.Provider {
            override fun getNotificationChannelInfo() = MediaNotification.Provider.NotificationChannelInfo("test", "Test")
            override fun handleCustomCommand(session: MediaSession, action: String, extras: android.os.Bundle) = false
            override fun createNotification(session: MediaSession, preferences: ImmutableList<CommandButton>, factory: MediaNotification.ActionFactory, callback: MediaNotification.Provider.Callback): MediaNotification {
                wrapped = factory
                return MediaNotification(1, Notification())
            }
        }
        val unused = java.lang.reflect.Proxy.newProxyInstance(MediaNotification.ActionFactory::class.java.classLoader,
            arrayOf(MediaNotification.ActionFactory::class.java)) { _, _, _ -> error("Unsafe default action factory invoked") } as MediaNotification.ActionFactory
        try {
            val provider = PlaybackNotifications(context, delegate)
            provider.createNotification(session, ImmutableList.of(), unused, object : MediaNotification.Provider.Callback {
                override fun onNotificationChanged(notification: MediaNotification) { }
            })
            for ((command, action) in listOf(Player.COMMAND_PLAY_PAUSE to "notification_toggle", Player.COMMAND_STOP to PlaybackService.CMD_STOP)) {
                val pending = wrapped!!.createMediaActionPendingIntent(session, command)
                val saved = shadowOf(pending).savedIntent
                assertEquals(action, saved.action)
                assertTrue(InternalPlayback.allowed(saved))
                assertTrue(pending.isImmutable)
                assertEquals(PlaybackService::class.java.name, saved.component!!.className)
            }
            val dismissal = wrapped!!.createNotificationDismissalIntent(session)
            assertTrue(dismissal.isImmutable)
            assertTrue(InternalPlayback.allowed(shadowOf(dismissal).savedIntent))
            assertEquals(PlaybackService.CMD_STOP, shadowOf(dismissal).savedIntent.action)
            assertEquals("test", provider.notificationChannelInfo.id)
            assertFalse(provider.handleCustomCommand(session, "untrusted", android.os.Bundle()))
        } finally { session.release(); player.release() }
    }
    @Test fun rawServiceAndMediaButtonIntentsCannotBypassControllerAuthorization() {
        val serviceController = Robolectric.buildService(PlaybackService::class.java).create()
        val service = serviceController.get()
        try {
            val urlField = PlaybackService::class.java.getDeclaredField("currentUrl").apply { isAccessible = true }
            service.onStartCommand(Intent().setAction(PlaybackService.CMD_PLAY_MEDIA).putExtra(PlaybackService.EXTRA_URL, "http://example.com/a.mp3"), 0, 1)
            assertNull(urlField.get(service))
            service.onStartCommand(Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT,
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)), 0, 2)
            val player = PlaybackService::class.java.getDeclaredField("player").apply { isAccessible = true }.get(service) as ExoPlayer
            assertFalse(player.playWhenReady)
            service.onStartCommand(InternalPlayback.authorize(Intent().setAction(PlaybackService.CMD_PLAY)), 0, 3)
            assertTrue(player.playWhenReady)
            service.onStartCommand(InternalPlayback.authorize(Intent().setAction("notification_toggle")), 0, 4)
        } finally { serviceController.destroy() }
    }
}
