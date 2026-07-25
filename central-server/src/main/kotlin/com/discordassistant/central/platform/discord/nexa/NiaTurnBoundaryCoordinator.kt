package com.discordassistant.central.platform.discord.nexa

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal fun interface NiaTurnBoundaryScheduledTask {
    fun cancel()
}

internal fun interface NiaTurnBoundaryScheduler {
    @Throws(RejectedExecutionException::class)
    fun schedule(
        delayMillis: Long,
        task: () -> Unit,
    ): NiaTurnBoundaryScheduledTask
}

internal class ScheduledExecutorNiaTurnBoundaryScheduler(
    private val executor: ScheduledExecutorService,
) : NiaTurnBoundaryScheduler {
    override fun schedule(
        delayMillis: Long,
        task: () -> Unit,
    ): NiaTurnBoundaryScheduledTask {
        val future = executor.schedule(task, delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        return NiaTurnBoundaryScheduledTask { future.cancel(false) }
    }
}

internal enum class NiaTurnBoundaryAdmission {
    BYPASS,
    DEFERRED,
    FAIL_CLOSED,
}

/**
 * Owns only the pre-judge burst boundary. All social judgment and outbound safety remain in
 * [NexaParticipationEmitBridge].
 *
 * Timer tasks never call the bridge directly. They enqueue a worker onto the caller-provided channel dispatcher, and
 * both the timer and that worker validate the same epoch/generation. Cancellation is therefore best-effort only; a
 * raced task becomes a no-op.
 */
internal class NiaTurnBoundaryCoordinator(
    private val enabled: Boolean,
    private val clock: Clock,
    private val scheduler: NiaTurnBoundaryScheduler,
    private val policy: AdaptiveTurnBoundaryPolicy = AdaptiveTurnBoundaryPolicy(),
    private val maximumTrackedScopes: Int = 512,
) : AutoCloseable {
    private val lock = Any()
    private val scopes = LinkedHashMap<Long, ScopeState>()
    private var closed = false
    private var nextEpoch = 0L

    init {
        require(maximumTrackedScopes > 0) { "maximumTrackedScopes must be positive" }
    }

    fun onMessage(
        realSendAtIngress: Boolean,
        routingId: Long,
        generation: Long,
        signal: ParticipationMessageSignal,
        callbacks: Callbacks,
    ): NiaTurnBoundaryAdmission {
        if (!enabled || !realSendAtIngress) return NiaTurnBoundaryAdmission.BYPASS
        require(routingId > 0) { "routingId must be positive: $routingId" }
        require(generation > 0) { "generation must be positive: $generation" }

        val now = clock.instant()
        val scheduled =
            synchronized(lock) {
                if (closed) return NiaTurnBoundaryAdmission.FAIL_CLOSED
                if (!ensureScopeCapacity(routingId)) return NiaTurnBoundaryAdmission.FAIL_CLOSED
                val state = scopes.getOrPut(routingId) { ScopeState() }
                state.recordMessageGap(now, policy.sampleLimit)
                val previous = state.pending
                previous?.scheduledTask?.cancel()
                val pending =
                    PendingTurn(
                        epoch = newEpoch(),
                        generation = generation,
                        firstMessageAt = previous?.firstMessageAt ?: now,
                        lastMessageAt = now,
                        typingUntil = previous?.typingUntil,
                        signal = signal,
                        callbacks = callbacks,
                    )
                state.pending = pending
                ScheduledBoundary(routingId, pending, deadline(state, pending))
            }
        return if (schedule(scheduled)) NiaTurnBoundaryAdmission.DEFERRED else NiaTurnBoundaryAdmission.FAIL_CLOSED
    }

    /** Edit/delete invalidation is fail-closed: a partially derived signal is never judged after its scene changed. */
    fun cancel(routingId: Long): Boolean {
        if (routingId <= 0) return false
        val task =
            synchronized(lock) {
                val pending = scopes[routingId]?.pending ?: return false
                scopes[routingId]?.pending = null
                pending.scheduledTask
            }
        task?.cancel()
        return true
    }

    fun cancelGuild(guildId: Long): Int = cancelMatching { it.signal.guildId == guildId }

    fun cancelUser(
        guildId: Long,
        userId: Long,
    ): Int = cancelMatching { it.signal.guildId == guildId && it.signal.userId == userId }

    /**
     * Extends an existing burst only. It does not create state, record a message gap, or alter the turn generation.
     */
    fun onTyping(routingId: Long): Boolean {
        if (!enabled || routingId <= 0) return false
        val now = clock.instant()
        val scheduled =
            synchronized(lock) {
                if (closed) return false
                val state = scopes[routingId] ?: return false
                val pending = state.pending ?: return false
                val hardDeadline = policy.hardDeadline(pending.firstMessageAt)
                if (!now.isBefore(hardDeadline)) return false
                val typingUntil = policy.typingUntil(now, hardDeadline)
                val currentDeadline = deadline(state, pending)
                if (!typingUntil.isAfter(currentDeadline)) return false

                pending.scheduledTask?.cancel()
                val extended =
                    pending.copy(
                        epoch = newEpoch(),
                        typingUntil = typingUntil,
                        scheduledTask = null,
                    )
                state.pending = extended
                ScheduledBoundary(routingId, extended, deadline(state, extended))
            }
        return schedule(scheduled)
    }

    internal fun pendingDeadline(routingId: Long): Instant? =
        synchronized(lock) {
            val state = scopes[routingId] ?: return@synchronized null
            state.pending?.let { deadline(state, it) }
        }

    override fun close() {
        val tasks =
            synchronized(lock) {
                if (closed) return
                closed = true
                scopes.values.mapNotNull { it.pending?.scheduledTask }.also {
                    scopes.values.forEach { state -> state.pending = null }
                }
            }
        tasks.forEach(NiaTurnBoundaryScheduledTask::cancel)
    }

    private fun schedule(boundary: ScheduledBoundary): Boolean {
        val delayMillis = Duration.between(clock.instant(), boundary.deadline).toMillis().coerceAtLeast(0)
        val task =
            try {
                scheduler.schedule(delayMillis) {
                    onTimer(boundary.routingId, boundary.pending.epoch, boundary.pending.generation)
                }
            } catch (_: RejectedExecutionException) {
                removePending(boundary.routingId, boundary.pending.epoch, boundary.pending.generation)
                return false
            }

        synchronized(lock) {
            val current = scopes[boundary.routingId]?.pending
            if (!matches(current, boundary.pending.epoch, boundary.pending.generation) || closed) {
                task.cancel()
                return !closed
            }
            current?.scheduledTask = task
        }
        return true
    }

    private fun onTimer(
        routingId: Long,
        epoch: Long,
        generation: Long,
    ) {
        val pending = pendingIfCurrent(routingId, epoch, generation) ?: return
        if (!pending.callbacks.safeStillRealSend() || !pending.callbacks.safeIsLatest(routingId, generation)) {
            removePending(routingId, epoch, generation)
            return
        }
        val accepted =
            runCatching {
                pending.callbacks.enqueueOnDispatcher(
                    routingId,
                    Runnable { onWorkerStart(routingId, epoch, generation) },
                )
            }.getOrDefault(false)
        if (!accepted) {
            removePending(routingId, epoch, generation)
            pending.callbacks.safeOnFailClosed()
        }
    }

    private fun onWorkerStart(
        routingId: Long,
        epoch: Long,
        generation: Long,
    ) {
        val pending = pendingIfCurrent(routingId, epoch, generation) ?: return
        if (!pending.callbacks.safeStillRealSend() || !pending.callbacks.safeIsLatest(routingId, generation)) {
            removePending(routingId, epoch, generation)
            return
        }
        val claimed =
            synchronized(lock) {
                val current = scopes[routingId]?.pending
                if (!matches(current, epoch, generation)) {
                    null
                } else {
                    scopes[routingId]?.pending = null
                    current
                }
            }
                ?: return
        claimed.callbacks.judge(claimed.signal)
    }

    private fun pendingIfCurrent(
        routingId: Long,
        epoch: Long,
        generation: Long,
    ): PendingTurn? =
        synchronized(lock) {
            if (closed) return@synchronized null
            scopes[routingId]?.pending?.takeIf { matches(it, epoch, generation) }
        }

    private fun removePending(
        routingId: Long,
        epoch: Long,
        generation: Long,
    ) {
        synchronized(lock) {
            val current = scopes[routingId]?.pending
            if (matches(current, epoch, generation)) {
                current?.scheduledTask?.cancel()
                scopes[routingId]?.pending = null
            }
        }
    }

    private fun cancelMatching(predicate: (PendingTurn) -> Boolean): Int {
        val pending =
            synchronized(lock) {
                scopes.values.mapNotNull { state ->
                    state.pending?.takeIf(predicate)?.also { state.pending = null }
                }
            }
        pending.mapNotNull(PendingTurn::scheduledTask).forEach(NiaTurnBoundaryScheduledTask::cancel)
        return pending.size
    }

    private fun deadline(
        state: ScopeState,
        pending: PendingTurn,
    ): Instant =
        policy.deadline(
            firstMessageAt = pending.firstMessageAt,
            lastMessageAt = pending.lastMessageAt,
            recentGapMillis = state.recentGapMillis,
            typingUntil = pending.typingUntil,
        )

    private fun newEpoch(): Long = ++nextEpoch

    private fun ensureScopeCapacity(routingId: Long): Boolean {
        if (routingId in scopes || scopes.size < maximumTrackedScopes) return true
        val evictable = scopes.entries.firstOrNull { (_, state) -> state.pending == null } ?: return false
        scopes.remove(evictable.key)
        return true
    }

    private fun matches(
        pending: PendingTurn?,
        epoch: Long,
        generation: Long,
    ): Boolean = pending?.epoch == epoch && pending.generation == generation

    internal data class Callbacks(
        val stillRealSendEnabled: () -> Boolean,
        val isLatestGeneration: (routingId: Long, generation: Long) -> Boolean,
        val enqueueOnDispatcher: (routingId: Long, task: Runnable) -> Boolean,
        val judge: (ParticipationMessageSignal) -> Unit,
        val onFailClosed: () -> Unit = {},
    ) {
        fun safeStillRealSend(): Boolean = runCatching(stillRealSendEnabled).getOrDefault(false)

        fun safeIsLatest(
            routingId: Long,
            generation: Long,
        ): Boolean = runCatching { isLatestGeneration(routingId, generation) }.getOrDefault(false)

        fun safeOnFailClosed() {
            runCatching(onFailClosed)
        }
    }

    private class ScopeState {
        var lastMessageAt: Instant? = null
        val recentGapMillis = ArrayDeque<Long>()
        var pending: PendingTurn? = null

        fun recordMessageGap(
            now: Instant,
            sampleLimit: Int,
        ) {
            lastMessageAt?.let { previous ->
                val gap = Duration.between(previous, now).toMillis()
                if (gap >= 0) {
                    recentGapMillis.addLast(gap)
                    while (recentGapMillis.size > sampleLimit) recentGapMillis.removeFirst()
                }
            }
            lastMessageAt = now
        }
    }

    private data class PendingTurn(
        val epoch: Long,
        val generation: Long,
        val firstMessageAt: Instant,
        val lastMessageAt: Instant,
        val typingUntil: Instant?,
        val signal: ParticipationMessageSignal,
        val callbacks: Callbacks,
        var scheduledTask: NiaTurnBoundaryScheduledTask? = null,
    )

    private data class ScheduledBoundary(
        val routingId: Long,
        val pending: PendingTurn,
        val deadline: Instant,
    )
}
