package us.marreros.haandroidmediaplayer
import org.junit.Assert.*
import org.junit.Test
class HaCommandTest {
 @Test fun defaults() {
  val c=HaCommand("stop")
  assertEquals("stop",c.command)
  assertNull(c.url)
  assertNull(c.title)
  assertNull(c.contentType)
  assertNull(c.volume)
  assertNull(c.position)
  assertNull(c.muted)
  assertFalse(c.announce)
 }
 @Test fun copiesAllProperties() {
  val c=HaCommand("play_media","https://example.com/a.mp3","Test","audio/mpeg",0.4f,120L,true,true)
  assertEquals("https://example.com/a.mp3",c.url)
  assertEquals("Test",c.title)
  assertEquals("audio/mpeg",c.contentType)
  assertEquals(0.4f,c.volume!!,0.001f)
  assertEquals(120L,c.position)
  assertEquals(true,c.muted)
  assertTrue(c.announce)
  assertEquals(c,c.copy())
  assertNotEquals(c,c.copy(announce=false))
 }
}
