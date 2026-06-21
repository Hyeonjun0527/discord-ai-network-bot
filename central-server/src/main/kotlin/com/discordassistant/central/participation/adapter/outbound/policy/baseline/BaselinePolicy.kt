package com.discordassistant.central.participation.adapter.outbound.policy.baseline

import com.discordassistant.central.participation.application.port.out.ParticipationPolicyPort
import com.discordassistant.central.participation.application.port.out.PolicyDecisionRequest
import com.discordassistant.central.participation.application.port.out.PolicyDecisionResponse
import com.discordassistant.central.participation.application.port.out.PolicyEngineCapabilities
import com.discordassistant.central.participation.application.port.out.SocialPolicyPort
import java.util.concurrent.CompletableFuture

/**
 * baseline 정책 공용 추상(NEXA-P09-T001~T006). baseline 후보가 **동기**([ParticipationPolicyPort.decide]) 와
 * **비동기**([SocialPolicyPort.predict]) 양쪽 계약을 한 번의 [decide] 구현으로 통과하도록 묶는다 — async 는 결정론
 * 동기 결과를 즉시 완료된 future 로 감싸기만 한다(baseline 은 원격 추론이 없다).
 *
 * 이렇게 양쪽 port 를 구현해 baseline 이 decision log·replay·shadow 경로를 다른 모델과 **동일하게** 통과한다
 * (acceptance T001). 구현체는 [decide] 와 [capabilities] 만 채우면 된다.
 *
 * 순수성: 결정론(같은 입력=같은 출력) — 시계·난수 상태·외부 IO 없음. shadow 비교가 재현된다.
 */
abstract class BaselinePolicy :
    ParticipationPolicyPort,
    SocialPolicyPort {
    /** baseline 은 schema 1 만, model 무관(supportedModelVersions 비움 → 모든 modelVersion 허용). */
    override fun capabilities(): PolicyEngineCapabilities =
        PolicyEngineCapabilities(
            supportedSchemaVersions = setOf(1),
            supportedModelVersions = emptySet(),
        )

    /** 동기 결정 결과를 즉시 완료된 future 로 감싼다(baseline 은 원격 추론 지연이 없다). */
    final override fun predict(request: PolicyDecisionRequest): CompletableFuture<PolicyDecisionResponse> =
        CompletableFuture.completedFuture(decide(request))
}
