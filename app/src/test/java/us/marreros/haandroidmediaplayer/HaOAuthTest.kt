package us.marreros.haandroidmediaplayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HaOAuthTest {
 @Test fun challengeMatchesRfc7636() {
  assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGzW7r5t8", HaOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
 }
 @Test fun randomVerifierIsUrlSafe() {
  val a=HaOAuth.randomUrlSafe(32)
  assertTrue(a.matches(Regex("[A-Za-z0-9_-]+")))
  assertEquals(43,a.length)
  assertNotEquals(a,HaOAuth.randomUrlSafe(32))
 }
 @Test fun rejectsHttp() {
  try { HaOAuth("http://example.com"); fail("Expected rejection") }
  catch (e:IllegalArgumentException) { assertTrue(e.message!!.contains("HTTPS")) }
 }
 @Test fun authorizationUrlHasCorrectPkce() {
  val uri=HaOAuth("https://ha.example/").authorizationUrl("state123","verifier")
  assertEquals("https",uri.scheme)
  assertEquals("/auth/authorize",uri.path)
  assertEquals("code",uri.getQueryParameter("response_type"))
  assertEquals("state123",uri.getQueryParameter("state"))
  assertEquals(HaOAuth.REDIRECT_URI,uri.getQueryParameter("redirect_uri"))
  assertEquals("S256",uri.getQueryParameter("code_challenge_method"))
  assertEquals(HaOAuth.challenge("verifier"),uri.getQueryParameter("code_challenge"))
  assertEquals("https://ha.example"+HaOAuth.CLIENT_PATH,uri.getQueryParameter("client_id"))
 }
}
