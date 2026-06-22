package com.discordassistant.central.participation.application.port.out

/**
 * 정책 결정 요청 계약(NEXA-P08-T006, application 레이어 값 객체·불변).
 *
 * participation 이 결정 엔진([ParticipationPolicyPort], JVM/ONNX/gRPC adapter)에 보내는 **입력 계약** 이다.
 * 결정에 필요한 참조·feature·설정만 담는다 — 장면 snapshot 참조, 사회 상태 feature, 유효 기억 feature,
 * 설정(채널 모드·정책), 모델/스키마 버전, 결정론 seed.
 *
 * **acceptance(T006) — Discord 원문과 JPA entity가 계약에 직접 포함되지 않는다**:
 * - 어떤 필드도 원문 텍스트를 담지 않는다([sceneSnapshotRef] 는 식별 참조, feature 는 수치/코드뿐).
 * - JPA/Spring/JDA 타입을 참조하지 않는다(application 레이어 — 표준·도메인/값 타입만). 어댑터가 직렬화한다.
 *
 * SSOT 미러: contracts/policy/policy-decision-request.schema.json (외부 ONNX/gRPC adapter 와 공유하는 JSON 형태).
 *
 * 순수성 경계: application 레이어라 표준 타입·feature 맵만 본다 — Spring/JPA/JDA 미참조.
 */
data class PolicyDecisionRequest(
    /** 장면 snapshot 식별 참조(원문 비포함) — 어느 장면 버전에 대한 결정인지. */
    val sceneSnapshotRef: SceneSnapshotRef,
    /** 정규화된 feature 벡터(이름→값, 원문 비포함). burst/thread/tempo/relationship/memory feature 의 합집합. */
    val features: FeatureVectorView,
    /** 결정 설정(채널 모드·자동응답·정책 게이트) — 코드/플래그뿐. */
    val config: PolicyConfigView,
    /** 결정 엔진이 사용해야 할 모델 버전(없으면 null=구현 기본). 버전 협상(T008)에 쓰인다. */
    val modelVersion: String?,
    /** 계약 스키마 버전(요청 형태의 버전). 버전 협상(T008)에 쓰인다. */
    val schemaVersion: Int,
    /** 결정론 seed — 같은 seed·같은 입력이면 같은 결정(재현·shadow 비교). */
    val seed: Long,
) {
    init {
        require(schemaVersion >= 1) { "schemaVersion 은 1 이상이어야 한다: $schemaVersion" }
    }
}

/**
 * 장면 snapshot 식별 참조(application 값 객체). 원문을 담지 않고 어느 장면 버전인지 가리킨다.
 */
data class SceneSnapshotRef(
    /** 길드 가명(원시 식별 — snowflake 타입 비포함). */
    val guildPseudonym: String,
    /** 채널 식별자(원시 String). */
    val channelId: String,
    /** 장면 순번(채널 내 단조 증가). */
    val sceneSeq: Long,
    /** 정책 무효화 추적 context 버전 — 이 버전 기준 결정임을 고정. */
    val contextVersion: Long,
) {
    init {
        require(guildPseudonym.isNotBlank()) { "guildPseudonym 은 비어 있을 수 없다" }
        require(channelId.isNotBlank()) { "channelId 는 비어 있을 수 없다" }
        require(sceneSeq >= 0) { "sceneSeq 는 음수일 수 없다" }
        require(contextVersion >= 0) { "contextVersion 은 음수일 수 없다" }
    }
}

/**
 * 결정 설정 뷰(application 값 객체). 채널 AI 모드·자동응답·정책 게이트를 **코드/플래그** 로만 운반한다(원문 없음).
 */
data class PolicyConfigView(
    /** 채널 AI 모드 코드(예: off/mention/auto) — 안정 코드 문자열. */
    val channelMode: String,
    /** 자동응답이 켜졌는가. */
    val autoRespondEnabled: Boolean,
    /** feature gate(라이선스 등)로 발화가 허용되는가 — false 면 정책이 SPEAK 를 막아야 한다. */
    val speechAllowed: Boolean,
) {
    init {
        require(channelMode.isNotBlank()) { "channelMode 는 비어 있을 수 없다" }
    }
}
