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
 @Test fun doesNotEnableHttpImplicitly() {
  val context = RuntimeEnvironment.getApplication() as android.content.Context
  AppPrefs.prefs(context).edit().clear().commit()
  assertFalse(AppPrefs.prefs(context).getBoolean(AppPrefs.KEY_ALLOW_HTTP, false))
  assertFalse(AppPrefs.isAllowedUrl("http://ha.local:8123", false))
  assertTrue(AppPrefs.isAllowedUrl("https://ha.local:8123", false))
 }
}
