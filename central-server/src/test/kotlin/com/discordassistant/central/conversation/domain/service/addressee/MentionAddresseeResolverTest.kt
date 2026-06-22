package com.discordassistant.central.conversation.domain.service.addressee

import com.discordassistant.central.conversation.domain.model.addressee.AddresseeEvidence
import com.discordassistant.central.conversation.domain.model.event.AuthorId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** NEXA-P05-T006: 문자열 이름 일치만으로 확정 target 을 만들지 않는다. */
class MentionAddresseeResolverTest {
    @Test
    fun `본문 직접 mention 은 강한 신호로 후보를 만든다`() {
        val dist = MentionAddresseeResolver.resolve(directMentions = setOf(AuthorId(7L)), nicknameMatches = emptySet())
        assertEquals(AuthorId(7L), dist.mostLikely?.member)
        assertTrue(dist.evidence.contains(AddresseeEvidence.DIRECT_MENTION))
    }

    @Test
    fun `닉네임 문자열만으로는 확정 target 을 만들지 않는다 (acceptance)`() {
        val dist = MentionAddresseeResolver.resolve(directMentions = emptySet(), nicknameMatches = setOf(AuthorId(9L)))
        // 약한 신호 — none 확률이 크게 남아 1.0 확정이 아니다.
        assertTrue(dist.noneProbability > 0.0)
        assertTrue(dist.mostLikely!!.probability < 1.0)
        assertTrue(dist.evidence.contains(AddresseeEvidence.NICKNAME_STRING))
    }

    @Test
    fun `본문 mention 이 있으면 닉네임보다 우선한다`() {
        val dist =
            MentionAddresseeResolver.resolve(
                directMentions = setOf(AuthorId(7L)),
                nicknameMatches = setOf(AuthorId(9L)),
            )
        assertEquals(AuthorId(7L), dist.mostLikely?.member)
        assertTrue(dist.evidence.contains(AddresseeEvidence.DIRECT_MENTION))
    }

    @Test
    fun `mention 신호가 없으면 none 이다`() {
        val dist = MentionAddresseeResolver.resolve(emptySet(), emptySet())
        assertEquals(1.0, dist.noneProbability)
    }

    @Test
    fun `여러 명 직접 mention 은 확신도를 분배한다`() {
        val dist =
            MentionAddresseeResolver.resolve(
                directMentions = setOf(AuthorId(1L), AuthorId(2L)),
                nicknameMatches = emptySet(),
            )
        assertEquals(2, dist.candidates.size)
        assertTrue(dist.candidates.all { it.probability < 0.85 })
    }
}
