package com.discordassistant.central.platform.discord

import org.slf4j.LoggerFactory
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal enum class DiscordChannelEventAdmission {
    ACCEPTED,
    ACCEPTED_AFTER_EVICTION,
    ACCEPTED_TO_MUTATION_OVERFLOW,
    REJECTED,
    ;

    val accepted: Boolean get() = this != REJECTED
}

/**
 * Keeps Discord message receive/edit/delete work FIFO within a channel without blocking JDA's calling thread.
 * Different channels can progress on different stripes. Ordinary message admission is deliberately smaller than the
 * physical queue so raw-context receive/edit/delete mutations retain capacity. The defaults retain a complete 100-message
 * Discord burst while remaining bounded. When that queue is completely full, a mutation may evict one
 * pending ordinary event; if it contains only mutations, the mutation enters a per-stripe overflow instead of being
 * rejected. The worker drains that overflow after every already-admitted event, preserving channel order and ensuring
 * a raw-context redaction is not lost under load.
 */
internal class DiscordChannelEventDispatcher(
    stripeCount: Int = DEFAULT_STRIPE_COUNT,
    queueCapacityPerStripe: Int = DEFAULT_QUEUE_CAPACITY,
    private val ordinaryQueueCapacityPerStripe: Int = DEFAULT_ORDINARY_QUEUE_CAPACITY,
    private val closeGraceMillis: Long = CLOSE_GRACE_MILLIS,
    private val forcedCloseGraceMillis: Long = FORCED_CLOSE_GRACE_MILLIS,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val threadSequence = AtomicInteger()
    private val stripes: List<Stripe>

    init {
        require(stripeCount > 0) { "stripeCount must be positive: $stripeCount" }
        require(queueCapacityPerStripe > 0) { "queueCapacityPerStripe must be positive: $queueCapacityPerStripe" }
        require(ordinaryQueueCapacityPerStripe in 1..queueCapacityPerStripe) {
            "ordinaryQueueCapacityPerStripe must be within 1..$queueCapacityPerStripe: $ordinaryQueueCapacityPerStripe"
        }
        require(closeGraceMillis >= 0) { "closeGraceMillis must be non-negative: $closeGraceMillis" }
        require(forcedCloseGraceMillis > 0) { "forcedCloseGraceMillis must be positive: $forcedCloseGraceMillis" }
        stripes =
            List(stripeCount) { stripe ->
                Stripe(
                    ThreadPoolExecutor(
                        1,
                        1,
                        0L,
                        TimeUnit.MILLISECONDS,
                        ArrayBlockingQueue(queueCapacityPerStripe),
                        { runnable ->
                            Thread(runnable, "discord-channel-$stripe-${threadSequence.incrementAndGet()}").also {
                                it.isDaemon = true
                            }
                        },
                        ThreadPoolExecutor.AbortPolicy(),
                    ),
                )
            }
    }

    fun submit(
        channelId: Long,
        task: Runnable,
    ): DiscordChannelEventAdmission = submit(channelId, mutation = false, task)

    fun submitMutation(
        channelId: Long,
        task: Runnable,
    ): DiscordChannelEventAdmission = submit(channelId, mutation = true, task)

    private fun submit(
        channelId: Long,
        mutation: Boolean,
        task: Runnable,
    ): DiscordChannelEventAdmission {
        if (closed.get()) return DiscordChannelEventAdmission.REJECTED
        val stripe = stripes[stripeIndex(channelId)]
        val executor = stripe.executor
        val dispatchTask = DispatchTask(mutation, task) { drainMutationOverflow(stripe) }
        synchronized(stripe) {
            if (closed.get() || executor.isShutdown) return DiscordChannelEventAdmission.REJECTED
            if (stripe.drainingMutationOverflow || stripe.mutationOverflow.isNotEmpty()) {
                if (!mutation) return DiscordChannelEventAdmission.REJECTED
                stripe.mutationOverflow.addLast(dispatchTask)
                return DiscordChannelEventAdmission.ACCEPTED_TO_MUTATION_OVERFLOW
            }
            if (!mutation && executor.queue.count { it is DispatchTask && !it.mutation } >= ordinaryQueueCapacityPerStripe) {
                return DiscordChannelEventAdmission.REJECTED
            }
            if (execute(executor, dispatchTask)) return DiscordChannelEventAdmission.ACCEPTED
            if (!mutation) return DiscordChannelEventAdmission.REJECTED

            val evicted = executor.queue.firstOrNull { it is DispatchTask && !it.mutation }
            if (evicted != null && executor.queue.remove(evicted) && execute(executor, dispatchTask)) {
                return DiscordChannelEventAdmission.ACCEPTED_AFTER_EVICTION
            }

            // A worker can dequeue the selected ordinary task between firstOrNull and remove. In that case capacity
            // may already be available, so retry once before putting a privacy mutation into its ordered in-memory
            // overflow.
            return if (execute(executor, dispatchTask)) {
                DiscordChannelEventAdmission.ACCEPTED_AFTER_EVICTION
            } else {
                stripe.mutationOverflow.addLast(dispatchTask)
                DiscordChannelEventAdmission.ACCEPTED_TO_MUTATION_OVERFLOW
            }
        }
    }

    /**
     * Runs only on the stripe worker after its physical queue is empty. Later ordinary events are rejected while the
     * overflow is draining, so a delete/edit cannot be overtaken by a new receive event from the same channel.
     */
    private fun drainMutationOverflow(stripe: Stripe) {
        synchronized(stripe) {
            if (
                stripe.forceStopping ||
                stripe.drainingMutationOverflow ||
                stripe.executor.queue.isNotEmpty() ||
                stripe.mutationOverflow.isEmpty()
            ) {
                return
            }
            stripe.drainingMutationOverflow = true
        }
        while (true) {
            val next =
                synchronized(stripe) {
                    when {
                        stripe.executor.queue.isNotEmpty() -> {
                            stripe.drainingMutationOverflow = false
                            null
                        }
                        stripe.mutationOverflow.isEmpty() -> {
                            stripe.drainingMutationOverflow = false
                            null
                        }
                        else -> stripe.mutationOverflow.removeFirst()
                    }
                }
                    ?: return
            next.runDelegate()
        }
    }

    private fun execute(
        executor: ThreadPoolExecutor,
        task: DispatchTask,
    ): Boolean =
        try {
            executor.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            false
        }

    private fun stripeIndex(channelId: Long): Int {
        val hash = (channelId xor (channelId ushr 32)).toInt()
        return Math.floorMod(hash, stripes.size)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var droppedOrdinary = 0
        stripes.forEach { stripe ->
            synchronized(stripe) {
                // During shutdown, do not start new model judgments. Preserve queued mutations so redaction/edit
                // events can catch up after at most the currently running task.
                droppedOrdinary +=
                    stripe.executor.queue
                        .filterIsInstance<DispatchTask>()
                        .filter { !it.mutation }
                        .count { stripe.executor.queue.remove(it) }
                stripe.executor.shutdown()
            }
        }

        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(closeGraceMillis)
        var interrupted = false
        val forcedMutationTasks = mutableMapOf<Stripe, List<DispatchTask>>()
        stripes.forEach { stripe ->
            val remainingNanos = (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)
            try {
                if (!stripe.executor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS)) {
                    forcedMutationTasks[stripe] = forceStop(stripe)
                }
            } catch (_: InterruptedException) {
                interrupted = true
                forcedMutationTasks[stripe] = forceStop(stripe)
            }
        }

        var drainedMutationsInline = 0
        forcedMutationTasks.forEach { (stripe, queuedMutations) ->
            interrupted = awaitForcedTermination(stripe.executor) || interrupted
            val overflowMutations =
                synchronized(stripe) {
                    stripe.drainingMutationOverflow = false
                    buildList {
                        addAll(queuedMutations)
                        while (stripe.mutationOverflow.isNotEmpty()) add(stripe.mutationOverflow.removeFirst())
                    }
                }
            overflowMutations.forEach { task ->
                task.runDelegate()
                drainedMutationsInline++
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        if (droppedOrdinary > 0 || drainedMutationsInline > 0) {
            LOG.warn(
                "Discord channel dispatcher stopped with {} queued ordinary events discarded; {} queued mutation events drained inline",
                droppedOrdinary,
                drainedMutationsInline,
            )
        }
    }

    /**
     * Prevent the running worker from draining newer overflow mutations before [shutdownNow] returns older queued
     * mutations. After termination, close drains both lists in their original order.
     */
    private fun forceStop(stripe: Stripe): List<DispatchTask> =
        synchronized(stripe) {
            stripe.forceStopping = true
            stripe.executor
                .shutdownNow()
                .filterIsInstance<DispatchTask>()
                .filter { it.mutation }
        }

    /** Privacy mutations are never reordered or discarded just to make application shutdown faster. */
    private fun awaitForcedTermination(executor: ThreadPoolExecutor): Boolean {
        var interrupted = false
        while (!executor.isTerminated) {
            try {
                if (!executor.awaitTermination(forcedCloseGraceMillis, TimeUnit.MILLISECONDS)) {
                    LOG.error("Discord channel dispatcher is still stopping; waiting to preserve queued raw-context mutations")
                }
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        return interrupted
    }

    private class Stripe(
        val executor: ThreadPoolExecutor,
        val mutationOverflow: ArrayDeque<DispatchTask> = ArrayDeque(),
        var drainingMutationOverflow: Boolean = false,
        var forceStopping: Boolean = false,
    )

    private class DispatchTask(
        val mutation: Boolean,
        private val delegate: Runnable,
        private val afterRun: () -> Unit,
    ) : Runnable {
        override fun run() {
            runDelegate()
            afterRun()
        }

        fun runDelegate() {
            try {
                delegate.run()
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                LOG.warn("Discord channel event task failed(mutation={})", mutation, e)
            }
        }
    }

    companion object {
        private const val DEFAULT_STRIPE_COUNT = 8
        private const val DEFAULT_QUEUE_CAPACITY = 256
        private const val DEFAULT_ORDINARY_QUEUE_CAPACITY = 128
        private const val CLOSE_GRACE_MILLIS = 20_000L
        private const val FORCED_CLOSE_GRACE_MILLIS = 2_000L
        private val LOG = LoggerFactory.getLogger(DiscordChannelEventDispatcher::class.java)
    }
}
