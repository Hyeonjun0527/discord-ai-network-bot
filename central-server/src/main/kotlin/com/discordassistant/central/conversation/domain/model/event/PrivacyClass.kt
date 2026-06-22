package com.discordassistant.central.conversation.domain.model.event

/**
 * 정규화 이벤트가 운반하는 데이터의 개인정보 등급(NEXA conversation 도메인 소유의 순수 enum).
 *
 * 근거: data-categories.md(High/Medium/Low 분류), domain-events.md(이벤트별 PII 등급).
 * High = 원문 내용/직접 식별자, Medium = 파생 텍스트/안정 식별자, Low = 결정·상태·correlation ID만.
 *
 * conversation.domain 순수성 규칙(NexaArchitectureTest.nexaDomainsArePure)을 지키기 위해 Spring/JPA/JDA/adapter
 * 타입을 일절 참조하지 않는다.
 */
enum class PrivacyClass {
    /** 원문 메시지 내용/직접 식별자를 담는다 — 최소 수집·최단 보존·삭제 보장. */
    HIGH,

    /** 원문에서 파생됐거나 안정 식별자다 — 가명화·보존 한도·외부 전송 제한. */
    MEDIUM,

    /** 결정·상태·correlation ID만. 원문/파생 텍스트 없음 — 운영 보존 가능. */
    LOW,
}
