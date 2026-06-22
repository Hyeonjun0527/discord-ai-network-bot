package com.discordassistant.central.socialmemory.domain.model.relationship

import java.time.Instant

/**
 * 최근 상호작용 결과 코드(NEXA-P06-T010, 순수 도메인 enum). NEXA 의 행동 직후 **관찰된 결과**를 닫힌 코드 집합으로
 * 정의한다 — 자유 텍스트 심리 판정이 아니다(observable-state-policy 금지: 기분/성격 추론 부재).
 *
 * 각 코드는 직접 관찰 가능한 행동 사실이다([wireName] 은 저장·로깅용 안정 라벨):
 * - [CONTINUED]: 상대가 대화를 이어감(후속 발화).
 * - [IGNORED]: 무응답(관찰된 부재 — "무시당했다는 감정" 이 아니라 응답 없음 사실).
 * - [REACTED]: reaction 이 붙음.
 * - [CORRECTED]: 상대가 정정/반박함.
 * - [COMPLAINED]: 상대가 불만/신고 행동을 함.
 * - [DELETED]: 관련 메시지가 삭제됨.
 *
 * 순수성: Spring/JPA/JDA 미참조.
 */
enum class InteractionOutcome(
    val wireName: String,
) {
    CONTINUED("continued"),
    IGNORED("ignored"),
    REACTED("reacted"),
    CORRECTED("corrected"),
    COMPLAINED("complained"),
    DELETED("deleted"),
    ;

    companion object {
        /** [wireName] 으로 코드를 되찾는다(저장된 값 복원). 알 수 없으면 null. */
        fun fromWireName(wireName: String): InteractionOutcome? = entries.firstOrNull { it.wireName == wireName }
    }
}

/**
 * 관찰된 상호작용 결과 한 건(NEXA-P06-T010, 순수 도메인 값 객체·불변).
 *
 * **acceptance(T010) — 자유 텍스트 심리 판정 없이 source event IDs 를 가진다**:
 * 결과는 닫힌 [InteractionOutcome] 코드 + 결과를 뒷받침하는 **원천 이벤트 ID 목록**([sourceEventIds]) + 관찰 시각만
 * 담는다. 자유 텍스트 설명/심리 라벨 필드가 없다([freeTextJudgmentPresent] 항상 false 가드). 모든 결과는 source
 * event 로 환원·설명 가능하다(observable-state-policy 체크리스트 #7 — 관리자에게 설명 가능).
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 java.time 만 쓴다.
 */
data class ObservedInteractionOutcome(
    /** guild-scoped 가명 관계 키. */
    val key: MemberKey,
    /** 관찰된 결과 코드(닫힌 집합). */
    val outcome: InteractionOutcome,
    /** 이 결과를 뒷받침하는 원천 이벤트 ID 목록(provenance). 비어 있을 수 없다 — 관찰 근거가 있어야 한다. */
    val sourceEventIds: List<String>,
    /** 결과가 관찰된 시각. */
    val observedAt: Instant,
) {
    init {
        require(sourceEventIds.isNotEmpty()) { "결과는 적어도 하나의 source event ID 를 가져야 한다(provenance)" }
        require(sourceEventIds.all { it.isNotBlank() }) { "source event ID 는 비어 있을 수 없다" }
        require(!freeTextJudgmentPresent) {
            "ObservedInteractionOutcome 은 자유 텍스트 심리 판정을 담지 않는다(acceptance T010)"
        }
    }

    /**
     * 자유 텍스트 심리 판정 포함 여부 — **항상 false**. 닫힌 코드 + source event ID 만 담는다는 불변식의 가드다
     * (acceptance T010, observable-state-policy 금지 추론 부재).
     */
    val freeTextJudgmentPresent: Boolean
        get() = false
}
