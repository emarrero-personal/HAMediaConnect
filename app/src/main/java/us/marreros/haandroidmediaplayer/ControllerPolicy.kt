package us.marreros.haandroidmediaplayer

import android.content.Intent
import android.os.Process
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommands

/** Trust is calculated by Media3/platform from Binder UID and package permissions. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object ControllerPolicy {
    fun allowed(uid: Int, trusted: Boolean, ownUid: Int = Process.myUid()) = uid >= 0 && (uid == ownUid || trusted)
    val playerCommands: Player.Commands = Player.Commands.Builder().addAll(
        Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
        Player.COMMAND_GET_METADATA, Player.COMMAND_GET_VOLUME, Player.COMMAND_GET_DEVICE_VOLUME
    ).build()
    fun result(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
        if (!allowed(controller.uid, controller.isTrusted)) MediaSession.ConnectionResult.reject()
        else MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            .setAvailableSessionCommands(SessionCommands.EMPTY)
            .setAvailablePlayerCommands(playerCommands).build()
}

/** Process-local 256-bit capability; only carried in explicit app intents and immutable notification PendingIntents. */
object InternalPlayback {
    private val capability = HaOAuth.randomUrlSafe(32)
    private const val EXTRA = "us.marreros.internal_playback"
    fun authorize(intent: Intent): Intent = intent.putExtra(EXTRA, capability)
    fun allowed(intent: Intent?): Boolean = try {
        intent?.getStringExtra(EXTRA)?.let {
            java.security.MessageDigest.isEqual(it.toByteArray(), capability.toByteArray())
        } ?: false
    } catch (_: RuntimeException) { false }
}
