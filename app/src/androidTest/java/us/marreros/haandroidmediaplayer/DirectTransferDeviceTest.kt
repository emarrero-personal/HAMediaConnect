package us.marreros.haandroidmediaplayer

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real device decoder/AudioTrack playback of a completed transfer file; disposable devices only. */
@RunWith(AndroidJUnit4::class)
class DirectTransferDeviceTest {
    @Test fun transferredAnnouncementPlaysCompletesAndIsDeleted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.stopService(Intent(context, ConnectionService::class.java))
        context.stopService(Intent(context, PlaybackService::class.java))
        val samples = 8000
        val wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
            repeat(samples) { putShort((Math.sin(it * 2 * Math.PI * 440 / 8000) * 6000).toInt().toShort()) }
        }.array()
        val file = File(TransferFiles.dir(context.cacheDir).apply { mkdirs() }, "D".repeat(22) + ".audio").apply { writeBytes(wav) }
        val playing = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val observer: (DeliveryStatus) -> Unit = {
            if (it.phase == DeliveryPhase.PLAYING) playing.countDown()
            if (it.phase == DeliveryPhase.COMPLETED) completed.countDown()
        }
        DeliveryTracker.observe(observer)
        try {
            val key = DeliveryTracker.localKey()
            DeliveryTracker.begin(key, null, DeliveryMethod.DIRECT, true)
            instrumentation.runOnMainSync {
                context.startService(InternalPlayback.authorize(Intent(context, PlaybackService::class.java)
                    .setAction(PlaybackService.CMD_PLAY_MEDIA)
                    .putExtra(PlaybackService.EXTRA_FILE, file.absolutePath)
                    .putExtra(PlaybackService.EXTRA_CONTENT_TYPE, "audio/wav")
                    .putExtra(PlaybackService.EXTRA_ANNOUNCE, true)
                    .putExtra(PlaybackService.EXTRA_DELIVERY_KEY, key)))
            }
            assertTrue("Transferred audio must reach Playing", playing.await(15, TimeUnit.SECONDS))
            assertTrue("The one-second announcement must complete", completed.await(15, TimeUnit.SECONDS))
            val deadline = System.currentTimeMillis() + 2000
            while (file.exists() && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertFalse("Temporary audio must be deleted after playback", file.exists())
        } finally {
            DeliveryTracker.unobserve(observer)
            file.delete()
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
