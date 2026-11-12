package us.marreros.haandroidmediaplayer

/** Process-local user activation; never persist this across boot or service recreation. */
object BackgroundAudio {
    const val NOTICE = "Tap Enable audio in the connection notification, or Enable background audio " +
        "in HAMediaConnect, to receive announcements while the screen is locked."
    const val PREF_CHANGED = "background_audio_changed"
    private const val ANDROID_17 = 37
    @Volatile private var activated = false

    fun allowed(sdk: Int): Boolean = sdk < ANDROID_17 || activated
    fun startedByUser() { activated = true }
    fun stopped() { activated = false }
}
