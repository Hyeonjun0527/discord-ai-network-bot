package com.discordassistant.central.participation.application.port.out

import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import java.time.Instant

/**
 * 참여 결정 로그 아웃바운드 포트(NEXA-P08-T023, application 레이어). participation 의 모든 결정(IGNORE 포함)을
 * **원문 없이** feature hash 와 결정 provenance 로 영속화한다(logging-boundary.md: 원문/프롬프트/응답 본문 금지).
 *
 * **acceptance(T023) — IGNORE 도 저장되며 보존/삭제 정책이 적용된다**:
 * - [append] 는 action kind 와 무관하게 저장한다(IGNORE 도 추적 — participation 불변식 1·3).
 * - [purgeExpired] 가 보존 기간이 지난 로그를 삭제한다(보존 정책).
 *
 * 순수성 경계: application 레이어 — 도메인 코드·표준 타입만. Spring/JPA/JDA 미참조(어댑터가 채운다).
 */
interface ParticipationDecisionLogPort {
    /** 결정 로그 한 건을 추가한다(IGNORE 포함). 같은 correlationId 재기록은 멱등(중복 1건). */
    fun append(record: DecisionLogRecord)

    /** [correlationId] 의 로그를 조회한다(없으면 null) — 사후 재현·감사용. */
    fun findByCorrelationId(correlationId: String): DecisionLogRecord?

    /** [olderThan] 시점보다 오래된(보존 기간 초과) 로그를 삭제하고 삭제 건수를 돌려준다(보존 정책). */
    fun purgeExpired(olderThan: Instant): Int
}

/**
 * 결정 로그 레코드(application 값 객체·불변). **원문 비포함** — feature hash + 결정 provenance(근거 feature 의
 * 해시·결정 종류·버전·seed·correlation)만. Discord 식별자는 가명 토큰만 담는다(logging-boundary.md).
 */
data class DecisionLogRecord(
    /** decision log ↔ requestlog 연결 키(원문 비포함). */
    val correlationId: String,
    /** 길드 가명(snowflake 원문 비포함). */
    val guildPseudonym: String,
    /** 채널 식별자(가명/원시 String — 원문 비포함). */
    val channelId: String,
    /** 정책 무효화 추적 context 버전. */
    val contextVersion: Long,
    /** 최종 결정 종류(IGNORE 포함 — 안정 코드). */
    val actionKind: SocialActionKind,
    /**
     * 근거 feature 벡터의 **해시**(원문/원시 feature 값 비저장 — feature hash 만, acceptance T023). 같은 입력이면
     * 같은 해시라 재현·diff 에 쓰되 원본 feature 를 복원하지는 않는다.
     */
    val featureHash: String,
    /** 입력 feature 벡터 버전(재현 시 같은 schema). */
    val featureVectorVersion: Int,
    /** 결정을 만든 모델 버전. */
    val modelVersion: String,
    /** 결정론 seed(재현 키). */
    val seed: Long,
    /** 하드 제약으로 제거된 action kind 들(안전 후처리 근거, T021). */
    val removedKinds: Set<SocialActionKind>,
    /** 이 결정이 generation quota 를 소모했는가(SPEAK 만 true). */
    val consumedGenerationQuota: Boolean,
    /** 결정 발생 시각(보존/삭제 정책의 기준). */
    val decidedAt: Instant,
) {
    init {
        require(correlationId.isNotBlank()) { "correlationId 는 비어 있을 수 없다" }
        require(guildPseudonym.isNotBlank()) { "guildPseudonym 은 비어 있을 수 없다" }
        require(channelId.isNotBlank()) { "channelId 는 비어 있을 수 없다" }
        require(featureHash.isNotBlank()) { "featureHash 는 비어 있을 수 없다" }
        require(modelVersion.isNotBlank()) { "modelVersion 은 비어 있을 수 없다" }
        require(featureVectorVersion >= 1) { "featureVectorVersion 은 1 이상이어야 한다" }
        require(contextVersion >= 0) { "contextVersion 은 음수일 수 없다" }
    }
}
