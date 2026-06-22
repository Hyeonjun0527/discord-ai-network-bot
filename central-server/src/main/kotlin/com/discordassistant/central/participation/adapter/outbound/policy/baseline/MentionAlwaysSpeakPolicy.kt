package com.discordassistant.central.participation.adapter.outbound.policy.baseline

import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.port.out.PolicyDecisionRequest
import com.discordassistant.central.participation.application.port.out.PolicyDecisionResponse

/**
 * 직접 mention 이면 즉시 SPEAK, 아니면 IGNORE 하는 **의도적으로 나쁜 기준선**(NEXA-P09-T002).
 *
 * **acceptance(T002) — 왜 제품 정책으로 쓰면 안 되는지 이름과 문서에 명확하다**:
 * 이 정책은 멘션 하나만 보고 즉답한다. 사람은 멘션돼도 늘 답하지 않고(맥락·타이밍·관계를 본다), 멘션 없이도
 * 대화에 끼어든다. 이 baseline 은 그 모든 사회 신호([sceneSnapshotRef]·tempo·relationship·burst 종료·다른 인간
 * 응답)를 **전부 무시**하므로:
 * - **거짓 침묵**: 멘션 안 한 자연스러운 호출(이름 언급·암시적 질문)에 절대 반응하지 않는다.
 * - **로봇 같은 즉답**: 멘션이면 타이밍·burst·delay 를 무시하고 항상 즉시 한 줄 — 사람 리듬과 다르다.
 * - **악용 취약**: 누구든 멘션만 하면 무조건 발화를 유발할 수 있다(스팸/비용 유도).
 *
 * 따라서 이 정책은 **shadow 대조군 전용**이다 — "멘션만 보는 단순 규칙이 얼마나 부족한지"의 기준선. 이름
 * (MentionAlwaysSpeak)이 "멘션이면 항상 말함"의 단순함을 그대로 드러내 LIVE 승격을 막는다.
 *
 * 멘션 신호는 [FeatureCatalog.BURST_HAS_MENTION] 불리언 feature 에서 읽는다(원문 비참조 — 정규화 신호만).
 */
class MentionAlwaysSpeakPolicy : BaselinePolicy() {
    override fun decide(request: PolicyDecisionRequest): PolicyDecisionResponse {
        val mentioned = request.features[FeatureCatalog.BURST_HAS_MENTION]?.let { !it.missing && it.value >= 0.5 } ?: false
        // speechAllowed=false(feature gate 차단)면 멘션이어도 SPEAK 금지 — 계약 안전(분포 밖 발화 금지).
        val weights =
            if (mentioned && request.config.speechAllowed) {
                BaselineDistributions.ALWAYS_SPEAK
            } else {
                BaselineDistributions.ALWAYS_IGNORE
            }
        return BaselineDistributions.response(actionWeights = weights, modelVersion = MODEL_VERSION)
    }

    companion object {
        /** 결정 추적·shadow 비교용 안정 모델 버전 식별자. */
        const val MODEL_VERSION: String = "baseline-mention-always-speak-1"
    }
}
