package com.discordassistant.central.conversation.application.ingest

import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.EventId
import com.discordassistant.central.conversation.domain.model.event.GenericObservedEvent
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent
import com.discordassistant.central.conversation.domain.model.event.PrivacyClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * NEXA-P03-T007 수신 envelope acceptance:
 * Clock 과 mapperVersion 이 주입(하드코딩 금지)돼 재생 시 원인 추적이 가능하다.
 */
class EnvelopeMetadataFactoryTest {
    private val fixedAt = Instant.parse("2026-06-21T12:00:00Z")
    private val clock = Clock.fixed(fixedAt, ZoneOffset.UTC)

    private fun event(): NormalizedDiscordEvent =
        GenericObservedEvent(
            eventId = EventId("evt-1"),
            guildId = GuildId(1L),
            channelId = ChannelId(2L),
            occurredAt = Instant.parse("2026-06-21T11:59:59Z"),
            receivedAt = fixedAt,
            sourceSequence = 1L,
            privacyClass = PrivacyClass.LOW,
        )

    @Test
    fun `receivedAt 은 주입된 Clock 에서 채워진다`() {
        val factory = EnvelopeMetadataFactory(clock, MapperVersion("v1"))
        val envelope =
            factory.wrap(
                event = event(),
                consentSnapshot = ConsentSnapshot(observationAllowed = true, speechAllowed = false),
            )
        assertEquals(fixedAt, envelope.receivedAt)
    }

    @Test
    fun `mapperVersion 은 주입값으로 봉투에 박힌다`() {
        val factory = EnvelopeMetadataFactory(clock, MapperVersion("mapper-2026-06-21"))
        val envelope =
            factory.wrap(
                event = event(),
                consentSnapshot = ConsentSnapshot(observationAllowed = true, speechAllowed = true),
            )
        assertEquals(MapperVersion("mapper-2026-06-21"), envelope.mapperVersion)
    }

    @Test
    fun `같은 입력과 주입값이면 같은 봉투가 재현된다`() {
        val factory = EnvelopeMetadataFactory(clock, MapperVersion("v1"))
        val consent = ConsentSnapshot(observationAllowed = true, speechAllowed = false)
        val a = factory.wrap(event = event(), consentSnapshot = consent)
        val b = factory.wrap(event = event(), consentSnapshot = consent)
        assertEquals(a, b)
    }

    @Test
    fun `샤드 세션 시퀀스 출처 메타가 봉투에 운반된다`() {
        val factory = EnvelopeMetadataFactory(clock, MapperVersion("v1"))
        val envelope =
            factory.wrap(
                event = event(),
                shardId = ShardId(3),
                sessionId = "sess-abc",
                gatewaySequence = 9001L,
                consentSnapshot = ConsentSnapshot(observationAllowed = true, speechAllowed = false),
            )
        assertEquals(ShardId(3), envelope.shardId)
        assertEquals("sess-abc", envelope.sessionId)
        assertEquals(9001L, envelope.gatewaySequence)
    }

    @Test
    fun `세션 시퀀스 미상은 명시 null 로 운반된다`() {
        val factory = EnvelopeMetadataFactory(clock, MapperVersion("v1"))
        val envelope =
            factory.wrap(
                event = event(),
                consentSnapshot = ConsentSnapshot(observationAllowed = false, speechAllowed = false),
            )
        assertNull(envelope.sessionId)
        assertNull(envelope.gatewaySequence)
        assertEquals(GatewaySource.NO_SHARD, envelope.shardId)
    }

    @Test
    fun `빈 mapperVersion 은 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) { MapperVersion("") }
    }

    @Test
    fun `발화 허용은 관찰 허용 없이 불가능하다`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConsentSnapshot(observationAllowed = false, speechAllowed = true)
        }
    }
}
