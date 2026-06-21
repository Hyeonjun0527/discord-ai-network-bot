package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.conversation.domain.model.event.MessageCreated
import com.discordassistant.central.conversation.domain.model.event.MessageId
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageType
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.message.MessageDeleteEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.message.MessageUpdateEvent
import net.dv8tion.jda.api.requests.GatewayIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.EnumSet

/**
 * NEXA-P03-T002/T003/T004 추출(extract) 경계 검증 — 어댑터가 JDA 이벤트를 읽어 JDA-free 스냅샷으로 변환한다.
 * JDA 객체는 Mockito 로 fake(기존 테스트 컨벤션 org.mockito.Mockito.mock). 결과 스냅샷에 JDA 참조가 없음을 확인한다.
 */
class JdaMessageExtractionTest {
    private val occurredAt = OffsetDateTime.of(2026, 6, 21, 10, 0, 0, 0, ZoneOffset.UTC)
    private val receivedAt = Instant.parse("2026-06-21T10:00:01Z")

    private fun jdaWithContentIntent(present: Boolean): JDA {
        val jda = mock(JDA::class.java)
        val intents = if (present) EnumSet.of(GatewayIntent.MESSAGE_CONTENT) else EnumSet.noneOf(GatewayIntent::class.java)
        `when`(jda.gatewayIntents).thenReturn(intents)
        return jda
    }

    @Test
    fun `MessageReceivedEvent 를 사람 메시지 스냅샷으로 추출한다`() {
        val author = mock(User::class.java)
        `when`(author.idLong).thenReturn(20L)
        `when`(author.isBot).thenReturn(false)

        val mentions = mock(Mentions::class.java)
        `when`(mentions.users).thenReturn(emptyList())

        val message = mock(Message::class.java)
        `when`(message.type).thenReturn(MessageType.DEFAULT)
        `when`(message.isWebhookMessage).thenReturn(false)
        `when`(message.contentRaw).thenReturn("hello")
        `when`(message.timeCreated).thenReturn(occurredAt)
        `when`(message.mentions).thenReturn(mentions)
        `when`(message.attachments).thenReturn(emptyList())
        `when`(message.referencedMessage).thenReturn(null)
        `when`(message.startedThread).thenReturn(null)

        val guild = mock(Guild::class.java)
        `when`(guild.idLong).thenReturn(1L)
        val channel = mock(MessageChannelUnion::class.java)
        `when`(channel.idLong).thenReturn(2L)

        val jda = jdaWithContentIntent(true)
        val event = mock(MessageReceivedEvent::class.java)
        `when`(event.message).thenReturn(message)
        `when`(event.author).thenReturn(author)
        `when`(event.jda).thenReturn(jda)
        `when`(event.guild).thenReturn(guild)
        `when`(event.channel).thenReturn(channel)
        `when`(event.messageIdLong).thenReturn(10L)

        val snapshot =
            JdaMessageEventMapper().extract(
                JdaMessageEventMapper.Input(event, receivedAt, sourceSequence = 7L),
            )

        assertEquals(MessageSourceType.HUMAN, snapshot.sourceType)
        assertEquals(ContentSnapshot.Readable("hello"), snapshot.content)
        assertEquals(10L, snapshot.messageId)
        assertEquals(20L, snapshot.authorId)
        assertEquals(7L, snapshot.sourceSequence)
        assertEquals(receivedAt, snapshot.receivedAt)
    }

    @Test
    fun `봇 메시지와 인텐트 없는 빈 본문을 구분 추출한다`() {
        val author = mock(User::class.java)
        `when`(author.idLong).thenReturn(99L)
        `when`(author.isBot).thenReturn(true)

        val mentions = mock(Mentions::class.java)
        `when`(mentions.users).thenReturn(emptyList())

        val message = mock(Message::class.java)
        `when`(message.type).thenReturn(MessageType.DEFAULT)
        `when`(message.isWebhookMessage).thenReturn(false)
        `when`(message.contentRaw).thenReturn("")
        `when`(message.timeCreated).thenReturn(occurredAt)
        `when`(message.mentions).thenReturn(mentions)
        `when`(message.attachments).thenReturn(emptyList())
        `when`(message.referencedMessage).thenReturn(null)
        `when`(message.startedThread).thenReturn(null)

        val guild = mock(Guild::class.java)
        `when`(guild.idLong).thenReturn(1L)
        val channel = mock(MessageChannelUnion::class.java)
        `when`(channel.idLong).thenReturn(2L)

        val jda = jdaWithContentIntent(false)
        val event = mock(MessageReceivedEvent::class.java)
        `when`(event.message).thenReturn(message)
        `when`(event.author).thenReturn(author)
        `when`(event.jda).thenReturn(jda)
        `when`(event.guild).thenReturn(guild)
        `when`(event.channel).thenReturn(channel)
        `when`(event.messageIdLong).thenReturn(11L)

        val snapshot =
            JdaMessageEventMapper().extract(
                JdaMessageEventMapper.Input(event, receivedAt, sourceSequence = 1L),
            )

        assertEquals(MessageSourceType.BOT, snapshot.sourceType)
        // 인텐트 없음 + 빈 본문 → IntentMissing(권한 부재 명시).
        assertEquals(ContentSnapshot.IntentMissing, snapshot.content)
    }

    @Test
    fun `시스템 메시지는 SYSTEM 출처로 추출된다`() {
        val author = mock(User::class.java)
        `when`(author.idLong).thenReturn(20L)
        `when`(author.isBot).thenReturn(false)

        val mentions = mock(Mentions::class.java)
        `when`(mentions.users).thenReturn(emptyList())

        val message = mock(Message::class.java)
        `when`(message.type).thenReturn(MessageType.CHANNEL_PINNED_ADD)
        `when`(message.isWebhookMessage).thenReturn(false)
        `when`(message.contentRaw).thenReturn("")
        `when`(message.timeCreated).thenReturn(occurredAt)
        `when`(message.mentions).thenReturn(mentions)
        `when`(message.attachments).thenReturn(emptyList())
        `when`(message.referencedMessage).thenReturn(null)
        `when`(message.startedThread).thenReturn(null)

        val guild = mock(Guild::class.java)
        `when`(guild.idLong).thenReturn(1L)
        val channel = mock(MessageChannelUnion::class.java)
        `when`(channel.idLong).thenReturn(2L)

        val jda = jdaWithContentIntent(true)
        val event = mock(MessageReceivedEvent::class.java)
        `when`(event.message).thenReturn(message)
        `when`(event.author).thenReturn(author)
        `when`(event.jda).thenReturn(jda)
        `when`(event.guild).thenReturn(guild)
        `when`(event.channel).thenReturn(channel)
        `when`(event.messageIdLong).thenReturn(12L)

        // map() 은 extract+toEvent 를 합친다 — 반환은 순수 도메인 이벤트(MessageCreated, JDA 참조 없음).
        val domainEvent = JdaMessageEventMapper().map(JdaMessageEventMapper.Input(event, receivedAt, sourceSequence = 2L))

        assertEquals(
            MessageSourceType.SYSTEM,
            JdaMessageEventMapper().extract(JdaMessageEventMapper.Input(event, receivedAt, 2L)).sourceType,
        )
        assertEquals(MessageId(12L), (domainEvent as MessageCreated).messageId)
    }

    @Test
    fun `MessageUpdateEvent 를 revision 스냅샷으로 추출한다`() {
        val message = mock(Message::class.java)
        `when`(message.contentRaw).thenReturn("edited")
        `when`(message.timeEdited).thenReturn(occurredAt)
        `when`(message.timeCreated).thenReturn(occurredAt)

        val guild = mock(Guild::class.java)
        `when`(guild.idLong).thenReturn(1L)
        val channel = mock(MessageChannelUnion::class.java)
        `when`(channel.idLong).thenReturn(2L)

        val jda = jdaWithContentIntent(true)
        val event = mock(MessageUpdateEvent::class.java)
        `when`(event.message).thenReturn(message)
        `when`(event.jda).thenReturn(jda)
        `when`(event.guild).thenReturn(guild)
        `when`(event.channel).thenReturn(channel)
        `when`(event.messageIdLong).thenReturn(10L)

        val snapshot =
            JdaMessageRevisionMapper().extractUpdate(
                JdaMessageRevisionMapper.UpdateInput(event, revision = 3L, receivedAt = receivedAt, sourceSequence = 1L),
            )

        assertEquals(10L, snapshot.messageId)
        assertEquals(3L, snapshot.revision)
        assertEquals(ContentSnapshot.Readable("edited"), snapshot.content)
    }

    @Test
    fun `MessageDeleteEvent 는 최소 키만 추출한다 (캐시 미스 무관)`() {
        val guild = mock(Guild::class.java)
        `when`(guild.idLong).thenReturn(1L)
        val channel = mock(MessageChannelUnion::class.java)
        `when`(channel.idLong).thenReturn(2L)

        val event = mock(MessageDeleteEvent::class.java)
        `when`(event.guild).thenReturn(guild)
        `when`(event.channel).thenReturn(channel)
        `when`(event.messageIdLong).thenReturn(10L)

        val snapshot =
            JdaMessageRevisionMapper().extractDelete(
                JdaMessageRevisionMapper.DeleteInput(event, occurredAt = receivedAt, receivedAt = receivedAt, sourceSequence = 1L),
            )

        assertEquals(10L, snapshot.messageId)
        assertEquals(1L, snapshot.guildId)
        assertEquals(2L, snapshot.channelId)
    }
}
