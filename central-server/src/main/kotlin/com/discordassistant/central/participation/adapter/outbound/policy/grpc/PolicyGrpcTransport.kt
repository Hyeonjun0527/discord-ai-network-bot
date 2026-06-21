package com.discordassistant.central.participation.adapter.outbound.policy.grpc

import com.discordassistant.central.participation.application.port.out.PolicyDecisionRequest
import com.discordassistant.central.participation.application.port.out.PolicyDecisionResponse
import com.discordassistant.central.participation.application.port.out.PolicyEngineCapabilities
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * gRPC 정책 서빙 wire 전송 추상(NEXA-P12-T020, 옵션 경로 — ADR 0013).
 *
 * ADR 0013 은 현행 JVM in-process ONNX 를 유지하고 gRPC serving 을 **옵션**으로 둔다. 이 인터페이스는 그 옵션
 * 경로의 **wire 호출**을 추상화한다 — 실제 gRPC stub(generated, social_policy.proto)이 구현으로 꽂힐 자리다.
 * **실제 gRPC 서버는 기동하지 않는다**(ADR 0013 비-목표): 어댑터/체인은 transport 부재·실패에서도 안전하게
 * fallback 하도록 설계된다. 따라서 이 추상을 두면 grpc-stub 런타임 의존 없이 어댑터 골격을 빌드·검증할 수 있다.
 *
 * 순수성 경계: adapter 레이어지만 application 계약 값 객체만 본다. proto 생성 타입은 구현체(미래) 내부에 둔다 —
 * 이 인터페이스는 grpc/proto 타입을 노출하지 않는다(계약 격리).
 */
interface PolicyGrpcTransport {
    /** 원격 정책 서비스가 현재 사용 가능한가(연결/헬스). 부재면 어댑터가 fallback 으로 간다(live action 정지 가능). */
    fun isAvailable(): Boolean

    /** 원격 (schema, model) 버전 능력 조회. 부재/실패면 어댑터가 안전 처리한다. */
    fun capabilities(): PolicyEngineCapabilities

    /**
     * [request] 를 [deadline] 안에 원격 예측한다(비동기). deadline 초과·서비스 오류는 future 를 예외로 완료해
     * 전파한다 — 어댑터가 circuit breaker 와 fallback 으로 처리한다.
     */
    fun predict(
        request: PolicyDecisionRequest,
        deadline: Duration,
    ): CompletableFuture<PolicyDecisionResponse>
}
