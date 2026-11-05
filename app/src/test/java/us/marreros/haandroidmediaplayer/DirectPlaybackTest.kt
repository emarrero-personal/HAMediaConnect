package us.marreros.haandroidmediaplayer

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.media3.common.Player
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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration

/** Plays transferred files through the production PlaybackService and Media3 decoding path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DirectPlaybackTest {
    private val controller = Robolectric.buildService(PlaybackService::class.java)
    private lateinit var service: PlaybackService
    private val phases = mutableListOf<Pair<String, DeliveryPhase>>()
    private val observer: (DeliveryStatus) -> Unit = { phases += it.key to it.phase }

    @Before fun setUp() {
        DeliveryTracker.reset()
        DeliveryTracker.observe(observer)
        service = controller.create().get()
    }
    @After fun tearDown() {
        DeliveryTracker.unobserve(observer)
        controller.destroy()
        TransferFiles.dir(service.cacheDir).deleteRecursively()
    }

    private fun player() = PlaybackService::class.java.getDeclaredField("player").apply { isAccessible = true }.get(service) as ExoPlayer

    /** A real 16-bit mono PCM WAV that Media3 must parse and render. */
    private fun wav(name: Char, millis: Int, dir: File = TransferFiles.dir(service.cacheDir)): File {
        val rate = 8000
        val samples = rate * millis / 1000
        val data = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(samples) { data.putShort((Math.sin(it * 2 * Math.PI * 440 / rate) * 8000).toInt().toShort()) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }
        dir.mkdirs()
        return File(dir, name.toString().repeat(22) + ".audio").apply { writeBytes(header.array() + data.array()) }
    }

    private fun play(file: File?, key: String, announce: Boolean, url: String? = null) {
        DeliveryTracker.begin(key, key, if (file != null) DeliveryMethod.DIRECT else DeliveryMethod.URL, announce)
        service.onStartCommand(InternalPlayback.authorize(Intent().setAction(PlaybackService.CMD_PLAY_MEDIA).apply {
            file?.let { putExtra(PlaybackService.EXTRA_FILE, it.absolutePath) }
            url?.let { putExtra(PlaybackService.EXTRA_URL, it) }
            putExtra(PlaybackService.EXTRA_CONTENT_TYPE, "audio/wav")
            putExtra(PlaybackService.EXTRA_ANNOUNCE, announce)
            putExtra(PlaybackService.EXTRA_DELIVERY_KEY, key)
        }), 0, 1)
    }

    private fun runUntil(limitMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + limitMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(5)
        }
        assertTrue("condition not reached; phases=$phases", condition())
    }

    @Test fun transferredAnnouncementDecodesPlaysCompletesAndIsDeleted() {
        val file = wav('A', 300)
        play(file, "R".repeat(16), announce = true)
        assertEquals(file.toURI().toString().removePrefix("file:"), player().currentMediaItem!!.localConfiguration!!.uri.path)
        assertTrue(PlayerStateBus.latest!!.direct)
        assertNull(PlayerStateBus.latest!!.contentId) // No path or URL is published to HA.
        runUntil { DeliveryTracker.latest?.phase == DeliveryPhase.COMPLETED }
        val seen = phases.filter { it.first == "R".repeat(16) }.map { it.second }
        assertEquals(DeliveryPhase.RECEIVED, seen.first())
        assertTrue(seen.toString(), seen.containsAll(listOf(DeliveryPhase.BUFFERING, DeliveryPhase.PLAYING, DeliveryPhase.COMPLETED)))
        assertTrue(seen.indexOf(DeliveryPhase.PLAYING) < seen.indexOf(DeliveryPhase.COMPLETED))
        assertFalse("temporary audio must be deleted after the announcement", file.exists())
    }

    @Test fun announcementInterruptsTransferredMusicAndRestoresItsPosition() {
        val music = wav('M', 60_000)
        play(music, "M".repeat(16), announce = false)
        runUntil { player().isPlaying }
        // Robolectric's audio output does not pace decoding, so a busy machine can report the
        // whole track as played before the test looks. Interrupt at a known position instead,
        // in the same main-thread turn, so the saved position cannot race to the end.
        val position = 10_000L
        player().seekTo(position)
        val announcement = wav('B', 200)
        play(announcement, "B".repeat(16), announce = true)
        runUntil { DeliveryTracker.latest?.key == "B".repeat(16) && DeliveryTracker.latest?.phase == DeliveryPhase.COMPLETED }
        assertEquals(music.absolutePath, player().currentMediaItem?.localConfiguration?.uri?.path)
        assertTrue("restored ${player().currentPosition} < saved $position", player().currentPosition >= position)
        assertTrue(player().playWhenReady)
        assertTrue("restored music file must remain", music.exists())
        assertFalse(announcement.exists())
        service.onStartCommand(InternalPlayback.authorize(Intent().setAction(PlaybackService.CMD_STOP)), 0, 1)
        assertFalse("stop deletes transferred music", music.exists())
    }

    @Test fun refusesFilesOutsideTheTransferDirectoryAndPartialTransfers() {
        val outside = wav('C', 100, service.cacheDir)
        play(outside, "C".repeat(16), announce = true)
        assertEquals(0, player().mediaItemCount)
        assertEquals(DeliveryPhase.FAILED, DeliveryTracker.latest!!.phase)
        assertTrue(outside.exists()) // Never deletes a file it does not own.
        val partial = File(TransferFiles.dir(service.cacheDir), "D".repeat(22) + ".part").apply { writeBytes(wav('D', 100).readBytes()) }
        play(partial, "D".repeat(16), announce = true)
        assertEquals(0, player().mediaItemCount)
    }

    @Test fun duplicateDeliveryIsPlayedOnce() {
        val file = wav('E', 2_000)
        play(file, "E".repeat(16), announce = false)
        runUntil { player().isPlaying }
        val before = phases.size
        service.onStartCommand(InternalPlayback.authorize(Intent().setAction(PlaybackService.CMD_PLAY_MEDIA)
            .putExtra(PlaybackService.EXTRA_FILE, file.absolutePath)
            .putExtra(PlaybackService.EXTRA_DELIVERY_KEY, "E".repeat(16))), 0, 1)
        assertTrue(player().isPlaying)
        assertEquals(before, phases.size)
    }

    @Test fun dndSuppressesTransferredAnnouncementAndDeletesIt() {
        val manager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        val file = wav('F', 100)
        play(file, "F".repeat(16), announce = true)
        assertEquals(DeliveryPhase.SUPPRESSED, DeliveryTracker.latest!!.phase)
        assertEquals(DeliveryReason.DND, DeliveryTracker.latest!!.reason)
        assertFalse(file.exists())
        assertEquals(0, player().mediaItemCount)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    @Test fun corruptAudioFailsAsDecodeThenNewPlaybackSucceeds() {
        val corrupt = File(TransferFiles.dir(service.cacheDir).apply { mkdirs() }, "G".repeat(22) + ".audio")
            .apply { writeBytes(ByteArray(4096) { 0x5a }) }
        play(corrupt, "G".repeat(16), announce = true)
        runUntil { DeliveryTracker.latest?.phase == DeliveryPhase.FAILED }
        assertEquals(DeliveryReason.DECODE, DeliveryTracker.latest!!.reason)
        assertFalse(corrupt.exists())
        assertTrue(PlaybackDiagnostic.summary(PlayerStateBus.latest!!, delivery = DeliveryTracker.latest).startsWith("Failed"))
        val good = wav('H', 200)
        play(good, "H".repeat(16), announce = true)
        runUntil { DeliveryTracker.latest?.key == "H".repeat(16) && DeliveryTracker.latest?.phase == DeliveryPhase.COMPLETED }
        assertNull(PlayerStateBus.latest!!.error)
    }

    @Test fun stopDuringPlaybackReportsStoppedNotCompleted() {
        val file = wav('J', 3_000)
        play(file, "J".repeat(16), announce = false)
        runUntil { player().isPlaying }
        service.onStartCommand(InternalPlayback.authorize(Intent().setAction(PlaybackService.CMD_STOP)), 0, 1)
        assertEquals(DeliveryPhase.FAILED, DeliveryTracker.latest!!.phase)
        assertEquals(DeliveryReason.STOPPED, DeliveryTracker.latest!!.reason)
        assertFalse(file.exists())
        assertEquals(Player.STATE_IDLE, player().playbackState)
    }
}
