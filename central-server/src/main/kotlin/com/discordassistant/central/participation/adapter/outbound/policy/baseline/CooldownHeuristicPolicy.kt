package com.discordassistant.central.participation.adapter.outbound.policy.baseline

import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.port.out.PolicyDecisionRequest
import com.discordassistant.central.participation.application.port.out.PolicyDecisionResponse

/**
 * 최근 NEXA 발화량과 mention 여부만 쓰는 단순 **cooldown 기준선**(NEXA-P09-T004).
 *
 * 규칙(딱 두 신호):
 * 1. 직접 mention([FeatureCatalog.BURST_HAS_MENTION])이면 cooldown 무시하고 SPEAK.
 * 2. 아니면, 최근 NEXA 발화([FeatureCatalog.AGENT_RECENT_BURST_COUNT])가 [cooldownThreshold] 미만일 때만 SPEAK,
 *    이미 충분히 말했으면 IGNORE(말 많음 억제 = cooldown).
 *
 * **acceptance(T004) — 사회 상태·기억을 쓰지 않는 제한이 문서화된다**:
 * 이 baseline 은 relationship/memory/social-state feature(REL_*·MEMORY_*·tempo 관계 신호)를 **일절 읽지 않는다**.
 * 따라서:
 * - **관계 무지**: 친한 사람의 농담·자기 개시 유도에 반응하지 못한다(reciprocity·familiarity 미사용).
 * - **기억 무지**: 직전에 한 약속/미완 의도(pending intent)를 이어가지 못한다(memory 미사용).
 * - **맥락 무지**: 채널 tempo·burst 종료·다른 인간 응답을 보지 않아 끼어들 타이밍을 못 잡는다.
 * 곧 "말 너무 자주 안 하게" 만 막는 1차원 cooldown 이다 — 풍부한 정책의 하한 비교군일 뿐 LIVE 부적합.
 */
class CooldownHeuristicPolicy(
    private val cooldownThreshold: Double = DEFAULT_COOLDOWN_THRESHOLD,
) : BaselinePolicy() {
    init {
        require(cooldownThreshold >= 0.0) { "cooldownThreshold 는 음수일 수 없다: $cooldownThreshold" }
    }

    override fun decide(request: PolicyDecisionRequest): PolicyDecisionResponse {
        val mentioned = request.features[FeatureCatalog.BURST_HAS_MENTION]?.let { !it.missing && it.value >= 0.5 } ?: false
        val recentBursts = request.features[FeatureCatalog.AGENT_RECENT_BURST_COUNT]?.let { if (it.missing) 0.0 else it.value } ?: 0.0
        val withinCooldown = recentBursts >= cooldownThreshold

        val wantsSpeak = request.config.speechAllowed && (mentioned || !withinCooldown)
        val weights = if (wantsSpeak) BaselineDistributions.ALWAYS_SPEAK else BaselineDistributions.ALWAYS_IGNORE
        return BaselineDistributions.response(
            actionWeights = weights,
            modelVersion = MODEL_VERSION,
        )
    }

    companion object {
        /** 결정 추적·shadow 비교용 안정 모델 버전 식별자. */
        const val MODEL_VERSION: String = "baseline-cooldown-heuristic-1"

        /** 최근 NEXA 발화가 이 횟수 이상이면 cooldown(멘션 아니면 침묵). 단순 기본값. */
        const val DEFAULT_COOLDOWN_THRESHOLD = 2.0
    }
}
