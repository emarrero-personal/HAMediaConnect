package us.marreros.haandroidmediaplayer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** Android Keystore AES-GCM encryption; the key never leaves the Keystore. */
class SecureTokenStore(context: Context, private val keyProvider: (() -> java.security.Key)? = null) : SecretStore {
    companion object {
        fun reset(context: Context) {
            check(context.getSharedPreferences("oauth_secrets", Context.MODE_PRIVATE).edit().clear().commit())
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("ha_android_media_player_oauth_v1") }
        }
    }
    private val prefs = context.getSharedPreferences("oauth_secrets", Context.MODE_PRIVATE)
    private val alias = "ha_android_media_player_oauth_v1"

    private fun key(): java.security.Key {
        keyProvider?.let { return it() }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(alias, null)?.let { return it }
        check(prefs.all.isEmpty()) { "Credential key unavailable; sign in again" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    @Synchronized override fun put(name: String, value: String) {
        require(name.length in 1..128 && value.length <= 16384)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).commit().also { check(it) }
    }

    @Synchronized override fun get(name: String): String? {
        val encoded = prefs.getString(name, null) ?: return null
        return try {
            require(encoded.length <= 32768)
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size >= 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    @Synchronized override fun remove(name: String) { check(prefs.edit().remove(name).commit()) }
    @Synchronized override fun clear() { check(prefs.edit().clear().commit()) }
}
