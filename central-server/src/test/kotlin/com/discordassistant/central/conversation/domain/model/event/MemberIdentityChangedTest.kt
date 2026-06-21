package com.discordassistant.central.conversation.domain.model.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P02-T021 MemberIdentityChanged acceptance:
 * 닉네임 변경(코알라/닉네임 사례)을 old→new 로 표현하고, 순서가 뒤집혀 도착해도 occurredAt 기준 현재값을
 * 판정한다.
 */
class MemberIdentityChangedTest {
    private fun change(
        eventId: String,
        occurredAt: Instant,
        actorId: Long = 20L,
        nickOld: String?,
        nickNew: String?,
    ): MemberIdentityChanged =
        MemberIdentityChanged(
            eventId = EventId(eventId),
            guildId = GuildId(1L),
            channelId = ChannelId(2L),
            occurredAt = occurredAt,
            receivedAt = occurredAt,
            sourceSequence = 1L,
            privacyClass = PrivacyClass.MEDIUM,
            actorId = AuthorId(actorId),
            nickname = IdentityChange(old = nickOld, new = nickNew),
            displayName = null,
        )

    @Test
    fun `닉네임 변경을 old new 로 표현한다 (코알라 사례)`() {
        val event = change("evt-1", Instant.parse("2026-06-21T10:00:00Z"), nickOld = "코알라", nickNew = "니키")
        assertEquals("코알라", event.nickname?.old)
        assertEquals("니키", event.nickname?.new)
    }

    @Test
    fun `순서가 뒤집혀 도착해도 occurredAt 기준 더 최근 변경을 판정한다`() {
        val earlier = change("evt-early", Instant.parse("2026-06-21T10:00:00Z"), nickOld = "코알라", nickNew = "니키")
        val later = change("evt-late", Instant.parse("2026-06-21T11:00:00Z"), nickOld = "니키", nickNew = "코알라")

        // 수신 순서와 무관하게 발생 시각으로 최신성 판정.
        assertTrue(later.isMoreRecentThan(earlier))
        assertFalse(earlier.isMoreRecentThan(later))
    }

    @Test
    fun `특정 시점의 닉네임은 그 시점 이하의 변경만 유효하다`() {
        val at1000 = change("evt-1000", Instant.parse("2026-06-21T10:00:00Z"), nickOld = "코알라", nickNew = "니키")
        val at1100 = change("evt-1100", Instant.parse("2026-06-21T11:00:00Z"), nickOld = "니키", nickNew = "코알라")

        // 10:30 시점에는 10:00 변경(→니키)만 적용, 11:00 변경은 아직 미래.
        assertEquals("니키", at1000.nicknameAsOf(Instant.parse("2026-06-21T10:30:00Z")))
        assertNull(at1100.nicknameAsOf(Instant.parse("2026-06-21T10:30:00Z")))
        assertEquals("코알라", at1100.nicknameAsOf(Instant.parse("2026-06-21T12:00:00Z")))
    }
}
