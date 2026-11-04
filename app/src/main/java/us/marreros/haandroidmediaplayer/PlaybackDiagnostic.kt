package us.marreros.haandroidmediaplayer

/** Deliberately omit hostnames, all query values and arbitrary path identifiers. */
object PlaybackDiagnostic {
    fun endpoint(value: String?): String {
        if (value == null || !TransportPolicy.media(value)) return "No media endpoint"
        val uri = java.net.URI(value)
        val host = uri.host.lowercase()
        val category = when {
            host.endsWith(".ui.nabu.casa") -> "Nabu Casa"
            host == "localhost" || host.endsWith(".local") || host.endsWith(".home.arpa") || host.endsWith(".lan") || host == "::1" || host == "[::1]" ||
                (host.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) &&
                    (host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("127.") || host.startsWith("169.254."))) ||
                Regex("172\\.(1[6-9]|2[0-9]|3[01])\\..*").matches(host) ||
                host.startsWith("[fc") || host.startsWith("[fd") || host.startsWith("[fe80:") -> "Local"
            else -> "External"
        }
        val route = when {
            uri.path.startsWith("/api/tts_proxy/") -> "/api/tts_proxy/[redacted]"
            uri.path.startsWith("/api/media_source/") -> "/api/media_source/[redacted]"
            uri.path.startsWith("/local/") -> "/local/[redacted]"
            else -> "/[redacted]"
        }
        return "${uri.scheme.uppercase()} · $category · $route"
    }

    fun summary(snapshot: PlayerSnapshot): String {
        val label = if (snapshot.error != null) "Failed" else when (snapshot.state) {
            "buffering" -> "Buffering"
            "playing" -> "Playing"
            "paused" -> "Paused"
            else -> "Ready"
        }
        if (label == "Ready") return "Ready — Waiting for audio from Home Assistant"
        // Metadata may itself contain a signed URL. Never render a URL as a title.
        val title = snapshot.title?.takeUnless { it.contains("://") || it.contains('?') }
            ?.filter { !it.isISOControl() }?.take(512)
        return listOfNotNull(label, title, endpoint(snapshot.contentId),
            "${snapshot.positionMs / 1000}s / ${snapshot.durationMs / 1000}s", snapshot.error?.let { if (it.contains("timed out")) "Load/buffering stage: $it" else it }, snapshot.notice).joinToString("\n")
    }
}
