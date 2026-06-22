package com.discordassistant.central.participation.application.port.out

/**
 * 참여 결정 엔진 아웃바운드 포트(NEXA-P08, 헥사고날). participation 이 "어떤 행동을 할지" 의 확률분포를 얻기 위해
 * 호출하는 결정 엔진 추상이다. 구현 어댑터는 JVM(규칙 기반)·ONNX(로컬 모델)·gRPC(원격) 중 하나일 수 있다
 * (module-dag.md: participation→speech/actionruntime 구현 의존 금지지만 결정 엔진은 자기 소유 포트다).
 *
 * 순수성 경계: application 레이어 — 계약 값 객체([PolicyDecisionRequest]/[PolicyDecisionResponse])만 본다.
 * Spring/JPA/JDA 미참조(어댑터가 채운다).
 */
interface ParticipationPolicyPort {
    /** 지원하는 (schema, model) 버전 능력 — 버전 협상(T008)에 쓰인다. */
    fun capabilities(): PolicyEngineCapabilities

    /**
     * [request] 에 대한 행동 확률분포를 돌려준다. 호출 전 버전 협상으로 호환이 확인됐다고 가정한다(비호환은
     * [com.discordassistant.central.participation.application.policy.PolicyVersionNegotiator] 가 fallback/shadow 로 전환).
     */
    fun decide(request: PolicyDecisionRequest): PolicyDecisionResponse
}

/**
 * 결정 엔진의 버전 능력(application 값 객체). 어떤 요청 schema 버전과 model 버전을 지원하는지 노출한다 —
 * 버전 협상(T008)의 입력이다.
 */
data class PolicyEngineCapabilities(
    /** 지원하는 요청 schema 버전 집합(비어 있으면 능력 미상 — 협상이 안전 모드로 처리). */
    val supportedSchemaVersions: Set<Int>,
    /** 지원하는 model 버전 집합(비어 있으면 model 무관). */
    val supportedModelVersions: Set<String>,
) {
    fun supportsSchema(version: Int): Boolean = version in supportedSchemaVersions

    fun supportsModel(version: String?): Boolean = version == null || version in supportedModelVersions
}
