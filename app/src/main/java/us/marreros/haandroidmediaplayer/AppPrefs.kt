package us.marreros.haandroidmediaplayer

import android.content.Context

object AppPrefs {
    private const val FILE = "ha_android_media_player"
    const val KEY_URL = "ha_url"
    const val KEY_ALLOW_HTTP = "allow_insecure_http"

    fun isAllowedUrl(url: String, allowHttp: Boolean): Boolean {
        return try { TransportPolicy.base(url, allowHttp); true } catch (_: Exception) { false }
    }
    const val KEY_NAME = "device_name"
    const val KEY_START_BOOT = "start_on_boot"
    const val KEY_BLOCKED_FAILURE = "blocked_connection_failure"
    const val KEY_CONNECTION_PHASE = "connection_phase"
    const val KEY_STATUS = "status"

    fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

}
