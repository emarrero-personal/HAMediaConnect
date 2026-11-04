package us.marreros.haandroidmediaplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "HAMediaConnectBoot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = AppPrefs.prefs(context)
        if (!prefs.getBoolean(AppPrefs.KEY_START_BOOT, true)) return
        if (prefs.getString(AppPrefs.KEY_URL, "").isNullOrBlank()) return

        // Credentials now live in Keystore-backed storage, not SharedPreferences.
        // ConnectionService handles OAuth refresh, including temporary network failures.
        val credentials = SecureTokenStore(context)
        val tokenMode = credentials.get("auth_method") == "token"
        val hasCredential = if (tokenMode) {
            !credentials.get("long_lived_token").isNullOrBlank()
        } else {
            !credentials.get("access_token").isNullOrBlank() ||
                !credentials.get("refresh_token").isNullOrBlank()
        }
        if (!hasCredential) {
            Log.i(TAG, "Skipping reconnect: no stored credentials")
            return
        }

        try {
            ContextCompat.startForegroundService(
                context, Intent(context, ConnectionService::class.java)
            )
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unable to start connection after boot", e)
        }
    }
}
