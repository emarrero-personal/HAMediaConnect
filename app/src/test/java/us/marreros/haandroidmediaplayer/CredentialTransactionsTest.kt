package us.marreros.haandroidmediaplayer

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CredentialTransactionsTest {
    @Test fun changesIncludingFailedWritesInvalidateOldRefreshes() {
        val version = CredentialTransactions.version()
        try { CredentialTransactions.change { error("fixture write failure") } } catch (_: IllegalStateException) { }
        var wrote = false
        assertFalse(CredentialTransactions.commit(version, { true }) { wrote = true })
        assertFalse(wrote)
        assertFalse(CredentialTransactions.commit(CredentialTransactions.version(), { false }) { wrote = true })
        assertFalse(wrote)
    }
    @Test fun replacementThroughAnotherStoreCannotInterleaveBetweenValidationAndWrite() {
        val context = RuntimeEnvironment.getApplication() as Context
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val first = SecureTokenStore(context) { key }
        val second = SecureTokenStore(context) { key }
        first.clear()
        first.put("credential_origin", "https://a.example")
        first.put("access_token", "A-old")
        val version = CredentialTransactions.version()
        val validated = CountDownLatch(1)
        val continueCommit = CountDownLatch(1)
        val writerAttempting = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val refresh = executor.submit<Boolean> {
                CredentialTransactions.commit(version, {
                    validated.countDown()
                    check(continueCommit.await(5, TimeUnit.SECONDS))
                    first.get("credential_origin") == "https://a.example"
                }) { first.put("access_token", "A-fresh") }
            }
            assertTrue(validated.await(5, TimeUnit.SECONDS))
            val replace = executor.submit {
                writerAttempting.countDown()
                CredentialTransactions.read {
                    second.clear()
                    second.put("credential_origin", "https://b.example")
                    second.put("access_token", "B-fresh")
                }
            }
            assertTrue(writerAttempting.await(5, TimeUnit.SECONDS))
            try { replace.get(100, TimeUnit.MILLISECONDS); fail("Replacement entered an active refresh transaction") }
            catch (_: TimeoutException) { }
            continueCommit.countDown()
            assertTrue(refresh.get(5, TimeUnit.SECONDS))
            replace.get(5, TimeUnit.SECONDS)
            assertEquals("https://b.example", first.get("credential_origin"))
            assertEquals("B-fresh", first.get("access_token"))
            assertFalse(CredentialTransactions.commit(version, { true }) { first.put("access_token", "A-stale") })
            second.clear() // Forgetting through another instance also invalidates a refresh snapshot.
            val forgottenVersion = CredentialTransactions.version()
            second.clear()
            assertFalse(CredentialTransactions.commit(forgottenVersion, { true }) {
                first.put("access_token", "A-stale")
            })
            assertNull(first.get("access_token"))
        } finally { continueCommit.countDown(); executor.shutdownNow(); first.clear() }
    }
}
