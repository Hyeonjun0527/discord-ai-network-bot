package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.conversation.domain.model.event.PrivacyClass
import com.discordassistant.central.conversation.domain.model.event.SessionBoundaryKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P03-T020 세션 경계 매퍼 acceptance: 끊김/resume/새 세션을 정규화 메타 이벤트로 기록하고, 새 세션이
 * 시퀀스 갭을 표시해 sourceSequence 재사용이 내부 순서를 깨지 않는다.
 */
class JdaSessionBoundaryMapperTest {
    private val mapper = JdaSessionBoundaryMapper()
    private val occurredAt = Instant.parse("2026-06-21T10:00:00Z")
    private val receivedAt = Instant.parse("2026-06-21T10:00:01Z")

    private fun snapshot(
        boundary: SessionBoundaryKindSnapshot,
        sessionId: String? = "sess-A",
        lastSeq: Long? = 42L,
        sourceSequence: Long = 100L,
    ): SessionBoundarySnapshot =
        SessionBoundarySnapshot(
            guildId = 0L,
            channelId = 0L,
            boundary = boundary,
            sessionId = sessionId,
            lastGatewaySequence = lastSeq,
            occurredAt = occurredAt,
            receivedAt = receivedAt,
            sourceSequence = sourceSequence,
        )

    @Test
    fun `세 경계 종류가 도메인 enum 으로 매핑되고 privacyClass LOW`() {
        val disc = mapper.toEvent(snapshot(SessionBoundaryKindSnapshot.DISCONNECTED))
        val resumed = mapper.toEvent(snapshot(SessionBoundaryKindSnapshot.RESUMED))
        val newSession = mapper.toEvent(snapshot(SessionBoundaryKindSnapshot.NEW_SESSION))

        assertEquals(SessionBoundaryKind.DISCONNECTED, disc.boundary)
        assertEquals(SessionBoundaryKind.RESUMED, resumed.boundary)
        assertEquals(SessionBoundaryKind.NEW_SESSION, newSession.boundary)
        assertEquals(PrivacyClass.LOW, newSession.privacyClass)
    }

    @Test
    fun `새 세션만 시퀀스 갭을 만든다`() {
        assertTrue(mapper.toEvent(snapshot(SessionBoundaryKindSnapshot.NEW_SESSION)).createsSequenceGap)
        assertFalse(mapper.toEvent(snapshot(SessionBoundaryKindSnapshot.RESUMED)).createsSequenceGap)
        assertFalse(mapper.toEvent(snapshot(SessionBoundaryKindSnapshot.DISCONNECTED)).createsSequenceGap)
    }

    @Test
    fun `같은 세션 메타의 다른 경계 종류는 eventId 가 충돌하지 않는다`() {
        val resumed = mapper.toEvent(snapshot(SessionBoundaryKindSnapshot.RESUMED))
        val newSession = mapper.toEvent(snapshot(SessionBoundaryKindSnapshot.NEW_SESSION))
        assertNotEquals(resumed.eventId, newSession.eventId)
    }

    @Test
    fun `같은 경계 재수신은 같은 eventId 라 dedup 안전`() {
        val first = mapper.toEvent(snapshot(SessionBoundaryKindSnapshot.NEW_SESSION, sourceSequence = 1L))
        // 같은 세션 메타를 재수신(다른 sourceSequence)해도 멱등 키는 같다(at-least-once 흡수).
        val again = mapper.toEvent(snapshot(SessionBoundaryKindSnapshot.NEW_SESSION, sourceSequence = 2L))
        assertEquals(first.eventId, again.eventId)
    }

    @Test
    fun `세션 식별자 unavailable 이면 null 로 명시한다`() {
        val event = mapper.toEvent(snapshot(SessionBoundaryKindSnapshot.DISCONNECTED, sessionId = null, lastSeq = null))
        assertEquals(null, event.sessionId)
        assertEquals(null, event.lastGatewaySequence)
    }
}
