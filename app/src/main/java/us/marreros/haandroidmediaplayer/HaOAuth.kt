package us.marreros.haandroidmediaplayer

import android.net.Uri
import android.util.Base64
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Home Assistant authorization-code + PKCE client. Never invoke token exchange on the UI thread. */
class OAuthRejected : IllegalStateException("OAuth authorization rejected")

class HaOAuth(base: String, private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()) {
    companion object {
        const val CLIENT_PATH = "/api/hamediaconnect/oauth-client"
        const val REDIRECT_URI = "haandroidmediaplayer://oauth/callback"
        private val rng = SecureRandom()
        fun randomUrlSafe(bytes: Int): String = ByteArray(bytes).also(rng::nextBytes)
            .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }
        fun challenge(verifier: String): String = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    private val baseUrl = TransportPolicy.base(base).toString().trimEnd('/')
    private val clientId: String = baseUrl.trimEnd('/') + CLIENT_PATH

    /** Check the configured HTTPS origin; never follow discovery redirects or trust cached support. */
    fun requirePkceSupport() {
        val request = Request.Builder().url("$baseUrl/.well-known/oauth-authorization-server").build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "OAuth server discovery unavailable" }
            val methods = readJson(response).getJSONArray("code_challenge_methods_supported")
            require(methods.length() in 1..8 && (0 until methods.length()).all { methods.get(it) is String } &&
                (0 until methods.length()).any { methods.getString(it) == "S256" }) { "S256 PKCE required" }
        }
    }

    fun authorizationUrl(state: String, verifier: String): Uri =
        Uri.parse("${baseUrl.trimEnd('/')}/auth/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

    fun exchange(code: String, verifier: String): JSONObject {
        requirePkceSupport() // Also covers callbacks from attempts started before an app update.
        return tokenRequest(
            FormBody.Builder().add("grant_type", "authorization_code")
                .add("code", code).add("client_id", clientId)
                .add("redirect_uri", REDIRECT_URI).add("code_verifier", verifier).build()
        ).also { require(it.optString("refresh_token").length in 1..8192) }
    }

    fun refresh(refreshToken: String): JSONObject = tokenRequest(
        FormBody.Builder().add("grant_type", "refresh_token")
            .add("client_id", clientId).add("refresh_token", refreshToken).build()
    )

    internal fun validateTokens(tokens: JSONObject): JSONObject {
        require(tokens.get("access_token") is String && tokens.getString("access_token").length in 1..8192)
        require(tokens.get("token_type") is String && tokens.getString("token_type").equals("Bearer", ignoreCase = true))
        val expiry = tokens.get("expires_in")
        require(expiry is Number && expiry.toDouble().isFinite() && expiry.toDouble() in 1.0..86400.0)
        if (tokens.has("refresh_token")) require(tokens.get("refresh_token") is String && tokens.getString("refresh_token").length in 1..8192)
        return tokens
    }

    private fun readJson(response: okhttp3.Response): JSONObject {
        val source = requireNotNull(response.body).source()
        source.request(16385)
        require(source.buffer.size <= 16384) { "OAuth response too large" }
        return HaWebSocketClient.boundedJson(source.readUtf8())
    }

    private fun tokenRequest(body: FormBody): JSONObject {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/auth/token").post(body).build()
        http.newCall(request).execute().use { response ->
            if (response.code in listOf(400, 401, 403) || response.isRedirect) throw OAuthRejected()
            check(response.isSuccessful) { "Token endpoint unavailable" }
            return validateTokens(readJson(response))
        }
    }
}
