package us.marreros.haandroidmediaplayer

data class HaCommand(
    val command: String,
    val url: String? = null,
    val title: String? = null,
    val contentType: String? = null,
    val volume: Float? = null,
    val position: Long? = null,
    val muted: Boolean? = null,
    val announce: Boolean = false
)
