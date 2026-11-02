package us.marreros.haandroidmediaplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = AppPrefs.prefs(context)
        if (!prefs.getBoolean(AppPrefs.KEY_START_BOOT, true)) return
        if (prefs.getString(AppPrefs.KEY_URL, "").isNullOrBlank()) return
        if (prefs.getString(AppPrefs.KEY_TOKEN, "").isNullOrBlank()) return
        ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
    }
}
