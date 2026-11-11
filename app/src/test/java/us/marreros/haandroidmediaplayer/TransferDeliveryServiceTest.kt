package us.marreros.haandroidmediaplayer

import android.content.Context
import android.content.Intent
import android.os.Looper
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** ConnectionService orchestration: transfer, reconnect, supersession, and HA delivery reports. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransferDeliveryServiceTest {
    private val audio = ByteArray(TransferOffer.CHUNK_SIZE * 2 + 99) { (it % 251).toByte() }
    private val digest = MessageDigest.getInstance("SHA-256").digest(audio).joinToString("") { "%02x".format(it) }
    private fun offer(id: Char = 'T') = TransferOffer(id.toString().repeat(22), audio.size, digest, TransferOffer.CHUNK_SIZE, "audio/mpeg")
    private fun transferCommand(id: Char = 'T', request: String = "R".repeat(16)) =
        HaCommand(PlaybackService.CMD_PLAY_MEDIA, title = "Doorbell", contentType = "audio/mpeg", announce = true,
            requestId = request, transfer = offer(id))

    private inner class Fake(val status: (ConnectionEvent) -> Unit, val command: (HaCommand) -> Unit) : HaConnection {
        var ready = false
        var closed = false
        val requests = mutableListOf<Pair<Int, (ChunkResult) -> Unit>>()
        val reports = mutableListOf<Triple<String, String, String?>>()
        val released = mutableListOf<String>()
        override fun connect() { status(ConnectionEvent(ConnectionPhase.CONNECTING)) }
        override fun close() { closed = true; ready = false; requests.toList().forEach { it.second(ChunkResult.Disconnected) }; requests.clear() }
        override fun fireState(event: JSONObject) {}
        override fun supports(feature: String) = ready
        override fun requestChunk(transferId: String, offset: Int, callback: (ChunkResult) -> Unit): Boolean {
            if (!ready) return false
            requests += offset to callback; return true
        }
        override fun releaseTransfer(transferId: String) { released += transferId }
        override fun reportDelivery(requestId: String, phase: String, reason: String?): Boolean {
            if (!ready) return false
            reports += Triple(requestId, phase, reason); return true
        }
        fun connected() { ready = true; status(ConnectionEvent(ConnectionPhase.CONNECTED)) }
        fun fail(kind: ConnectionFailureKind) { ready = false; status(ConnectionEvent.failed(ConnectionFailure(kind))) }
        fun answer(count: Int = requests.size) = repeat(count) {
            val (offset, callback) = requests.removeAt(0)
            callback(ChunkResult.Data(offset, audio.copyOfRange(offset, minOf(audio.size, offset + TransferOffer.CHUNK_SIZE))))
        }
    }

    private val context = RuntimeEnvironment.getApplication() as Context
    private lateinit var controller: org.robolectric.android.controller.ServiceController<ConnectionService>
    private lateinit var service: ConnectionService
    private val sockets = mutableListOf<Fake>()

    @Before fun setUp() {
        DeliveryTracker.reset()
        AppPrefs.prefs(context).edit().clear().putString(AppPrefs.KEY_URL, "https://remote.ui.nabu.casa").commit()
        controller = Robolectric.buildService(ConnectionService::class.java).create()
        service = controller.get()
        val credentials = MemorySecrets().apply {
            put("credential_origin", "https://remote.ui.nabu.casa"); put("auth_method", "token"); put("long_lived_token", "fixture-token")
        }
        val signer = DeviceIdentity(context, TestKeys().apply { create() })
        service.tokenStoreFactory = { credentials }
        service.identityFactory = { signer }
        service.createConnection = { _, _, _, _, status, command -> Fake(status, command).also { sockets += it } }
        service.onStartCommand(Intent(), 0, 1); drain()
        sockets.last().connected(); drain()
    }
    @After fun tearDown() {
        controller.destroy()
        TransferFiles.dir(service.cacheDir).deleteRecursively()
    }

    private fun drain() {
        val scheduler = ConnectionService::class.java.getDeclaredField("scheduler").apply { isAccessible = true }.get(service) as ScheduledExecutorService
        repeat(3) { scheduler.submit {}.get(5, TimeUnit.SECONDS) }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun send(command: HaCommand, socket: Fake = sockets.last()) { socket.command(command); drain() }
    private fun startedPlayback(): Intent? {
        val app = shadowOf(RuntimeEnvironment.getApplication())
        var found: Intent? = null
        while (true) {
            val next = app.nextStartedService ?: break
            if (next.component?.className == PlaybackService::class.java.name) found = next
        }
        return found
    }
    private fun reconnect() {
        (ConnectionService::class.java.getDeclaredField("scheduler").apply { isAccessible = true }.get(service) as ScheduledExecutorService).submit {
            ConnectionService::class.java.getDeclaredMethod("connect").apply { isAccessible = true }.invoke(service)
        }.get(5, TimeUnit.SECONDS)
        drain()
        sockets.last().connected(); drain()
    }

    @Test fun completedTransferIsHandedToPlaybackAsAnOwnedFileWithReports() {
        startedPlayback()
        send(transferCommand())
        val socket = sockets.last()
        assertEquals(3, socket.requests.size)
        assertEquals("Downloading audio", DeliveryTracker.latest!!.label())
        socket.answer(); drain()
        val intent = startedPlayback()!!
        assertTrue(InternalPlayback.allowed(intent))
        assertEquals(PlaybackService.CMD_PLAY_MEDIA, intent.action)
        val file = File(intent.getStringExtra(PlaybackService.EXTRA_FILE)!!)
        assertTrue(TransferFiles.owned(service.cacheDir, file))
        assertArrayEquals(audio, file.readBytes())
        assertNull(intent.getStringExtra(PlaybackService.EXTRA_URL))
        assertTrue(intent.getBooleanExtra(PlaybackService.EXTRA_ANNOUNCE, false))
        assertEquals("R".repeat(16), intent.getStringExtra(PlaybackService.EXTRA_DELIVERY_KEY))
        assertEquals(listOf("T".repeat(22)), socket.released)
        assertEquals(listOf("received", "retrieving"), socket.reports.map { it.second })
        // Command receipt is not playback: only PlaybackService can advance to playing/completed.
        DeliveryTracker.update("R".repeat(16), DeliveryPhase.PLAYING); drain()
        DeliveryTracker.update("R".repeat(16), DeliveryPhase.COMPLETED); drain()
        assertEquals(listOf("received", "retrieving", "playing", "completed"), socket.reports.map { it.second })
    }

    @Test fun networkTransitionMidTransferResumesWithoutUserActionOrDuplicatePlayback() {
        send(transferCommand())
        val first = sockets.last()
        first.answer(1); drain()
        // Default network changes: the old socket closes, a fresh proof reconnects automatically.
        val network = android.net.Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(77)
        ConnectionService::class.java.getDeclaredMethod("networkChanged", android.net.Network::class.java).apply { isAccessible = true }.invoke(service, network)
        drain()
        assertTrue(first.closed)
        assertTrue(DeliveryTracker.latest!!.resuming)
        val second = sockets.last()
        assertNotSame(first, second)
        second.connected(); drain()
        assertEquals(listOf(49152, 98304), second.requests.map { it.first })
        second.answer(); drain()
        val intent = startedPlayback()!!
        assertArrayEquals(audio, File(intent.getStringExtra(PlaybackService.EXTRA_FILE)!!).readBytes())
        assertNull("exactly one playback request", startedPlayback())
        send(transferCommand(), second) // HA retransmission of the same offer.
        assertTrue(second.requests.isEmpty())
        assertNull(startedPlayback())
        assertFalse(AppPrefs.prefs(context).contains(AppPrefs.KEY_BLOCKED_FAILURE))
    }

    @Test fun retryableDisconnectResumesAfterReconnect() {
        send(transferCommand())
        sockets.last().fail(ConnectionFailureKind.TRANSPORT); drain()
        assertEquals("Reconnecting — audio transfer will resume", DeliveryTracker.latest!!.label())
        reconnect()
        sockets.last().answer(); drain()
        assertNotNull(startedPlayback())
    }

    @Test fun phasesMissedWhileOfflineAreReportedOnceAfterReconnect() {
        send(HaCommand(PlaybackService.CMD_PLAY_MEDIA, url = "https://public.example/a.mp3", requestId = "S".repeat(16)))
        assertEquals(listOf("received"), sockets.last().reports.map { it.second })
        sockets.last().fail(ConnectionFailureKind.TRANSPORT); drain()
        DeliveryTracker.update("S".repeat(16), DeliveryPhase.PLAYING); drain() // Played while HA was unreachable.
        reconnect()
        assertEquals(listOf(Triple("S".repeat(16), "playing", null)), sockets.last().reports)
        DeliveryTracker.update("S".repeat(16), DeliveryPhase.FAILED, DeliveryReason.UNREACHABLE); drain()
        assertEquals(Triple("S".repeat(16), "failed", "unreachable"), sockets.last().reports.last())
        reconnect()
        assertTrue(sockets.last().reports.isEmpty()) // Already acknowledged phases are not repeated.
    }

    @Test fun permanentRejectionCancelsTransferAndDeletesPartialAudio() {
        send(transferCommand())
        val socket = sockets.last()
        socket.answer(1); drain()
        socket.fail(ConnectionFailureKind.DEVICE_REJECTED); drain()
        assertEquals(DeliveryPhase.FAILED, DeliveryTracker.latest!!.phase)
        assertEquals(DeliveryReason.INTERRUPTED, DeliveryTracker.latest!!.reason)
        assertTrue(TransferFiles.dir(service.cacheDir).listFiles().orEmpty().isEmpty())
        assertNull(startedPlayback())
        assertEquals("Failed — audio transfer interrupted", DeliveryTracker.latest!!.label())
    }

    @Test fun newerUrlCommandOrStopSupersedesTransfer() {
        send(transferCommand())
        val socket = sockets.last()
        startedPlayback()
        send(HaCommand(PlaybackService.CMD_PLAY_MEDIA, url = "https://public.example/music.mp3", requestId = "S".repeat(16)))
        socket.answer(); drain()
        val intent = startedPlayback()!!
        assertEquals("https://public.example/music.mp3", intent.getStringExtra(PlaybackService.EXTRA_URL))
        assertNull(intent.getStringExtra(PlaybackService.EXTRA_FILE))
        assertEquals("S".repeat(16), DeliveryTracker.latest!!.key)
        assertTrue(TransferFiles.dir(service.cacheDir).listFiles().orEmpty().isEmpty())
        send(transferCommand('U', "U".repeat(16)))
        send(HaCommand(PlaybackService.CMD_STOP))
        socket.answer(); drain()
        assertEquals(PlaybackService.CMD_STOP, startedPlayback()!!.action)
        assertEquals(DeliveryReason.STOPPED, DeliveryTracker.latest!!.reason)
    }
}
