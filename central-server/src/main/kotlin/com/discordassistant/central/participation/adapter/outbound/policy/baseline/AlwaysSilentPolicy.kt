package com.discordassistant.central.participation.adapter.outbound.policy.baseline

import com.discordassistant.central.participation.application.port.out.PolicyDecisionRequest
import com.discordassistant.central.participation.application.port.out.PolicyDecisionResponse

/**
 * 항상 IGNORE 를 반환하는 **하한 기준선**(NEXA-P09-T001). 어떤 입력에도 NEXA 가 침묵한다 — 모든 baseline·모델의
 * **하한 대조군**이다(이보다 나쁠 수 없는 "절대 말 안 함").
 *
 * **acceptance(T001) — 정책 계약·decision log·replay 경로를 다른 모델과 동일하게 통과한다**:
 * [BaselinePolicy] 를 통해 동기/비동기 port 를 모두 구현하므로, 같은 [PolicyDecisionResponse] 계약을 내고
 * decision log(SocialActionKind.IGNORE)·replay(결정론: 입력 무관 항상 같음) 경로를 동일하게 통과한다.
 *
 * **용도**: shadow 비교에서 "아무 것도 안 하면 얼마나 잃나/얻나" 의 절대 기준선. 운영 정책으로 쓰면 NEXA 가
 * 영원히 침묵하므로 절대 LIVE 로 승격하지 않는다(이름이 의도를 명시).
 */
class AlwaysSilentPolicy : BaselinePolicy() {
    override fun decide(request: PolicyDecisionRequest): PolicyDecisionResponse =
        BaselineDistributions.response(
            actionWeights = BaselineDistributions.ALWAYS_IGNORE,
            modelVersion = MODEL_VERSION,
        )

    companion object {
        /** 결정 추적·shadow 비교용 안정 모델 버전 식별자. */
        const val MODEL_VERSION: String = "baseline-always-silent-1"
    }
}
