package us.marreros.haandroidmediaplayer

import android.content.Context
import android.view.View
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StatusUiTest {
    @Test fun idleFollowsConnectivityWhileBufferedAudioAndErrorsRemainIndependent() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = AppPrefs.prefs(context)
        prefs.edit().clear().commit()
        PlayerStateBus.publish(PlayerSnapshot("idle", .5f, false, 0, 0, null, null))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            fun phase(value: ConnectionPhase) {
                prefs.edit().putString(AppPrefs.KEY_CONNECTION_PHASE, value.name)
                    .putString(AppPrefs.KEY_STATUS, ConnectionEvent(value).message).commit()
                shadowOf(Looper.getMainLooper()).idle()
            }
            fun text() = activity.findViewById<TextView>(R.id.playbackStatus).text.toString()
            phase(ConnectionPhase.CONNECTED); assertTrue(text().startsWith("Ready"))
            phase(ConnectionPhase.RECONNECTING); assertEquals("Waiting for Home Assistant connection", text())
            phase(ConnectionPhase.DISCONNECTED); assertEquals("Unavailable — Not connected", text())
            PlayerStateBus.publish(PlayerSnapshot("playing", .5f, false, 1000, 2000, "Music", "https://public.example/song"))
            assertTrue(text().startsWith("Playing"))
            phase(ConnectionPhase.RECONNECTING); assertTrue(text().startsWith("Playing"))
            PlayerStateBus.publish(PlayerSnapshot("idle", .5f, false, 0, 0, null, "https://ha.example/api/tts_proxy/secret", PlaybackFailure.TIMEOUT))
            phase(ConnectionPhase.DISCONNECTED); assertTrue(text().startsWith("Failed"))
            phase(ConnectionPhase.CONNECTED); assertTrue(text().contains(PlaybackFailure.TIMEOUT))
            PlayerStateBus.publish(PlayerSnapshot("idle", .5f, false, 0, 0, null, null))
            assertTrue(text().startsWith("Ready"))
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun settingsWaitsForDeviceAuthenticationAndPlaybackNeverChangesConnection() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = AppPrefs.prefs(context)
        prefs.edit().clear().putString(AppPrefs.KEY_STATUS, "Disconnected").commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            activity.findViewById<View>(R.id.settingsButton).performClick()
            MainActivity::class.java.getDeclaredField("awaitingConnection").apply { isAccessible = true }.set(activity, true)
            fun status(value: String) {
                prefs.edit().putString(AppPrefs.KEY_STATUS, value).commit()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(value, activity.findViewById<TextView>(R.id.settingsStatus).text.toString())
            }
            status("Connecting")
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsScreen).visibility)
            status("Device authentication rejected; review registration")
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsScreen).visibility)
            activity.findViewById<View>(R.id.settingsBack).performClick()
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.homeScreen).visibility)
            activity.findViewById<View>(R.id.settingsButton).performClick()
            status("Connected")
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.homeScreen).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.settingsScreen).visibility)
            for ((state, error) in listOf("buffering" to null, "playing" to null, "paused" to null, "idle" to PlaybackFailure.TIMEOUT, "idle" to null)) {
                PlayerStateBus.publish(PlayerSnapshot(state, .5f, false, 0, 0, "Music", "https://ha.example/api/tts_proxy/secret", error))
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("Connected", activity.findViewById<TextView>(R.id.statusText).text.toString())
                val text = activity.findViewById<TextView>(R.id.playbackStatus).text.toString()
                assertFalse(text.contains("secret"))
                if (error != null) assertTrue(text.contains("Failed"))
            }
            assertTrue(activity.findViewById<TextView>(R.id.playbackStatus).isTextSelectable)
        } finally { controller.pause().stop().destroy() }
    }
}
