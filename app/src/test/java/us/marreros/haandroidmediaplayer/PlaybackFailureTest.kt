package us.marreros.haandroidmediaplayer

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackFailureTest {
    private fun error(cause: Throwable?, code: Int = PlaybackException.ERROR_CODE_IO_UNSPECIFIED) =
        PlaybackException("https://secret.example/path?token=secret", cause, code)

    @Test fun categorizesHttpFailuresWithoutLeakingResponseOrSignedUrl() {
        for ((code, expected) in listOf(401 to "access denied", 403 to "access denied", 404 to "expired",
            410 to "expired", 500 to "unavailable", 503 to "unavailable", 302 to "redirect refused", 418 to "request failed")) {
            val failure = HttpDataSource.InvalidResponseCodeException(code, "secret", null,
                mapOf("Authorization" to listOf("secret")),
                DataSpec(Uri.parse("https://secret.example/a?authSig=secret")), "secret".toByteArray())
            val diagnostic = PlaybackFailure.describe(error(failure))
            assertTrue(diagnostic, diagnostic.contains(expected))
            assertFalse(diagnostic.contains("secret"))
            assertFalse(diagnostic.contains("https://"))
        }
    }

    @Test fun connectionTlsTimeoutPermissionAndDecodeAreActionable() {
        assertTrue(PlaybackFailure.describe(error(SocketTimeoutException("secret"))).contains("timed out"))
        assertTrue(PlaybackFailure.describe(error(UnknownHostException("secret"))).contains("external HTTPS"))
        assertTrue(PlaybackFailure.describe(error(SSLException("secret"))).contains("certificate"))
        val content = HttpDataSource.InvalidContentTypeException("text/html; secret",
            DataSpec(Uri.parse("https://secret.example")))
        assertTrue(PlaybackFailure.describe(error(content)).contains("content type"))
        for (code in listOf(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)) {
            assertTrue(PlaybackFailure.describe(error(null, code)).contains("decoding failed"))
        }
        assertTrue(PlaybackFailure.describe(error(null, PlaybackException.ERROR_CODE_IO_NO_PERMISSION)).contains("Local network"))
        assertTrue(PlaybackFailure.describe(error(null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)).contains("external HTTPS"))
        assertEquals(PlaybackFailure.TIMEOUT, PlaybackFailure.describe(error(null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)))
        assertEquals(PlaybackFailure.TIMEOUT, PlaybackFailure.describe(error(null, PlaybackException.ERROR_CODE_TIMEOUT)))
        assertFalse(PlaybackFailure.describe(error(null)).contains("secret"))
        assertEquals(30_000L, PlaybackFailure.BUFFER_TIMEOUT_MS)
    }
}
