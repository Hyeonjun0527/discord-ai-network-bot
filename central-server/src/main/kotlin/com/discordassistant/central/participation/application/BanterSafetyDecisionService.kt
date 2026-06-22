package com.discordassistant.central.participation.application

import com.discordassistant.central.participation.application.port.out.DecisionLogRecord
import com.discordassistant.central.participation.application.port.out.ParticipationDecisionLogPort
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.decision.ActionDistribution
import com.discordassistant.central.participation.domain.service.BanterSafetyContext
import com.discordassistant.central.participation.domain.service.BanterSafetyOverride
import com.discordassistant.central.participation.domain.service.SampledPolicyOutcome
import com.discordassistant.central.participation.domain.service.SeededPolicySampler
import java.time.Clock
import java.time.Instant

/**
 * banter 안전 override 결정 서비스(NEXA-P17-T015 enforcement seam, application).
 *
 * security-reviewer M3 갭(안전 override 미호출·decision-log sink 미소비) 해소: 실제 결정 경로가 raw 분포를
 * **[BanterSafetyOverride] 로 후처리한 뒤** seed 로 접고, 그 결과를 [ParticipationDecisionLogPort] 로 기록한다.
 * 즉 안전 override 가 **전송될 행동을 정하기 전**에 적용되고, raw 와 override(제거된 kind) 가 decision log 에 함께
 * 남는다(은폐 없는 감사성). 합성 evaluator 가 아니라 이 서비스가 실제 override·로그를 구동한다.
 *
 * IGNORE 결정도 기록한다(participation 불변식 1·3 — logging-boundary.md). 원문/프롬프트/응답 본문은 담지 않는다
 * (feature hash·결정 provenance 만).
 *
 * 순수성: application — participation 도메인 서비스·값 객체 + 결정 로그 포트 + 표준 타입만. Spring/JPA/JDA 미참조.
 */
class BanterSafetyDecisionService(
    private val decisionLog: ParticipationDecisionLogPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * [raw] 분포에 [safetyContext] 의 banter 안전 override 를 적용한 뒤 [seed] 로 접어 최종 행동을 정하고, raw 와
     * override 근거를 decision log 에 기록한다(IGNORE 포함). override 후 분포로 샘플하므로 위험 socialAct·SPEAK 가
     * 안전하게 제거된 채로 결정된다.
     */
    fun decideAndLog(
        provenance: DecisionProvenance,
        raw: ActionDistribution,
        safetyContext: BanterSafetyContext,
        seed: Long,
    ): SafeDecision {
        // 1) 안전 override 를 **샘플링 전에** 적용한다(전송될 행동을 정하기 전 — 위험 act/SPEAK 제거).
        val overrideResult = BanterSafetyOverride.apply(raw, safetyContext)
        // 2) override 분포로 접는다(분포가 비면 IGNORE 로 안전 하강).
        val sampled: SampledPolicyOutcome = SeededPolicySampler.sample(overrideResult.overridden, seed)
        val removedKinds: Set<SocialActionKind> =
            overrideResult.removals
                .filter {
                    it.targetKind ==
                        com.discordassistant.central.participation.domain.service.SafetyOverrideTargetKind.ACTION
                }.mapNotNull { runCatching { SocialActionKind.valueOf(it.name) }.getOrNull() }
                .toSet()
        val consumedGenerationQuota = sampled.action == SocialActionKind.SPEAK

        // 3) raw + override 근거를 decision log 에 기록(은폐 없는 감사성 — sink 가 실제 소비).
        decisionLog.append(
            DecisionLogRecord(
                correlationId = provenance.correlationId,
                guildPseudonym = provenance.guildPseudonym,
                channelId = provenance.channelId,
                contextVersion = provenance.contextVersion,
                actionKind = sampled.action,
                featureHash = provenance.featureHash,
                featureVectorVersion = provenance.featureVectorVersion,
                modelVersion = provenance.modelVersion,
                seed = seed,
                removedKinds = removedKinds,
                consumedGenerationQuota = consumedGenerationQuota,
                decidedAt = Instant.now(clock),
            ),
        )
        return SafeDecision(
            finalAction = sampled.action,
            safetyChanged = overrideResult.changed,
            removedKinds = removedKinds,
            consumedGenerationQuota = consumedGenerationQuota,
        )
    }
}

/** 결정 provenance(원문 비포함) — decision log 가 요구하는 재현 메타. 호출자(평가 유스케이스)가 채운다. */
data class DecisionProvenance(
    val correlationId: String,
    val guildPseudonym: String,
    val channelId: String,
    val contextVersion: Long,
    val featureHash: String,
    val featureVectorVersion: Int,
    val modelVersion: String,
)

/** banter 안전 결정 결과 — 최종 행동·override 발생 여부·제거된 kind·quota 소비. */
data class SafeDecision(
    val finalAction: SocialActionKind,
    val safetyChanged: Boolean,
    val removedKinds: Set<SocialActionKind>,
    val consumedGenerationQuota: Boolean,
)
