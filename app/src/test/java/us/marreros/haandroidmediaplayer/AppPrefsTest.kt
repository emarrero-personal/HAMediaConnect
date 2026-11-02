package us.marreros.haandroidmediaplayer
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppPrefsTest {
 @Test fun createsAndPersistsDeviceId() {
  val context:Context=RuntimeEnvironment.getApplication()
  AppPrefs.prefs(context).edit().clear().commit()
  val id=AppPrefs.ensureDeviceId(context)
  assertTrue(id.isNotBlank())
  assertEquals(id,AppPrefs.ensureDeviceId(context))
  assertEquals(id,AppPrefs.prefs(context).getString(AppPrefs.KEY_DEVICE_ID,null))
 }
 @Test fun keepsExistingDeviceId() {
  val context:Context=RuntimeEnvironment.getApplication()
  AppPrefs.prefs(context).edit().putString(AppPrefs.KEY_DEVICE_ID,"existing").commit()
  assertEquals("existing",AppPrefs.ensureDeviceId(context))
 }
}
