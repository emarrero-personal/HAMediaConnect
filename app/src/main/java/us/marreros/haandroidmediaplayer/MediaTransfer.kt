package us.marreros.haandroidmediaplayer

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One in-band audio offer from the integration; validated before anything is allocated. */
data class TransferOffer(val transferId: String, val size: Int, val sha256: String, val chunkSize: Int, val mime: String) {
    companion object {
        const val CHUNK_SIZE = 48 * 1024
        const val MAX_BYTES = 4 * 1024 * 1024
        /** Base64 of one full chunk; chunk results may exceed the general 16 KiB message bound only by this. */
        const val MAX_ENCODED_CHUNK = CHUNK_SIZE / 3 * 4
        val ID = Regex("[A-Za-z0-9_-]{22}")
        private val HEX = Regex("[a-f0-9]{64}")
        private val AUDIO = Regex("audio/[a-z0-9.+-]{1,64}")
    }
    init {
        require(transferId.matches(ID) && size in 1..MAX_BYTES && sha256.matches(HEX) &&
            chunkSize == CHUNK_SIZE && mime.matches(AUDIO))
    }
    val chunks: Int get() = (size + chunkSize - 1) / chunkSize
    fun offset(index: Int) = index * chunkSize
    fun length(index: Int) = minOf(chunkSize, size - offset(index))
}

sealed interface ChunkResult {
    class Data(val offset: Int, val bytes: ByteArray) : ChunkResult
    /** Unknown, expired or superseded on the server: the audio is gone. */
    object Unavailable : ChunkResult
    /** Rate-limited; request again after a short delay. */
    object Retry : ChunkResult
    /** The connection carrying the request ended; re-request on the next connection. */
    object Disconnected : ChunkResult
}

/** Authenticated device connection able to serve chunks for this device's own transfers. */
interface ChunkSource {
    fun requestChunk(transferId: String, offset: Int, callback: (ChunkResult) -> Unit): Boolean
    fun releaseTransfer(transferId: String) {}
}

/** Temporary audio lives only in the app cache, under unguessable names, and is deleted after use. */
object TransferFiles {
    private val NAME = Regex("[A-Za-z0-9_-]{22}\\.(audio|part)")
    private const val STALE_MS = 10 * 60 * 1000L
    fun dir(cache: File): File = File(cache, "transfers")
    fun owned(cache: File, file: File?): Boolean = try {
        file != null && file.name.endsWith(".audio") && NAME.matches(file.name) && file.isFile &&
            file.canonicalFile.parentFile == dir(cache).canonicalFile
    } catch (_: java.io.IOException) { false }
    /** Removes audio orphaned by process death without touching a file that may be playing now. */
    fun purgeStale(cache: File, now: Long = System.currentTimeMillis()) {
        dir(cache).listFiles()?.forEach { if (now - it.lastModified() > STALE_MS) it.delete() }
    }
    fun delete(cache: File, file: File?) { if (file != null && owned(cache, file)) file.delete() }
}

/**
 * Pulls one transfer at a time over whichever authenticated connection is attached,
 * writing chunks at their validated offsets. All methods run on [executor].
 */
class TransferController(
    private val cache: File,
    private val executor: ScheduledExecutorService,
    private val onComplete: (key: String, offer: TransferOffer, file: File) -> Unit,
    private val onStatus: (key: String, phase: DeliveryPhase, reason: DeliveryReason?, resuming: Boolean) -> Unit,
    private val timeoutMs: Long = TIMEOUT_MS
) {
    companion object {
        const val WINDOW = 4
        const val TIMEOUT_MS = 90_000L
        const val RETRY_MS = 1_000L
    }
    private inner class Active(val key: String, val offer: TransferOffer, val part: File, val output: RandomAccessFile) {
        val pending = java.util.TreeSet((0 until offer.chunks).toList())
        val inFlight = mutableSetOf<Int>()
        var deadline: ScheduledFuture<*>? = null
        var retry: ScheduledFuture<*>? = null
    }
    private var active: Active? = null
    private var source: ChunkSource? = null
    private var generation = 0L
    private val recent = ArrayDeque<String>()

    val activeKey: String? get() = active?.key

    /** False for a repeated command naming a transfer that is active or already handled. */
    fun accepts(offer: TransferOffer) = active?.offer?.transferId != offer.transferId && offer.transferId !in recent

    fun start(key: String, offer: TransferOffer) {
        if (!accepts(offer)) return
        active?.let { fail(it, DeliveryReason.SUPERSEDED) }
        remember(offer.transferId)
        val dir = TransferFiles.dir(cache)
        if (!dir.isDirectory && !dir.mkdirs()) return onStatus(key, DeliveryPhase.FAILED, DeliveryReason.OTHER, false)
        if (dir.usableSpace < offer.size.toLong() * 2) return onStatus(key, DeliveryPhase.FAILED, DeliveryReason.TOO_LARGE, false)
        val part = File(dir, "${offer.transferId}.part")
        val output = try { RandomAccessFile(part, "rw").apply { setLength(offer.size.toLong()) } } catch (_: java.io.IOException) {
            part.delete(); return onStatus(key, DeliveryPhase.FAILED, DeliveryReason.OTHER, false)
        }
        val transfer = Active(key, offer, part, output)
        active = transfer
        transfer.deadline = executor.schedule({ if (active === transfer) {
            fail(transfer, if (source == null) DeliveryReason.INTERRUPTED else DeliveryReason.TIMEOUT)
        } }, timeoutMs, TimeUnit.MILLISECONDS)
        onStatus(key, DeliveryPhase.RETRIEVING, null, source == null)
        pump()
    }

    fun attach(next: ChunkSource) {
        source = next
        generation++
        active?.let { it.inFlight.clear(); onStatus(it.key, DeliveryPhase.RETRIEVING, null, false) }
        pump()
    }

    fun detach() {
        source = null
        generation++
        active?.let { it.inFlight.clear(); onStatus(it.key, DeliveryPhase.RETRIEVING, null, true) }
    }

    fun cancel(reason: DeliveryReason) { active?.let { fail(it, reason) } }

    private fun remember(id: String) {
        recent.addLast(id)
        while (recent.size > 16) recent.removeFirst()
    }

    private fun pump() {
        val transfer = active ?: return
        val current = source ?: return
        val requestGeneration = generation
        for (index in transfer.pending) {
            if (transfer.inFlight.size >= WINDOW) break
            if (index in transfer.inFlight) continue
            transfer.inFlight += index
            val sent = current.requestChunk(transfer.offer.transferId, transfer.offer.offset(index)) { result ->
                executor.execute { if (active === transfer && generation == requestGeneration) handle(transfer, index, result) }
            }
            if (!sent) { transfer.inFlight -= index; break } // Not ready; resumes on the next attach.
        }
    }

    private fun handle(transfer: Active, index: Int, result: ChunkResult) {
        if (index !in transfer.inFlight) return // Duplicate or stale result; the offset is already settled.
        transfer.inFlight -= index
        when (result) {
            is ChunkResult.Data -> {
                if (result.offset != transfer.offer.offset(index) || result.bytes.size != transfer.offer.length(index))
                    return fail(transfer, DeliveryReason.INTEGRITY)
                try {
                    transfer.output.seek(result.offset.toLong())
                    transfer.output.write(result.bytes)
                } catch (_: java.io.IOException) { return fail(transfer, DeliveryReason.OTHER) }
                transfer.pending -= index
                if (transfer.pending.isEmpty()) finish(transfer) else pump()
            }
            ChunkResult.Unavailable -> fail(transfer, DeliveryReason.MISSING)
            ChunkResult.Disconnected -> Unit // A detach/attach pair re-requests it.
            ChunkResult.Retry -> if (transfer.retry == null) {
                transfer.retry = executor.schedule({ transfer.retry = null; if (active === transfer) pump() }, RETRY_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun finish(transfer: Active) {
        val digest = MessageDigest.getInstance("SHA-256")
        val verified = try {
            transfer.output.seek(0)
            val buffer = ByteArray(16 * 1024)
            while (true) { val read = transfer.output.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
            transfer.output.length() == transfer.offer.size.toLong() &&
                MessageDigest.isEqual(digest.digest(), transfer.offer.sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        } catch (_: java.io.IOException) { false }
        if (!verified) return fail(transfer, DeliveryReason.INTEGRITY)
        close(transfer)
        val audio = File(transfer.part.parentFile, "${transfer.offer.transferId}.audio")
        if (!transfer.part.renameTo(audio)) { transfer.part.delete(); return onStatus(transfer.key, DeliveryPhase.FAILED, DeliveryReason.OTHER, false) }
        source?.releaseTransfer(transfer.offer.transferId)
        onComplete(transfer.key, transfer.offer, audio)
    }

    private fun fail(transfer: Active, reason: DeliveryReason) {
        close(transfer)
        transfer.part.delete()
        if (reason != DeliveryReason.MISSING) source?.releaseTransfer(transfer.offer.transferId)
        onStatus(transfer.key, DeliveryPhase.FAILED, reason, false)
    }

    private fun close(transfer: Active) {
        if (active === transfer) active = null
        transfer.deadline?.cancel(false)
        transfer.retry?.cancel(false)
        try { transfer.output.close() } catch (_: java.io.IOException) {}
    }
}
