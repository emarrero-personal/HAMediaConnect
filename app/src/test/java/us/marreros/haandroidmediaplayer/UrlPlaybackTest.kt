package us.marreros.haandroidmediaplayer

import android.content.Intent
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.concurrent.Executors

/** URL delivery through the real Media3 OkHttp source against a deterministic local HTTP fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UrlPlaybackTest {
    private val controller = Robolectric.buildService(PlaybackService::class.java)
    private lateinit var service: PlaybackService
    private val server = ServerSocket(0)
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var status = 200
    private val requests = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun wav(millis: Int): ByteArray {
        val samples = 8000 * millis / 1000
        return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
            repeat(samples) { putShort((Math.sin(it * 0.3) * 6000).toInt().toShort()) }
        }.array()
    }

    @Before fun setUp() {
        DeliveryTracker.reset()
        service = controller.create().get()
        val body = wav(300)
        executor.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                executor.execute {
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val line = reader.readLine() ?: return@use
                        requests += line
                        while (reader.readLine()?.isNotEmpty() == true) {}
                        val payload = if (status == 200) body else "secret".toByteArray()
                        it.getOutputStream().write(("HTTP/1.1 $status Fixture\r\nContent-Type: ${if (status == 200) "audio/wav" else "text/plain"}\r\n" +
                            "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n").toByteArray() + payload)
                    }
                }
            }
        }
    }
    @After fun tearDown() {
        server.close(); executor.shutdownNow(); controller.destroy()
    }

    private fun player() = PlaybackService::class.java.getDeclaredField("player").apply { isAccessible = true }.get(service) as ExoPlayer
    private fun play(url: String, key: String, announce: Boolean = true) {
        DeliveryTracker.begin(key, key, DeliveryMethod.URL, announce)
        service.onStartCommand(InternalPlayback.authorize(Intent().setAction(PlaybackService.CMD_PLAY_MEDIA)
            .putExtra(PlaybackService.EXTRA_URL, url).putExtra(PlaybackService.EXTRA_ANNOUNCE, announce)
            .putExtra(PlaybackService.EXTRA_DELIVERY_KEY, key)), 0, 1)
    }
    private fun awaitTerminal(limitMs: Long = 20_000): DeliveryStatus {
        val deadline = System.currentTimeMillis() + limitMs
        while (DeliveryTracker.latest?.phase?.terminal != true && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); Thread.sleep(5)
        }
        return DeliveryTracker.latest!!.also { assertTrue("not terminal: $it", it.phase.terminal) }
    }
    private val local get() = "http://127.0.0.1:${server.localPort}"

    @Test fun localHttpTtsIsRetrievedDecodedAndCompleted() {
        val phases = mutableListOf<DeliveryPhase>()
        val observer: (DeliveryStatus) -> Unit = { phases += it.phase }
        DeliveryTracker.observe(observer)
        try {
            play("$local/api/tts_proxy/fixture-token.wav", "A".repeat(16))
            assertEquals(DeliveryPhase.COMPLETED, awaitTerminal().phase)
            assertEquals(DeliveryPhase.RETRIEVING, phases[1])
            assertTrue(phases.toString(), phases.indexOf(DeliveryPhase.PLAYING) in 2 until phases.indexOf(DeliveryPhase.COMPLETED))
            assertTrue(requests.single().startsWith("GET /api/tts_proxy/fixture-token.wav"))
        } finally { DeliveryTracker.unobserve(observer) }
    }

    @Test fun httpFailuresAreClassifiedWithoutLeakingResponses() {
        for ((code, reason) in listOf(401 to DeliveryReason.ACCESS_DENIED, 403 to DeliveryReason.ACCESS_DENIED,
            404 to DeliveryReason.MISSING, 410 to DeliveryReason.MISSING, 500 to DeliveryReason.SERVER_ERROR,
            503 to DeliveryReason.SERVER_ERROR)) {
            status = code
            play("$local/api/tts_proxy/opaque-name.mp3?token=secret", "H$code".padEnd(16, 'x'))
            val result = awaitTerminal()
            assertEquals("HTTP $code", DeliveryPhase.FAILED, result.phase)
            assertEquals("HTTP $code", reason, result.reason)
            val card = PlaybackDiagnostic.summary(PlayerStateBus.latest!!, delivery = result)
            assertFalse(card, card.contains("secret") || card.contains("127.0.0.1") || card.contains("opaque-name"))
        }
        // A failure is not sticky: the next request plays normally.
        status = 200
        play("$local/api/tts_proxy/fresh.wav", "N".repeat(16))
        assertEquals(DeliveryPhase.COMPLETED, awaitTerminal().phase)
    }

    @Test fun dnsAndTlsFailuresAreDistinguished() {
        play("https://hamediaconnect-fixture.invalid/api/tts_proxy/a.mp3", "D".repeat(16))
        assertEquals(DeliveryReason.DNS, awaitTerminal().reason)
        // A plaintext reply to a TLS ClientHello: the handshake itself fails.
        ServerSocket(0).use { plain ->
            // Answer every attempt: OkHttp retries a failed handshake on a fresh connection.
            executor.execute {
                while (!plain.isClosed) try {
                    plain.accept().use { it.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".toByteArray()) }
                } catch (_: Exception) { break }
            }
            play("https://127.0.0.1:${plain.localPort}/api/tts_proxy/a.mp3", "T".repeat(16))
            assertEquals(DeliveryReason.TLS, awaitTerminal().reason)
        }
    }
}
