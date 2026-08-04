package com.discordassistant.central.participation.application.catchup

import com.discordassistant.central.participation.application.port.out.NiaCatchUpStateStorePort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class NiaCatchUpCadenceTest {
    private val start = Instant.parse("2026-08-02T00:00:00Z")
    private val scope = NiaCatchUpScope(guildId = 10, channelId = 20)

    @Test
    fun `연속 IGNORE가 기준에 도달하면 CATCH_UP으로 전환하고 일반 메시지는 누적한다`() {
        val clock = MutableClock(start)
        val states = InMemoryStates()
        val cadence = cadence(states, clock, threshold = 3)

        cadence.recordEvaluation(message(1), NiaCatchUpJudgeResult.IGNORE)
        cadence.recordEvaluation(message(2), NiaCatchUpJudgeResult.IGNORE)
        cadence.recordEvaluation(message(3), NiaCatchUpJudgeResult.IGNORE)

        val entered = checkNotNull(states.state(scope))
        assertThat(entered.mode).isEqualTo(NiaJudgeCadenceMode.CATCH_UP)
        assertThat(entered.consecutiveIgnoreCount).isEqualTo(3)
        assertThat(entered.lastJudgedMessageId).isEqualTo(3L)
        val scheduledAt = entered.nextCatchUpAt

        assertThat(cadence.admit(message(4))).isEqualTo(NiaCatchUpAdmission.DEFERRED)
        assertThat(states.state(scope)?.latestMessage?.messageId).isEqualTo(4L)
        assertThat(states.state(scope)?.nextCatchUpAt).isEqualTo(scheduledAt)
    }

    @Test
    fun `CATCH_UP 중 멘션이나 니아 답글은 즉시 ACTIVE로 깨운다`() {
        val clock = MutableClock(start)
        val states = InMemoryStates()
        val cadence = cadence(states, clock, threshold = 2)
        cadence.recordEvaluation(message(1), NiaCatchUpJudgeResult.IGNORE)
        cadence.recordEvaluation(message(2), NiaCatchUpJudgeResult.IGNORE)

        assertThat(cadence.admit(message(3, mentioned = true))).isEqualTo(NiaCatchUpAdmission.WAKE_NOW)
        val woken = checkNotNull(states.state(scope))
        assertThat(woken.mode).isEqualTo(NiaJudgeCadenceMode.ACTIVE)
        assertThat(woken.consecutiveIgnoreCount).isZero()
        assertThat(woken.nextCatchUpAt).isNull()
        assertThat(woken.latestMessage?.messageId).isEqualTo(3L)
    }

    @Test
    fun `CATCH_UP 중 니아 답글도 debounce 없이 즉시 ACTIVE로 깬다`() {
        val clock = MutableClock(start)
        val states = InMemoryStates()
        val cadence = cadence(states, clock, threshold = 1)
        cadence.recordEvaluation(message(1), NiaCatchUpJudgeResult.IGNORE)

        val reply = message(2).copy(replyToNia = true)

        assertThat(cadence.admit(reply)).isEqualTo(NiaCatchUpAdmission.WAKE_NOW)
        assertThat(states.state(scope)?.mode).isEqualTo(NiaJudgeCadenceMode.ACTIVE)
        assertThat(states.state(scope)?.latestMessage?.messageId).isEqualTo(2L)
    }

    @Test
    fun `due CATCH_UP Judge의 IGNORE는 cursor를 전진시키고 다음 간격으로 다시 예약한다`() {
        val clock = MutableClock(start)
        val states = InMemoryStates()
        val cadence = cadence(states, clock, threshold = 2, intervalMillis = 60_000)
        cadence.recordEvaluation(message(1), NiaCatchUpJudgeResult.IGNORE)
        cadence.recordEvaluation(message(2), NiaCatchUpJudgeResult.IGNORE)
        cadence.admit(message(3))

        clock.advanceMillis(60_000)
        val claim = cadence.claimDue().single()

        assertThat(claim.target?.messageId).isEqualTo(3L)
        assertThat(cadence.complete(claim, NiaCatchUpJudgeResult.IGNORE)).isTrue()
        val completed = checkNotNull(states.state(scope))
        assertThat(completed.mode).isEqualTo(NiaJudgeCadenceMode.CATCH_UP)
        assertThat(completed.lastJudgedMessageId).isEqualTo(3L)
        assertThat(completed.leaseOwner).isNull()
        assertThat(completed.nextCatchUpAt).isEqualTo(start.plusSeconds(120))
    }

    @Test
    fun `due Judge의 비침묵 결과는 ACTIVE로 복귀하고 실패는 재시도한다`() {
        val clock = MutableClock(start)
        val states = InMemoryStates()
        val cadence = cadence(states, clock, threshold = 1, intervalMillis = 60_000, retryMillis = 10_000)
        cadence.recordEvaluation(message(1), NiaCatchUpJudgeResult.IGNORE)
        cadence.admit(message(2))
        clock.advanceMillis(60_000)
        val retryClaim = cadence.claimDue().single()

        assertThat(cadence.complete(retryClaim, NiaCatchUpJudgeResult.UNPROCESSED)).isTrue()
        assertThat(states.state(scope)?.nextCatchUpAt).isEqualTo(start.plusSeconds(70))
        assertThat(states.state(scope)?.retryCount).isEqualTo(1)

        clock.advanceMillis(10_000)
        val speakClaim = cadence.claimDue().single()
        assertThat(cadence.complete(speakClaim, NiaCatchUpJudgeResult.NON_IGNORE)).isTrue()
        val active = checkNotNull(states.state(scope))
        assertThat(active.mode).isEqualTo(NiaJudgeCadenceMode.ACTIVE)
        assertThat(active.consecutiveIgnoreCount).isZero()
        assertThat(active.retryCount).isZero()
        assertThat(active.nextCatchUpAt).isNull()
    }

    @Test
    fun `실패 재시도는 한도를 넘으면 경고 후 ACTIVE로 복귀해 무한 Judge 호출을 막는다`() {
        val clock = MutableClock(start)
        val states = InMemoryStates()
        val cadence = cadence(states, clock, threshold = 1, intervalMillis = 60_000, retryMillis = 10_000, maxRetryCount = 1)
        cadence.recordEvaluation(message(1), NiaCatchUpJudgeResult.IGNORE)
        cadence.admit(message(2))

        clock.advanceMillis(60_000)
        assertThat(cadence.complete(cadence.claimDue().single(), NiaCatchUpJudgeResult.UNPROCESSED)).isTrue()
        assertThat(states.state(scope)?.retryCount).isEqualTo(1)

        clock.advanceMillis(10_000)
        assertThat(cadence.complete(cadence.claimDue().single(), NiaCatchUpJudgeResult.UNPROCESSED)).isTrue()
        val exhausted = checkNotNull(states.state(scope))
        assertThat(exhausted.mode).isEqualTo(NiaJudgeCadenceMode.ACTIVE)
        assertThat(exhausted.lastJudgedMessageId).isEqualTo(2L)
        assertThat(exhausted.retryCount).isZero()
        assertThat(cadence.claimDue()).isEmpty()
    }

    @Test
    fun `만료된 이전 lease는 같은 worker id여도 새 claim을 완료할 수 없다`() {
        val clock = MutableClock(start)
        val states = InMemoryStates()
        val cadence = cadence(states, clock, threshold = 1, intervalMillis = 60_000)
        cadence.recordEvaluation(message(1), NiaCatchUpJudgeResult.IGNORE)
        cadence.admit(message(2))

        clock.advanceMillis(60_000)
        val staleClaim = cadence.claimDue().single()
        clock.advanceMillis(30_001)
        val currentClaim = cadence.claimDue().single()

        assertThat(staleClaim.leaseOwner).isEqualTo(currentClaim.leaseOwner)
        assertThat(staleClaim.leaseToken).isNotEqualTo(currentClaim.leaseToken)
        assertThat(cadence.complete(staleClaim, NiaCatchUpJudgeResult.NON_IGNORE)).isFalse()
        assertThat(states.state(scope)?.leaseToken).isEqualTo(currentClaim.leaseToken)
        assertThat(cadence.complete(currentClaim, NiaCatchUpJudgeResult.NON_IGNORE)).isTrue()
        assertThat(states.state(scope)?.mode).isEqualTo(NiaJudgeCadenceMode.ACTIVE)
    }

    @Test
    fun `한 tick은 due 채널 하나만 claim해 대기 중인 batch lease 만료를 막는다`() {
        val clock = MutableClock(start)
        val states = InMemoryStates()
        val cadence = cadence(states, clock, threshold = 1, intervalMillis = 60_000)
        val otherScope = NiaCatchUpScope(guildId = 10, channelId = 21)
        cadence.recordEvaluation(message(1), NiaCatchUpJudgeResult.IGNORE)
        cadence.admit(message(2))
        cadence.recordEvaluation(message(3, scope = otherScope), NiaCatchUpJudgeResult.IGNORE)
        cadence.admit(message(4, scope = otherScope))

        clock.advanceMillis(60_000)

        assertThat(cadence.claimDue()).hasSize(1)
        assertThat(cadence.claimDue()).hasSize(1)
    }

    @Test
    fun `claim 중 새 메시지가 오면 이전 cursor만 완료하고 최신 메시지는 다음 CATCH_UP에 남긴다`() {
        val clock = MutableClock(start)
        val states = InMemoryStates()
        val cadence = cadence(states, clock, threshold = 1, intervalMillis = 60_000)
        cadence.recordEvaluation(message(1), NiaCatchUpJudgeResult.IGNORE)
        cadence.admit(message(2))

        clock.advanceMillis(60_000)
        val firstClaim = cadence.claimDue().single()
        assertThat(firstClaim.target?.messageId).isEqualTo(2L)
        assertThat(cadence.admit(message(3))).isEqualTo(NiaCatchUpAdmission.DEFERRED)

        assertThat(cadence.complete(firstClaim, NiaCatchUpJudgeResult.IGNORE)).isTrue()
        val afterFirst = checkNotNull(states.state(scope))
        assertThat(afterFirst.lastJudgedMessageId).isEqualTo(2L)
        assertThat(afterFirst.latestMessage?.messageId).isEqualTo(3L)

        clock.advanceMillis(60_000)
        assertThat(
            cadence
                .claimDue()
                .single()
                .target
                ?.messageId,
        ).isEqualTo(3L)
    }

    @Test
    fun `익명 1000개 사람 대화 replay는 초기 10회 뒤 한 번의 CATCH_UP Judge로 합친다`() {
        val clock = MutableClock(start)
        val states = InMemoryStates()
        val cadence = cadence(states, clock, threshold = 10, intervalMillis = 300_000)
        val immediateJudgeMessageIds = mutableListOf<Long>()

        (1L..1_000L).forEach { id ->
            val signal = message(id)
            if (cadence.admit(signal) == NiaCatchUpAdmission.EVALUATE_NOW) {
                immediateJudgeMessageIds += id
                cadence.recordEvaluation(signal, NiaCatchUpJudgeResult.IGNORE)
            }
        }

        assertThat(immediateJudgeMessageIds).containsExactlyElementsOf(1L..10L)
        assertThat(cadence.claimDue()).isEmpty()

        clock.advanceMillis(300_000)
        val catchUpClaim = cadence.claimDue().single()
        assertThat(catchUpClaim.target?.messageId).isEqualTo(1_000L)
        assertThat(cadence.complete(catchUpClaim, NiaCatchUpJudgeResult.IGNORE)).isTrue()
        assertThat(states.state(scope)?.lastJudgedMessageId).isEqualTo(1_000L)
    }

    @Test
    fun `꺼진 cadence는 기존 ACTIVE Judge 경로를 그대로 유지한다`() {
        val clock = MutableClock(start)
        val states = InMemoryStates()
        val cadence =
            NiaCatchUpCadence(
                states = states,
                enabled = false,
                consecutiveIgnoreThreshold = 1,
                intervalMillis = 60_000,
                leaseMillis = 30_000,
                retryMillis = 10_000,
                maxRetryCount = 3,
                workerId = "test",
                clock = clock,
            )

        assertThat(cadence.admit(message(1))).isEqualTo(NiaCatchUpAdmission.EVALUATE_NOW)
        cadence.recordEvaluation(message(1), NiaCatchUpJudgeResult.IGNORE)
        assertThat(states.state(scope)).isNull()
    }

    private fun cadence(
        states: InMemoryStates,
        clock: MutableClock,
        threshold: Int,
        intervalMillis: Long = 300_000,
        retryMillis: Long = 10_000,
        maxRetryCount: Int = 3,
    ): NiaCatchUpCadence =
        NiaCatchUpCadence(
            states = states,
            enabled = true,
            consecutiveIgnoreThreshold = threshold,
            intervalMillis = intervalMillis,
            leaseMillis = 30_000,
            retryMillis = retryMillis,
            maxRetryCount = maxRetryCount,
            workerId = "test",
            clock = clock,
        )

    private fun message(
        id: Long,
        mentioned: Boolean = false,
        scope: NiaCatchUpScope = this.scope,
    ): NiaCatchUpMessage =
        NiaCatchUpMessage(
            scope = scope,
            messageId = id,
            userId = 100 + id,
            replyToMessageId = null,
            occurredAt = start.plusSeconds(id),
            mentioned = mentioned,
            replyToNia = false,
        )
}

private class InMemoryStates : NiaCatchUpStateStorePort {
    private val states = linkedMapOf<NiaCatchUpScope, NiaCatchUpState>()
    private var nextId = 1L
    private var nextLeaseToken = 1L

    override fun lock(scope: NiaCatchUpScope): NiaCatchUpState? = states[scope]

    override fun lockClaim(claim: NiaCatchUpClaim): NiaCatchUpState? =
        states[claim.scope]?.takeIf {
            it.id == claim.stateId &&
                it.leaseOwner == claim.leaseOwner &&
                it.leaseToken == claim.leaseToken
        }

    override fun save(state: NiaCatchUpState): NiaCatchUpState {
        val saved = state.copy(id = state.id ?: nextId++)
        states[saved.scope] = saved
        return saved
    }

    override fun claimDue(
        now: Instant,
        leaseOwner: String,
        leaseExpiresAt: Instant,
        limit: Int,
    ): List<NiaCatchUpClaim> =
        states.values
            .asSequence()
            .filter {
                it.mode == NiaJudgeCadenceMode.CATCH_UP &&
                    it.nextCatchUpAt != null &&
                    !it.nextCatchUpAt.isAfter(now) &&
                    (it.latestMessage?.messageId ?: 0) > it.lastJudgedMessageId &&
                    (it.leaseExpiresAt == null || it.leaseExpiresAt.isBefore(now))
            }.take(limit)
            .map { state ->
                val token = "lease-${nextLeaseToken++}"
                val claimed = state.copy(leaseOwner = leaseOwner, leaseToken = token, leaseExpiresAt = leaseExpiresAt)
                states[state.scope] = claimed
                NiaCatchUpClaim(
                    stateId = checkNotNull(claimed.id),
                    scope = claimed.scope,
                    target = claimed.latestMessage,
                    leaseOwner = leaseOwner,
                    leaseToken = token,
                )
            }.toList()

    override fun deleteScope(scope: NiaCatchUpScope) {
        states.remove(scope)
    }

    override fun deleteChannel(
        guildId: Long,
        channelId: Long,
    ) {
        states.keys.removeIf { it.guildId == guildId && it.channelId == channelId }
    }

    override fun deleteGuild(guildId: Long) {
        states.keys.removeIf { it.guildId == guildId }
    }

    fun state(scope: NiaCatchUpScope): NiaCatchUpState? = states[scope]
}

private class MutableClock(
    private var current: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current

    fun advanceMillis(millis: Long) {
        current = current.plusMillis(millis)
    }
}
