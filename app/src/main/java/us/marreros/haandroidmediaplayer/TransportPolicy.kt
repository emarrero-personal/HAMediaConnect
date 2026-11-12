package us.marreros.haandroidmediaplayer

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object TransportPolicy {
    fun base(url: String): HttpUrl {
        require(url.length in 1..2048 && url.none { it.isWhitespace() || it == '\\' })
        val parsed = java.net.URI(url) // Reject lenient parser repairs and userinfo, including empty @.
        require(parsed.scheme == "https") { "Home Assistant control requires HTTPS" }
        require(parsed.rawUserInfo == null && parsed.rawQuery == null && parsed.rawFragment == null)
        require(parsed.host != null && parsed.port in -1..65535 && parsed.port != 0)
        require(parsed.rawPath.isNullOrEmpty() || parsed.rawPath == "/")
        return requireNotNull(url.toHttpUrlOrNull())
    }

    fun media(url: String): Boolean = try {
        require(url.length in 1..2048 && url.none { it.isWhitespace() || it == '\\' })
        val uri = java.net.URI(url)
        (uri.scheme == "http" || uri.scheme == "https") && uri.host != null &&
            uri.rawUserInfo == null && uri.port in -1..65535 && uri.port != 0 && uri.rawFragment == null
    } catch (_: Exception) { false }

    fun resolveMedia(baseUrl: String, value: String): String {
        if (media(value)) return value
        require(value.length in 1..2048 && value.none { it.isWhitespace() || it == '\\' })
        val uri = java.net.URI(value)
        require(uri.scheme == null && uri.rawAuthority == null && uri.rawFragment == null)
        val resolved = base(baseUrl).resolve(value)?.toString()
        require(resolved != null && media(resolved))
        return resolved
    }

    fun reconnectDelay(attempt: Int, random: Double = java.security.SecureRandom().nextDouble()): Long {
        val ceiling = (5000L * (1L shl attempt.coerceIn(0, 6))).coerceAtMost(300000L)
        return (ceiling / 2 + random.coerceIn(0.0, 1.0) * ceiling / 2).toLong()
    }
}
