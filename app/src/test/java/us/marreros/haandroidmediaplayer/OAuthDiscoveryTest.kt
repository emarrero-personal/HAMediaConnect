package us.marreros.haandroidmediaplayer

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OAuthDiscoveryTest {
    private val supported = """{"code_challenge_methods_supported":["S256"]}"""
    private fun oauth(body: String, code: Int = 200, paths: MutableList<String> = mutableListOf()): HaOAuth {
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request()
                paths += request.url.encodedPath
                assertEquals("https", request.url.scheme)
                assertEquals("ha.example", request.url.host)
                if (request.url.encodedPath.contains(".well-known")) {
                    assertEquals("GET", request.method)
                    assertNull(request.header("Authorization"))
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                        .header("Location", "https://other.example/discovery")
                        .body(body.toResponseBody("application/json".toMediaType())).build()
                } else {
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                        .body("""{"access_token":"fixture","refresh_token":"fixture","token_type":"Bearer","expires_in":1800}"""
                            .toResponseBody("application/json".toMediaType())).build()
                }
            }.build()
        return HaOAuth("https://ha.example", client)
    }
    @Test fun supportedDiscoveryAllowsExchangeOnTheConfiguredOrigin() {
        val paths = mutableListOf<String>()
        val oauth = oauth(supported, paths = paths)
        oauth.requirePkceSupport()
        assertEquals("fixture", oauth.exchange("fixture-code", "fixture-verifier").getString("access_token"))
        assertEquals(listOf("/.well-known/oauth-authorization-server", "/.well-known/oauth-authorization-server", "/auth/token"), paths)
    }
    @Test fun unsupportedMalformedOrOversizedDiscoveryNeverReachesTokenEndpoint() {
        for (body in listOf("{}", "not-json", "[]", """{"code_challenge_methods_supported":["plain"]}""",
                """{"code_challenge_methods_supported":"S256"}""", """{"code_challenge_methods_supported":[42,"S256"]}""",
                """{"code_challenge_methods_supported":[]}""", " ".repeat(16385),
                """{"code_challenge_methods_supported":["S256"],"code_challenge_methods_supported":["S256"]}""")) {
            val paths = mutableListOf<String>()
            try { oauth(body, paths = paths).exchange("fixture-code", "fixture-verifier"); fail("Unsupported discovery accepted") }
            catch (_: Exception) { }
            assertEquals(listOf("/.well-known/oauth-authorization-server"), paths)
        }
    }
    @Test fun redirectsAndHttpFailuresCannotAuthorizeExchange() {
        for (code in listOf(301, 302, 307, 308, 401, 404, 500)) {
            val paths = mutableListOf<String>()
            try { oauth(supported, code, paths).exchange("fixture-code", "fixture-verifier"); fail("Failed discovery accepted") }
            catch (_: Exception) { }
            assertEquals(listOf("/.well-known/oauth-authorization-server"), paths)
        }
    }
}
