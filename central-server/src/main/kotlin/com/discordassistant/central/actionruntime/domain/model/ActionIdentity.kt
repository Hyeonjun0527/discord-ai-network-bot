package com.discordassistant.central.actionruntime.domain.model

/**
 * 예약 행동의 **안정적 idempotency key**(NEXA-P13-T004, 순수 도메인 value object·불변).
 *
 * participation 결정(correlation/decision ID)과 그 결정 안에서 샘플된 행동 index 의 조합으로 **결정론적**이고
 * **안정적**인 key 를 만든다. 같은 결정을 (재시작·중복 이벤트·at-least-once 메시징 등으로) 다시 처리해도 같은 key
 * 가 나오므로, persistence 의 unique 제약이 **중복 예약을 거부**한다.
 *
 * **acceptance(T004) — 같은 decision 재처리로 중복 예약이 생기지 않는다**:
 * - key 는 [decisionId] + [sampledActionIndex] 만의 함수다(Date.now·UUID·random 미포함 — 안정적).
 * - 같은 (decisionId, sampledActionIndex) 면 항상 같은 [value] → DB unique 가 두 번째 insert 를 거부.
 * - 한 결정이 여러 버블(멀티 SPEAK)을 예약하면 index 로 구분된다(같은 decision 안의 서로 다른 행동은 다른 key).
 *
 * 순수성: Spring/JPA/JDA 미참조. 도메인 value object 만.
 */
@JvmInline
value class ActionIdentity private constructor(
    /** 안정적 idempotency key 문자열(`<decisionId>#<index>`). persistence unique 키로 그대로 쓴다. */
    val value: String,
) {
    companion object {
        /**
         * [decisionId] 와 [sampledActionIndex] 로 안정적 key 를 만든다. 같은 입력이면 항상 같은 key(결정론).
         *
         * @param decisionId participation 결정의 안정 식별자(correlationId 등 — 원문 비포함).
         * @param sampledActionIndex 같은 결정 안에서 샘플된 행동의 0-기반 index(단일 SPEAK 면 0).
         */
        fun of(
            decisionId: String,
            sampledActionIndex: Int,
        ): ActionIdentity {
            require(decisionId.isNotBlank()) { "decisionId 는 비어 있을 수 없다" }
            require(sampledActionIndex >= 0) { "sampledActionIndex 는 음수일 수 없다: $sampledActionIndex" }
            return ActionIdentity("$decisionId#$sampledActionIndex")
        }

        /** 이미 영속·전달된 안정 key를 복원한다. CANCEL_PENDING 같은 교차 결정 참조에서 사용한다. */
        fun from(value: String): ActionIdentity {
            require(value.matches(Regex(".{1,240}#[0-9]+"))) { "action identity 형식이 잘못됐다: $value" }
            return ActionIdentity(value)
        }
    }
}
