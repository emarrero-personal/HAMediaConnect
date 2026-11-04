package us.marreros.haandroidmediaplayer

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Optional LAN fixture test; run only on a disposable emulator (see TTS_PLAYBACK.md). */
@RunWith(AndroidJUnit4::class)
class LocalMediaDeviceTest {
    @Test fun localAudioUsesAppPermissionAndProductionPlayer() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("local_media_test_url")
        assumeTrue("A controlled LAN audio fixture is required", !url.isNullOrBlank())
        val expected = args.getString("local_media_expected", "playing")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val finished = CountDownLatch(1)
        val completed = CountDownLatch(1)
        var played = false
        context.stopService(Intent(context, ConnectionService::class.java))
        context.stopService(Intent(context, PlaybackService::class.java))
        AppPrefs.prefs(context).edit().remove("playback_error").commit()
        PlayerStateBus.listener = { state ->
            if (state.state == "playing") { played = true; finished.countDown() }
            if (played && state.state == "idle" && state.contentId == null) completed.countDown()
            if (AppPrefs.prefs(context).contains("playback_error")) finished.countDown()
        }
        try {
            instrumentation.runOnMainSync {
                context.startService(InternalPlayback.authorize(Intent(context, PlaybackService::class.java)
                    .setAction(PlaybackService.CMD_PLAY_MEDIA)
                    .putExtra(PlaybackService.EXTRA_URL, url)
                    .putExtra(PlaybackService.EXTRA_CONTENT_TYPE, "audio/mpeg")
                    .putExtra(PlaybackService.EXTRA_ANNOUNCE, true)))
            }
            assertTrue("Playback must finish loading within its deadline", finished.await(35, TimeUnit.SECONDS))
            if (expected == "denied") {
                assertFalse("Denied LAN permission must prevent playback", played)
                assertNotNull("Failure must be visible", AppPrefs.prefs(context).getString("playback_error", null))
            } else {
                assertTrue("Granted LAN permission must allow MP3 decoding/playback", played)
                assertTrue("The short announcement must complete and restore idle", completed.await(10, TimeUnit.SECONDS))
                assertNull(AppPrefs.prefs(context).getString("playback_error", null))
            }
        } finally {
            PlayerStateBus.listener = null
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
