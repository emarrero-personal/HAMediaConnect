package us.marreros.haandroidmediaplayer

import java.util.concurrent.CopyOnWriteArrayList

/** Wire values match the integration's `playback_report` schema. Labels never contain URLs or tokens. */
enum class DeliveryPhase(val wire: String, val terminal: Boolean = false) {
    RECEIVED("received"), RETRIEVING("retrieving"), BUFFERING("buffering"), READY("ready"),
    PLAYING("playing"), COMPLETED("completed", true), FAILED("failed", true), SUPPRESSED("suppressed", true)
}

enum class DeliveryMethod { URL, DIRECT }

enum class DeliveryReason(val wire: String, val message: String) {
    UNREACHABLE("unreachable", "media unreachable"),
    DNS("dns", "media hostname unavailable"),
    TLS("tls", "media TLS validation failed"),
    TIMEOUT("timeout", "timed out"),
    ACCESS_DENIED("access_denied", "media access denied"),
    MISSING("missing", "media missing or expired"),
    SERVER_ERROR("server_error", "media server error"),
    REDIRECT("redirect", "media redirect refused"),
    CONTENT_TYPE("content_type", "unexpected media content type"),
    DECODE("decode", "audio could not be decoded"),
    INTEGRITY("integrity", "audio transfer integrity check failed"),
    TOO_LARGE("too_large", "audio too large"),
    INTERRUPTED("interrupted", "audio transfer interrupted"),
    SUPERSEDED("superseded", "replaced by newer audio"),
    STOPPED("stopped", "stopped"),
    DND("dnd", "Do Not Disturb"),
    UNAVAILABLE("unavailable", "audio transfer unavailable"),
    OTHER("other", "playback failed")
}

data class DeliveryStatus(
    val key: String,
    val requestId: String?,
    val method: DeliveryMethod,
    val announce: Boolean,
    val phase: DeliveryPhase = DeliveryPhase.RECEIVED,
    val reason: DeliveryReason? = null,
    val resuming: Boolean = false
) {
    fun label(): String = when (phase) {
        DeliveryPhase.RECEIVED -> if (announce) "Receiving announcement" else "Receiving media"
        DeliveryPhase.RETRIEVING -> when {
            resuming -> "Reconnecting — audio transfer will resume"
            method == DeliveryMethod.DIRECT -> "Downloading audio"
            else -> "Loading media"
        }
        DeliveryPhase.BUFFERING -> "Buffering"
        DeliveryPhase.READY -> "Media ready"
        DeliveryPhase.PLAYING -> "Playing"
        DeliveryPhase.COMPLETED -> "Completed"
        DeliveryPhase.FAILED -> "Failed — ${(reason ?: DeliveryReason.OTHER).message}"
        DeliveryPhase.SUPPRESSED -> suppressedLabel()
    }
    private fun suppressedLabel(): String = if (reason == DeliveryReason.UNAVAILABLE)
        "Suppressed — Enable background audio in HAMediaConnect"
        else "Suppressed — ${(reason ?: DeliveryReason.DND).message}"
    val methodLabel: String get() = if (method == DeliveryMethod.DIRECT) "Direct transfer · Home Assistant connection" else "Media URL"
}

/**
 * Process-wide record of the latest playback request. Receiving a command is never
 * treated as proof of playback: only Media3 transitions advance past RETRIEVING.
 */
object DeliveryTracker {
    @Volatile var latest: DeliveryStatus? = null
        private set
    private val observers = CopyOnWriteArrayList<(DeliveryStatus) -> Unit>()
    private var counter = 0L

    @Synchronized fun localKey(): String = "local-${++counter}"

    fun observe(observer: (DeliveryStatus) -> Unit) { observers.add(observer) }
    fun unobserve(observer: (DeliveryStatus) -> Unit) { observers.remove(observer) }

    /** Starts tracking a new request; a repeated key is a duplicate command and changes nothing. */
    fun begin(key: String, requestId: String?, method: DeliveryMethod, announce: Boolean): Boolean {
        val status = synchronized(this) {
            if (latest?.key == key) return false
            DeliveryStatus(key, requestId, method, announce).also { latest = it }
        }
        publish(status)
        return true
    }

    /** Ignores stale keys and transitions out of a terminal phase. */
    fun update(key: String?, phase: DeliveryPhase, reason: DeliveryReason? = null, resuming: Boolean = false): Boolean {
        val next = synchronized(this) {
            val current = latest
            if (key == null || current == null || current.key != key || current.phase.terminal) return false
            if (current.phase == phase && current.reason == reason && current.resuming == resuming) return false
            current.copy(phase = phase, reason = reason, resuming = resuming).also { latest = it }
        }
        publish(next)
        return true
    }

    private fun publish(status: DeliveryStatus) = observers.forEach { it(status) }

    @Synchronized internal fun reset() { latest = null }
}
