package us.marreros.haandroidmediaplayer

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Deterministic transfer-engine tests: real files, virtual time, scripted chunk sources. */
class MediaTransferTest {
    private lateinit var cache: File
    private val scheduler = VirtualScheduler()
    private val statuses = mutableListOf<Triple<DeliveryPhase, DeliveryReason?, Boolean>>()
    private val completed = mutableListOf<File>()
    private lateinit var controller: TransferController
    private val audio = ByteArray(TransferOffer.CHUNK_SIZE * 5 + 1234) { (it * 31 + 7).toByte() }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun offer(id: String = "A".repeat(22), bytes: ByteArray = audio, digest: String = sha(bytes)) =
        TransferOffer(id, bytes.size, digest, TransferOffer.CHUNK_SIZE, "audio/mpeg")

    /** Records requests; the test decides when and how each is answered. */
    private inner class Source(val data: ByteArray = audio) : ChunkSource {
        val requests = mutableListOf<Pair<Int, (ChunkResult) -> Unit>>()
        val released = mutableListOf<String>()
        var accept = true
        override fun requestChunk(transferId: String, offset: Int, callback: (ChunkResult) -> Unit): Boolean {
            if (!accept) return false
            requests += offset to callback
            return true
        }
        override fun releaseTransfer(transferId: String) { released += transferId }
        fun answer(index: Int = 0, result: ChunkResult? = null) {
            val (offset, callback) = requests.removeAt(index)
            callback(result ?: ChunkResult.Data(offset, data.copyOfRange(offset, minOf(data.size, offset + TransferOffer.CHUNK_SIZE))))
            scheduler.runAll()
        }
        fun answerAll() { while (requests.isNotEmpty()) answer() }
    }

    @Before fun setUp() {
        cache = Files.createTempDirectory("transfer-test").toFile()
        controller = TransferController(cache, scheduler, { _, _, file -> completed += file },
            { _, phase, reason, resuming -> statuses += Triple(phase, reason, resuming) })
    }
    @After fun tearDown() { cache.deleteRecursively() }
    private fun transferFiles() = TransferFiles.dir(cache).listFiles()?.toList().orEmpty()

    @Test fun completesWithBoundedWindowVerifiedIntegrityAndReleasesServerCopy() {
        val source = Source()
        controller.attach(source)
        controller.start("k", offer())
        assertEquals(TransferController.WINDOW, source.requests.size)
        assertEquals(listOf(0, 49152, 98304, 147456), source.requests.map { it.first })
        source.answerAll()
        assertEquals(1, completed.size)
        assertArrayEquals(audio, completed.single().readBytes())
        assertTrue(TransferFiles.owned(cache, completed.single()))
        assertEquals(listOf("A".repeat(22)), source.released)
        assertEquals(Triple(DeliveryPhase.RETRIEVING, null, false), statuses.first())
        assertFalse(statuses.any { it.first == DeliveryPhase.FAILED })
        assertNull(controller.activeKey)
    }

    @Test fun acceptsOutOfOrderChunksAndIgnoresDuplicates() {
        val source = Source()
        controller.attach(source)
        controller.start("k", offer())
        val (firstOffset, firstCallback) = source.requests.first()
        source.answer(3); source.answer(1); source.answer(0)
        // A duplicate of an already-settled offset cannot overwrite or complete anything.
        firstCallback(ChunkResult.Data(firstOffset, ByteArray(TransferOffer.CHUNK_SIZE) { 9 })); scheduler.runAll()
        source.answerAll()
        assertArrayEquals(audio, completed.single().readBytes())
    }

    @Test fun wrongLengthOffsetOrDigestFailsIntegrityAndDeletesPartialAudio() {
        for (bad in listOf<(Source) -> Unit>(
            { it.answer(0, ChunkResult.Data(0, ByteArray(10))) },
            { it.answer(0, ChunkResult.Data(49152, ByteArray(TransferOffer.CHUNK_SIZE))) }
        )) {
            setUp()
            val source = Source(); controller.attach(source); controller.start("k", offer())
            bad(source)
            assertEquals(Triple(DeliveryPhase.FAILED, DeliveryReason.INTEGRITY, false), statuses.last())
            assertTrue(transferFiles().isEmpty())
            assertTrue(completed.isEmpty())
        }
        setUp()
        val source = Source(); controller.attach(source); controller.start("k", offer(digest = "0".repeat(64)))
        source.answerAll()
        assertEquals(DeliveryReason.INTEGRITY, statuses.last().second)
        assertTrue(transferFiles().isEmpty())
        assertTrue(completed.isEmpty())
    }

    @Test fun expiredServerTransferFailsAsMissingWithoutRetry() {
        val source = Source(); controller.attach(source); controller.start("k", offer())
        source.answer(0, ChunkResult.Unavailable)
        assertEquals(Triple(DeliveryPhase.FAILED, DeliveryReason.MISSING, false), statuses.last())
        assertTrue(source.released.isEmpty())
        val remaining = source.requests.size
        source.answerAll() // Late results for the failed transfer are ignored.
        assertTrue(completed.isEmpty())
        assertTrue(remaining > 0 && transferFiles().isEmpty())
    }

    @Test fun rateLimitedChunkIsRequestedAgainAfterDelay() {
        val source = Source(); controller.attach(source); controller.start("k", offer())
        source.answer(0, ChunkResult.Retry)
        assertEquals(3, source.requests.size)
        scheduler.advance(TransferController.RETRY_MS)
        assertEquals(4, source.requests.size)
        source.answerAll()
        assertArrayEquals(audio, completed.single().readBytes())
    }

    @Test fun reconnectDuringTransferResumesOnNewConnectionAndIgnoresOldResults() {
        val old = Source(); controller.attach(old); controller.start("k", offer())
        old.answer(0); old.answer(0)
        controller.detach()
        assertEquals(Triple(DeliveryPhase.RETRIEVING, null, true), statuses.last())
        old.answer(0, ChunkResult.Disconnected)
        old.answerAll() // Results arriving from the abandoned connection are ignored.
        assertTrue(completed.isEmpty())
        val fresh = Source(); controller.attach(fresh)
        assertEquals(Triple(DeliveryPhase.RETRIEVING, null, false), statuses.last())
        assertEquals(listOf(98304, 147456, 196608, 245760), fresh.requests.map { it.first })
        fresh.answerAll()
        assertArrayEquals(audio, completed.single().readBytes())
    }

    @Test fun startsWhileDisconnectedAndWaitsForConnection() {
        controller.start("k", offer())
        assertEquals(Triple(DeliveryPhase.RETRIEVING, null, true), statuses.last())
        val source = Source(); source.accept = false
        controller.attach(source)
        assertTrue(source.requests.isEmpty())
        source.accept = true
        controller.attach(source)
        source.answerAll()
        assertEquals(1, completed.size)
    }

    @Test fun deadlineFailsInterruptedOrTimedOutAndCleansUp() {
        controller.start("k", offer())
        scheduler.advance(TransferController.TIMEOUT_MS)
        assertEquals(Triple(DeliveryPhase.FAILED, DeliveryReason.INTERRUPTED, false), statuses.last())
        assertTrue(transferFiles().isEmpty())
        val source = Source(); controller.attach(source)
        controller.start("k2", offer("B".repeat(22)))
        source.answer(0)
        scheduler.advance(TransferController.TIMEOUT_MS)
        assertEquals(DeliveryReason.TIMEOUT, statuses.last().second)
        assertEquals(listOf("B".repeat(22)), source.released)
        assertTrue(transferFiles().isEmpty())
    }

    @Test fun newerOfferSupersedesAndCancellationCleansUp() {
        val source = Source(); controller.attach(source)
        controller.start("old", offer())
        source.answer(0)
        controller.start("new", offer("B".repeat(22)))
        assertEquals(Triple(DeliveryPhase.FAILED, DeliveryReason.SUPERSEDED, false), statuses[statuses.size - 2])
        assertEquals(listOf("B".repeat(22) + ".part"), transferFiles().map { it.name })
        controller.cancel(DeliveryReason.STOPPED)
        assertEquals(DeliveryReason.STOPPED, statuses.last().second)
        assertTrue(transferFiles().isEmpty())
        controller.cancel(DeliveryReason.STOPPED) // Idempotent.
        source.answerAll()
        assertTrue(completed.isEmpty())
    }

    @Test fun duplicateCommandsForTheSameTransferAreIgnored() {
        val source = Source(); controller.attach(source)
        controller.start("k", offer())
        assertFalse(controller.accepts(offer()))
        val requests = source.requests.size
        controller.start("k", offer())
        assertEquals(requests, source.requests.size)
        source.answerAll()
        assertFalse(controller.accepts(offer()))
        controller.start("k", offer())
        assertEquals(1, completed.size)
    }

    @Test fun offersAreStrictlyBounded() {
        val digest = "0".repeat(64)
        for (create in listOf<() -> Unit>(
            { TransferOffer("short", 10, digest, TransferOffer.CHUNK_SIZE, "audio/mpeg") },
            { TransferOffer("A".repeat(21) + "/", 10, digest, TransferOffer.CHUNK_SIZE, "audio/mpeg") },
            { TransferOffer("A".repeat(22), 0, digest, TransferOffer.CHUNK_SIZE, "audio/mpeg") },
            { TransferOffer("A".repeat(22), TransferOffer.MAX_BYTES + 1, digest, TransferOffer.CHUNK_SIZE, "audio/mpeg") },
            { TransferOffer("A".repeat(22), 10, "ABC", TransferOffer.CHUNK_SIZE, "audio/mpeg") },
            { TransferOffer("A".repeat(22), 10, digest, 1024, "audio/mpeg") },
            { TransferOffer("A".repeat(22), 10, digest, TransferOffer.CHUNK_SIZE, "text/html") },
            { TransferOffer("A".repeat(22), 10, digest, TransferOffer.CHUNK_SIZE, "audio/../../x") }
        )) assertThrows(IllegalArgumentException::class.java) { create() }
        assertEquals(65536, TransferOffer.MAX_ENCODED_CHUNK)
        assertEquals(86, TransferOffer("A".repeat(22), TransferOffer.MAX_BYTES, digest, TransferOffer.CHUNK_SIZE, "audio/mpeg").chunks)
    }

    @Test fun onlyCompletedTransferFilesInsideTheCacheDirectoryAreOwned() {
        val dir = TransferFiles.dir(cache).apply { mkdirs() }
        val good = File(dir, "A".repeat(22) + ".audio").apply { writeText("x") }
        assertTrue(TransferFiles.owned(cache, good))
        assertFalse(TransferFiles.owned(cache, null))
        assertFalse(TransferFiles.owned(cache, File(dir, "A".repeat(22) + ".part").apply { writeText("x") }))
        assertFalse(TransferFiles.owned(cache, File(dir, "missing-file-name-xxxx.audio")))
        assertFalse(TransferFiles.owned(cache, File(cache, "A".repeat(22) + ".audio").apply { writeText("x") }))
        assertFalse(TransferFiles.owned(cache, File(dir, "../" + "A".repeat(22) + ".audio")))
        val outside = Files.createTempFile("other", ".audio").toFile()
        TransferFiles.delete(cache, outside)
        assertTrue(outside.exists()); outside.delete()
        good.setLastModified(0)
        val fresh = File(dir, "B".repeat(22) + ".audio").apply { writeText("x") }
        TransferFiles.purgeStale(cache)
        assertFalse(good.exists())
        assertTrue(fresh.exists())
    }
}
