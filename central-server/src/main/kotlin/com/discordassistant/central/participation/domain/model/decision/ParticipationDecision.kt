package com.discordassistant.central.participation.domain.model.decision

import com.discordassistant.central.participation.domain.model.action.SocialAction
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.service.CalibrationRecord
import com.discordassistant.central.participation.domain.service.SampledPolicyOutcome

/**
 * 참여 결정 aggregate(NEXA-P08-T022, 순수 도메인 값 객체·불변). 한 번의 평가가 낸 **단 하나의 행동**과 그 결정을
 * **사후 재현** 하는 데 필요한 모든 근거를 묶는다 — raw distribution, calibration 근거, 적용된 안전 제약,
 * sampled outcome, 최종 [SocialAction], 그리고 provenance(contextVersion·model/feature/seed).
 *
 * **acceptance(T022) — 모든 live 행동을 사후 재현할 정보가 있다**:
 * - **raw distribution**([rawDistribution]): 모델이 낸 원분포(보정 전).
 * - **calibration**([calibrationRecord]): 어떤 modifier 가 raw 를 덮었는지(T020 — decision log 의 override 근거).
 * - **applied constraints**([removedKinds]): 안전 후처리가 제거한 action kind(T021).
 * - **sampled outcome**([sampled]): seed 로 접은 구체 선택(T019 — 같은 seed·분포면 재현).
 * - **provenance**: [modelVersion]·[featureVectorVersion]·[seed]·[contextVersion] — 어떤 모델·feature 버전·seed·
 *   context 버전에서 나온 결정인지. 이 넷 + raw distribution 이면 sampled·final 을 결정론적으로 재현할 수 있다.
 * - **correlation ID**([correlationId]): decision log ↔ requestlog 연결 키(원문 비공유, logging-boundary.md).
 *
 * IGNORE 도 정상 결정이라 이 aggregate 로 기록된다(participation-context.md 불변식 3, quota-boundary.md 불변식 1).
 *
 * 순수성: Spring/JPA/JDA 미참조. 도메인 타입만.
 */
data class ParticipationDecision(
    /** decision log ↔ requestlog 연결 상관 식별자(원문 비포함). */
    val correlationId: String,
    /** 정책 무효화 추적 context 버전(이 버전 기준 결정임을 고정 — 재현 키). */
    val contextVersion: Long,
    /** 모델이 낸 원분포(보정 전). 재현의 출발점. */
    val rawDistribution: ActionDistribution,
    /** calibration 근거(T020) — raw 가 운영 규칙으로 덮였는지의 사후 증거. */
    val calibrationRecord: CalibrationRecord,
    /** 안전 후처리(T021)가 제거한 action kind 들(무엇이 하드 차단됐는지). */
    val removedKinds: Set<SocialActionKind>,
    /** seed 로 분포를 접은 구체 선택(T019). */
    val sampled: SampledPolicyOutcome,
    /** 이번 평가의 **단 하나의** 최종 행동(불변식 1). */
    val finalAction: SocialAction,
    /** 결정을 만든 모델 버전(재현·shadow 비교). */
    val modelVersion: String,
    /** 입력 feature 벡터 버전(코드·데이터셋 동기화 — 재현 시 같은 feature schema). */
    val featureVectorVersion: Int,
    /** 결정론 seed — 같은 seed·같은 raw distribution 이면 같은 sampled/final. */
    val seed: Long,
) {
    init {
        require(correlationId.isNotBlank()) { "correlationId 는 비어 있을 수 없다" }
        require(contextVersion >= 0) { "contextVersion 은 음수일 수 없다: $contextVersion" }
        require(modelVersion.isNotBlank()) { "modelVersion 은 비어 있을 수 없다" }
        require(featureVectorVersion >= 1) { "featureVectorVersion 은 1 이상이어야 한다: $featureVectorVersion" }
        // final 행동 종류는 sampled 행동 종류와 일치해야 한다(접기 결과의 일관성 — 재현 무결성).
        require(finalAction.kind == sampled.action) {
            "finalAction.kind(${finalAction.kind}) 와 sampled.action(${sampled.action}) 이 일치해야 한다"
        }
    }

    /** 이 결정이 LLM(generation) 호출을 유발하는가 — SPEAK 만 true(quota-boundary.md). */
    val consumesGenerationQuota: Boolean
        get() = finalAction.consumesGenerationQuota

    /** 침묵 결정인가(IGNORE) — IGNORE 도 정상 경로이고 로그에 남는다(불변식 1·3). */
    val isIgnore: Boolean
        get() = finalAction.kind == SocialActionKind.IGNORE
}
