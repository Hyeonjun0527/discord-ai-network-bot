package com.discordassistant.central.participation.adapter.outbound.policy.grpc

import com.discordassistant.central.participation.application.port.out.PolicyDecisionRequest
import com.discordassistant.central.participation.application.port.out.PolicyDecisionResponse
import com.discordassistant.central.participation.application.port.out.PolicyEngineCapabilities
import com.discordassistant.central.participation.application.port.out.SocialPolicyPort
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * gRPC 정책 서빙 어댑터 골격(NEXA-P12-T020, 옵션 경로 — ADR 0013). [SocialPolicyPort] 를 구현해 원격 정책
 * 서비스([PolicyGrpcTransport])로 추론을 위임하되, **deadline·circuit breaker·schema validation** 을 가진다.
 *
 * **acceptance(T020) — 서비스 부재 시 configured fallback 이 작동하고 live action 을 멈출 수 있다**:
 * - **서비스 부재**([PolicyGrpcTransport.isAvailable]=false) 또는 circuit open: 원격을 호출하지 않고 주입된
 *   [fallback] 으로 즉시 응답한다(보통 always-silent → live action 정지).
 * - **deadline**: 각 호출에 [deadline] 을 강제한다. 초과/실패는 circuit breaker 의 실패로 집계되고 [fallback] 으로
 *   하강한다(예외를 호출자에게 던지지 않는다 — fail-safe).
 * - **circuit breaker**: 연속 실패가 [failureThreshold] 에 닿으면 회로를 열어 [openWindow] 동안 원격을 건너뛰고
 *   fallback 만 쓴다(원격이 죽었을 때 매 호출 timeout 대기 방지). 성공하면 회로를 닫는다.
 * - **schema validation**: 원격 응답의 modelVersion 이 비어 있는 등 계약 위반이면 실패로 보고 fallback.
 *
 * fallback 은 [SocialPolicyPort] 라 어떤 안전 정책(always-silent 권장)이든 주입 가능하다 — 이 어댑터는 **절대
 * mention-always 로 떨어지지 않는다**(무엇을 fallback 으로 줄지는 구성 책임, 안전 단조는 PolicyFallbackChain 과 일관).
 *
 * 순수성 경계: adapter 레이어 — application 계약·표준 타입·[PolicyGrpcTransport] 만. grpc/proto·Spring/JPA/JDA
 * 타입을 노출하지 않는다(NexaArchitectureTest 의 participation 경계 준수).
 */
class GrpcSocialPolicyEngine(
    private val transport: PolicyGrpcTransport,
    private val fallback: SocialPolicyPort,
    private val deadline: Duration = Duration.ofMillis(DEFAULT_DEADLINE_MS),
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    private val openWindow: Duration = Duration.ofSeconds(DEFAULT_OPEN_WINDOW_S),
    private val clock: () -> Long = System::currentTimeMillis,
) : SocialPolicyPort {
    init {
        require(!deadline.isNegative && !deadline.isZero) { "deadline 은 양수여야 한다: $deadline" }
        require(failureThreshold >= 1) { "failureThreshold 는 1 이상이어야 한다: $failureThreshold" }
    }

    private val consecutiveFailures = AtomicInteger(0)
    private val openedAtMs = AtomicLong(0L)

    override fun capabilities(): PolicyEngineCapabilities =
        if (transport.isAvailable() && !isCircuitOpen()) {
            runCatching { transport.capabilities() }.getOrElse { fallback.capabilities() }
        } else {
            fallback.capabilities()
        }

    override fun predict(request: PolicyDecisionRequest): CompletableFuture<PolicyDecisionResponse> {
        // 서비스 부재·circuit open: 원격 미호출, configured fallback 으로 즉시(=live action 정지 가능).
        if (!transport.isAvailable() || isCircuitOpen()) {
            return fallback.predict(request)
        }
        return transport
            .predict(request, deadline)
            .thenApply { response ->
                validateSchema(response) // 계약 위반이면 예외 → 아래 fallback.
                onSuccess()
                response
            }.exceptionallyCompose {
                onFailure()
                fallback.predict(request) // deadline/오류/계약위반 → 안전 fallback.
            }
    }

    /** schema validation: 원격 응답이 계약을 만족하는지(modelVersion 필수). 위반은 예외로 fallback 유발. */
    private fun validateSchema(response: PolicyDecisionResponse) {
        require(response.modelVersion.isNotBlank()) { "gRPC 응답 modelVersion 이 비어 있다(계약 위반)" }
        // actionWeights 합·범위는 PolicyDecisionResponse.init 가 이미 강제한다(여기 도달 = 통과).
    }

    private fun isCircuitOpen(): Boolean {
        if (consecutiveFailures.get() < failureThreshold) return false
        val elapsed = clock() - openedAtMs.get()
        if (elapsed >= openWindow.toMillis()) {
            // open window 경과 → half-open: 다음 호출을 한 번 허용(성공하면 닫힘).
            consecutiveFailures.set(failureThreshold - 1)
            return false
        }
        return true
    }

    private fun onSuccess() {
        consecutiveFailures.set(0)
    }

    private fun onFailure() {
        val failures = consecutiveFailures.incrementAndGet()
        if (failures >= failureThreshold) {
            openedAtMs.set(clock())
        }
    }

    companion object {
        /** 기본 호출 deadline(ms). 정책은 GLM 호출보다 먼저 끝나야 한다(EXP-policy-latency 목표와 일관). */
        const val DEFAULT_DEADLINE_MS: Long = 80

        /** circuit 을 여는 연속 실패 임계. */
        const val DEFAULT_FAILURE_THRESHOLD: Int = 3

        /** circuit open 유지 창(초). 이 동안 원격을 건너뛰고 fallback 만 쓴다. */
        const val DEFAULT_OPEN_WINDOW_S: Long = 5
    }
}
