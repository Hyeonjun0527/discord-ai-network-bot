package com.discordassistant.central.participation.application.policy

import com.discordassistant.central.participation.application.port.out.PolicyDecisionRequest
import com.discordassistant.central.participation.application.port.out.PolicyDecisionResponse
import com.discordassistant.central.participation.application.port.out.SocialPolicyPort
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.decision.ActionTargetDistribution
import com.discordassistant.central.participation.domain.model.decision.BurstProfile
import com.discordassistant.central.participation.domain.model.decision.DelayDistribution
import java.util.concurrent.CompletableFuture

/**
 * 정책 fallback 체인(NEXA-P12-T021, application 레이어·순수). 결정 엔진이 실패하거나 부재할 때 **안전한 방향**
 * 으로만 하강한다: learned(ML 정책) → safe baseline(보수 휴리스틱) → always silent(완전 침묵).
 *
 * **acceptance(T021) — 오류 시 mention-always 로 떨어지지 않고 이유가 decision log 에 남는다**:
 * - 하강 순서가 항상 **더 조용한** 정책으로 간다(SPEAK 를 늘리는 쪽으로 절대 fallback 하지 않는다). 최종 단계는
 *   [SocialPolicyPort] 로 주입된 **always-silent**(IGNORE 1.0)이며, mention-always 같은 "무조건 발화" 정책으로는
 *   절대 떨어지지 않는다(이 클래스는 그런 정책을 단계로 받지 않는다 — fail-safe 는 침묵 쪽이다).
 * - 각 단계 실패는 [FallbackReason] 으로 [onFallback] 콜백에 통지되어 호출자가 decision log 에 남긴다(이 순수
 *   application 클래스는 JPA/로깅에 직접 의존하지 않는다 — 콜백으로 경계 유지).
 *
 * 단계 구성은 환경별로 다를 수 있다([stages] 주입) — 단, 첫 단계가 learned, 마지막이 always-silent 라는
 * **안전 단조성**(점점 조용해짐)을 호출자가 보장해야 한다. 이 클래스는 그 순서를 그대로 시도하고, 어떤 단계도
 * 성공하지 못하면 마지막(always-silent) 결과를 쓴다.
 *
 * 순수성 경계: application 레이어 — port·계약 값 객체·표준 [CompletableFuture] 만. Spring/JPA/JDA 미참조.
 */
class PolicyFallbackChain(
    /** 시도 순서(learned → safe baseline → always silent). 안전 단조(점점 조용)는 호출자 책임. 비어 있을 수 없다. */
    private val stages: List<FallbackStage>,
    /** 각 단계 실패 시 호출되는 통지(이유를 decision log 에 남기는 쪽은 호출자). */
    private val onFallback: (FallbackReason) -> Unit = {},
) {
    init {
        require(stages.isNotEmpty()) { "fallback 체인은 최소 1개 단계를 가져야 한다(보통 always-silent)" }
    }

    /**
     * [request] 에 대해 체인을 순서대로 시도한다. 한 단계가 예외로 완료되면 그 이유를 [onFallback] 으로 통지하고
     * 다음(더 조용한) 단계로 내려간다. 모든 단계가 실패하면 마지막 단계 결과를 동기 대기 없이 약속한다.
     *
     * 반환 future 는 **항상 성공 완료** 한다(엔진 실패를 호출자에게 던지지 않는다) — fail-safe: 불확실하면 침묵.
     */
    fun decide(request: PolicyDecisionRequest): CompletableFuture<FallbackOutcome> = attempt(request, index = 0)

    private fun attempt(
        request: PolicyDecisionRequest,
        index: Int,
    ): CompletableFuture<FallbackOutcome> {
        val stage = stages[index]
        val isLast = index == stages.lastIndex
        return stage.port
            .predict(request)
            .thenApply { response -> FallbackOutcome(response = response, usedStage = stage.name, degraded = index > 0) }
            .exceptionallyCompose { error ->
                onFallback(
                    FallbackReason(
                        failedStage = stage.name,
                        nextStage = if (isLast) null else stages[index + 1].name,
                        cause = unwrap(error),
                    ),
                )
                if (isLast) {
                    // 마지막 단계(always-silent)마저 실패하면 계약상 안전한 IGNORE 를 직접 합성한다(절대 발화 금지).
                    CompletableFuture.completedFuture(
                        FallbackOutcome(
                            response = SilentFallbackResponse.IGNORE,
                            usedStage = stage.name,
                            degraded = true,
                        ),
                    )
                } else {
                    attempt(request, index + 1)
                }
            }
    }

    private fun unwrap(error: Throwable): Throwable =
        if (error is java.util.concurrent.CompletionException && error.cause != null) error.cause!! else error

    companion object {
        /**
         * 표준 3단계 체인(learned → safe baseline → always silent)을 구성한다. 안전 단조성을 이름으로 강제한다 —
         * always-silent 가 마지막이라 어떤 실패에도 mention-always 로 떨어지지 않는다(acceptance T021).
         */
        fun standard(
            learned: SocialPolicyPort,
            safeBaseline: SocialPolicyPort,
            alwaysSilent: SocialPolicyPort,
            onFallback: (FallbackReason) -> Unit = {},
        ): PolicyFallbackChain =
            PolicyFallbackChain(
                stages =
                    listOf(
                        FallbackStage(name = "learned", port = learned),
                        FallbackStage(name = "safe-baseline", port = safeBaseline),
                        FallbackStage(name = "always-silent", port = alwaysSilent),
                    ),
                onFallback = onFallback,
            )
    }
}

/** fallback 체인 한 단계: 정책 port + 추적용 안정 이름(decision log 기록). */
data class FallbackStage(
    val name: String,
    val port: SocialPolicyPort,
) {
    init {
        require(name.isNotBlank()) { "fallback stage 이름은 비어 있을 수 없다" }
    }
}

/** 체인 결과: 어떤 단계가 응답을 냈는지·강등 여부. [degraded] = 1차(learned) 가 아닌 단계가 답함. */
data class FallbackOutcome(
    val response: PolicyDecisionResponse,
    val usedStage: String,
    val degraded: Boolean,
)

/**
 * 한 단계 실패 사유(decision log 기록용). 어떤 단계가 왜 실패해 어디로 내려갔는지 — 원문/PII 비포함(예외 메시지는
 * 진단용). 호출자가 이 이유를 결정 로그에 남긴다(acceptance T021: "이유가 decision log 에 남는다").
 */
data class FallbackReason(
    /** 실패한 단계 이름. */
    val failedStage: String,
    /** 다음으로 내려간 단계 이름(없으면 null = 마지막 단계까지 실패). */
    val nextStage: String?,
    /** 실패 원인(진단용). */
    val cause: Throwable,
) {
    init {
        require(failedStage.isNotBlank()) { "failedStage 는 비어 있을 수 없다" }
    }

    /** 로그 문구(원문 비포함 — 단계명과 예외 타입/메시지만). */
    val logMessage: String
        get() =
            "정책 단계 '$failedStage' 실패(${cause::class.simpleName}: ${cause.message})" +
                (nextStage?.let { " → '$it' 로 하강" } ?: " → 더 내려갈 단계 없음(안전 IGNORE)")
}

/**
 * 마지막 단계마저 실패했을 때 쓰는 계약상 안전 응답(IGNORE 1.0). always-silent port 가 어떤 이유로든 예외를
 * 내도 발화로 이어지지 않도록, 체인이 직접 합성하는 최종 안전망이다.
 */
private object SilentFallbackResponse {
    val IGNORE: PolicyDecisionResponse =
        PolicyDecisionResponse(
            actionWeights = mapOf(SocialActionKind.IGNORE to 1.0),
            targetDistribution = ActionTargetDistribution.none(resolverVersion = "fallback-silent-1"),
            delayDistribution = DelayDistribution.NEVER,
            socialActWeights = emptyMap(),
            burstProfile = BurstProfile.singleLine(),
            uncertainty = 1.0,
            modelVersion = "fallback-silent-1",
        )
}
