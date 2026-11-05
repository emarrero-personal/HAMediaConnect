package us.marreros.haandroidmediaplayer

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeliveryStatusTest {
    @Before fun reset() = DeliveryTracker.reset()
    private val idle = PlayerSnapshot("idle", .5f, false, 0, 0, null, null)

    @Test fun trackerFollowsOnlyTheLatestRequestAndTerminalPhasesAreFinal() {
        val seen = mutableListOf<DeliveryStatus>()
        val observer: (DeliveryStatus) -> Unit = { seen += it }
        DeliveryTracker.observe(observer)
        try {
            assertTrue(DeliveryTracker.begin("a", "a".repeat(16), DeliveryMethod.DIRECT, true))
            assertFalse(DeliveryTracker.begin("a", "a".repeat(16), DeliveryMethod.DIRECT, true)) // Duplicate.
            assertTrue(DeliveryTracker.update("a", DeliveryPhase.RETRIEVING))
            assertFalse(DeliveryTracker.update("a", DeliveryPhase.RETRIEVING)) // No-op change.
            assertFalse(DeliveryTracker.update("other", DeliveryPhase.PLAYING))
            assertFalse(DeliveryTracker.update(null, DeliveryPhase.PLAYING))
            assertTrue(DeliveryTracker.update("a", DeliveryPhase.COMPLETED))
            assertFalse(DeliveryTracker.update("a", DeliveryPhase.FAILED, DeliveryReason.STOPPED))
            assertTrue(DeliveryTracker.begin("b", null, DeliveryMethod.URL, false))
            assertFalse(DeliveryTracker.update("a", DeliveryPhase.PLAYING)) // Stale request.
            assertEquals(listOf(DeliveryPhase.RECEIVED, DeliveryPhase.RETRIEVING, DeliveryPhase.COMPLETED, DeliveryPhase.RECEIVED), seen.map { it.phase })
            assertNotEquals(DeliveryTracker.localKey(), DeliveryTracker.localKey())
        } finally { DeliveryTracker.unobserve(observer) }
    }

    @Test fun labelsAreConciseAndDistinguishEveryPhase() {
        val direct = DeliveryStatus("k", null, DeliveryMethod.DIRECT, true)
        assertEquals("Receiving announcement", direct.label())
        assertEquals("Receiving media", direct.copy(announce = false).label())
        assertEquals("Downloading audio", direct.copy(phase = DeliveryPhase.RETRIEVING).label())
        assertEquals("Loading media", direct.copy(phase = DeliveryPhase.RETRIEVING, method = DeliveryMethod.URL).label())
        assertEquals("Reconnecting — audio transfer will resume", direct.copy(phase = DeliveryPhase.RETRIEVING, resuming = true).label())
        assertEquals("Buffering", direct.copy(phase = DeliveryPhase.BUFFERING).label())
        assertEquals("Media ready", direct.copy(phase = DeliveryPhase.READY).label())
        assertEquals("Playing", direct.copy(phase = DeliveryPhase.PLAYING).label())
        assertEquals("Completed", direct.copy(phase = DeliveryPhase.COMPLETED).label())
        assertEquals("Failed — media unreachable", direct.copy(phase = DeliveryPhase.FAILED, reason = DeliveryReason.UNREACHABLE).label())
        assertEquals("Failed — playback failed", direct.copy(phase = DeliveryPhase.FAILED).label())
        assertEquals("Suppressed — Do Not Disturb", direct.copy(phase = DeliveryPhase.SUPPRESSED).label())
        assertEquals(PlaybackDiagnostic.DIRECT, direct.methodLabel)
        assertEquals("Media URL", direct.copy(method = DeliveryMethod.URL).methodLabel)
        val wire = DeliveryReason.values().map { it.wire } + DeliveryPhase.values().map { it.wire }
        assertTrue(wire.all { it.matches(Regex("[a-z_]{2,16}")) })
    }

    @Test fun playbackCardShowsDeliveryBeforePlaybackAndNeverUrls() {
        val receiving = DeliveryStatus("k", "r".repeat(16), DeliveryMethod.DIRECT, true)
        assertEquals("Receiving announcement\n${PlaybackDiagnostic.DIRECT}", PlaybackDiagnostic.summary(idle, delivery = receiving))
        val downloading = receiving.copy(phase = DeliveryPhase.RETRIEVING)
        val music = idle.copy(state = "playing", title = "Song", contentId = "https://music.example/a.mp3?token=secret")
        val overMusic = PlaybackDiagnostic.summary(music, delivery = downloading)
        assertTrue(overMusic.startsWith("Downloading audio\nPlaying\nSong"))
        assertFalse(overMusic.contains("secret"))
        val playingDirect = PlaybackDiagnostic.summary(idle.copy(state = "playing", direct = true),
            delivery = receiving.copy(phase = DeliveryPhase.PLAYING))
        assertTrue(playingDirect.startsWith("Playing\nDirect transfer"))
        assertEquals("Ready — Waiting for audio from Home Assistant\nLast delivery: Completed",
            PlaybackDiagnostic.summary(idle, delivery = receiving.copy(phase = DeliveryPhase.COMPLETED)))
        assertEquals("Failed — audio transfer interrupted\nReady — Waiting for audio from Home Assistant",
            PlaybackDiagnostic.summary(idle, delivery = receiving.copy(phase = DeliveryPhase.FAILED, reason = DeliveryReason.INTERRUPTED)))
        assertTrue(PlaybackDiagnostic.summary(idle, ConnectionPhase.RECONNECTING,
            receiving.copy(phase = DeliveryPhase.RETRIEVING, resuming = true)).startsWith("Reconnecting — audio transfer will resume"))
        assertEquals("Ready — Waiting for audio from Home Assistant",
            PlaybackDiagnostic.summary(idle, delivery = receiving.copy(phase = DeliveryPhase.FAILED, reason = DeliveryReason.STOPPED)))
        // A Media3 failure is shown once, by the playback section.
        val failed = PlaybackDiagnostic.summary(idle.copy(error = PlaybackFailure.TIMEOUT),
            delivery = receiving.copy(phase = DeliveryPhase.FAILED, reason = DeliveryReason.TIMEOUT))
        assertTrue(failed.startsWith("Failed\n"))
    }

    private fun http(code: Int) = HttpDataSource.InvalidResponseCodeException(code, "secret", null, emptyMap(),
        DataSpec(Uri.parse("https://secret.example/a?authSig=secret")), ByteArray(0))
    private fun error(cause: Throwable?, code: Int = PlaybackException.ERROR_CODE_IO_UNSPECIFIED) = PlaybackException("secret", cause, code)

    @Test fun mediaFailuresMapToReportableReasons() {
        for ((code, reason) in mapOf(401 to DeliveryReason.ACCESS_DENIED, 403 to DeliveryReason.ACCESS_DENIED,
            404 to DeliveryReason.MISSING, 410 to DeliveryReason.MISSING, 302 to DeliveryReason.REDIRECT,
            500 to DeliveryReason.SERVER_ERROR, 502 to DeliveryReason.SERVER_ERROR, 418 to DeliveryReason.UNREACHABLE)) {
            assertEquals("HTTP $code", reason, PlaybackFailure.reason(error(http(code))))
        }
        assertEquals(DeliveryReason.CONTENT_TYPE, PlaybackFailure.reason(error(HttpDataSource.InvalidContentTypeException("text/html",
            DataSpec(Uri.parse("https://e/a"))))))
        assertEquals(DeliveryReason.DNS, PlaybackFailure.reason(error(java.io.IOException(java.net.UnknownHostException("secret")))))
        assertEquals(DeliveryReason.TLS, PlaybackFailure.reason(error(javax.net.ssl.SSLHandshakeException("secret"))))
        assertEquals(DeliveryReason.TIMEOUT, PlaybackFailure.reason(error(java.net.SocketTimeoutException())))
        for ((code, reason) in mapOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED to DeliveryReason.UNREACHABLE,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION to DeliveryReason.UNREACHABLE,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT to DeliveryReason.TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT to DeliveryReason.TIMEOUT,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND to DeliveryReason.MISSING,
            PlaybackException.ERROR_CODE_DECODING_FAILED to DeliveryReason.DECODE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED to DeliveryReason.DECODE,
            PlaybackException.ERROR_CODE_UNSPECIFIED to DeliveryReason.OTHER)) {
            assertEquals(reason, PlaybackFailure.reason(error(null, code)))
        }
    }
}
