package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.conversation.domain.model.event.AuthorId
import com.discordassistant.central.conversation.domain.model.event.EmojiIdentity
import com.discordassistant.central.conversation.domain.model.event.PrivacyClass
import com.discordassistant.central.conversation.domain.model.event.ReactionChange
import com.discordassistant.central.conversation.domain.model.event.ReactionIntensity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P03-T005 reaction/typing/member 매퍼 acceptance:
 * 미지원/미관측 필드는 explicit unavailable 로 기록(null 뭉갬 금지) + 버스트/단발 보존.
 */
class JdaSocialEventMapperTest {
    private val mapper = JdaSocialEventMapper()
    private val occurredAt = Instant.parse("2026-06-21T10:00:00Z")
    private val receivedAt = Instant.parse("2026-06-21T10:00:01Z")

    private fun reaction(
        change: ReactionChangeSnapshot = ReactionChangeSnapshot.ADDED,
        emoji: EmojiSnapshot = EmojiSnapshot.Unicode("👍"),
        burst: Boolean = false,
    ): ReactionSnapshot =
        ReactionSnapshot(
            guildId = 1L,
            channelId = 2L,
            messageId = 10L,
            actorId = 20L,
            emoji = emoji,
            change = change,
            occurredAt = occurredAt,
            receivedAt = receivedAt,
            sourceSequence = 1L,
            burst = burst,
        )

    @Test
    fun `add 와 remove 가 방향으로 구분되고 eventId 가 충돌하지 않는다`() {
        val added = mapper.toReaction(reaction(change = ReactionChangeSnapshot.ADDED))
        val removed = mapper.toReaction(reaction(change = ReactionChangeSnapshot.REMOVED))
        assertEquals(ReactionChange.ADDED, added.change)
        assertEquals(ReactionChange.REMOVED, removed.change)
        assertNotEquals(added.eventId, removed.eventId)
    }

    @Test
    fun `단발과 버스트 강도가 보존된다`() {
        assertEquals(ReactionIntensity.SINGLE, mapper.toReaction(reaction(burst = false)).intensity)
        assertEquals(ReactionIntensity.BURST, mapper.toReaction(reaction(burst = true)).intensity)
    }

    @Test
    fun `unicode 와 custom 이모지가 구분 매핑된다`() {
        val unicode = mapper.toReaction(reaction(emoji = EmojiSnapshot.Unicode("👍"))).emoji
        val custom = mapper.toReaction(reaction(emoji = EmojiSnapshot.Custom(777L, "blob"))).emoji
        assertEquals(EmojiIdentity.Unicode("👍"), unicode)
        assertEquals(EmojiIdentity.Custom(777L, "blob"), custom)
    }

    @Test
    fun `typing 은 LOW 이고 actor 와 만료 시각을 보존한다`() {
        val expiresAt = occurredAt.plusSeconds(8)
        val event =
            mapper.toTyping(
                TypingSnapshot(
                    guildId = 1L,
                    channelId = 2L,
                    actorId = 20L,
                    startedAt = occurredAt,
                    expiresAt = expiresAt,
                    receivedAt = receivedAt,
                    sourceSequence = 1L,
                ),
            )
        assertEquals(AuthorId(20L), event.actorId)
        assertEquals(expiresAt, event.expiresAt)
        assertEquals(PrivacyClass.LOW, event.privacyClass)
    }

    @Test
    fun `변경 없음 필드는 null, 변경됨 필드는 old new 를 보존한다 (단일 null 뭉갬 금지)`() {
        val event =
            mapper.toMemberIdentity(
                MemberIdentitySnapshot(
                    guildId = 1L,
                    channelId = 2L,
                    actorId = 20L,
                    occurredAt = occurredAt,
                    receivedAt = receivedAt,
                    sourceSequence = 1L,
                    nickname = IdentityFieldSnapshot.Changed(old = "옛이름", new = "새이름"),
                    displayName = IdentityFieldSnapshot.Unchanged,
                ),
            )
        // 변경된 닉네임: old→new 보존
        assertEquals("옛이름", event.nickname?.old)
        assertEquals("새이름", event.nickname?.new)
        // 변경 없는 표시명: null (이번 이벤트가 안 바꿈) — "이전 값 모름" 과 구분됨
        assertNull(event.displayName)
        assertEquals(PrivacyClass.MEDIUM, event.privacyClass)
    }

    @Test
    fun `이전 값을 모르는 변경은 old null 로 명시된다 (변경 없음과 다름)`() {
        val event =
            mapper.toMemberIdentity(
                MemberIdentitySnapshot(
                    guildId = 1L,
                    channelId = 2L,
                    actorId = 20L,
                    occurredAt = occurredAt,
                    receivedAt = receivedAt,
                    sourceSequence = 1L,
                    nickname = IdentityFieldSnapshot.Changed(old = null, new = "새이름"),
                    displayName = IdentityFieldSnapshot.Unchanged,
                ),
            )
        // Changed(old=null) → IdentityChange 존재(old=null), Unchanged → null. 둘은 다른 의미.
        assertTrue(event.nickname != null)
        assertNull(event.nickname?.old)
        assertEquals("새이름", event.nickname?.new)
        assertNull(event.displayName)
    }
}
