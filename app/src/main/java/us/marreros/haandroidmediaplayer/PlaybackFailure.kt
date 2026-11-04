package us.marreros.haandroidmediaplayer

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Fixed diagnostics only: exception messages, headers and URLs may contain secrets. */
object PlaybackFailure {
    const val BUFFER_TIMEOUT_MS = 30_000L
    const val TIMEOUT = "Playback timed out; check media endpoint reachability and retry"

    fun describe(error: PlaybackException): String {
        var cause: Throwable? = error
        repeat(12) {
            when (val current = cause) {
                is HttpDataSource.InvalidResponseCodeException -> return when (current.responseCode) {
                    401, 403 -> "Media access denied (HTTP ${current.responseCode}); regenerate media or check proxy authentication"
                    404, 410 -> "Media missing or expired (HTTP ${current.responseCode}); generate TTS again"
                    in 300..399 -> "Media redirect refused; configure a direct HTTPS media endpoint"
                    in 500..599 -> "Media server unavailable (HTTP ${current.responseCode}); check Home Assistant or proxy"
                    else -> "Media HTTP request failed (HTTP ${current.responseCode})"
                }
                is HttpDataSource.InvalidContentTypeException -> return "Unexpected media content type; check proxy login pages and audio response"
                is SocketTimeoutException -> return TIMEOUT
                is UnknownHostException -> return "Media hostname unavailable; configure reachable external HTTPS in Home Assistant"
                is SSLException -> return "Media TLS validation failed; check endpoint certificate"
            }
            cause = cause?.cause
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "Media permission denied; allow Local network access in Android app settings"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Media connection failed; check Local network permission or configure external HTTPS in Home Assistant"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_TIMEOUT -> TIMEOUT
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "Media decoding failed; check generated audio format and proxy response"
            else -> "Media playback failed (Media3 code ${error.errorCode}); check audio endpoint and format"
        }
    }
}
