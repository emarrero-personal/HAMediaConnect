package us.marreros.haandroidmediaplayer

import org.junit.Assert.*
import org.junit.Test

class PlaybackDiagnosticTest {
    private fun snapshot(state: String, error: String? = null, title: String? = "Music") =
        PlayerSnapshot(state, .5f, false, 5000, 10000, title,
            "https://remote.ui.nabu.casa/api/tts_proxy/opaque-secret?authSig=secret", error)
    @Test fun lifecycleAndRedaction() {
        for ((state, label) in listOf("idle" to "Ready", "buffering" to "Buffering", "playing" to "Playing", "paused" to "Paused")) {
            val text = PlaybackDiagnostic.summary(snapshot(state))
            assertTrue(text.startsWith(label))
            assertFalse(text.contains("secret"))
            assertFalse(text.contains("remote.ui"))
        }
        val failed = PlaybackDiagnostic.summary(snapshot("idle", PlaybackFailure.TIMEOUT))
        assertTrue(failed.startsWith("Failed"))
        assertTrue(failed.contains(PlaybackFailure.TIMEOUT))
        assertTrue(failed.contains("HTTPS · Nabu Casa · /api/tts_proxy/[redacted]"))
        val playing = PlaybackDiagnostic.summary(snapshot("playing"))
        assertTrue(playing.contains("Music"))
        assertTrue(playing.contains("5s / 10s"))
        for (title in listOf(null, "https://ha.example/secret?token=secret", "opaque?secret")) {
            assertFalse(PlaybackDiagnostic.summary(snapshot("playing", title = title)).contains("secret"))
        }
    }
    @Test fun endpointClassesAndRoutesNeverRevealPathsOrQueries() {
        assertEquals("No media endpoint", PlaybackDiagnostic.endpoint(null))
        assertEquals("No media endpoint", PlaybackDiagnostic.endpoint("https://user:password@example.com/"))
        for (host in listOf("localhost", "ha.local", "ha.home.arpa", "ha.lan", "169.254.1.2", "10.0.0.1", "192.168.1.2", "127.0.0.1", "172.16.0.1", "172.31.1.2", "[::1]", "[fd00::1]", "[fe80::1]")) {
            assertTrue(host, PlaybackDiagnostic.endpoint("http://$host/local/secret?token=hidden").contains("HTTP · Local · /local/[redacted]"))
        }
        assertEquals("HTTPS · External · /api/media_source/[redacted]", PlaybackDiagnostic.endpoint("https://example.com/api/media_source/secret?authSig=secret"))
        assertTrue(PlaybackDiagnostic.endpoint("https://10.example.com/music.mp3").contains("External"))
        assertEquals("HTTPS · External · /[redacted]", PlaybackDiagnostic.endpoint("https://example.com/private/secret.mp3"))
    }
    @Test fun connectionFailuresAreSanitizedAndRetryable() {
        for (error in listOf(java.net.UnknownHostException("secret"), javax.net.ssl.SSLException("secret"), java.net.SocketTimeoutException("secret"), Exception("secret"))) {
            val status = TransportPolicy.connectionFailure(error)
            assertTrue(TransportPolicy.shouldReconnect(status))
            assertFalse(status.contains("secret"))
        }
        assertTrue(TransportPolicy.shouldReconnect("Connection temporarily unavailable; retrying"))
        assertFalse(TransportPolicy.shouldReconnect("Device authentication rejected; review registration"))
    }
}
