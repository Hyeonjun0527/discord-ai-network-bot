package com.discordassistant.central.conversation.domain.model.event

import java.time.Instant

/**
 * 메시지 삭제 정규화 이벤트(NEXA-P02-T018). 메시지가 삭제됐다는 관찰 사실.
 *
 * Discord 의 MESSAGE_DELETE 는 **원문을 싣지 않는다**(messageId 만 온다). 이 이벤트는 원문 없이도 provenance
 * (그 메시지에서 파생된 burst/scene/관계)를 **무효화**할 수 있는 최소 키([messageId])만 운반한다. 그래서 봉투
 * privacyClass 는 보통 LOW(원문/파생 텍스트 없음)다.
 *
 * 근거: conversation-context.md(정규화 이벤트 delete), domain-events.md(원문 비전파).
 *
 * **acceptance(T018)**: content 를 요구하지 않고 **idempotent** 하게 적용된다 — 같은 messageId 삭제를 여러 번
 * 받아도 결과가 같다([targets] 로 동일 대상 판정). data class 라 동일 키면 equals 가 동일.
 */
data class MessageDeleted(
    override val eventId: EventId,
    override val guildId: GuildId,
    override val channelId: ChannelId,
    override val occurredAt: Instant,
    override val receivedAt: Instant,
    override val sourceSequence: Long,
    override val privacyClass: PrivacyClass,
    /** 삭제된 메시지의 식별자 — provenance 무효화의 유일한 키(원문 불필요). */
    val messageId: MessageId,
) : NormalizedDiscordEvent {
    /**
     * 이 삭제가 주어진 [messageId] 를 대상으로 하는가.
     *
     * 같은 messageId 삭제를 중복 수신해도 무효화 대상은 동일하다(idempotent 적용의 근거).
     */
    fun targets(messageId: MessageId): Boolean = this.messageId == messageId
}
