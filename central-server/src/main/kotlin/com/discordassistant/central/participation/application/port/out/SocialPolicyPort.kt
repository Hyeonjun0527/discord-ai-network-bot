package com.discordassistant.central.participation.application.port.out

import java.util.concurrent.CompletableFuture

/**
 * 사회 정책 예측 아웃바운드 포트(NEXA-P08-T018, 헥사고날 application 레이어). participation 이 "어떤 행동을 할지"의
 * **확률분포** 를 **비동기** 로 얻기 위해 호출하는 결정 엔진 추상이다. 동기 [ParticipationPolicyPort.decide] 와 달리
 * 원격 추론(ONNX/gRPC) 지연을 호출자가 블록하지 않도록 [CompletableFuture] 로 분포를 약속한다.
 *
 * **acceptance(T018) — 정책 port 가 routing/GLM API 를 요구하지 않는다**:
 * 시그니처는 정책 계약 값 객체([PolicyDecisionRequest] → [PolicyDecisionResponse])만 본다. routing 의
 * CloudLlm/RequestOrchestrator·provider-agent glm·Z.AI SDK 타입을 일절 import 하지 않는다 — 정책 결정은
 * GLM 텍스트 생성과 결합되지 않는다(participation 만이 "말할지 여부" 의 결정자, GLM 은 speech 의 텍스트 책임).
 *
 * 순수성 경계: application 레이어 — 계약 값 객체와 표준 [CompletableFuture] 만. Spring/JPA/JDA/routing/GLM 미참조.
 */
interface SocialPolicyPort {
    /** 지원하는 (schema, model) 버전 능력 — 버전 협상(T008)에 쓰인다. */
    fun capabilities(): PolicyEngineCapabilities

    /**
     * [request] 에 대한 행동 확률분포([PolicyDecisionResponse])를 **비동기** 로 예측한다. 호출 전 버전 협상으로
     * 호환이 확인됐다고 가정한다. 엔진 오류는 future 를 예외로 완료해 전파한다(호출자가 shadow/fallback 으로 처리).
     */
    fun predict(request: PolicyDecisionRequest): CompletableFuture<PolicyDecisionResponse>
}
