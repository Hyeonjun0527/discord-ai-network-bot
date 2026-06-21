package com.discordassistant.central.conversation.domain.model.event

import java.time.Instant

/**
 * 메시지 수정 정규화 이벤트(NEXA-P02-T017). 기존 메시지가 편집됐다는 관찰 사실.
 *
 * Discord 의 MESSAGE_UPDATE 는 **부분 업데이트**다(content 외 embed/pin 등만 바뀌어도 발행). 또한 이전 버전을
 * 항상 알 수 없다(인텐트/캐시 한계). 그래서 [content] 는 [MessageContent](Available/Unavailable) 로 가용 상태를
 * 명시하고, [revision] 으로 동일 메시지의 편집 순서를 결정론적으로 판정한다.
 *
 * 근거: conversation-context.md(정규화 이벤트 edit), domain-events.md.
 *
 * **acceptance(T017)**: 동일 revision 중복 수신(at-least-once)과 역순 revision 도착을 안전하게 다룬다.
 * [supersedes]/[isStaleAgainst] 가 그 규칙을 제공한다(과거/같은 revision 은 현재 상태를 덮어쓰지 않는다).
 */
data class MessageUpdated(
    override val eventId: EventId,
    override val guildId: GuildId,
    override val channelId: ChannelId,
    override val occurredAt: Instant,
    override val receivedAt: Instant,
    override val sourceSequence: Long,
    override val privacyClass: PrivacyClass,
    /** 수정된 메시지의 식별자. */
    val messageId: MessageId,
    /** 동일 메시지의 편집 순번(단조 증가). 같은 messageId 안에서 어느 편집이 최신인지 결정한다. */
    val revision: Long,
    /** 수정 후 텍스트의 가용 상태 — 부분 업데이트라 원문이 없을 수 있음(Unavailable 로 구분). */
    val content: MessageContent,
) : NormalizedDiscordEvent {
    init {
        require(revision >= 0) { "revision 은 음수일 수 없다" }
    }

    /**
     * 같은 메시지의 [other] 편집을 이 편집이 **덮어쓰는가**(더 최신인가).
     *
     * 더 큰 revision 만 최신이다. 동일 revision(중복 수신)이나 더 작은 revision(역순 도착)은 덮어쓰지 않는다 —
     * 멱등·순서 안전. 다른 messageId 면 비교 대상이 아니므로 false.
     */
    fun supersedes(other: MessageUpdated): Boolean = messageId == other.messageId && revision > other.revision

    /**
     * 이 편집이 [other] 대비 **무시해야 하는가**(같거나 과거 revision = stale).
     *
     * 같은 messageId 에서 이 revision 이 other 보다 작거나 같으면 stale(현재 상태를 바꾸면 안 됨).
     */
    fun isStaleAgainst(other: MessageUpdated): Boolean = messageId == other.messageId && revision <= other.revision
}
