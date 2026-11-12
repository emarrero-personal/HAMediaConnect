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
    companion object { private const val KEY = "oauth_pending" }
    fun begin(
        origin: String,
        now: Long = System.currentTimeMillis()
    ): Pair<String, String> = CredentialTransactions.change {
        TransportPolicy.base(origin)
        val state = HaOAuth.randomUrlSafe(32)
        val verifier = HaOAuth.randomUrlSafe(48)
        store.put("oauth_epoch", state)
        store.put(KEY, JSONObject().put("origin", origin).put("state", state)
            .put("verifier", verifier).put("issued", now).toString())
        state to verifier
    }
    fun consume(uri: Uri, now: Long = System.currentTimeMillis()): OAuthCallback? = CredentialTransactions.read {
        val text = uri.toString()
        if (text.length > 4096) return@read null
        val raw = try { java.net.URI(text) } catch (_: Exception) { return@read null }
        if (raw.scheme != "haandroidmediaplayer" || raw.rawAuthority != "oauth" ||
            raw.rawPath != "/callback" || raw.rawFragment != null) return@read null
        val allowed = setOf("state", "code", "error", "error_description", "error_uri")
        if (uri.queryParameterNames.any { it !in allowed || uri.getQueryParameters(it).size != 1 ||
                (uri.getQueryParameter(it)?.length ?: 0) > 2048 }) return@read null
        val pending = store.get(KEY)?.let { try { JSONObject(it) } catch (_: Exception) { null } } ?: return@read null
        val issued = pending.getLong("issued")
        if (now - issued !in 0..600000) { store.remove(KEY); return@read null }
        val state = uri.getQueryParameter("state") ?: return@read null
        if (!MessageDigest.isEqual(state.toByteArray(), pending.getString("state").toByteArray())) return@read null
        store.remove(KEY) // Matching response is consumed even on authorization error.
        if (uri.getQueryParameter("error") != null) {
            return@read OAuthCallback(pending.getString("origin"), null, "", state)
        }
        val code = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() } ?: return@read null
        OAuthCallback(pending.getString("origin"), code, pending.getString("verifier"), state)
    }
    fun cancel() = CredentialTransactions.change { store.remove(KEY); store.remove("oauth_epoch") }
}
