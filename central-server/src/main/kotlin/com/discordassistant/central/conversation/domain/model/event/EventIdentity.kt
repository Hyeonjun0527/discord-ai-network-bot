package com.discordassistant.central.conversation.domain.model.event

/**
 * 정규화 이벤트의 멱등성 키 구성 규칙(NEXA-P03-T006, 순수 도메인).
 *
 * Discord Gateway 는 같은 이벤트를 **여러 번**(at-least-once: 재연결·resume·중복 디스패치) 보낼 수 있다.
 * 같은 Gateway 이벤트를 재수신하면 **같은 [EventId]** 가, 같은 메시지의 다른 편집(revision)이면 **다른 [EventId]**
 * 가 나와야 dedup 과 편집 추적이 동시에 안전하다. 이 객체는 그 키 조합 규칙([discordId] + [type] + [revision])을
 * 한곳에 캡슐화해 어댑터(platform/discord/nexa)가 [EventId] 를 결정론적으로 만들도록 한다.
 *
 * 순수성: conversation.domain 규칙(NexaArchitectureTest.nexaDomainsArePure)을 위해 Spring/JPA/JDA/adapter
 * 타입을 일절 참조하지 않는다. Discord snowflake 는 원시 [Long] 으로만 운반한다(JDA snowflake 타입 금지).
 *
 * **acceptance(T006)**: 같은 Gateway 이벤트 재수신 → 같은 [EventId]([eventId] 가 결정론적). 같은 대상의 다른
 * revision → 다른 [EventId](편집 충돌 없음). [type] 이 다르면(같은 메시지의 생성 vs 삭제) 충돌하지 않는다.
 */
data class EventIdentity(
    /** 이벤트가 가리키는 Discord 고유 객체 ID(메시지/유저 등 대상 snowflake). 재수신 dedup 의 1차 키. */
    val discordId: Long,
    /** 이벤트 종류 — 같은 discordId 의 생성/수정/삭제/리액션 등을 구분해 키 충돌을 막는다. */
    val type: EventType,
    /**
     * 같은 대상·같은 종류 안에서의 개정 번호(편집 순번/리액션 차수 등). 기본 0.
     * 다른 revision 은 다른 키라 같은 메시지의 서로 다른 편집이 dedup 으로 뭉개지지 않는다.
     */
    val revision: Long = 0,
) {
    init {
        require(revision >= 0) { "revision 은 음수일 수 없다" }
    }

    /**
     * 결정론적 멱등성 키. 같은 Gateway 이벤트(같은 discordId+type+revision)면 항상 같은 문자열이다.
     * 구분자(`:`)로 필드를 합쳐 어느 한 필드만 달라도 키가 갈라지게 한다(접두 충돌 방지).
     */
    fun key(): String = "${type.wireName}:$discordId:$revision"

    /** 이 식별 규칙으로부터 정규화 이벤트의 고유 [EventId] 를 만든다(어댑터가 봉투에 채운다). */
    fun toEventId(): EventId = EventId(key())
}

/**
 * 정규화 이벤트 종류 식별자(순수 도메인 enum). [EventIdentity] 의 키 구분과 멱등성에 쓰인다.
 *
 * [wireName] 은 키 문자열에 들어가는 안정 라벨이다 — enum 이름이 바뀌어도 키 호환을 유지하려면 이 값만 고정한다.
 */
enum class EventType(
    val wireName: String,
) {
    MESSAGE_CREATED("msg.created"),
    MESSAGE_UPDATED("msg.updated"),
    MESSAGE_DELETED("msg.deleted"),
    REACTION("reaction"),
    TYPING_STARTED("typing.started"),
    MEMBER_IDENTITY_CHANGED("member.identity"),
    CONSENT_DENIED("consent.denied"),
}
