package com.discordassistant.central.conversation.domain.model.scene

import com.discordassistant.central.conversation.domain.model.burst.BurstId
import com.discordassistant.central.conversation.domain.model.event.AuthorId
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** NEXA-P05-T014: 최근 참여자·마지막 발화·open burst·mention 상태 요약(content-derived feature 미포함). */
class SceneParticipantTest {
    private val t0 = Instant.parse("2026-01-01T10:00:00Z")
    private val alice = AuthorId(1L)
    private val bob = AuthorId(2L)

    @Test
    fun `content-derived feature 를 담지 않는다 (옵트아웃 보호 불변식)`() {
        val p = SceneParticipant(authorId = alice, lastSpokeAt = t0)
        // 항상 false — content feature 미포함 가드.
        assertFalse(p.contentDerived)
    }

    @Test
    fun `open burst 가 있으면 발화 중`() {
        val p = SceneParticipant(authorId = alice, lastSpokeAt = t0, openBurst = BurstId("burst:1"))
        assertTrue(p.isSpeaking)
    }

    @Test
    fun `open burst 가 없으면 발화 중 아님`() {
        val p = SceneParticipant(authorId = alice, lastSpokeAt = t0)
        assertFalse(p.isSpeaking)
    }

    @Test
    fun `mention 받으면 호명 상태`() {
        val p = SceneParticipant(authorId = alice, lastSpokeAt = t0, mentionedBy = setOf(bob))
        assertTrue(p.isAddressed)
    }

    @Test
    fun `자기 자신 mention 은 기록하지 않는다`() {
        assertThrows(IllegalArgumentException::class.java) {
            SceneParticipant(authorId = alice, lastSpokeAt = t0, mentionedBy = setOf(alice))
        }
    }
}
