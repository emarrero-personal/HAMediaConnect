package us.marreros.haandroidmediaplayer

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class MediaRedirectPolicyTest {
    private fun run(locations: List<String?>, origin: String = "https://ha.example/api/tts_proxy/fixture?authSig=a%2Fb&x=1&x=2"): Pair<Response, List<Request>> {
        val initial = Request.Builder().url(origin).build()
        val requests = mutableListOf<Request>()
        val chain = Proxy.newProxyInstance(Interceptor.Chain::class.java.classLoader, arrayOf(Interceptor.Chain::class.java)) { _, method, args ->
            when (method.name) {
                "request" -> initial
                "proceed" -> {
                    val request = args!![0] as Request
                    val index = requests.size
                    requests += request
                    val redirect = index < locations.size
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(if (redirect) 302 else 200).message("fixture")
                        .body("abc".toResponseBody())
                        .apply { if (redirect && locations[index] != null) header("Location", locations[index]!!) }.build()
                }
                else -> null
            }
        } as Interceptor.Chain
        return MediaRedirectPolicy().intercept(chain) to requests
    }
    @Test fun signedInitialRequestAndSameOriginRelativeRedirectArePreservedWithoutCredentials() {
        val (response, requests) = run(listOf("/api/tts_proxy/target?authSig=b%2Fc&x=1&x=2"))
        response.use { assertEquals(200, it.code) }
        assertEquals(2, requests.size)
        assertEquals("authSig=a%2Fb&x=1&x=2", requests.first().url.encodedQuery)
        assertEquals("authSig=b%2Fc&x=1&x=2", requests.last().url.encodedQuery)
        for (request in requests) {
            assertNull(request.header("Authorization")); assertNull(request.header("Cookie"))
        }
        val http = run(listOf("/target"), "http://ha.local/api/tts_proxy/fixture")
        http.first.use { assertEquals(200, it.code) }
    }
    @Test fun unsafeOrMalformedRedirectsNeverMakeAnotherRequest() {
        for (location in listOf(null, "http://[", "https://third-party.example/secret", "http://ha.example/secret",
            "https://ha.example:444/secret", "https://user:secret@ha.example/secret", "/target#secret", "https://@ha.example/secret", "/white space", "/back\\slash", "x".repeat(2049), "mailto:fixture@example.com")) {
            val (response, requests) = run(listOf(location))
            response.use { assertEquals(302, it.code) }
            assertEquals(location, 1, requests.size)
        }
    }
    @Test fun redirectLoopsAreBounded() {
        val (response, requests) = run(List(20) { "/loop" })
        response.use { assertEquals(302, it.code) }
        assertEquals(6, requests.size)
    }
}
