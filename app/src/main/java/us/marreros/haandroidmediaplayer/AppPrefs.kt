package us.marreros.haandroidmediaplayer

import android.content.Context
import java.util.UUID

object AppPrefs {
    private const val FILE = "ha_android_media_player"
    const val KEY_URL = "ha_url"
    const val KEY_TOKEN = "ha_token"
    const val KEY_NAME = "device_name"
    const val KEY_DEVICE_ID = "device_id"
    const val KEY_START_BOOT = "start_on_boot"
    const val KEY_STATUS = "status"

    fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun ensureDeviceId(context: Context): String {
        val prefs = prefs(context)
        return prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }
}
