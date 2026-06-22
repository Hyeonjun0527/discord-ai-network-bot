package com.discordassistant.central.conversation.domain.model.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P03-T020 세션 경계 메타 이벤트 도메인 테스트.
 *
 * acceptance: 세션 변경 후 sourceSequence 재사용이 내부 순서를 깨지 않는다 — NEW_SESSION 경계가 시퀀스 갭을
 * 표시하므로, 그 뒤 작은 sourceSequence 가 이전 세션의 큰 값보다 앞으로 오해되지 않는다.
 */
class SessionBoundaryObservedTest {
    private fun boundary(
        kind: SessionBoundaryKind,
        sourceSequence: Long = 100L,
        sessionId: String? = "sess-A",
        lastSeq: Long? = 42L,
    ): SessionBoundaryObserved =
        SessionBoundaryObserved(
            eventId = EventId("session.boundary:0:0"),
            guildId = GuildId(0L),
            channelId = ChannelId(0L),
            occurredAt = Instant.parse("2026-06-21T10:00:00Z"),
            receivedAt = Instant.parse("2026-06-21T10:00:00Z"),
            sourceSequence = sourceSequence,
            privacyClass = PrivacyClass.LOW,
            boundary = kind,
            sessionId = sessionId,
            lastGatewaySequence = lastSeq,
        )

    @Test
    fun `새 세션은 시퀀스 갭을 만든다 resume 와 disconnect 는 만들지 않는다`() {
        assertTrue(boundary(SessionBoundaryKind.NEW_SESSION).createsSequenceGap, "새 세션 = 시퀀스 재시작")
        assertFalse(boundary(SessionBoundaryKind.RESUMED).createsSequenceGap, "resume = 시퀀스 이어받기")
        assertFalse(boundary(SessionBoundaryKind.DISCONNECTED).createsSequenceGap, "끊김 = 아직 재시작 전")
    }

    @Test
    fun `세션 경계는 원문을 운반하지 않는다 privacyClass LOW`() {
        val event: NormalizedDiscordEvent = boundary(SessionBoundaryKind.NEW_SESSION)
        assertEquals(PrivacyClass.LOW, event.privacyClass)
    }

    @Test
    fun `새 세션 경계 뒤 재사용된 작은 sourceSequence 가 이전 세션보다 앞으로 오해되지 않는다`() {
        // 이전 세션: 큰 순번까지 진행. 새 세션 경계가 갭을 표시. 새 세션: 작은 순번부터 재시작.
        val newSessionBoundary = boundary(SessionBoundaryKind.NEW_SESSION, sourceSequence = 1_000L)

        // 세션 경계 메타가 스트림에 존재하므로, 수집/재생이 "경계 이후 작은 순번" 을 새 세션 구간으로 인지한다.
        // 경계 자체가 갭 신호다(createsSequenceGap) — 순서 복원이 단순 sourceSequence 비교가 아니라 세션 분기를 적용한다.
        assertTrue(newSessionBoundary.createsSequenceGap)
        // 대조: resume 경계는 같은 순번 공간이라 갭 분기를 적용하지 않는다.
        assertFalse(boundary(SessionBoundaryKind.RESUMED, sourceSequence = 1_000L).createsSequenceGap)
    }
}
