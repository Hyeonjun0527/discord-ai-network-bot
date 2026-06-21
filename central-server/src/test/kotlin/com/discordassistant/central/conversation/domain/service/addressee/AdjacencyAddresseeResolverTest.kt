package com.discordassistant.central.conversation.domain.service.addressee

import com.discordassistant.central.conversation.domain.model.addressee.AddresseeEvidence
import com.discordassistant.central.conversation.domain.model.event.AuthorId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** NEXA-P05-T007: 단일 정답 대신 none 포함 확률분포를 반환한다. */
class AdjacencyAddresseeResolverTest {
    private val speaker = AuthorId(1L)
    private val recent = AuthorId(2L)

    @Test
    fun `직전 화자를 후보로 두되 none 여지를 남긴다 (acceptance)`() {
        val dist =
            AdjacencyAddresseeResolver.resolve(
                speaker = speaker,
                recentSpeaker = recent,
                isQuestion = false,
                alternating = false,
            )
        assertEquals(recent, dist.mostLikely?.member)
        assertTrue(dist.noneProbability > 0.0)
        assertTrue(dist.mostLikely!!.probability < 1.0)
        assertTrue(dist.evidence.contains(AddresseeEvidence.RECENT_SPEAKER))
    }

    @Test
    fun `질문 형태와 교대 패턴은 직전 화자 가중을 올린다`() {
        val base =
            AdjacencyAddresseeResolver.resolve(speaker, recent, isQuestion = false, alternating = false)
        val boosted =
            AdjacencyAddresseeResolver.resolve(speaker, recent, isQuestion = true, alternating = true)
        assertTrue(boosted.mostLikely!!.probability > base.mostLikely!!.probability)
        assertTrue(boosted.evidence.contains(AddresseeEvidence.QUESTION_FORM))
        assertTrue(boosted.evidence.contains(AddresseeEvidence.ALTERNATION))
        // 모든 신호를 합쳐도 단일 정답으로 단정하지 않는다(none 여지 유지).
        assertTrue(boosted.noneProbability > 0.0)
    }

    @Test
    fun `직전 화자가 없으면 none 이다`() {
        val dist = AdjacencyAddresseeResolver.resolve(speaker, recentSpeaker = null, isQuestion = true, alternating = true)
        assertEquals(1.0, dist.noneProbability)
    }

    @Test
    fun `직전 화자가 자기 자신이면 none 이다 (혼잣말)`() {
        val dist = AdjacencyAddresseeResolver.resolve(speaker, recentSpeaker = speaker, isQuestion = false, alternating = false)
        assertEquals(1.0, dist.noneProbability)
    }
}
