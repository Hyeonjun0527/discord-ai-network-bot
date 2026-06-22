package com.discordassistant.central.conversation.domain.model.event

import java.time.Instant

/**
 * 리액션 정규화 이벤트(NEXA-P02-T019). 어떤 actor 가 메시지에 이모지 반응을 추가/삭제했다는 관찰 사실.
 *
 * 봉투([NormalizedDiscordEvent]) 공통 필드 + 리액션 고유 필드(추가/삭제 [change]·emoji identity·actor·target
 * message)만 운반한다. 순수 도메인이라 JDA 타입을 일절 참조하지 않는다.
 *
 * 근거: conversation-context.md(정규화 이벤트 reaction), domain-events.md.
 *
 * **acceptance(T019)**: 일반/버스트 리액션 차이를 [intensity] 로 보존하고(여러 actor 가 짧게 몰리는 버스트 vs
 * 단발), 같은 (message,emoji,actor,change) 의 중복 수신은 [isSameChangeAs] 로 안전하게 판정(idempotent).
 */
data class Reaction(
    override val eventId: EventId,
    override val guildId: GuildId,
    override val channelId: ChannelId,
    override val occurredAt: Instant,
    override val receivedAt: Instant,
    override val sourceSequence: Long,
    override val privacyClass: PrivacyClass,
    /** 반응이 달린 대상 메시지. */
    val messageId: MessageId,
    /** 반응을 한 actor. */
    val actorId: AuthorId,
    /** 어떤 이모지인가(unicode/커스텀 구분). */
    val emoji: EmojiIdentity,
    /** 추가인가 삭제인가. */
    val change: ReactionChange,
    /** 단발인가 버스트의 일부인가 — 일반/버스트 리액션 차이 보존. */
    val intensity: ReactionIntensity,
) : NormalizedDiscordEvent {
    /**
     * [other] 와 동일한 리액션 변경인가(같은 메시지·이모지·actor·방향).
     *
     * intensity 는 분류 신호일 뿐 동일성 판정에서 제외한다 — 같은 변경이면 중복 수신으로 보고 멱등 적용한다.
     */
    fun isSameChangeAs(other: Reaction): Boolean =
        messageId == other.messageId &&
            actorId == other.actorId &&
            emoji == other.emoji &&
            change == other.change
}

/** 리액션 추가/삭제 방향. */
enum class ReactionChange {
    /** 이모지 반응 추가. */
    ADDED,

    /** 이모지 반응 제거. */
    REMOVED,
}

/** 일반 단발 리액션과 짧은 시간에 몰리는 버스트 리액션을 구분(차이 보존). */
enum class ReactionIntensity {
    /** 단발(일반) 리액션. */
    SINGLE,

    /** 짧은 간격에 몰린 버스트 리액션(여러 반응의 일부). */
    BURST,
}

/** 이모지의 정체성(unicode 이모지 또는 커스텀 길드 이모지). 순수 도메인 sealed. */
sealed interface EmojiIdentity {
    /** unicode 이모지(예: "👍"). 코드포인트 문자열로 운반. */
    data class Unicode(
        val codepoints: String,
    ) : EmojiIdentity

    /** 커스텀(길드) 이모지 — 식별자로 운반(JDA 타입 금지). */
    data class Custom(
        val customEmojiId: Long,
        val name: String?,
    ) : EmojiIdentity
}
