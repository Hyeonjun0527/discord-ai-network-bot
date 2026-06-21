package com.discordassistant.central.participation.application.policy

import com.discordassistant.central.participation.application.port.out.PolicyEngineCapabilities

/**
 * 정책 계약 버전 협상(NEXA-P08-T008, application 레이어·순수 함수). 요청이 요구하는 (schema, model) 버전을
 * 결정 엔진([com.discordassistant.central.participation.application.port.out.ParticipationPolicyPort])의 능력과 맞춰
 * 본다. JVM/ONNX/gRPC 어느 어댑터든 같은 협상 규칙을 공유한다(DRY).
 *
 * **acceptance(T008) — 호환되지 않는 모델은 안전하게 shadow-only 또는 fallback 으로 전환된다**:
 * - schema 호환 + model 호환 → [PolicyEngineMode.ACTIVE](결정을 실제로 사용).
 * - schema 호환 + model **불호환** → [PolicyEngineMode.SHADOW_ONLY](돌리되 결과를 행동에 반영하지 않음 — 관측만).
 * - schema **불호환** → [PolicyEngineMode.FALLBACK](엔진을 쓰지 않고 안전한 보수 정책으로 우회).
 *
 * 즉 비호환을 절대 무시하고 실행하지 않는다 — fail-safe(보수)로만 강등한다(quota-boundary·observable-state 정책과
 * 일관: 불확실하면 침묵/관측 쪽으로).
 *
 * 순수성: Spring/JPA/JDA 미참조.
 */
object PolicyVersionNegotiator {
    /**
     * [requestedSchemaVersion]/[requestedModelVersion] 을 엔진 [capabilities] 와 협상해 운영 모드를 정한다.
     *
     * schema 가 안 맞으면 FALLBACK(엔진 미사용), schema 는 맞지만 model 이 안 맞으면 SHADOW_ONLY(관측만),
     * 둘 다 맞으면 ACTIVE.
     */
    fun negotiate(
        capabilities: PolicyEngineCapabilities,
        requestedSchemaVersion: Int,
        requestedModelVersion: String?,
    ): PolicyEngineMode =
        when {
            !capabilities.supportsSchema(requestedSchemaVersion) -> PolicyEngineMode.FALLBACK
            !capabilities.supportsModel(requestedModelVersion) -> PolicyEngineMode.SHADOW_ONLY
            else -> PolicyEngineMode.ACTIVE
        }
}

/**
 * 결정 엔진 운영 모드(NEXA-P08-T008, 협상 결과). 비호환을 안전하게 강등하는 fail-safe 단계다.
 */
enum class PolicyEngineMode {
    /** 호환 — 엔진 결정을 실제 행동에 사용한다. */
    ACTIVE,

    /** model 비호환 — 엔진을 돌리되 결과를 행동에 반영하지 않고 관측/비교만 한다(shadow). */
    SHADOW_ONLY,

    /** schema 비호환 — 엔진을 쓰지 않고 보수적 fallback 정책으로 우회한다. */
    FALLBACK,
    ;

    /** 이 모드의 결정이 실제 행동에 반영되는가 — ACTIVE 만 true. */
    val appliesToBehavior: Boolean
        get() = this == ACTIVE
}
