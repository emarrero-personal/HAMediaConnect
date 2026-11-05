package us.marreros.haandroidmediaplayer

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Single-threaded, virtual-time executor: tasks run only when the test calls [runAll] or [advance]. */
class VirtualScheduler : AbstractExecutorService(), ScheduledExecutorService {
    var now = 0L
        private set
    private var sequence = 0L
    private inner class Task(val at: Long, val body: Runnable) : ScheduledFuture<Any?> {
        val order = sequence++
        var cancelled = false
        var done = false
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean { val was = !done && !cancelled; cancelled = true; return was }
        override fun isCancelled() = cancelled
        override fun isDone() = done || cancelled
        override fun get(): Any? = null
        override fun get(timeout: Long, unit: TimeUnit): Any? = null
        override fun getDelay(unit: TimeUnit) = unit.convert(at - now, TimeUnit.MILLISECONDS)
        override fun compareTo(other: Delayed) = compareValues(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS))
    }
    private val tasks = mutableListOf<Task>()

    override fun execute(command: Runnable) { tasks += Task(now, command) }
    override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
        Task(now + unit.toMillis(delay), command).also { tasks += it }
    override fun <V : Any?> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> = throw UnsupportedOperationException()
    override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit) = throw UnsupportedOperationException()
    override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit) = throw UnsupportedOperationException()

    /** Runs every task due at the current virtual time, including tasks they enqueue. */
    fun runAll() {
        while (true) {
            val next = tasks.filter { !it.cancelled && it.at <= now }.minByOrNull { it.at * 1_000_000 + it.order } ?: return
            tasks.remove(next)
            next.done = true
            next.body.run()
        }
    }
    fun advance(ms: Long) {
        val target = now + ms
        while (true) {
            runAll()
            val next = tasks.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
            now = next.at
        }
        now = target
        runAll()
    }
    val pending: Int get() = tasks.count { !it.cancelled }

    override fun shutdown() {}
    override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
    override fun isShutdown() = false
    override fun isTerminated() = false
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}
