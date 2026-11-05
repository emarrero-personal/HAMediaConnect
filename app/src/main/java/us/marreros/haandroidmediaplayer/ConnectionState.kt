package us.marreros.haandroidmediaplayer

/** Control decisions use these values; display text never determines authorization or retries. */
enum class ConnectionPhase { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, AUTHENTICATION_REJECTED }
enum class FailureStage { TRANSPORT, HANDSHAKE, OAUTH, HA_AUTHENTICATION, REGISTRATION, SUBSCRIPTION, STATE, PROTOCOL, CONFIGURATION }
enum class ConnectionFailureKind(val retryable: Boolean, val message: String, val stage: FailureStage) {
    TRANSPORT(true, "Disconnected", FailureStage.TRANSPORT),
    DNS(true, "Disconnected: DNS lookup failed; check network", FailureStage.TRANSPORT),
    TLS(true, "Disconnected: TLS validation failed; check certificate", FailureStage.TRANSPORT),
    TIMEOUT(true, "Disconnected: connection timed out", FailureStage.TRANSPORT),
    HANDSHAKE_TIMEOUT(true, "Authentication timed out", FailureStage.HANDSHAKE),
    STATE_TIMEOUT(true, "State acknowledgment timed out", FailureStage.STATE),
    OAUTH_UNAVAILABLE(true, "Token refresh unavailable; retrying", FailureStage.OAUTH),
    OAUTH_REJECTED(false, "Authentication failed; sign in again", FailureStage.OAUTH),
    HA_REJECTED(false, "Authentication failed", FailureStage.HA_AUTHENTICATION),
    DEVICE_REJECTED(false, "Device authentication rejected; review registration", FailureStage.REGISTRATION),
    TEMPORARY(true, "Connection temporarily unavailable; retrying", FailureStage.REGISTRATION),
    SUBSCRIPTION_BUSY(true, "Previous connection is closing; retrying", FailureStage.SUBSCRIPTION),
    PROTOCOL(false, "Protocol rejected", FailureStage.PROTOCOL),
    KEY_UNAVAILABLE(false, "Device key unavailable; review identity in Settings", FailureStage.REGISTRATION),
    CONFIGURATION(false, "Check Home Assistant URL and saved credentials in Settings", FailureStage.CONFIGURATION)
}

data class ConnectionFailure(val kind: ConnectionFailureKind, val stage: FailureStage = kind.stage) {
    val retryable get() = kind.retryable
    companion object {
        fun transport(error: Throwable) = ConnectionFailure(when (error) {
            is java.net.UnknownHostException -> ConnectionFailureKind.DNS
            is javax.net.ssl.SSLException -> ConnectionFailureKind.TLS
            is java.net.SocketTimeoutException -> ConnectionFailureKind.TIMEOUT
            else -> ConnectionFailureKind.TRANSPORT
        })
        fun response(code: String?, stage: FailureStage) = ConnectionFailure(when (code) {
            "device_auth_failed" -> if (stage == FailureStage.SUBSCRIPTION) ConnectionFailureKind.TEMPORARY else ConnectionFailureKind.DEVICE_REJECTED
            "unauthorized", "invalid_auth" -> ConnectionFailureKind.DEVICE_REJECTED
            "subscription_busy" -> ConnectionFailureKind.SUBSCRIPTION_BUSY
            null, "", "invalid_format", "unknown_command" -> ConnectionFailureKind.PROTOCOL
            // HA can report unknown_error for transient server failures. Never authenticate on failure;
            // close and repeat the whole proof, rather than guessing that the identity was revoked.
            else -> ConnectionFailureKind.TEMPORARY
        }, stage)
    }
}

data class ConnectionEvent(val phase: ConnectionPhase, val failure: ConnectionFailure? = null) {
    val message: String get() = failure?.kind?.message ?: when (phase) {
        ConnectionPhase.CONNECTED -> "Connected"
        ConnectionPhase.CONNECTING -> "Connecting…"
        ConnectionPhase.RECONNECTING -> "Reconnecting"
        ConnectionPhase.DISCONNECTED -> "Disconnected"
        ConnectionPhase.AUTHENTICATION_REJECTED -> "Authentication rejected; review Settings"
    }
    companion object {
        fun failed(failure: ConnectionFailure) = ConnectionEvent(
            if (failure.kind in setOf(ConnectionFailureKind.OAUTH_REJECTED, ConnectionFailureKind.HA_REJECTED,
                ConnectionFailureKind.DEVICE_REJECTED, ConnectionFailureKind.KEY_UNAVAILABLE))
                ConnectionPhase.AUTHENTICATION_REJECTED else ConnectionPhase.DISCONNECTED, failure)
    }
}
