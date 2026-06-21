package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.conversation.domain.model.event.AttachmentMetadata
import com.discordassistant.central.conversation.domain.model.event.AuthorId
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.EventIdentity
import com.discordassistant.central.conversation.domain.model.event.EventType
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.MessageContent
import com.discordassistant.central.conversation.domain.model.event.MessageCreated
import com.discordassistant.central.conversation.domain.model.event.MessageId
import com.discordassistant.central.conversation.domain.model.event.PrivacyClass
import net.dv8tion.jda.api.entities.MessageType
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import java.time.Instant

/**
 * MessageCreated 매퍼(NEXA-P03-T003). JDA [MessageReceivedEvent] → 정규화 [MessageCreated].
 *
 * 추출 단계만 JDA 를 보고(guild/channel/thread/reply/mention/attachment metadata/content 가용성), 매핑 단계는
 * 순수 [MessageCreatedSnapshot] → 도메인 이벤트라 JDA 없이 테스트된다(불변식 1). [toEvent] 는 스냅샷만의 순수
 * 함수다 — receivedAt/sourceSequence 는 스냅샷이 운반(추출 단계 주입값)하므로 매퍼가 시각/순번을 하드코딩하지 않는다.
 *
 * **acceptance(T003)**: 봇/웹훅/시스템/사람 메시지의 출처를 [MessageSourceType] 으로 구분해 보존한다(단일 isBot 로
 * 뭉개지 않음). content 가용성은 인텐트 없음/빈/사용 가능을 [MessageContent] 로 명시 구분한다.
 */
class JdaMessageEventMapper : DiscordEventMapper<JdaMessageEventMapper.Input, MessageCreatedSnapshot> {
    /** 추출 입력 — JDA 이벤트 + 어댑터 주입 수집 메타(receivedAt·sourceSequence). */
    data class Input(
        val jdaEvent: MessageReceivedEvent,
        val receivedAt: Instant,
        val sourceSequence: Long,
    )

    override fun extract(jdaEvent: Input): MessageCreatedSnapshot {
        val event = jdaEvent.jdaEvent
        val message = event.message
        val sourceType =
            when {
                message.type != MessageType.DEFAULT && message.type != MessageType.INLINE_REPLY -> MessageSourceType.SYSTEM
                message.isWebhookMessage -> MessageSourceType.WEBHOOK
                event.author.isBot -> MessageSourceType.BOT
                else -> MessageSourceType.HUMAN
            }
        val hasContentIntent = event.jda.gatewayIntents.any { it.name == "MESSAGE_CONTENT" }
        val content: ContentSnapshot =
            if (hasContentIntent) {
                ContentSnapshot.Readable(message.contentRaw)
            } else {
                // 인텐트 없음과 빈 본문 구분: 인텐트가 없는데 contentRaw 도 비면 권한 부재(IntentMissing).
                if (message.contentRaw.isEmpty()) ContentSnapshot.IntentMissing else ContentSnapshot.Readable(message.contentRaw)
            }
        return MessageCreatedSnapshot(
            guildId = event.guild.idLong,
            channelId = event.channel.idLong,
            messageId = event.messageIdLong,
            authorId = event.author.idLong,
            sourceType = sourceType,
            content = content,
            occurredAt = message.timeCreated.toInstant(),
            receivedAt = jdaEvent.receivedAt,
            sourceSequence = jdaEvent.sourceSequence,
            replyToMessageId = message.referencedMessage?.idLong,
            mentionedUserIds =
                message.mentions.users
                    .map { it.idLong }
                    .toSet(),
            attachments =
                message.attachments.map {
                    AttachmentSnapshot(
                        attachmentId = it.idLong,
                        fileName = it.fileName,
                        contentType = it.contentType,
                        sizeBytes = it.size.toLong(),
                    )
                },
            threadId = message.startedThread?.idLong,
        )
    }

    override fun toEvent(snapshot: MessageCreatedSnapshot): MessageCreated {
        val content = snapshot.content.toMessageContent()
        return MessageCreated(
            eventId =
                EventIdentity(
                    discordId = snapshot.messageId,
                    type = EventType.MESSAGE_CREATED,
                ).toEventId(),
            guildId = GuildId(snapshot.guildId),
            channelId = ChannelId(snapshot.channelId),
            occurredAt = snapshot.occurredAt,
            receivedAt = snapshot.receivedAt,
            sourceSequence = snapshot.sourceSequence,
            privacyClass = content.privacyClass(),
            messageId = MessageId(snapshot.messageId),
            authorId = AuthorId(snapshot.authorId),
            content = content,
            replyTo = snapshot.replyToMessageId?.let { MessageId(it) },
            mentions = snapshot.mentionedUserIds.map { AuthorId(it) }.toSet(),
            attachments =
                snapshot.attachments.map {
                    AttachmentMetadata(
                        attachmentId = it.attachmentId.toString(),
                        fileName = it.fileName,
                        contentType = it.contentType,
                        sizeBytes = it.sizeBytes,
                    )
                },
            threadId = snapshot.threadId?.let { ChannelId(it) },
        )
    }
}

/** 스냅샷 content 를 도메인 [MessageContent] 로 변환 — 인텐트 없음/빈/사용 가능을 명시 구분. */
internal fun ContentSnapshot.toMessageContent(): MessageContent =
    when (this) {
        is ContentSnapshot.IntentMissing -> MessageContent.Unavailable.IntentMissing
        is ContentSnapshot.Readable ->
            if (text.isEmpty()) MessageContent.Unavailable.Empty else MessageContent.Available(text)
    }

/** content 가용 상태에 따른 PII 등급 — 원문 텍스트가 있으면 HIGH, 없으면 LOW. */
internal fun MessageContent.privacyClass(): PrivacyClass =
    when (this) {
        is MessageContent.Available -> PrivacyClass.HIGH
        is MessageContent.Unavailable -> PrivacyClass.LOW
    }
