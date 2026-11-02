package us.marreros.haandroidmediaplayer

import android.Manifest
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import java.util.concurrent.Executors
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.LinearLayout
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private val oauthExecutor = Executors.newSingleThreadExecutor()
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
        val settingsPanel = findViewById<LinearLayout>(R.id.settingsPanel)
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            settingsPanel.visibility = if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.helpButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Getting started with HAMediaConnect")
                .setMessage("1. Open Connection settings and enter your Home Assistant URL.\n\n2. For HTTPS, select Sign in with Home Assistant (OAuth). For a local HTTP instance, paste a long-lived access token from your Home Assistant profile and tap Save & Start.\n\n3. Once connected, use the HAMediaConnect media player entity in Home Assistant to play audio or send TTS announcements.\n\nTip: Keep your access token private. Never share it in screenshots or logs.")
                .setPositiveButton("Got it", null).show()
        }
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
        findViewById<Button>(R.id.oauthButton).setOnClickListener { startOAuth() }
        handleOAuthCallback(intent)

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


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthCallback(intent)
    }

    private fun startOAuth() {
        val url = urlInput.text.toString().trim().trimEnd('/')
        if (!url.startsWith("https://")) {
            statusText.text = "OAuth requires HTTPS (Nabu Casa URL supported)"
            return
        }
        val state = HaOAuth.randomUrlSafe(24)
        val verifier = HaOAuth.randomUrlSafe(48)
        AppPrefs.prefs(this).edit()
            .putString(AppPrefs.KEY_URL, url)
            .putString("oauth_pending_state", state)
            .putString("oauth_pending_verifier", verifier)
            .putLong("oauth_pending_at", System.currentTimeMillis()).apply()
        CustomTabsIntent.Builder().build().launchUrl(this, HaOAuth(url).authorizationUrl(state, verifier))
    }

    private fun handleOAuthCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "haandroidmediaplayer" || uri.host != "oauth" || uri.path != "/callback") return
        intent.data = null
        val prefs = AppPrefs.prefs(this)
        val state = prefs.getString("oauth_pending_state", null)
        val verifier = prefs.getString("oauth_pending_verifier", null)
        val issued = prefs.getLong("oauth_pending_at", 0)
        prefs.edit().remove("oauth_pending_state").remove("oauth_pending_verifier")
            .remove("oauth_pending_at").apply()
        if (state == null || verifier == null || issued <= 0 ||
            System.currentTimeMillis() - issued !in 0..600000 ||
            uri.getQueryParameter("state") != state) {
            statusText.text = "OAuth callback rejected"
            return
        }
        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            statusText.text = "OAuth authorization denied"
            return
        }
        statusText.text = "Exchanging authorization code…"
        val url = prefs.getString(AppPrefs.KEY_URL, "")!!
        oauthExecutor.execute {
            try {
                val tokens = HaOAuth(url).exchange(code, verifier)
                val store = SecureTokenStore(this)
                store.put("access_token", tokens.getString("access_token"))
                store.put("refresh_token", tokens.getString("refresh_token"))
                prefs.edit().putLong("oauth_expiry",
                    System.currentTimeMillis() + tokens.optLong("expires_in", 1800) * 1000).apply()
                runOnUiThread {
                    statusText.text = "OAuth authenticated"
                    requestNotificationsIfNeeded()
                    ContextCompat.startForegroundService(this, Intent(this, ConnectionService::class.java))
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "OAuth exchange failed: ${e.message}" }
            }
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
        oauthExecutor.shutdown()
        super.onDestroy()
    }
}
