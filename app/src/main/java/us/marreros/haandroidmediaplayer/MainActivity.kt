package us.marreros.haandroidmediaplayer

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import java.util.concurrent.Executors
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.RadioButton
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.OnBackPressedCallback

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private val oauthExecutor = Executors.newSingleThreadExecutor()
    private lateinit var urlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var startOnBootCheck: CheckBox
    private lateinit var allowHttpCheck: CheckBox
    private lateinit var deviceIdText: TextView
    private var oauthGeneration = 0
    private var awaitingConnection = false
    private var openSettings: (() -> Unit)? = null
    private var returnHome: (() -> Unit)? = null
    private fun connectionStatus(value: String, authenticated: Boolean = false) {
        statusText.text = value
        findViewById<TextView>(R.id.settingsStatus).text = value
        if (awaitingConnection && authenticated) {
            awaitingConnection = false
            returnHome?.invoke()
        }
    }
    private fun playbackConnection(): ConnectionPhase {
        val prefs = AppPrefs.prefs(this)
        return prefs.getString(AppPrefs.KEY_CONNECTION_PHASE, null)?.let {
            runCatching { ConnectionPhase.valueOf(it) }.getOrNull()
        } ?: when (prefs.getString(AppPrefs.KEY_STATUS, "Disconnected")) {
            "Connected" -> ConnectionPhase.CONNECTED
            "Connecting", "Connecting…" -> ConnectionPhase.CONNECTING
            "Reconnecting" -> ConnectionPhase.RECONNECTING
            else -> ConnectionPhase.DISCONNECTED
        }
    }
    private fun renderPlayback(snapshot: PlayerSnapshot? = PlayerStateBus.latest) {
        val idle = PlayerSnapshot("idle", 0f, false, 0, 0, null, null)
        findViewById<TextView>(R.id.playbackStatus).text =
            PlaybackDiagnostic.summary(snapshot ?: idle, playbackConnection(), DeliveryTracker.latest)
    }
    private val playbackObserver: (PlayerSnapshot) -> Unit = { snapshot ->
        runOnUiThread { renderPlayback(snapshot) }
    }
    private val deliveryObserver: (DeliveryStatus) -> Unit = { runOnUiThread { renderPlayback() } }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants["android.permission.ACCESS_LOCAL_NETWORK"] == false) {
            findViewById<TextView>(R.id.playbackStatus).text = "Local playback needs Local network permission in Android app settings"
        }
    }

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == AppPrefs.KEY_STATUS || key == AppPrefs.KEY_CONNECTION_PHASE) {
            connectionStatus(prefs.getString(AppPrefs.KEY_STATUS, "Disconnected") ?: "Disconnected",
                authenticated = playbackConnection() == ConnectionPhase.CONNECTED)
            renderPlayback()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        awaitingConnection = savedInstanceState?.getBoolean("awaiting_connection") ?: false
        val root = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        statusText = findViewById(R.id.statusText)
        val homeScreen = findViewById<android.view.View>(R.id.homeScreen)
        val settingsScreen = findViewById<android.view.View>(R.id.settingsScreen)
        val helpScreen = findViewById<android.view.View>(R.id.helpScreen)
        val navigationCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                homeScreen.visibility = View.VISIBLE
                settingsScreen.visibility = View.GONE
                helpScreen.visibility = View.GONE
                isEnabled = false
            }
        }
        onBackPressedDispatcher.addCallback(this, navigationCallback)
        fun showScreen(screen: android.view.View) {
            homeScreen.visibility = if (screen === homeScreen) View.VISIBLE else View.GONE
            settingsScreen.visibility = if (screen === settingsScreen) View.VISIBLE else View.GONE
            helpScreen.visibility = if (screen === helpScreen) View.VISIBLE else View.GONE
            navigationCallback.isEnabled = screen !== homeScreen
        }
        returnHome = { showScreen(homeScreen) }
        openSettings = { showScreen(settingsScreen) }
        if (awaitingConnection) showScreen(settingsScreen)
        PlayerStateBus.observer = playbackObserver
        DeliveryTracker.observe(deliveryObserver)
        renderPlayback()
        findViewById<android.view.View>(R.id.settingsButton).setOnClickListener { showScreen(settingsScreen) }
        findViewById<android.view.View>(R.id.helpButton).setOnClickListener { showScreen(helpScreen) }
        findViewById<android.view.View>(R.id.gettingStartedCard).setOnClickListener { showScreen(helpScreen) }
        findViewById<android.view.View>(R.id.settingsBack).setOnClickListener { showScreen(homeScreen) }
        findViewById<android.view.View>(R.id.helpBack).setOnClickListener { showScreen(homeScreen) }
        findViewById<View>(R.id.authHelpLink).setOnClickListener {
            AlertDialog.Builder(this).setTitle("OAuth or long-lived token?")
                .setMessage("OAuth is recommended when Home Assistant is available over HTTPS. It uses account sign-in and refreshable credentials. A long-lived access token works for local HTTP installations without HTTPS. HTTP does not encrypt your token in transit: use it only on a trusted local network, never over the public internet.")
                .setPositiveButton("Close", null).show()
        }
        findViewById<View>(R.id.integrationInstallLink).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/emarrero-personal/HAMediaConnect#install-the-home-assistant-integration")))
        }
        val oauthChoice = findViewById<RadioButton>(R.id.oauthChoice)
        val tokenChoice = findViewById<RadioButton>(R.id.tokenChoice)
        val oauthButton = findViewById<Button>(R.id.oauthButton)
        val tokenField = findViewById<EditText>(R.id.tokenInput)
        findViewById<RadioGroup>(R.id.authMethodGroup).setOnCheckedChangeListener { _, checkedId ->
            val useToken = checkedId == R.id.tokenChoice
            tokenField.visibility = if (useToken) View.VISIBLE else View.GONE
            oauthButton.visibility = if (useToken) View.GONE else View.VISIBLE
        }
        urlInput = findViewById(R.id.urlInput)
        tokenInput = findViewById(R.id.tokenInput)
        tokenInput.isSaveEnabled = false
        tokenInput.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        nameInput = findViewById(R.id.nameInput)
        startOnBootCheck = findViewById(R.id.startOnBootCheck)
        allowHttpCheck = findViewById(R.id.allowHttpCheck)
        deviceIdText = findViewById(R.id.deviceIdText)

        val prefs = AppPrefs.prefs(this)
        val deviceId = try { DeviceIdentity(this).deviceId } catch (_: Exception) { "Key unavailable — reset identity" }
        urlInput.setText(prefs.getString(AppPrefs.KEY_URL, ""))
        tokenInput.setText("")
        if (SecureTokenStore(this).get("auth_method") == "token") tokenChoice.isChecked = true
        nameInput.setText(prefs.getString(AppPrefs.KEY_NAME, Build.MODEL))
        startOnBootCheck.isChecked = prefs.getBoolean(AppPrefs.KEY_START_BOOT, true)
        allowHttpCheck.isChecked = prefs.getBoolean(AppPrefs.KEY_ALLOW_HTTP, false)
        connectionStatus(prefs.getString(AppPrefs.KEY_STATUS, "Disconnected") ?: "Disconnected",
                authenticated = playbackConnection() == ConnectionPhase.CONNECTED)
        deviceIdText.text = "Device ID: $deviceId"
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        allowHttpCheck.setOnCheckedChangeListener { _, checked ->
            if (checked) AlertDialog.Builder(this).setTitle("Unencrypted control connection")
                .setMessage("HTTP exposes your Home Assistant token, commands, and device authentication to anyone able to intercept this network. Use only on a trusted local network.")
                .setPositiveButton("Allow HTTP", null)
                .setNegativeButton("Cancel") { _, _ -> allowHttpCheck.isChecked = false }.show()
        }
        findViewById<Button>(R.id.forgetCredentialsButton).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Forget Home Assistant credentials?")
                .setMessage("Disconnect and remove saved tokens and pending sign-in. Your device identity remains registered. Sign in again to reconnect.")
                .setPositiveButton("Forget credentials") { _, _ ->
                    oauthGeneration++
                    stopService(Intent(this, ConnectionService::class.java))
                    stopService(Intent(this, PlaybackService::class.java))
                    try { SecureTokenStore.reset(this); tokenInput.text.clear(); connectionStatus("Credentials removed; sign in again") }
                    catch (_: Exception) { connectionStatus("Unable to reset credential storage") }
                }.setNegativeButton("Cancel", null).show()
        }
        findViewById<Button>(R.id.resetIdentityButton).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Reset device identity?")
                .setMessage("This creates a new player identity. Revoke the old device in Home Assistant first. Existing entities will not transfer to the new identity.")
                .setPositiveButton("Reset identity") { _, _ ->
                    stopService(Intent(this, ConnectionService::class.java))
                    stopService(Intent(this, PlaybackService::class.java))
                    oauthGeneration++
                    try {
                        OAuthAttempt(SecureTokenStore(this)).cancel()
                        DeviceIdentity.reset(this)
                        deviceIdText.text = "Device ID: ${DeviceIdentity(this).deviceId}"
                        connectionStatus("New identity created; save and connect to register")
                    } catch (_: Exception) { connectionStatus("Device key unavailable") }
                }.setNegativeButton("Cancel", null).show()
        }
        findViewById<Button>(R.id.oauthButton).setOnClickListener { startOAuth() }
        handleOAuthCallback(intent)

        findViewById<Button>(R.id.saveStartButton).setOnClickListener {
            val url = urlInput.text.toString().trim().trimEnd('/')
            val token = tokenInput.text.toString().trim()
            if (!AppPrefs.isAllowedUrl(url, allowHttpCheck.isChecked)) {
                urlInput.error = "Use HTTPS or explicitly allow insecure HTTP"
                return@setOnClickListener
            }
            if (token.length > 8192) { tokenInput.error = "Token is too long"; return@setOnClickListener }
            val secrets = SecureTokenStore(this)
            if (tokenChoice.isChecked && token.isBlank() &&
                (secrets.get("long_lived_token").isNullOrBlank() || secrets.get("credential_origin") != url)) {
                tokenInput.error = "Enter a long-lived access token"
                return@setOnClickListener
            }
            if (!tokenChoice.isChecked && (secrets.get("auth_method") != "oauth" || secrets.get("credential_origin") != url)) {
                connectionStatus("Sign in with OAuth for this Home Assistant URL")
                return@setOnClickListener
            }
            oauthGeneration++
            try {
                OAuthAttempt(secrets).cancel()
                if (tokenChoice.isChecked) secrets.apply {
                    if (get("auth_method") != "token" || get("credential_origin") != url) clear()
                    if (token.isNotBlank()) put("long_lived_token", token)
                    put("credential_origin", url)
                    put("auth_method", "token")
                    tokenInput.text.clear()
                }
            } catch (_: Exception) { connectionStatus("Credential storage unavailable; sign in again"); return@setOnClickListener }
            val name = nameInput.text.toString().trim().ifBlank { Build.MODEL }.take(128)
            prefs.edit()
                .putString(AppPrefs.KEY_URL, url)
                .putString(AppPrefs.KEY_NAME, name)
                .putBoolean(AppPrefs.KEY_START_BOOT, startOnBootCheck.isChecked)
                .putBoolean(AppPrefs.KEY_ALLOW_HTTP, allowHttpCheck.isChecked)
                .apply()

            awaitingConnection = true
            connectionStatus("Connecting")
            stopService(Intent(this, ConnectionService::class.java))
            requestNotificationsIfNeeded()
            ContextCompat.startForegroundService(this, Intent(this, ConnectionService::class.java).setAction(ConnectionService.ACTION_RECONNECT))
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            awaitingConnection = false
            oauthGeneration++
            runCatching { OAuthAttempt(SecureTokenStore(this)).cancel() }
            stopService(Intent(this, ConnectionService::class.java))
            stopService(Intent(this, PlaybackService::class.java))
            prefs.edit().putString(AppPrefs.KEY_STATUS, "Stopped")
                .putString(AppPrefs.KEY_CONNECTION_PHASE, ConnectionPhase.DISCONNECTED.name).apply()
        }
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthCallback(intent)
    }

    private fun startOAuth() {
        val url = urlInput.text.toString().trim().trimEnd('/')
        if (!AppPrefs.isAllowedUrl(url, false)) {
            connectionStatus("OAuth requires a valid HTTPS Home Assistant URL")
            return
        }
        oauthGeneration++
        try {
            val (state, verifier) = OAuthAttempt(SecureTokenStore(this)).begin(url)
            AppPrefs.prefs(this).edit().putString(AppPrefs.KEY_URL, url).apply()
            connectionStatus("Connecting — waiting for OAuth sign-in")
            CustomTabsIntent.Builder().build().launchUrl(this, HaOAuth(url).authorizationUrl(state, verifier))
        } catch (_: Exception) {
            runCatching { OAuthAttempt(SecureTokenStore(this)).cancel() }
            connectionStatus("Unable to start OAuth; credential storage unavailable")
        }
    }

    private fun handleOAuthCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        intent.data = null
        openSettings?.invoke()
        val callback = try { OAuthAttempt(SecureTokenStore(this)).consume(uri) } catch (_: Exception) { null }
        if (callback == null) { connectionStatus("OAuth callback rejected"); return }
        if (callback.code == null) { connectionStatus("OAuth authorization denied"); return }
        val prefs = AppPrefs.prefs(this)
        if (prefs.getString(AppPrefs.KEY_URL, "") != callback.origin) {
            connectionStatus("Home Assistant URL changed; sign in again")
            return
        }
        val generation = ++oauthGeneration
        awaitingConnection = false
        connectionStatus("Connecting — exchanging authorization code…")
        oauthExecutor.execute {
            try {
                val tokens = HaOAuth(callback.origin).exchange(callback.code, callback.verifier)
                runOnUiThread {
                    if (generation != oauthGeneration || prefs.getString(AppPrefs.KEY_URL, "") != callback.origin) return@runOnUiThread
                    try {
                        val store = SecureTokenStore(this)
                        if (store.get("oauth_epoch") != callback.transaction) return@runOnUiThread
                        store.clear()
                        store.put("auth_method", "oauth")
                        store.put("credential_origin", callback.origin)
                        store.put("access_token", tokens.getString("access_token"))
                        store.put("refresh_token", tokens.getString("refresh_token"))
                        prefs.edit().putLong("oauth_expiry", System.currentTimeMillis() + tokens.getLong("expires_in") * 1000).apply()
                        awaitingConnection = true
                        connectionStatus("Connecting — authenticating device")
                        stopService(Intent(this, ConnectionService::class.java))
                        requestNotificationsIfNeeded()
                        ContextCompat.startForegroundService(this, Intent(this, ConnectionService::class.java).setAction(ConnectionService.ACTION_RECONNECT))
                    } catch (_: Exception) { connectionStatus("Credential storage unavailable; sign in again") }
                }
            } catch (_: Exception) {
                runOnUiThread { if (generation == oauthGeneration) connectionStatus("OAuth exchange failed; sign in again") }
            }
        }
    }

    private fun requestNotificationsIfNeeded() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(this,
                "android.permission.ACCESS_LOCAL_NETWORK") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissions.add("android.permission.ACCESS_LOCAL_NETWORK")
        }
        if (permissions.isNotEmpty()) notificationPermission.launch(permissions.toTypedArray())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("awaiting_connection", awaitingConnection)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (PlayerStateBus.observer === playbackObserver) PlayerStateBus.observer = null
        DeliveryTracker.unobserve(deliveryObserver)
        AppPrefs.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        oauthExecutor.shutdown()
        super.onDestroy()
    }
}
