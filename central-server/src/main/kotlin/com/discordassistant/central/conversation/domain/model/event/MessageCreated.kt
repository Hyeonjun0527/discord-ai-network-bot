package com.discordassistant.central.conversation.domain.model.event

import java.time.Instant

/**
 * 메시지 생성 정규화 이벤트(NEXA-P02-T016). 누군가 채널/스레드에 새 메시지를 보냈다는 관찰 사실.
 *
 * 봉투([NormalizedDiscordEvent]) 공통 필드 + 메시지 고유 필드(작성자·content reference·reply·mentions·
 * attachments metadata·thread)만 운반한다. 순수 도메인이라 JDA/Spring/JPA 타입을 일절 참조하지 않는다
 * (NexaArchitectureTest.nexaDomainsArePure).
 *
 * 근거: conversation-context.md(정규화 이벤트 message), domain-events.md(`EventIngested` PII high).
 *
 * **acceptance(T016)**: "MESSAGE_CONTENT 인텐트 없음(미허용)" 과 "content unavailable(빈/접근 불가)" 는
 * 의미가 다르다. 둘을 단일 null 로 뭉개면 하류(participation/socialmemory)가 "관찰 권한 부재" 와
 * "이 메시지에 텍스트가 없음" 을 구분 못 한다. 그래서 [content] 를 sealed [MessageContent] 로 명시 구분한다.
 */
data class MessageCreated(
    override val eventId: EventId,
    override val guildId: GuildId,
    override val channelId: ChannelId,
    override val occurredAt: Instant,
    override val receivedAt: Instant,
    override val sourceSequence: Long,
    override val privacyClass: PrivacyClass,
    /** 이 메시지의 식별자(reply/edit/delete 가 가리키는 대상 키). */
    val messageId: MessageId,
    /** 작성자(봇/사람 구분 없이 actor 식별자만; 순수 도메인). */
    val authorId: AuthorId,
    /** 메시지 텍스트의 가용 상태 — 미허용/빈/사용 가능을 명시 구분(단일 null 금지). */
    val content: MessageContent,
    /** 답글 대상 메시지(없으면 null). reply 사슬·thread projection 의 근거. */
    val replyTo: MessageId?,
    /** 멘션된 actor 들(순서 보존 X, 집합 의미). */
    val mentions: Set<AuthorId>,
    /** 첨부 파일 metadata(원문 바이트는 운반하지 않는다 — 참조/메타만). */
    val attachments: List<AttachmentMetadata>,
    /** 이 메시지가 속한 스레드(채널 직속이면 null). */
    val threadId: ChannelId?,
) : NormalizedDiscordEvent

/**
 * 메시지 텍스트의 가용 상태(sealed). "권한 없음" 과 "내용 없음" 과 "사용 가능" 을 타입으로 구분한다.
 *
 * - [Unavailable.IntentMissing]: 봇에 MESSAGE_CONTENT 인텐트가 없어 원문을 **볼 권한이 없다**(관찰 한계).
 * - [Unavailable.Empty]: 권한은 있으나 메시지에 텍스트가 없다(첨부만, 또는 빈 본문).
 * - [Available]: 원문 텍스트를 관찰했다(PII high — 봉투의 privacyClass 로 등급 운반).
 */
sealed interface MessageContent {
    /** 원문 텍스트를 관찰함. [text] 는 PII high(봉투 privacyClass 로 등급 운반). */
    data class Available(
        val text: String,
    ) : MessageContent

    /** 원문 텍스트를 운반하지 않음 — 이유를 타입으로 구분(단일 null 금지). */
    sealed interface Unavailable : MessageContent {
        /** MESSAGE_CONTENT 인텐트 부재로 원문을 볼 **권한이 없음**(관찰 한계, content unavailable 과 다름). */
        data object IntentMissing : Unavailable

        /** 권한은 있으나 메시지에 텍스트가 **없음**(첨부만/빈 본문). 인텐트 미허용과 다름. */
        data object Empty : Unavailable
    }
}

/** 첨부 파일 metadata — 원문 바이트가 아니라 참조/메타만(파일명·content type·크기). */
data class AttachmentMetadata(
    /** 첨부 식별자(Discord 첨부 id 를 원시 문자열로 운반; JDA 타입 금지). */
    val attachmentId: String,
    /** 파일명(있으면). */
    val fileName: String?,
    /** content type(예: image/png; 있으면). */
    val contentType: String?,
    /** 바이트 크기(있으면). */
    val sizeBytes: Long?,
)

/** 메시지 식별자(순수 도메인 value type; JDA snowflake 타입 금지). */
@JvmInline
value class MessageId(
    val value: Long,
)

/** actor(작성자/멘션 대상) 식별자(순수 도메인 value type; JDA snowflake 타입 금지). */
@JvmInline
value class AuthorId(
    val value: Long,
)
