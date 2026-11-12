package us.marreros.haandroidmediaplayer

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Test

class BackgroundAudioTest {
    @After fun reset() = BackgroundAudio.stopped()

    @Test fun android17NeedsUserActivationAgainAfterServiceStops() {
        BackgroundAudio.stopped()
        assertTrue(BackgroundAudio.allowed(36))
        assertFalse(BackgroundAudio.allowed(37))
        BackgroundAudio.startedByUser()
        assertTrue(BackgroundAudio.allowed(37))
        assertTrue(BackgroundAudio.allowed(38))
        BackgroundAudio.stopped()
        assertFalse(BackgroundAudio.allowed(37))
    }

    @Test fun unavailableSuppressionDoesNotClaimDndOrSuccessfulPlayback() {
        DeliveryTracker.reset()
        DeliveryTracker.begin("background-test", null, DeliveryMethod.DIRECT, true)
        DeliveryTracker.update("background-test", DeliveryPhase.SUPPRESSED, DeliveryReason.UNAVAILABLE)
        assertFalse(DeliveryTracker.update("background-test", DeliveryPhase.COMPLETED))
        val label = DeliveryTracker.latest!!.label()
        assertTrue(label.contains("Enable background audio"))
        assertFalse(label.contains("Do Not Disturb"))
        assertEquals("Suppressed — Do Not Disturb", DeliveryStatus("dnd", null,
            DeliveryMethod.DIRECT, true, DeliveryPhase.SUPPRESSED, DeliveryReason.DND).label())
    }
}
