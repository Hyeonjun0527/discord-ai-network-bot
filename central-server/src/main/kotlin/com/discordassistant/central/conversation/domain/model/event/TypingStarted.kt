package com.discordassistant.central.conversation.domain.model.event

import java.time.Instant

/**
 * 타이핑 시작 정규화 이벤트(NEXA-P02-T020). 어떤 actor 가 채널에서 입력을 시작했다는 관찰 사실.
 *
 * 봉투([NormalizedDiscordEvent]) 공통 필드 + 타이핑 고유 필드(actor·시작/만료 시각)만 운반한다.
 *
 * **acceptance(T020) — 중요한 의미 경계**: typing 은 **메시지 내용도, 응답 의무도 아니다**. 누군가 타이핑
 * 중이라는 신호일 뿐이며, conversation 은 관찰만 한다(무엇을 할지는 participation 소유, conversation-context.md
 * 불변식 2). 따라서 이 이벤트로부터 "응답해야 한다" 거나 "메시지가 곧 온다" 를 단정하면 안 된다 —
 * typing 은 자주 만료되고([expiresAt]) 메시지 없이 사라질 수 있다. privacyClass 는 LOW(원문 없음).
 *
 * 근거: conversation-context.md(관찰만, 행동 비결정), domain-events.md.
 */
data class TypingStarted(
    override val eventId: EventId,
    override val guildId: GuildId,
    override val channelId: ChannelId,
    override val occurredAt: Instant,
    override val receivedAt: Instant,
    override val sourceSequence: Long,
    override val privacyClass: PrivacyClass,
    /** 입력을 시작한 actor. */
    val actorId: AuthorId,
    /** 타이핑 시작 시각(= [occurredAt] 와 동일 의미; 명시 필드로 가독성 확보). */
    val startedAt: Instant,
    /**
     * 타이핑 신호 만료 시각. 이 시각이 지나면 신호는 무효다 — 메시지가 오지 않을 수 있고, 응답 의무를
     * 의미하지 않는다(타이핑은 곧 메시지/응답 보장이 아님).
     */
    val expiresAt: Instant,
) : NormalizedDiscordEvent {
    init {
        require(!expiresAt.isBefore(startedAt)) { "expiresAt 은 startedAt 이전일 수 없다" }
    }

    /**
     * 주어진 시각 [at] 기준으로 이 타이핑 신호가 이미 만료됐는가.
     *
     * 만료된 typing 은 "곧 메시지가 온다" 는 근거가 되지 못한다(응답 의무로 오해 금지).
     */
    fun isExpiredAt(at: Instant): Boolean = !at.isBefore(expiresAt)
}
