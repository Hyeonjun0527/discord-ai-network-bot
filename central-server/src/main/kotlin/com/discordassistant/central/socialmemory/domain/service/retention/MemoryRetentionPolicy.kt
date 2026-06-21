package com.discordassistant.central.socialmemory.domain.service.retention

import java.time.Duration

/**
 * 기억 **유형별 TTL(보존 기간)** 의 단일 출처(NEXA-P07-T012, 순수 도메인 정책·retention-policy.md).
 *
 * 어떤 유형도 영구 보존하지 않는다(retention-policy 불변식 1). 일화·관계 사건은 비교적 짧게, 안정 사실(TemporalFact)은
 * 좀 더 길게, 미완 의도는 가장 짧게 둔다. TTL 종료 책임자는 socialmemory(만료 스윕)다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 java.time 만 쓴다.
 */
enum class MemoryRetentionPolicy(
    /** 이 유형의 기본 TTL. occurredAt/validFrom 기준 이 기간이 지나면 만료 후보다. */
    val ttl: Duration,
) {
    /** 일화(시점 사건) — 중기. */
    EPISODIC(Duration.ofDays(90)),

    /** 안정 사실 — 장기(여전히 영구 아님). 변하면 supersession 으로 닫힘. */
    TEMPORAL_FACT(Duration.ofDays(365)),

    /** 관계 사건 기록 — 중기(상태는 별도 decay). */
    RELATIONSHIP(Duration.ofDays(90)),

    /** 미완 의도 — 단기(오래 안 하면 만료). */
    PENDING_INTENT(Duration.ofDays(30)),
    ;

    /** [from] 시점에 만들어진 이 유형 기억의 만료 시각. */
    fun expiryFrom(from: java.time.Instant): java.time.Instant = from.plus(ttl)
}
