package us.marreros.haandroidmediaplayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppPrefsTest {
 @Test fun legacyHttpPreferenceCannotEnablePlaintextControl() {
  val context = RuntimeEnvironment.getApplication() as android.content.Context
  AppPrefs.prefs(context).edit().clear().commit()
  AppPrefs.prefs(context).edit().putBoolean("allow_insecure_http", true).commit()
  assertFalse(AppPrefs.isAllowedUrl("http://ha.local:8123"))
  assertTrue(AppPrefs.isAllowedUrl("https://ha.local:8123"))
 }
}
