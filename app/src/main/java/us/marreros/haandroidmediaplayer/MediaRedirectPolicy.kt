package us.marreros.haandroidmediaplayer

import okhttp3.Interceptor
import okhttp3.Response

/** Follow only bounded same-origin redirects. Media requests never carry HA credentials. */
class MediaRedirectPolicy : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val origin = request.url
        var response = chain.proceed(request)
        repeat(5) {
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            val location = response.header("Location") ?: return response
            if (location.length > 2048 || location.any { it.isWhitespace() || it == '\\' }) return response
            val raw = try { java.net.URI(location) } catch (_: Exception) { return response }
            if (raw.rawUserInfo != null || raw.rawFragment != null) return response
            val target = request.url.resolve(location) ?: return response
            // Reject userinfo, fragments, cross-origin redirects and any TLS downgrade.
            if (!TransportPolicy.media(target.toString()) || target.scheme != origin.scheme ||
                target.host != origin.host || target.port != origin.port) return response
            response.close()
            request = request.newBuilder().url(target).build()
            response = chain.proceed(request)
        }
        return response // A loop/overlong chain surfaces as a sanitized HTTP redirect failure.
    }
}
