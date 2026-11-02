package us.marreros.haandroidmediaplayer

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var urlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var startOnBootCheck: CheckBox
    private lateinit var deviceIdText: TextView

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == AppPrefs.KEY_STATUS) {
            statusText.text = prefs.getString(AppPrefs.KEY_STATUS, "Disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        urlInput = findViewById(R.id.urlInput)
        tokenInput = findViewById(R.id.tokenInput)
        nameInput = findViewById(R.id.nameInput)
        startOnBootCheck = findViewById(R.id.startOnBootCheck)
        deviceIdText = findViewById(R.id.deviceIdText)

        val prefs = AppPrefs.prefs(this)
        val deviceId = AppPrefs.ensureDeviceId(this)
        urlInput.setText(prefs.getString(AppPrefs.KEY_URL, ""))
        tokenInput.setText(prefs.getString(AppPrefs.KEY_TOKEN, ""))
        nameInput.setText(prefs.getString(AppPrefs.KEY_NAME, Build.MODEL))
        startOnBootCheck.isChecked = prefs.getBoolean(AppPrefs.KEY_START_BOOT, true)
        statusText.text = prefs.getString(AppPrefs.KEY_STATUS, "Disconnected")
        deviceIdText.text = "Device ID: $deviceId"
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        findViewById<Button>(R.id.saveStartButton).setOnClickListener {
            val url = urlInput.text.toString().trim().trimEnd('/')
            val token = tokenInput.text.toString().trim()
            val name = nameInput.text.toString().trim().ifBlank { Build.MODEL }
            prefs.edit()
                .putString(AppPrefs.KEY_URL, url)
                .putString(AppPrefs.KEY_TOKEN, token)
                .putString(AppPrefs.KEY_NAME, name)
                .putBoolean(AppPrefs.KEY_START_BOOT, startOnBootCheck.isChecked)
                .apply()

            requestNotificationsIfNeeded()
            ContextCompat.startForegroundService(this, Intent(this, ConnectionService::class.java))
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, ConnectionService::class.java))
            stopService(Intent(this, PlaybackService::class.java))
            prefs.edit().putString(AppPrefs.KEY_STATUS, "Stopped").apply()
        }
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onDestroy() {
        AppPrefs.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }
}
