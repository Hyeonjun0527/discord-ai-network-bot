package com.discordassistant.central.conversation.domain.model.event

import java.time.Instant

/**
 * platform/discord 어댑터가 변환한 `DiscordEventEnvelope`를 받아 conversation 이 소유하는 정규화 이벤트의
 * **공통 봉투**(sealed 베이스). 구체 이벤트(MessageCreated/Updated/Deleted/Typing/Reaction)는 후속 task
 * (T016~)에서 이 봉투를 상속해 각자 파일로 추가한다 — 여기서는 베이스 + 공통 필드만 둔다(KISS).
 *
 * Kotlin 의 sealed 계층은 **같은 컴파일 모듈** 안에서만 상속 가능하다. main 과 test 는 별도 모듈이므로 봉투
 * 계약(동등성·순서·직렬화)을 결정론적으로 증명하려면 봉투와 함께 최소 1개의 허용 구현이 main 에 있어야 한다.
 * [GenericObservedEvent] 가 그 최소 seed 다 — 특정 Discord 이벤트가 아니라 봉투 공통 필드만 운반하는 일반 관찰
 * 이벤트이며, T016~ 의 구체 이벤트와 역할이 겹치지 않는다(relay `Frame` sealed + 구체 subtype 동거 패턴과 동일).
 *
 * 근거: conversation-context.md(conversation=관찰, 정규화 이벤트 소유), domain-events.md(이벤트 카탈로그·PII 등급),
 * discord-adapter-boundary.md(JDA→DiscordEventEnvelope 정규화 후 도메인은 JDA 타입을 보지 않는다).
 *
 * 순수성: conversation.domain 규칙(NexaArchitectureTest.nexaDomainsArePure)을 위해 Spring/JPA/JDA/adapter 타입을
 * 일절 참조하지 않는다. 식별자는 순수 도메인 value class([EventId]/[GuildId]/[ChannelId])로 운반한다(JDA snowflake
 * 타입 금지). [Instant] 만 표준 라이브러리 시각으로 사용한다.
 *
 * 결정론: 같은 필드면 [equals]/[hashCode] 가 동일(데이터 클래스). 순서 비교는 [chronology] Comparator 가
 * `sourceSequence → occurredAt` 우선순위로 전순서를 제공한다(동일 sourceSequence 면 occurredAt 으로 안정 정렬).
 */
sealed interface NormalizedDiscordEvent {
    /** 이 이벤트의 고유 식별자(멱등성·dedup 기준; domain-events.md `discordMessageId + eventType` 계열). */
    val eventId: EventId

    /** 이벤트가 일어난 길드. */
    val guildId: GuildId

    /** 이벤트가 일어난 채널. */
    val channelId: ChannelId

    /** Discord 에서 실제로 일어난 시각(원천 타임스탬프). */
    val occurredAt: Instant

    /** conversation 수집 경계가 이벤트를 수신한 시각(관찰 시각). */
    val receivedAt: Instant

    /** 같은 채널 내 결정론적 순서 키(어댑터가 부여하는 단조 증가 수신 순번). */
    val sourceSequence: Long

    /** 이 이벤트가 운반하는 데이터의 개인정보 등급(data-categories.md). */
    val privacyClass: PrivacyClass

    companion object {
        /**
         * 정규화 이벤트의 결정론적 전순서 비교자.
         *
         * 1차 키: [sourceSequence](어댑터 수신 순번) — 단조 증가라 동일 채널 순서를 결정한다.
         * 2차 키: [occurredAt] — 동일 sourceSequence 면 발생 시각으로 안정 정렬(타이브레이크).
         */
        val chronology: Comparator<NormalizedDiscordEvent> =
            compareBy<NormalizedDiscordEvent> { it.sourceSequence }
                .thenBy { it.occurredAt }
    }
}

/**
 * 봉투 공통 필드만 가진 최소 일반 관찰 이벤트(sealed seed).
 *
 * 특정 Discord 이벤트 종류(message/typing/reaction 등)가 아니라 봉투 계약 자체를 운반·증명하기 위한 일반 이벤트다.
 * 구체 이벤트는 T016~ 에서 별도 타입으로 추가한다. 데이터 클래스라 동등성/직렬화 구조 비교가 결정론적이다.
 */
data class GenericObservedEvent(
    override val eventId: EventId,
    override val guildId: GuildId,
    override val channelId: ChannelId,
    override val occurredAt: Instant,
    override val receivedAt: Instant,
    override val sourceSequence: Long,
    override val privacyClass: PrivacyClass,
) : NormalizedDiscordEvent

/** 정규화 이벤트의 고유 식별자(순수 도메인 value type; JDA snowflake 타입 금지). */
@JvmInline
value class EventId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "EventId 는 비어 있을 수 없다" }
    }
}

/** 길드(서버) 식별자(순수 도메인 value type; Discord snowflake 를 원시 Long 으로만 운반). */
@JvmInline
value class GuildId(
    val value: Long,
)

/** 채널 식별자(순수 도메인 value type; Discord snowflake 를 원시 Long 으로만 운반). */
@JvmInline
value class ChannelId(
    val value: Long,
)
