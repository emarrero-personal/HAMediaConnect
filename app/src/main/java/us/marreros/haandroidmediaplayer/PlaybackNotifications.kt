package us.marreros.haandroidmediaplayer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.Player
import androidx.media3.session.*
import com.google.common.collect.ImmutableList

/** Immutable, narrowly-scoped notification capabilities; raw exported service intents do nothing. */
class PlaybackNotifications(private val context: Context,
    private val delegate: MediaNotification.Provider = DefaultMediaNotificationProvider(context)
) : MediaNotification.Provider {
    override fun getNotificationChannelInfo() = delegate.notificationChannelInfo
    override fun handleCustomCommand(session: MediaSession, action: String, extras: android.os.Bundle) = false
    override fun createNotification(
        session: MediaSession, preferences: ImmutableList<CommandButton>,
        factory: MediaNotification.ActionFactory, callback: MediaNotification.Provider.Callback
    ): MediaNotification = delegate.createNotification(session, preferences, object : MediaNotification.ActionFactory by factory {
        override fun createMediaActionPendingIntent(session: MediaSession, command: Int): PendingIntent {
            val action = when (command) {
                Player.COMMAND_PLAY_PAUSE -> "notification_toggle"
                Player.COMMAND_STOP -> PlaybackService.CMD_STOP
                else -> "notification_ignored"
            }
            val intent = InternalPlayback.authorize(Intent(context, PlaybackService::class.java).setAction(action))
            return PendingIntent.getService(context, command, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        override fun createNotificationDismissalIntent(session: MediaSession): PendingIntent =
            createMediaActionPendingIntent(session, Player.COMMAND_STOP)
        override fun createMediaAction(session: MediaSession, icon: IconCompat, title: CharSequence, command: Int): NotificationCompat.Action =
            NotificationCompat.Action.Builder(icon, title, createMediaActionPendingIntent(session, command)).build()
    }, callback)
}
