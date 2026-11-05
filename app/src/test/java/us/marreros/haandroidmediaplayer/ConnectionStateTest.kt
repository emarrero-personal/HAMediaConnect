package us.marreros.haandroidmediaplayer

import org.junit.Assert.*
import org.junit.Test

class ConnectionStateTest {
    @Test fun everyFailureHasAnExplicitRetryPolicyAndSafePresentation() {
        val permanent = setOf(ConnectionFailureKind.OAUTH_REJECTED, ConnectionFailureKind.HA_REJECTED,
            ConnectionFailureKind.DEVICE_REJECTED, ConnectionFailureKind.PROTOCOL,
            ConnectionFailureKind.KEY_UNAVAILABLE, ConnectionFailureKind.CONFIGURATION)
        for (kind in ConnectionFailureKind.values()) {
            val failure = ConnectionFailure(kind)
            assertEquals(kind !in permanent, failure.retryable)
            assertEquals(kind.message, ConnectionEvent.failed(failure).message)
            assertFalse(kind.message.contains("://"))
            assertFalse(kind.message.contains("secret"))
            assertEquals(kind.stage, failure.stage)
        }
        for (phase in ConnectionPhase.values()) assertTrue(ConnectionEvent(phase).message.isNotBlank())
        for (stage in FailureStage.values()) assertTrue(stage.name.isNotBlank())
    }
    @Test fun actualServerCodesDistinguishDenialFromRetryWithoutAcceptingAnyFailedProof() {
        for (stage in listOf(FailureStage.REGISTRATION, FailureStage.SUBSCRIPTION, FailureStage.STATE)) {
            for (code in listOf("device_auth_failed", "unauthorized", "invalid_auth", null, "", "invalid_format", "unknown_command")) {
                val failure = ConnectionFailure.response(code, stage)
                assertEquals(code == "device_auth_failed" && stage == FailureStage.SUBSCRIPTION, failure.retryable)
                assertEquals(stage, failure.stage)
            }
            for (code in listOf("subscription_busy", "storage_failed", "temporarily_unavailable", "unknown_error", "server_busy")) {
                val failure = ConnectionFailure.response(code, stage)
                assertTrue(failure.retryable)
                assertEquals(stage, failure.stage)
                assertNotEquals(ConnectionPhase.CONNECTED, ConnectionEvent.failed(failure).phase)
            }
        }
    }
    @Test fun backoffIsBoundedWithJitterAndNeverStopsRetrying() {
        for (attempt in listOf(0, 1, 6, 100, Int.MAX_VALUE)) {
            val low = TransportPolicy.reconnectDelay(attempt, 0.0)
            val high = TransportPolicy.reconnectDelay(attempt, 1.0)
            assertTrue(low >= 2500)
            assertTrue(high <= 300000)
            assertTrue(low < high)
            assertEquals((low + high) / 2, TransportPolicy.reconnectDelay(attempt, .5))
        }
    }
}
