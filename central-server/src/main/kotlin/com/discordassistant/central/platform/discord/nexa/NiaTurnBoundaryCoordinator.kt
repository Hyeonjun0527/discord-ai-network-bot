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
 * Judge 호출 전 연속 메시지 경계만 소유한다. 사회적 판단과 발화 안전은 [NexaParticipationEmitBridge]에 남긴다.
 *
 * 타이머는 브리지를 직접 호출하지 않고 채널 dispatcher에 작업을 넣는다. 타이머와 작업은 같은 epoch·generation을
 * 확인하므로 취소와 경합한 작업도 실제 판단으로 이어지지 않는다.
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

    /** 새 사람 메시지가 FIFO 처리되기 전에 기존 타이머가 먼저 판단하지 못하게 일시 정지한다. */
    fun onMessageIngress(
        routingId: Long,
        generation: Long,
    ): Boolean {
        if (!enabled || routingId <= 0 || generation <= 0) return false
        return synchronized(lock) {
            if (closed) return@synchronized false
            val state = scopes[routingId] ?: return@synchronized false
            if (generation <= state.latestGeneration) return@synchronized false
            val pending = state.pending ?: return@synchronized false
            if (pending.ingressSuspended) return@synchronized true

            pending.scheduledTask?.cancel()
            state.pending =
                pending.copy(
                    epoch = newEpoch(),
                    ingressSuspended = true,
                    scheduledTask = null,
                )
            true
        }
    }

    fun onMessage(
        realSendAtIngress: Boolean,
        routingId: Long,
        generation: Long,
        signal: ParticipationMessageSignal,
        callbacks: Callbacks,
    ): NiaTurnBoundaryAdmission {
        if (!enabled) return NiaTurnBoundaryAdmission.BYPASS
        if (!realSendAtIngress) {
            cancel(routingId)
            return NiaTurnBoundaryAdmission.BYPASS
        }
        require(routingId > 0) { "routingId must be positive: $routingId" }
        require(generation > 0) { "generation must be positive: $generation" }

        val now = clock.instant()
        val messageAt = messageTime(signal, now)
        val scheduled =
            synchronized(lock) {
                if (closed) return NiaTurnBoundaryAdmission.FAIL_CLOSED
                if (!ensureScopeCapacity(routingId)) return NiaTurnBoundaryAdmission.FAIL_CLOSED
                val state = scopes.getOrPut(routingId) { ScopeState() }
                if (generation <= state.latestGeneration) return@synchronized null
                state.latestGeneration = generation
                val effectiveMessageAt =
                    state.recordMessageGap(messageAt, policy.sampleLimit, policy.sampleHorizon)
                val previous = state.pending
                previous?.scheduledTask?.cancel()
                val pending = mergePending(state, previous, generation, signal, callbacks, effectiveMessageAt)
                state.pending = pending
                ScheduledBoundary(routingId, pending, pending.deadline)
            }
                ?: return NiaTurnBoundaryAdmission.DEFERRED
        return if (schedule(scheduled)) NiaTurnBoundaryAdmission.DEFERRED else NiaTurnBoundaryAdmission.FAIL_CLOSED
    }

    /** 편집·삭제로 장면이 바뀌면 일부만 갱신된 신호를 판단하지 않고 닫는다. */
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

    /** 현재 판단 대상 작성자의 기존 묶음만 연장한다. */
    fun onTyping(
        routingId: Long,
        userId: Long,
    ): Boolean {
        if (!enabled || routingId <= 0 || userId <= 0) return false
        val now = clock.instant()
        val scheduled =
            synchronized(lock) {
                if (closed) return false
                val state = scopes[routingId] ?: return false
                val pending = state.pending ?: return false
                if (pending.ingressSuspended) return false
                if (pending.signal.userId != userId) return false
                val hardDeadline = policy.hardDeadline(pending.firstMessageAt)
                if (!now.isBefore(hardDeadline)) return false
                val typingUntil = policy.typingUntil(now, hardDeadline)
                if (!typingUntil.isAfter(pending.deadline)) return false

                pending.scheduledTask?.cancel()
                val extended =
                    pending.copy(
                        epoch = newEpoch(),
                        typingUntil = typingUntil,
                        deadline = typingUntil,
                        scheduledTask = null,
                    )
                state.pending = extended
                ScheduledBoundary(routingId, extended, extended.deadline)
            }
        return schedule(scheduled)
    }

    internal fun pendingDeadline(routingId: Long): Instant? =
        synchronized(lock) {
            scopes[routingId]?.pending?.deadline
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

    private fun mergePending(
        state: ScopeState,
        previous: PendingTurn?,
        generation: Long,
        incoming: ParticipationMessageSignal,
        callbacks: Callbacks,
        now: Instant,
    ): PendingTurn {
        if (previous == null) {
            return newPending(
                state = state,
                generation = generation,
                signal = incoming,
                callbacks = callbacks,
                firstMessageAt = now,
                lastMessageAt = now,
                typingUntil = null,
                now = now,
            )
        }

        val replacesTarget = shouldReplaceTarget(previous.signal, incoming)
        if (!replacesTarget) {
            return previous.copy(
                epoch = newEpoch(),
                generation = generation,
                signal = previous.signal.copy(turnGeneration = generation),
                ingressSuspended = false,
                scheduledTask = null,
            )
        }

        val sameAuthor = previous.signal.userId == incoming.userId
        return newPending(
            state = state,
            generation = generation,
            signal = inheritSameAuthorAddressing(previous.signal, incoming),
            callbacks = callbacks,
            firstMessageAt = previous.firstMessageAt,
            lastMessageAt = now,
            typingUntil = previous.typingUntil.takeIf { sameAuthor },
            now = now,
        )
    }

    private fun newPending(
        state: ScopeState,
        generation: Long,
        signal: ParticipationMessageSignal,
        callbacks: Callbacks,
        firstMessageAt: Instant,
        lastMessageAt: Instant,
        typingUntil: Instant?,
        now: Instant,
    ): PendingTurn {
        val targetSignal =
            signal.copy(turnGeneration = generation)
        val deadline =
            policy.deadline(
                firstMessageAt = firstMessageAt,
                lastMessageAt = lastMessageAt,
                recentGapMillis = state.recentGapMillis(now, policy.sampleHorizon),
                typingUntil = typingUntil,
            )
        return PendingTurn(
            epoch = newEpoch(),
            generation = generation,
            firstMessageAt = firstMessageAt,
            lastMessageAt = lastMessageAt,
            typingUntil = typingUntil,
            deadline = deadline,
            signal = targetSignal,
            callbacks = callbacks,
            ingressSuspended = false,
        )
    }

    private fun shouldReplaceTarget(
        current: ParticipationMessageSignal,
        incoming: ParticipationMessageSignal,
    ): Boolean {
        if (current.explicitlyAddressed && incoming.replyToHuman && !incoming.explicitlyAddressed) return false
        return incoming.userId == current.userId ||
            incoming.explicitlyAddressed ||
            !current.explicitlyAddressed
    }

    private fun inheritSameAuthorAddressing(
        current: ParticipationMessageSignal?,
        incoming: ParticipationMessageSignal,
    ): ParticipationMessageSignal {
        if (current == null || current.userId != incoming.userId) return incoming
        return incoming.copy(
            mentioned = current.mentioned || incoming.mentioned,
            replyToNia = current.replyToNia || incoming.replyToNia,
            conversationMentionsNia = current.conversationMentionsNia || incoming.conversationMentionsNia,
            nicknameCall = current.nicknameCall || incoming.nicknameCall,
            directAddressPressure = maxOf(current.directAddressPressure, incoming.directAddressPressure),
            speechImageInput = incoming.speechImageInput ?: current.speechImageInput,
        )
    }

    private fun messageTime(
        signal: ParticipationMessageSignal,
        now: Instant,
    ): Instant {
        if (signal.tsMs <= 0) return now
        val observedAt = Instant.ofEpochMilli(signal.tsMs)
        return if (observedAt.isAfter(now)) now else observedAt
    }

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
        var latestGeneration: Long = 0
        var lastMessageAt: Instant? = null
        private val recentGaps = ArrayDeque<ObservedGap>()
        var pending: PendingTurn? = null

        fun recordMessageGap(
            now: Instant,
            sampleLimit: Int,
            sampleHorizon: Duration,
        ): Instant {
            prune(now, sampleHorizon)
            val previous = lastMessageAt
            if (previous != null) {
                if (now.isBefore(previous)) return previous
                val gap = Duration.between(previous, now).toMillis()
                if (gap >= sampleHorizon.toMillis()) {
                    recentGaps.clear()
                } else {
                    recentGaps.addLast(ObservedGap(now, gap))
                    while (recentGaps.size > sampleLimit) recentGaps.removeFirst()
                }
            }
            lastMessageAt = now
            return now
        }

        fun recentGapMillis(
            now: Instant,
            sampleHorizon: Duration,
        ): List<Long> {
            prune(now, sampleHorizon)
            return recentGaps.map(ObservedGap::millis)
        }

        private fun prune(
            now: Instant,
            sampleHorizon: Duration,
        ) {
            val cutoff = now.minus(sampleHorizon)
            while (recentGaps.firstOrNull()?.observedAt?.isBefore(cutoff) == true) {
                recentGaps.removeFirst()
            }
        }
    }

    private data class PendingTurn(
        val epoch: Long,
        val generation: Long,
        val firstMessageAt: Instant,
        val lastMessageAt: Instant,
        val typingUntil: Instant?,
        val deadline: Instant,
        val signal: ParticipationMessageSignal,
        val callbacks: Callbacks,
        val ingressSuspended: Boolean,
        var scheduledTask: NiaTurnBoundaryScheduledTask? = null,
    )

    private data class ObservedGap(
        val observedAt: Instant,
        val millis: Long,
    )

    private data class ScheduledBoundary(
        val routingId: Long,
        val pending: PendingTurn,
        val deadline: Instant,
    )
}

private val ParticipationMessageSignal.explicitlyAddressed: Boolean
    get() = mentioned || replyToNia || speechImageInput != null
