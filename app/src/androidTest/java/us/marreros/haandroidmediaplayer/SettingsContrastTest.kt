package us.marreros.haandroidmediaplayer

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** UI contrast regression test: run on a disposable emulator in light and dark system modes. */
@RunWith(AndroidJUnit4::class)
class SettingsContrastTest {
    @Test fun connectionFieldsRemainReadableOnLightCards() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val activity = instrumentation.startActivitySync(intent) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.findViewById<android.view.View>(R.id.settingsButton).performClick()
                for (id in listOf(R.id.urlInput, R.id.tokenInput, R.id.nameInput)) {
                    val field = activity.findViewById<EditText>(id)
                    assertEquals("Input text must contrast with the light card", Color.rgb(41, 28, 82), field.currentTextColor)
                    assertEquals("Hint must contrast with the light card", Color.rgb(101, 90, 122), field.hintTextColors.defaultColor)
                    assertTrue("Field must be enabled", field.isEnabled)
                }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
