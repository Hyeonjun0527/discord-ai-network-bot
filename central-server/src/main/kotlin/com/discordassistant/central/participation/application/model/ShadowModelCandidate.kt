package com.discordassistant.central.participation.application.model

import java.time.Instant

/**
 * shadow 모델 후보(NEXA-P11-T020, application 레이어 값 객체·불변). 학습·export 된 정책 모델 artifact 후보를
 * 레지스트리에 등록하는 단위다.
 *
 * **deliverable(T020)**: 모델 ID, artifact hash, feature schema 버전, calibration 버전, [status] 를 담는다.
 *
 * **acceptance(T020) — 승인되지 않은 artifact 는 LIVE 상태로 선택할 수 없다**: [status] 는 단계적으로만 진행하며
 * ([ModelStatus]), LIVE 선택은 [ModelStatus.APPROVED] 후보만 가능하다([ShadowModelRegistry.selectForLive] 가
 * 강제). 등록·shadow 단계 후보는 절대 LIVE 로 자동 승격되지 않는다(human gate 전제 — 안전).
 *
 * 순수성 경계: application 레이어 — 표준 타입만. Spring/JPA/JDA 미참조.
 */
data class ShadowModelCandidate(
    /** 모델 식별자(고유). */
    val modelId: String,
    /** ONNX artifact sha256 hex(무결성 봉인). */
    val artifactSha256: String,
    /** 모델 버전(추론 응답·추적). */
    val modelVersion: String,
    /** 이 모델이 기대하는 feature schema 버전(FeatureCatalog.VERSION 과 일치해야 추론 가능). */
    val featureSchemaVersion: Int,
    /** calibration 버전(보정 식별). */
    val calibrationVersion: String,
    /** 후보 상태(등록/shadow/승인/거부). LIVE 선택 가능 여부를 좌우한다. */
    val status: ModelStatus,
    /** 등록 시각. */
    val registeredAt: Instant,
) {
    init {
        require(modelId.isNotBlank()) { "modelId 는 비어 있을 수 없다" }
        require(artifactSha256.isNotBlank()) { "artifactSha256 은 비어 있을 수 없다" }
        require(modelVersion.isNotBlank()) { "modelVersion 은 비어 있을 수 없다" }
        require(featureSchemaVersion >= 1) { "featureSchemaVersion 은 1 이상이어야 한다: $featureSchemaVersion" }
        require(calibrationVersion.isNotBlank()) { "calibrationVersion 은 비어 있을 수 없다" }
    }

    /** LIVE 로 선택 가능한가 — [ModelStatus.APPROVED] 만 true(미승인 artifact LIVE 금지, acceptance T020). */
    val isSelectableForLive: Boolean
        get() = status == ModelStatus.APPROVED
}

/**
 * 모델 후보 상태(NEXA-P11-T020, application enum). 등록→shadow→승인의 **단계적 게이트**다 — 등록되자마자 LIVE 가
 * 되는 자동 승격은 없다(안전). [REJECTED] 는 영구 차단.
 *
 * | 상태 | 의미 | LIVE 선택 |
 * | --- | --- | --- |
 * | [REGISTERED] | 막 등록됨(아직 평가 전) | 불가 |
 * | [SHADOW] | shadow 비교 대상으로 승인(관측만) | 불가 |
 * | [APPROVED] | 독립 리뷰(human gate) 통과 — LIVE 자격 | **가능** |
 * | [REJECTED] | 기준 미달 — 영구 차단 | 불가 |
 */
enum class ModelStatus {
    /** 등록만 됨. 아직 어떤 비교/승인도 안 됨. */
    REGISTERED,

    /** shadow 비교 대상으로 승인됨(미발화 관측만). LIVE 아님. */
    SHADOW,

    /** human gate 통과 — LIVE 자격을 갖춘 유일한 상태. */
    APPROVED,

    /** 기준 미달로 거부됨(영구 차단). */
    REJECTED,
    ;

    /** 이 상태에서 [to] 로의 전이가 허용되는가. 자동 LIVE 승격을 막는 단계 규칙(T020). */
    fun canTransitionTo(to: ModelStatus): Boolean =
        when (this) {
            // 등록 → shadow 로 승인하거나, 바로 거부 가능. 등록에서 곧장 APPROVED 금지(반드시 shadow 경유).
            REGISTERED -> to == SHADOW || to == REJECTED
            // shadow 비교 후 승인 또는 거부.
            SHADOW -> to == APPROVED || to == REJECTED
            // 승인 후에도 회수(거부) 가능 — 안전 방향(LIVE 자격 박탈).
            APPROVED -> to == REJECTED
            // 거부는 종착(되살리려면 새 후보 등록).
            REJECTED -> false
        }
}

/**
 * 모델 레지스트리 불변식 위반(NEXA-P11-T020). 미승인 LIVE 선택·불법 전이·중복 등록 시 던진다(fail-closed).
 */
class ShadowModelRegistryException(
    message: String,
) : RuntimeException(message)
