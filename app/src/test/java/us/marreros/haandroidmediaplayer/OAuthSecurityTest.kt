package us.marreros.haandroidmediaplayer

import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

class MemorySecrets : SecretStore {
 val values = mutableMapOf<String, String>()
 override fun put(name: String, value: String) { values[name] = value }
 override fun get(name: String) = values[name]
 override fun remove(name: String) { values.remove(name) }
 override fun clear() { values.clear() }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OAuthSecurityTest {
 private fun callback(state: String, tail: String = "&code=code") = Uri.parse("${HaOAuth.REDIRECT_URI}?state=$state$tail")
 @Test fun callbackIsExactStateBoundExpiringAndSingleUse() {
  val store = MemorySecrets(); val pending = OAuthAttempt(store)
  val (state, verifier) = pending.begin("https://ha.example", 1000)
  for (prefix in listOf("haandroidmediaplayer://oauth:9/callback", "haandroidmediaplayer://user@oauth/callback",
      "haandroidmediaplayer://oauth/callback/", "haandroidmediaplayer://oauth/%63allback", "https://oauth/callback"))
   assertNull(pending.consume(Uri.parse("$prefix?state=$state&code=code"), 2000))
  assertNull(pending.consume(callback("wrong"), 2000))
  assertNull(pending.consume(callback(state, "&state=another&code=code"), 2000))
  assertNull(pending.consume(callback(state, "&code=code#fragment"), 2000))
  assertNull(pending.consume(callback(state, "&extra=value&code=code"), 2000))
  assertNull(pending.consume(callback(state, "&code=" + "a".repeat(3000)), 2000))
  assertNull(pending.consume(callback(state, "&code=" + "a".repeat(5000)), 2000))
  val result = pending.consume(callback(state), 2000)!!
  assertEquals("https://ha.example", result.origin)
  assertEquals(verifier, result.verifier)
  assertEquals("code", result.code)
  assertNull(pending.consume(callback(state), 2000))
  val expired = pending.begin("https://ha.example", 1000).first
  assertNull(pending.consume(callback(expired), 601001))
  assertNull(store.get("oauth_pending"))
  val future = pending.begin("https://ha.example", 1000).first
  assertNull(pending.consume(callback(future), 999))
 }
 @Test fun errorsAndMissingCodesConsumeOnlyMatchingAttempts() {
  val store = MemorySecrets(); val pending = OAuthAttempt(store)
  val state = pending.begin("https://ha.example", 1).first
  assertNull(pending.consume(Uri.parse(HaOAuth.REDIRECT_URI + "?code=c"), 2))
  val denied = pending.consume(callback(state, "&error=access_denied&error_description=untrusted"), 2)!!
  assertNull(denied.code)
  assertNull(pending.consume(callback(state), 2))
  val noCode = pending.begin("https://ha.example", 1).first
  assertNull(pending.consume(callback(noCode, "&code="), 2))
  assertNull(pending.consume(callback(noCode), 2))
  pending.begin("https://ha.example", 1)
  pending.cancel()
  assertTrue(store.values.isEmpty())
  store.put("oauth_pending", "malformed")
  assertNull(pending.consume(callback("any"), 2))
 }
 @Test fun validatesTokenResponsesAndDoesNotFollowRedirects() {
  val oauth = HaOAuth("https://ha.example")
  fun valid() = JSONObject().put("access_token", "access").put("token_type", "Bearer").put("expires_in", 1800)
  assertEquals("access", oauth.validateTokens(valid()).getString("access_token"))
  assertEquals("refresh", oauth.validateTokens(valid().put("refresh_token", "refresh")).getString("refresh_token"))
  for (bad in listOf(valid().put("token_type", "other"), valid().put("access_token", 123), valid().put("refresh_token", 123), valid().put("access_token", ""), valid().put("expires_in", -1),
      valid().put("expires_in", "1800"), valid().put("refresh_token", ""))) {
   try { oauth.validateTokens(bad); fail("Invalid token accepted") } catch (_: IllegalArgumentException) { }
  }
  val http = HaOAuth::class.java.getDeclaredField("http").apply { isAccessible = true }.get(oauth) as okhttp3.OkHttpClient
  assertFalse(http.followRedirects); assertFalse(http.followSslRedirects)
 }
 @Test fun aNewLoginInvalidatesAnOlderExchangeTransaction() {
  val store = MemorySecrets(); val pending = OAuthAttempt(store)
  val state = pending.begin("https://ha.example", 1).first
  val response = pending.consume(callback(state), 2)!!
  assertEquals(response.transaction, store.get("oauth_epoch"))
  pending.begin("https://ha.example", 3)
  assertNotEquals(response.transaction, store.get("oauth_epoch"))
  pending.cancel()
  assertNull(store.get("oauth_epoch"))
 }
 @Test fun encryptedStorageUsesRandomIvAndBindsFieldNamesAndFailsClosed() {
  val context = RuntimeEnvironment.getApplication() as android.content.Context
  val prefs = context.getSharedPreferences("oauth_secrets", 0)
  prefs.edit().clear().commit()
  val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
  val store = SecureTokenStore(context) { key }
  store.put("access_token", "sensitive")
  val first = prefs.getString("access_token", null)!!
  assertFalse(first.contains("sensitive"))
  store.put("access_token", "sensitive")
  assertNotEquals(first, prefs.getString("access_token", null))
  assertEquals("sensitive", store.get("access_token"))
  prefs.edit().putString("refresh_token", first).commit()
  assertNull(store.get("refresh_token")) // AAD prevents ciphertext substitution between names.
  prefs.edit().putString("access_token", "invalid").commit()
  assertNull(store.get("access_token"))
  prefs.edit().putString("access_token", "A".repeat(40000)).commit()
  assertNull(store.get("access_token"))
  store.put("access_token", "sensitive")
  assertNull(SecureTokenStore(context) { error("Key unavailable") }.get("access_token"))
  store.remove("access_token"); assertNull(store.get("access_token"))
  store.clear(); assertTrue(prefs.all.isEmpty())
 }
}
