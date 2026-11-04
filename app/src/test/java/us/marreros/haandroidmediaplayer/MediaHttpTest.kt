package us.marreros.haandroidmediaplayer

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercises the same HTTP data source Media3 uses, not a browser or a URL probe. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaHttpTest {
    private fun response(code: Int, type: String = "audio/mpeg", block: (String, PlaybackService) -> Unit) {
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val executor = Executors.newSingleThreadExecutor()
            val request = executor.submit<List<String>> {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val headers = mutableListOf<String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers.add(line)
                    }
                    socket.getOutputStream().write(("HTTP/1.1 $code Test\r\nContent-Type: $type\r\n" +
                        "Content-Length: 3\r\nLocation: http://127.0.0.1:1/leak\r\nConnection: close\r\n\r\nabc").toByteArray())
                    headers
                }
            }
            val controller = Robolectric.buildService(PlaybackService::class.java).create()
            try {
                block("http://127.0.0.1:${server.localPort}/api/tts_proxy/a.mp3?authSig=fixture", controller.get())
                val lines = request.get(5, TimeUnit.SECONDS)
                assertTrue(lines.first().contains("?authSig=fixture"))
                assertFalse(lines.any { it.startsWith("Authorization:", ignoreCase = true) || it.startsWith("Cookie:", ignoreCase = true) })
            } finally { controller.destroy(); executor.shutdownNow() }
        }
    }
    @Test fun productionDataSourceFollowsOnlySameOriginRedirectAndPreservesSignatures() {
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val executor = Executors.newSingleThreadExecutor()
            val requests = executor.submit<List<List<String>>> {
                (0..1).map { index -> server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val lines = mutableListOf<String>()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; lines += line }
                    val response = if (index == 0) "HTTP/1.1 302 Found\r\nLocation: /api/tts_proxy/fresh?authSig=a%2Fb&x=1&x=2\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    else "HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc"
                    socket.getOutputStream().write(response.toByteArray())
                    lines
                } }
            }
            val controller = Robolectric.buildService(PlaybackService::class.java).create()
            try {
                val source = controller.get().mediaDataSourceFactory.createDataSource()
                try {
                    assertEquals(3, source.open(DataSpec(Uri.parse("http://127.0.0.1:${server.localPort}/start?authSig=fixture"))))
                    val bytes = ByteArray(3); assertEquals(3, source.read(bytes, 0, 3)); assertEquals("abc", String(bytes))
                } finally { source.close() }
                val headers = requests.get(5, TimeUnit.SECONDS)
                assertTrue(headers.last().first().contains("authSig=a%2Fb&x=1&x=2"))
                assertFalse(headers.flatten().any { it.startsWith("Authorization:", true) || it.startsWith("Cookie:", true) })
            } finally { controller.destroy(); executor.shutdownNow() }
        }
    }
    @Test fun localHttpLoadsAudioBytesWithoutControlCredentials() {
        response(200) { url, service ->
            val source = service.mediaDataSourceFactory.createDataSource()
            try {
                assertEquals(3, source.open(DataSpec(Uri.parse(url))))
                val bytes = ByteArray(3)
                assertEquals(3, source.read(bytes, 0, 3))
                assertEquals("abc", String(bytes))
            } finally { source.close() }
        }
    }
    @Test fun httpErrorsAndRedirectsSurfaceThroughMedia3() {
        for (code in listOf(401, 403, 404, 410, 500, 503, 302, 307)) response(code) { url, service ->
            val source = service.mediaDataSourceFactory.createDataSource()
            try {
                source.open(DataSpec(Uri.parse(url)))
                fail("Expected HTTP failure")
            } catch (error: HttpDataSource.InvalidResponseCodeException) {
                assertEquals(code, error.responseCode)
            } finally { source.close() }
        }
    }
    @Test fun proxyLoginHtmlIsRejectedBeforeDecoding() {
        response(200, "text/html") { url, service ->
            val source = service.mediaDataSourceFactory.createDataSource()
            try {
                source.open(DataSpec(Uri.parse(url)))
                fail("Expected content type rejection")
            } catch (_: HttpDataSource.InvalidContentTypeException) {
                // A successful proxy login page must not be treated as audio.
            } finally { source.close() }
        }
    }
}
