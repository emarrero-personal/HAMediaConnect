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
class HaOAuth(private val baseUrl: String) {
    companion object {
        // Must be a real, publicly accessible HTTPS client-identifier page before production release.
        const val CLIENT_ID = "https://github.com/emarrero-personal/HARemoteMediaPlayer"
        const val REDIRECT_URI = "haandroidmediaplayer://oauth/callback"
        private val rng = SecureRandom()
        fun randomUrlSafe(bytes: Int): String = ByteArray(bytes).also(rng::nextBytes)
            .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }
        fun challenge(verifier: String): String = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).build()

    init { require(baseUrl.startsWith("https://")) { "OAuth requires HTTPS" } }

    fun authorizationUrl(state: String, verifier: String): Uri =
        Uri.parse("${baseUrl.trimEnd('/')}/auth/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

    fun exchange(code: String, verifier: String): JSONObject = tokenRequest(
        FormBody.Builder().add("grant_type", "authorization_code")
            .add("code", code).add("client_id", CLIENT_ID)
            .add("redirect_uri", REDIRECT_URI).add("code_verifier", verifier).build()
    )

    fun refresh(refreshToken: String): JSONObject = tokenRequest(
        FormBody.Builder().add("grant_type", "refresh_token")
            .add("client_id", CLIENT_ID).add("refresh_token", refreshToken).build()
    )

    private fun tokenRequest(body: FormBody): JSONObject {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/auth/token").post(body).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Token endpoint returned HTTP ${response.code}")
            return JSONObject(response.body?.string() ?: error("Empty token response"))
        }
    }
}
