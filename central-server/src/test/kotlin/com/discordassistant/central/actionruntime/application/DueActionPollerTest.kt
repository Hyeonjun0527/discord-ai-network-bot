package com.discordassistant.central.actionruntime.application

import com.discordassistant.central.actionruntime.application.port.out.ActionReevaluationPort
import com.discordassistant.central.actionruntime.application.port.out.ReevaluationTarget
import com.discordassistant.central.actionruntime.application.reevaluate.StaleActionReevaluator
import com.discordassistant.central.actionruntime.application.scheduler.DueActionDisposition
import com.discordassistant.central.actionruntime.application.scheduler.DueActionPoller
import com.discordassistant.central.actionruntime.application.scheduler.SceneEvidenceProvider
import com.discordassistant.central.actionruntime.domain.model.ActionStatus
import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.actionruntime.domain.model.ScheduledActionType
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import com.discordassistant.central.actionruntime.domain.service.CancellationPolicy
import com.discordassistant.central.actionruntime.domain.service.CancellationVerdict
import com.discordassistant.central.actionruntime.domain.service.SceneEvidence
import com.discordassistant.central.actionruntime.support.InMemoryActionScheduler
import com.discordassistant.central.actionruntime.support.MutableTestClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * NEXA-P13-T006/T008/T011/T012/T013 — DueActionPoller: Clock 전진 기반 due claim·재평가·취소 통합 단위 테스트.
 * 실제 sleep 없이([MutableTestClock]) due 도달과 분기를 검증한다.
 */
class DueActionPollerTest {
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val scheduler = InMemoryActionScheduler(clock)

    private fun reevaluation(
        stale: Long? = null,
        valid: Boolean = true,
    ) = object : ActionReevaluationPort {
        override fun currentContextVersion(target: ReevaluationTarget): Long? = stale

        override fun stillValid(
            decisionId: String,
            target: ReevaluationTarget,
            scheduledContextVersion: Long,
            currentContextVersion: Long,
        ): Boolean = valid
    }

    private fun poller(
        reeval: ActionReevaluationPort,
        evidence: SceneEvidence = SceneEvidence(0, "t1", false),
    ) = DueActionPoller(
        scheduler = scheduler,
        reevaluator = StaleActionReevaluator(reeval),
        cancellationPolicy = CancellationPolicy(),
        sceneEvidenceProvider = SceneEvidenceProvider { evidence },
        clock = clock,
    )

    private fun schedule(
        executeAfter: Instant,
        contextVersion: Long = 1,
    ) {
        scheduler.schedule(
            ScheduledSocialAction.create(
                decisionId = "d1",
                sampledActionIndex = 0,
                type = ScheduledActionType.SPEAK,
                target = ActionTarget("g1", "c1", "t1"),
                executeAfter = executeAfter,
                contextVersion = contextVersion,
            ),
        )
    }

    @Test
    fun `due 가 아니면 claim 하지 않는다(시간 전진 전)`() {
        schedule(executeAfter = clock.instant().plus(Duration.ofHours(1)))
        assertThat(poller(reevaluation(stale = 1)).pollOnce()).isEmpty()
    }

    @Test
    fun `시계를 전진시키면 due 가 되어 READY_TO_TYPE 로 진행한다(실제 sleep 없음)`() {
        schedule(executeAfter = clock.instant().plus(Duration.ofHours(1)), contextVersion = 1)
        clock.advance(Duration.ofHours(1)) // 1시간을 실제로 기다리지 않고 전진
        val outcomes = poller(reevaluation(stale = 1)).pollOnce()
        assertThat(outcomes)
            .singleElement()
            .satisfies({ assertThat(it.disposition).isEqualTo(DueActionDisposition.READY_TO_TYPE) })
        assertThat(outcomes[0].action.status).isEqualTo(ActionStatus.TYPING)
    }

    @Test
    fun `contextVersion 이 바뀌고 재평가가 무효면 stale 취소된다(stale 직행 경로 없음)`() {
        schedule(executeAfter = clock.instant(), contextVersion = 1)
        // 현재 버전 2(예약 당시 1과 다름) + stillValid=false → 취소.
        val outcomes = poller(reevaluation(stale = 2, valid = false)).pollOnce()
        assertThat(outcomes[0].disposition).isEqualTo(DueActionDisposition.CANCELLED_STALE)
    }

    @Test
    fun `장면이 사라지면(version null) stale 취소된다`() {
        schedule(executeAfter = clock.instant(), contextVersion = 1)
        val outcomes = poller(reevaluation(stale = null)).pollOnce()
        assertThat(outcomes[0].disposition).isEqualTo(DueActionDisposition.CANCELLED_STALE)
    }

    @Test
    fun `다른 인간이 충분히 답하면 취소 정책으로 취소된다(재평가 전에)`() {
        schedule(executeAfter = clock.instant(), contextVersion = 1)
        val outcomes =
            poller(reevaluation(stale = 1), evidence = SceneEvidence(2, "t1", false)).pollOnce()
        assertThat(outcomes[0].disposition).isEqualTo(DueActionDisposition.CANCELLED_BY_POLICY)
        assertThat(outcomes[0].cancellation).isEqualTo(CancellationVerdict.CANCEL_OTHER_HUMAN_ANSWERED)
    }

    @Test
    fun `취소된 행동은 다음 poll 에서 다시 claim 되지 않는다`() {
        schedule(executeAfter = clock.instant(), contextVersion = 1)
        poller(reevaluation(stale = 2, valid = false)).pollOnce()
        // 두 번째 poll: 이미 CANCELLED 라 SCHEDULED 가 아니므로 claim 대상 아님.
        assertThat(poller(reevaluation(stale = 1)).pollOnce()).isEmpty()
    }
}
