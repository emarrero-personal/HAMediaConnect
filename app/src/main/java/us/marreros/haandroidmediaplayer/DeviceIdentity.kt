package us.marreros.haandroidmediaplayer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec

interface DeviceSigner {
    val publicKey: String
    val deviceId: String
    fun sign(payload: String): String
}

interface IdentityKeys {
    fun exists(): Boolean
    fun create()
    fun delete()
    fun publicBytes(): ByteArray
    fun sign(payload: ByteArray): ByteArray
}

/** Nonexportable installation key. Missing previously-created keys require explicit reset. */
class DeviceIdentity(context: Context, private val keys: IdentityKeys = AndroidDeviceKeys()) : DeviceSigner {
    companion object {
        @Synchronized fun reset(context: Context, keys: IdentityKeys = AndroidDeviceKeys()) {
            keys.delete()
            check(AppPrefs.prefs(context).edit().remove("device_key_created").commit())
        }
    }
    init {
        synchronized(DeviceIdentity::class.java) {
            if (!keys.exists()) {
                check(!AppPrefs.prefs(context).getBoolean("device_key_created", false)) {
                    "Device key unavailable; reset identity and register again"
                }
                keys.create()
            }
            check(AppPrefs.prefs(context).edit().putBoolean("device_key_created", true).commit())
        }
    }
    private val raw = keys.publicBytes()
    override val publicKey: String = Base64.encodeToString(raw, Base64.NO_WRAP)
    override val deviceId: String = MessageDigest.getInstance("SHA-256").digest(raw)
        .joinToString("") { "%02x".format(it) }
    override fun sign(payload: String): String = Base64.encodeToString(keys.sign(payload.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
}

/** Android framework adapter; real hardware/Keystore behavior requires device testing. */
class AndroidDeviceKeys : IdentityKeys {
    private val alias = "hamediaconnect_device_p256_v1"
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    override fun exists() = store.containsAlias(alias)
    override fun delete() { store.deleteEntry(alias) }
    override fun create() {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKeyPair()
    }
    override fun publicBytes(): ByteArray = store.getCertificate(alias).publicKey.encoded
    override fun sign(payload: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(store.getKey(alias, null) as java.security.PrivateKey)
        update(payload)
        sign()
    }
}
