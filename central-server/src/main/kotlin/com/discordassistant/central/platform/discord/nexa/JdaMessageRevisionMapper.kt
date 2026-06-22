package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.EventIdentity
import com.discordassistant.central.conversation.domain.model.event.EventType
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.MessageDeleted
import com.discordassistant.central.conversation.domain.model.event.MessageId
import com.discordassistant.central.conversation.domain.model.event.MessageUpdated
import com.discordassistant.central.conversation.domain.model.event.PrivacyClass
import net.dv8tion.jda.api.events.message.MessageDeleteEvent
import net.dv8tion.jda.api.events.message.MessageUpdateEvent
import java.time.Instant

/**
 * 메시지 수정/삭제 매퍼(NEXA-P03-T004). JDA [MessageUpdateEvent]/[MessageDeleteEvent] → 정규화
 * [MessageUpdated]/[MessageDeleted]. revision 인지(revision-aware) 정규화.
 *
 * **acceptance(T004)**:
 *  - **캐시 미스에서도 최소 키로 이벤트 생성**: 수정/삭제 이벤트는 이전 내용을 항상 알 수 없다(인텐트/캐시 한계).
 *    삭제는 messageId 만으로([MessageDeletedSnapshot]), 수정은 content 가 없으면 Unavailable 로 이벤트를 만든다 —
 *    이전 내용이 없어도 이벤트가 유실되지 않는다.
 *  - **예외로 유실 안 됨**: 추출 중 첨부/스레드 같은 보조 필드 접근이 실패해도 핵심 키(guild/channel/message)만
 *    있으면 이벤트를 만든다. 보조 필드는 처음부터 읽지 않아(삭제) 예외 표면이 없다.
 *
 * revision: Discord MESSAGE_UPDATE 는 편집 순번을 직접 주지 않는다. 어댑터가 같은 messageId 의 단조 증가 revision 을
 * 부여해 [extractUpdate] 입력으로 넣는다(모르면 0=최소 키). 도메인 [MessageUpdated] 가 supersedes/isStaleAgainst 로
 * 순서를 판정하므로 매퍼는 revision 을 그대로 전달만 한다.
 */
class JdaMessageRevisionMapper {
    /** 수정 추출 입력 — JDA 이벤트 + 어댑터 주입(revision·receivedAt·sourceSequence). */
    data class UpdateInput(
        val jdaEvent: MessageUpdateEvent,
        val revision: Long,
        val receivedAt: Instant,
        val sourceSequence: Long,
    )

    /** 삭제 추출 입력 — JDA 이벤트 + 어댑터 주입(occurredAt·receivedAt·sourceSequence). */
    data class DeleteInput(
        val jdaEvent: MessageDeleteEvent,
        val occurredAt: Instant,
        val receivedAt: Instant,
        val sourceSequence: Long,
    )

    fun extractUpdate(input: UpdateInput): MessageUpdatedSnapshot {
        val event = input.jdaEvent
        val message = event.message
        val hasContentIntent = event.jda.gatewayIntents.any { it.name == "MESSAGE_CONTENT" }
        val content: ContentSnapshot =
            if (hasContentIntent) {
                ContentSnapshot.Readable(message.contentRaw)
            } else {
                if (message.contentRaw.isEmpty()) ContentSnapshot.IntentMissing else ContentSnapshot.Readable(message.contentRaw)
            }
        return MessageUpdatedSnapshot(
            guildId = event.guild.idLong,
            channelId = event.channel.idLong,
            messageId = event.messageIdLong,
            revision = input.revision,
            content = content,
            occurredAt = message.timeEdited?.toInstant() ?: message.timeCreated.toInstant(),
            receivedAt = input.receivedAt,
            sourceSequence = input.sourceSequence,
        )
    }

    /**
     * 삭제 추출. Discord MESSAGE_DELETE 는 원문/작성자/원천 시각을 싣지 않는다 — guild/channel/messageId 만 읽어
     * 최소 키 스냅샷을 만든다(캐시 미스 무관). occurredAt 은 어댑터 수신 시각으로 주입한다.
     */
    fun extractDelete(input: DeleteInput): MessageDeletedSnapshot {
        val event = input.jdaEvent
        return MessageDeletedSnapshot(
            guildId = event.guild.idLong,
            channelId = event.channel.idLong,
            messageId = event.messageIdLong,
            occurredAt = input.occurredAt,
            receivedAt = input.receivedAt,
            sourceSequence = input.sourceSequence,
        )
    }

    /** 수정 스냅샷 → 도메인 이벤트(순수). content 가 원문이면 HIGH, 아니면 LOW. */
    fun toUpdated(snapshot: MessageUpdatedSnapshot): MessageUpdated {
        val content = snapshot.content.toMessageContent()
        return MessageUpdated(
            eventId =
                EventIdentity(
                    discordId = snapshot.messageId,
                    type = EventType.MESSAGE_UPDATED,
                    revision = snapshot.revision,
                ).toEventId(),
            guildId = GuildId(snapshot.guildId),
            channelId = ChannelId(snapshot.channelId),
            occurredAt = snapshot.occurredAt,
            receivedAt = snapshot.receivedAt,
            sourceSequence = snapshot.sourceSequence,
            privacyClass = content.privacyClass(),
            messageId = MessageId(snapshot.messageId),
            revision = snapshot.revision,
            content = content,
        )
    }

    /** 삭제 스냅샷 → 도메인 이벤트(순수). 원문 없음 → privacyClass LOW. */
    fun toDeleted(snapshot: MessageDeletedSnapshot): MessageDeleted =
        MessageDeleted(
            eventId =
                EventIdentity(
                    discordId = snapshot.messageId,
                    type = EventType.MESSAGE_DELETED,
                ).toEventId(),
            guildId = GuildId(snapshot.guildId),
            channelId = ChannelId(snapshot.channelId),
            occurredAt = snapshot.occurredAt,
            receivedAt = snapshot.receivedAt,
            sourceSequence = snapshot.sourceSequence,
            privacyClass = PrivacyClass.LOW,
            messageId = MessageId(snapshot.messageId),
        )
}
