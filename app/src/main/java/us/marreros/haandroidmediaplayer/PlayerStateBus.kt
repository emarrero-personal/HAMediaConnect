package us.marreros.haandroidmediaplayer

object PlayerStateBus {
    @Volatile
    var listener: ((PlayerSnapshot) -> Unit)? = null
}

data class PlayerSnapshot(
    val state: String,
    val volume: Float,
    val positionMs: Long,
    val durationMs: Long,
    val title: String?,
    val contentId: String?
)
