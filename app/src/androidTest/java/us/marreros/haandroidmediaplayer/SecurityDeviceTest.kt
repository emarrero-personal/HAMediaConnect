package us.marreros.haandroidmediaplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit

/** Run on a disposable emulator/test device, never against an installation containing real credentials. */
@RunWith(AndroidJUnit4::class)
class SecurityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Before fun resetTestInstallation() {
        context.stopService(Intent(context, ConnectionService::class.java))
        context.stopService(Intent(context, PlaybackService::class.java))
        DeviceIdentity.reset(context)
        SecureTokenStore.reset(context)
        AppPrefs.prefs(context).edit().clear().commit()
    }
    @After fun cleanup() {
        context.stopService(Intent(context, ConnectionService::class.java))
        context.stopService(Intent(context, PlaybackService::class.java))
        DeviceIdentity.reset(context)
        SecureTokenStore.reset(context)
        AppPrefs.prefs(context).edit().clear().commit()
    }
    @Test fun realKeystoreSignsWithoutExportAndFailsClosedAfterKeyLoss() {
        val identity = DeviceIdentity(context)
        val public = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(identity.publicKey, Base64.NO_WRAP)))
        val signature = Base64.decode(identity.sign("device challenge"), Base64.NO_WRAP)
        assertTrue(Signature.getInstance("SHA256withECDSA").run {
            initVerify(public); update("device challenge".toByteArray()); verify(signature)
        })
        val privateKey = keyStore().getKey("hamediaconnect_device_p256_v1", null)
        assertNull(privateKey.encoded)
        assertEquals(identity.deviceId, DeviceIdentity(context).deviceId)
        keyStore().deleteEntry("hamediaconnect_device_p256_v1")
        try { identity.sign("new challenge"); fail("Deleted key must fail signing") } catch (_: Exception) { }
        try { DeviceIdentity(context); fail("Missing identity key must not regenerate") } catch (_: IllegalStateException) { }
        DeviceIdentity.reset(context)
        assertNotEquals(identity.deviceId, DeviceIdentity(context).deviceId)
    }
    @Test fun realCredentialEncryptionSurvivesReopenAndRejectsTamperingAndKeyLoss() {
        val store = SecureTokenStore(context)
        store.put("access_token", "test-only-token")
        assertEquals("test-only-token", SecureTokenStore(context).get("access_token"))
        val prefs = context.getSharedPreferences("oauth_secrets", Context.MODE_PRIVATE)
        val ciphertext = prefs.getString("access_token", null)!!
        assertFalse(ciphertext.contains("test-only-token"))
        store.put("access_token", "test-only-token")
        assertNotEquals(ciphertext, prefs.getString("access_token", null))
        prefs.edit().putString("refresh_token", ciphertext).commit()
        assertNull(store.get("refresh_token"))
        val bytes = Base64.decode(ciphertext, Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        prefs.edit().putString("access_token", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
        assertNull(store.get("access_token"))
        assertNull(keyStore().getKey("ha_android_media_player_oauth_v1", null).encoded)
        keyStore().deleteEntry("ha_android_media_player_oauth_v1")
        assertNull(SecureTokenStore(context).get("access_token"))
        try { store.put("access_token", "replacement"); fail("Cannot replace key over existing ciphertext") } catch (_: IllegalStateException) { }
        SecureTokenStore.reset(context)
        SecureTokenStore(context).put("access_token", "fresh-test-token")
        assertEquals("fresh-test-token", SecureTokenStore(context).get("access_token"))
    }
    @Test fun actualPlaybackServiceRejectsRawIntentsButAcceptsInternalCommands() {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var controller: MediaController? = null
        try {
            // Bind through the real MediaSessionService. This controller has the verified app UID.
            lateinit var pending: com.google.common.util.concurrent.ListenableFuture<MediaController>
            instrumentation.runOnMainSync {
                pending = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
            }
            controller = pending.get(15, TimeUnit.SECONDS)
            val active = controller
            context.startService(Intent(context, PlaybackService::class.java).setAction(PlaybackService.CMD_PLAY))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertFalse(active.playWhenReady) }
            context.startService(InternalPlayback.authorize(Intent(context, PlaybackService::class.java).setAction(PlaybackService.CMD_PLAY)))
            awaitCondition { active.playWhenReady }
            context.startService(Intent(context, PlaybackService::class.java).setAction(PlaybackService.CMD_PAUSE))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertTrue(active.playWhenReady) }
            context.startService(InternalPlayback.authorize(Intent(context, PlaybackService::class.java).setAction(PlaybackService.CMD_PAUSE)))
            awaitCondition { !active.playWhenReady }
        } finally {
            instrumentation.runOnMainSync { controller?.release(); activity.finish() }
        }
    }
    private fun awaitCondition(condition: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 5000
        while (android.os.SystemClock.elapsedRealtime() < end) {
            var done = false
            instrumentation.runOnMainSync { done = condition() }
            if (done) return
            android.os.SystemClock.sleep(25)
        }
        fail("Service/controller state did not reach the expected value")
    }
}
