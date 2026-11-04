package us.marreros.haandroidmediaplayer

import android.net.Uri
import org.json.JSONObject
import java.security.MessageDigest

interface SecretStore {
    fun put(name: String, value: String)
    fun get(name: String): String?
    fun remove(name: String)
    fun clear()
}

data class OAuthCallback(val origin: String, val code: String?, val verifier: String, val transaction: String)

/** Single encrypted transaction. Wrong-state intents cannot cancel a genuine browser login. */
class OAuthAttempt(private val store: SecretStore) {
    companion object { private const val KEY = "oauth_pending"; private val lock = Any() }
    fun begin(origin: String, now: Long = System.currentTimeMillis()): Pair<String, String> = synchronized(lock) {
        TransportPolicy.base(origin, false)
        val state = HaOAuth.randomUrlSafe(32)
        val verifier = HaOAuth.randomUrlSafe(48)
        store.put("oauth_epoch", state)
        store.put(KEY, JSONObject().put("origin", origin).put("state", state)
            .put("verifier", verifier).put("issued", now).toString())
        state to verifier
    }
    fun consume(uri: Uri, now: Long = System.currentTimeMillis()): OAuthCallback? = synchronized(lock) {
        val text = uri.toString()
        if (text.length > 4096) return null
        val raw = try { java.net.URI(text) } catch (_: Exception) { return null }
        if (raw.scheme != "haandroidmediaplayer" || raw.rawAuthority != "oauth" ||
            raw.rawPath != "/callback" || raw.rawFragment != null) return null
        val allowed = setOf("state", "code", "error", "error_description", "error_uri")
        if (uri.queryParameterNames.any { it !in allowed || uri.getQueryParameters(it).size != 1 ||
                (uri.getQueryParameter(it)?.length ?: 0) > 2048 }) return null
        val pending = store.get(KEY)?.let { try { JSONObject(it) } catch (_: Exception) { null } } ?: return null
        val issued = pending.getLong("issued")
        if (now - issued !in 0..600000) { store.remove(KEY); return null }
        val state = uri.getQueryParameter("state") ?: return null
        if (!MessageDigest.isEqual(state.toByteArray(), pending.getString("state").toByteArray())) return null
        store.remove(KEY) // Matching response is consumed even on authorization error.
        if (uri.getQueryParameter("error") != null) return OAuthCallback(pending.getString("origin"), null, "", state)
        val code = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() } ?: return null
        OAuthCallback(pending.getString("origin"), code, pending.getString("verifier"), state)
    }
    fun cancel() = synchronized(lock) { store.remove(KEY); store.remove("oauth_epoch") }
}
