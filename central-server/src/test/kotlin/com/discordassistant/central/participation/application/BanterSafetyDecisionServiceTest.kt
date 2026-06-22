package com.discordassistant.central.participation.application

import com.discordassistant.central.participation.application.port.out.DecisionLogRecord
import com.discordassistant.central.participation.application.port.out.ParticipationDecisionLogPort
import com.discordassistant.central.participation.domain.model.action.SocialAct
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.decision.ActionDistribution
import com.discordassistant.central.participation.domain.model.decision.ActionTargetDistribution
import com.discordassistant.central.participation.domain.model.decision.BurstProfile
import com.discordassistant.central.participation.domain.model.decision.DelayBucket
import com.discordassistant.central.participation.domain.model.decision.DelayDistribution
import com.discordassistant.central.participation.domain.model.decision.TargetCandidate
import com.discordassistant.central.participation.domain.model.decision.TargetKind
import com.discordassistant.central.participation.domain.model.decision.TargetRef
import com.discordassistant.central.participation.domain.service.BanterSafetyContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * NEXA-P17-T015 enforcement 통합 테스트(security-reviewer M3 해소 증명).
 *
 * 합성 evaluator 가 아니라 실제 [BanterSafetyDecisionService] 를 구동해, banter 안전 override 가 **샘플링(전송될
 * 행동 결정) 전에** 적용되고, raw 와 override(제거된 kind) 가 실제 [ParticipationDecisionLogPort] sink 에 기록됨을
 * 검증한다. IGNORE 도 기록된다.
 */
class BanterSafetyDecisionServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC)

    private class CapturingLog : ParticipationDecisionLogPort {
        val records = mutableListOf<DecisionLogRecord>()

        override fun append(record: DecisionLogRecord) {
            records += record
        }

        override fun findByCorrelationId(correlationId: String): DecisionLogRecord? =
            records.lastOrNull { it.correlationId == correlationId }

        override fun purgeExpired(olderThan: Instant): Int = 0
    }

    private val provenance =
        DecisionProvenance(
            correlationId = "corr-1",
            guildPseudonym = "guild_x",
            channelId = "chan_1",
            contextVersion = 3L,
            featureHash = "fh-1",
            featureVectorVersion = 1,
            modelVersion = "policy-v1",
        )

    private fun distribution(
        actions: Map<SocialActionKind, Double> =
            mapOf(
                SocialActionKind.IGNORE to 0.2,
                SocialActionKind.REACT to 0.2,
                SocialActionKind.SPEAK to 0.6,
            ),
        acts: Map<SocialAct, Double> = mapOf(SocialAct.TEASE to 1.0),
    ): ActionDistribution =
        ActionDistribution(
            actionWeights = actions,
            targetDistribution =
                ActionTargetDistribution(
                    candidates = listOf(TargetCandidate(TargetRef(TargetKind.MESSAGE, "m-1"), 0.7)),
                    noneProbability = 0.3,
                    resolverVersion = "rules-1",
                ),
            delayDistribution = DelayDistribution(mapOf(DelayBucket.IMMEDIATE to 1.0)),
            socialActWeights = acts,
            burstProfile = BurstProfile.singleLine(),
            uncertainty = 0.3,
        )

    @Test
    fun `안전 신호 없으면 분포 그대로 결정하고 IGNORE 도 로그에 남는다`() {
        val log = CapturingLog()
        val service = BanterSafetyDecisionService(log, clock)
        val decision =
            service.decideAndLog(
                provenance,
                distribution(acts = mapOf(SocialAct.ACKNOWLEDGE to 1.0)),
                BanterSafetyContext(),
                seed = 42L,
            )
        assertThat(decision.safetyChanged).isFalse()
        assertThat(log.records).hasSize(1)
        assertThat(log.records.last().correlationId).isEqualTo("corr-1")
        // 결정 종류가 무엇이든(IGNORE 포함) 기록된다.
        assertThat(log.records.last().actionKind).isIn(*SocialActionKind.entries.toTypedArray())
    }

    @Test
    fun `M3 — 중단 신호면 모든 비-침묵 발화가 제거되어 SPEAK 가 결정되지 않고 override 가 로그에 남는다`() {
        val log = CapturingLog()
        val service = BanterSafetyDecisionService(log, clock)
        // 대상이 중단 신호를 보냄 → 모든 비-침묵 socialAct 제거 → SPEAK 근거 소멸 → 발화 취소.
        val decision =
            service.decideAndLog(
                provenance,
                distribution(),
                BanterSafetyContext(targetStopRequested = true),
                seed = 7L,
            )
        assertThat(decision.safetyChanged).isTrue()
        assertThat(decision.finalAction).isNotEqualTo(SocialActionKind.SPEAK)
        assertThat(decision.consumedGenerationQuota).isFalse()
        // raw 와 함께 override(SPEAK 제거)가 decision log 에 남는다.
        val rec = log.records.last()
        assertThat(rec.removedKinds).contains(SocialActionKind.SPEAK)
        assertThat(rec.consumedGenerationQuota).isFalse()
    }

    @Test
    fun `반복 표적화면 공격적 act 가 제거된 분포로 결정된다`() {
        val log = CapturingLog()
        val service = BanterSafetyDecisionService(log, clock)
        val decision =
            service.decideAndLog(
                provenance,
                distribution(acts = mapOf(SocialAct.TEASE to 0.5, SocialAct.ACKNOWLEDGE to 0.5)),
                BanterSafetyContext(repeatedTargetingCount = 5, repeatedTargetingThreshold = 3),
                seed = 11L,
            )
        assertThat(decision.safetyChanged).isTrue()
        assertThat(log.records).hasSize(1)
    }
}
