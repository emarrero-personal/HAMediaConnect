package us.marreros.haandroidmediaplayer

/** One process-wide boundary for credential readers, writers and key reset across store instances. */
object CredentialTransactions {
    private val lock = Any()
    private var generation = 0L
    fun <T> read(block: () -> T): T = synchronized(lock) { block() }
    fun <T> change(block: () -> T): T = synchronized(lock) {
        generation++ // Invalidate in-flight refreshes even when a write fails.
        block()
    }
    fun version(): Long = read { generation }
    fun commit(expected: Long, valid: () -> Boolean, write: () -> Unit): Boolean = read {
        if (generation != expected || !valid()) false
        else { change(write); true }
    }
}
