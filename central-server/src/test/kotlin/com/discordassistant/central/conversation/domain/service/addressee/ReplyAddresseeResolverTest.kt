package com.discordassistant.central.conversation.domain.service.addressee

import com.discordassistant.central.conversation.domain.model.addressee.AddresseeEvidence
import com.discordassistant.central.conversation.domain.model.event.AuthorId
import com.discordassistant.central.conversation.domain.model.event.MessageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** NEXA-P05-T005: self-reply 와 삭제된 target 의 fallback 이 명시된다. */
class ReplyAddresseeResolverTest {
    private val speaker = AuthorId(1L)
    private val target = AuthorId(2L)
    private val msg = MessageId(50L)

    @Test
    fun `일반 reply 는 대상에 높은 확률을 준다`() {
        val dist = ReplyAddresseeResolver.resolve(speaker, msg, replyTargetAuthor = target)
        assertEquals(target, dist.mostLikely?.member)
        assertEquals(msg, dist.mostLikely?.message)
        assertTrue(dist.mostLikely!!.probability >= 0.8)
        assertTrue(dist.evidence.contains(AddresseeEvidence.DIRECT_REPLY))
    }

    @Test
    fun `self-reply 는 확률을 낮추고 evidence 를 남긴다 (acceptance)`() {
        val dist = ReplyAddresseeResolver.resolve(speaker, msg, replyTargetAuthor = speaker)
        assertTrue(dist.mostLikely!!.probability < 0.5)
        assertTrue(dist.evidence.contains(AddresseeEvidence.SELF_REPLY))
    }

    @Test
    fun `삭제된 target 은 none fallback 이고 evidence 를 남긴다 (acceptance)`() {
        val dist = ReplyAddresseeResolver.resolve(speaker, msg, replyTargetAuthor = null)
        assertTrue(dist.candidates.isEmpty())
        assertEquals(1.0, dist.noneProbability)
        assertTrue(dist.evidence.contains(AddresseeEvidence.DELETED_REPLY_TARGET))
        assertTrue(dist.evidence.contains(AddresseeEvidence.DIRECT_REPLY))
    }
}
