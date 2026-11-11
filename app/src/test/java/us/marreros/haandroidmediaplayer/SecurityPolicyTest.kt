package us.marreros.haandroidmediaplayer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Base64
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.*
import java.security.spec.ECGenParameterSpec

class TestKeys : IdentityKeys {
    var pair: KeyPair? = null
    var invalidated = false
    override fun exists() = pair != null
    override fun create() { pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair() }
    override fun delete() { pair = null; invalidated = false }
    override fun publicBytes() = pair!!.public.encoded
    override fun sign(payload: ByteArray): ByteArray {
        check(!invalidated)
        return Signature.getInstance("SHA256withECDSA").run { initSign(pair!!.private); update(payload); sign() }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecurityPolicyTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun controlUrlsRequireHttpsAndRejectParserAmbiguity() {
        assertTrue(AppPrefs.isAllowedUrl("https://ha.example/"))
        assertFalse(AppPrefs.isAllowedUrl("http://192.168.1.2:8123"))
        assertFalse(AppPrefs.isAllowedUrl("http://ha.example"))
        for (url in listOf("ftp://ha.example", "wss://ha.example", "https://u:p@ha.example", "https://@ha.example",
            "https://ha.example?token=x", "https://ha.example#x", "https://ha.example/path", "https://ha.example:0",
            "https://ha.example:65536", "https://ha.example\\@evil", "https:///evil", "https://ha.example\n", "ha.example")) {
            assertFalse(url, AppPrefs.isAllowedUrl(url))
        }
        assertTrue(AppPrefs.isAllowedUrl("https://[::1]:8123"))
    }
    @Test fun mediaHttpRemainsSupportedWithHttpsControl() {
        assertTrue(TransportPolicy.media("http://ha.example:8123/api/tts_proxy/a.mp3?authSig=signed"))
        assertTrue(TransportPolicy.media("https://example.com/song.mp3"))
        for (url in listOf("file:///secret", "content://secret", "http://u:p@example.com", "//example.com/song",
            "javascript:alert(1)", "http://example.com:99999/a", "http://example.com/a#x", "http://example.com\\a"))
            assertFalse(url, TransportPolicy.media(url))
    }
    @Test fun relativeMediaCannotEscapeToUnsupportedSchemesOrAuthorities() {
        assertEquals("https://ha.local:8123/api/tts.mp3?sig=x",
            TransportPolicy.resolveMedia("https://ha.local:8123", "/api/tts.mp3?sig=x"))
        assertEquals("https://example.com/song", TransportPolicy.resolveMedia("https://ha.local", "https://example.com/song"))
        assertEquals("https://ha.local/song.mp3", TransportPolicy.resolveMedia("https://ha.local", "song.mp3"))
        for (url in listOf("file:///secret", "content://secret", "//evil.example/song", "http://u:p@host/a", "a#fragment")) {
            rejects { TransportPolicy.resolveMedia("https://ha.local", url) }
        }
    }
    @Test fun jitterIsBoundedAndSaturates() {
        assertEquals(2500L, TransportPolicy.reconnectDelay(0, 0.0))
        assertEquals(5000L, TransportPolicy.reconnectDelay(0, 1.0))
        assertEquals(150000L, TransportPolicy.reconnectDelay(100, 0.0))
        assertEquals(300000L, TransportPolicy.reconnectDelay(100, 1.0))
    }
    @Test fun rejectsThirdPartyControllersAndRestrictsTrustedCommands() {
        assertFalse(ControllerPolicy.allowed(54321, false, 12345))
        assertFalse(ControllerPolicy.allowed(-1, false, 12345))
        assertFalse(ControllerPolicy.allowed(-1, true, 12345))
        assertTrue(ControllerPolicy.allowed(1000, true, 12345))
        assertTrue(ControllerPolicy.allowed(12345, false, 12345))
        assertTrue(ControllerPolicy.playerCommands.contains(Player.COMMAND_PLAY_PAUSE))
        assertTrue(ControllerPolicy.playerCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        assertFalse(ControllerPolicy.playerCommands.contains(Player.COMMAND_SET_MEDIA_ITEM))
        assertFalse(ControllerPolicy.playerCommands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        // Exercise the Media3 authorization callback with verified/unverified ControllerInfo fixtures.
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
        try {
            val remoteType = Class.forName("androidx.media3.session.legacy.MediaSessionManager\$RemoteUserInfo")
            val remote = remoteType.getConstructor(String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .newInstance("com.android.systemui", 10, 54321)
            val constructor = MediaSession.ControllerInfo::class.java.declaredConstructors.single().apply { isAccessible = true }
            fun info(trusted: Boolean) = constructor.newInstance(remote, 0, 0, trusted, null, android.os.Bundle(), 0, true) as MediaSession.ControllerInfo
            assertFalse(ControllerPolicy.result(session, info(false)).isAccepted)
            val accepted = ControllerPolicy.result(session, info(true))
            assertTrue(accepted.isAccepted)
            assertEquals(0, accepted.availableSessionCommands.commands.size)
        } finally { session.release(); player.release() }
    }
    @Test fun internalPlaybackCannotBeRequestedWithForgedIntents() {
        assertFalse(InternalPlayback.allowed(null))
        assertFalse(InternalPlayback.allowed(Intent().setAction(PlaybackService.CMD_STOP)))
        assertFalse(InternalPlayback.allowed(Intent().putExtra("us.marreros.internal_playback", "forged")))
        assertTrue(InternalPlayback.allowed(InternalPlayback.authorize(Intent().setAction(PlaybackService.CMD_STOP))))
    }
    @Test fun androidAutoIsNotDeclared() {
        val appInfo = context.packageManager.getApplicationInfo(
            context.packageName, PackageManager.GET_META_DATA
        )
        assertFalse(appInfo.metaData?.containsKey("com.google.android.gms.car.application") == true)
    }
    @Test fun trustedSystemControllerRemainsAccepted() {
        assertTrue(ControllerPolicy.allowed(uid = 1000, trusted = true, ownUid = 99999))
    }
    @Test fun deviceIdentityPersistsAndMissingKeysRequireDeliberateReset() {
        AppPrefs.prefs(context).edit().clear().commit()
        val keys = TestKeys()
        val first = DeviceIdentity(context, keys)
        assertTrue(first.deviceId.matches(Regex("[a-f0-9]{64}")))
        assertEquals(first.deviceId, DeviceIdentity(context, keys).deviceId)
        val signed = Base64.decode(first.sign("challenge"), Base64.NO_WRAP)
        assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(keys.pair!!.public); update("challenge".toByteArray()); verify(signed) })
        assertArrayEquals(keys.pair!!.public.encoded, Base64.decode(first.publicKey, Base64.NO_WRAP))
        keys.invalidated = true
        try { first.sign("challenge"); fail("Invalidated key must fail") } catch (_: IllegalStateException) { }
        keys.delete()
        try { DeviceIdentity(context, keys); fail("Missing key must not silently regenerate") } catch (_: IllegalStateException) { }
        assertFalse(keys.exists())
        DeviceIdentity.reset(context, keys)
        val replacement = DeviceIdentity(context, keys)
        assertNotEquals(first.deviceId, replacement.deviceId)
    }
}
