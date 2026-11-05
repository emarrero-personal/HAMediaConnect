package us.marreros.haandroidmediaplayer

object PlayerStateBus {
    @Volatile var latest: PlayerSnapshot? = null
        private set
    @Volatile var observer: ((PlayerSnapshot) -> Unit)? = null
    fun publish(snapshot: PlayerSnapshot) {
        latest = snapshot
        listener?.invoke(snapshot)
        observer?.invoke(snapshot)
    }
    @Volatile
    var listener: ((PlayerSnapshot) -> Unit)? = null
}

data class PlayerSnapshot(
    val state: String,
    val volume: Float,
    val muted: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val title: String?,
    val contentId: String?,
    val error: String? = null,
    val notice: String? = null,
    /** Playing a completed in-band transfer rather than a media URL. */
    val direct: Boolean = false
)
