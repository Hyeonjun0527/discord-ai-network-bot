package com.discordassistant.central.conversation.application.burst

import com.discordassistant.central.conversation.domain.model.burst.BurstStatus
import com.discordassistant.central.conversation.domain.model.burst.BurstTestFragments
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
import com.discordassistant.central.conversation.domain.model.event.EventId
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.MessageContent
import com.discordassistant.central.conversation.domain.model.event.MessageDeleted
import com.discordassistant.central.conversation.domain.model.event.MessageId
import com.discordassistant.central.conversation.domain.model.event.MessageUpdated
import com.discordassistant.central.conversation.domain.model.event.PrivacyClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P04-T014/T015 acceptance:
 * - T014: finalize 전 수정은 fragment 갱신, finalize 후 수정은 CORRECTED projection event. 과거 burst ID 불변·revision provenance 보존.
 * - T015: 삭제 content 가 combined text 에 남지 않고 빈 버스트는 lineage 삭제가 발생한다.
 */
class BurstCorrectionPolicyTest {
    private val guild = GuildId(1L)
    private val policy = BurstCorrectionPolicy()

    private fun twoFragmentBurst(): UtteranceBurst =
        UtteranceBurst
            .open(guild, BurstTestFragments.fragment(1, seq = 1, content = MessageContent.Available("원본1")))
            .append(
                BurstTestFragments.fragment(
                    2,
                    seq = 2,
                    at = BurstTestFragments.T0.plusSeconds(1),
                    content = MessageContent.Available("원본2"),
                ),
            )

    private fun edit(
        messageId: Long,
        revision: Long,
        text: String,
    ): MessageUpdated =
        MessageUpdated(
            eventId = EventId("edit-$messageId-$revision"),
            guildId = guild,
            channelId =
                com.discordassistant.central.conversation.domain.model.event
                    .ChannelId(100),
            occurredAt = Instant.parse("2026-01-01T11:20:00Z"),
            receivedAt = Instant.parse("2026-01-01T11:20:01Z"),
            sourceSequence = 99,
            privacyClass = PrivacyClass.HIGH,
            messageId = MessageId(messageId),
            revision = revision,
            content = MessageContent.Available(text),
        )

    private fun deletion(messageId: Long): MessageDeleted =
        MessageDeleted(
            eventId = EventId("del-$messageId"),
            guildId = guild,
            channelId =
                com.discordassistant.central.conversation.domain.model.event
                    .ChannelId(100),
            occurredAt = Instant.parse("2026-01-01T11:21:00Z"),
            receivedAt = Instant.parse("2026-01-01T11:21:01Z"),
            sourceSequence = 100,
            privacyClass = PrivacyClass.LOW,
            messageId = MessageId(messageId),
        )

    @Test
    fun `finalize 전 수정은 fragment content 를 갱신한다 (T014)`() {
        val open = twoFragmentBurst()
        val result = policy.applyEdit(open, edit(2, revision = 1, text = "수정된2"))
        val updated = assertInstanceOf(BurstCorrection.FragmentUpdated::class.java, result)
        assertEquals(BurstStatus.OPEN, updated.burst.status)
        val text2 =
            (
                updated.burst.fragments
                    .first { it.messageId == MessageId(2) }
                    .content as MessageContent.Available
            ).text
        assertEquals("수정된2", text2)
        assertEquals(open.burstId, updated.burst.burstId, "burstId 불변")
    }

    @Test
    fun `finalize 후 수정은 CORRECTED 로 전이하고 burst ID·revision provenance 를 보존한다 (T014)`() {
        val finalized = twoFragmentBurst().finalize()
        val result = policy.applyEdit(finalized, edit(2, revision = 3, text = "사후수정"))
        val corrected = assertInstanceOf(BurstCorrection.Corrected::class.java, result)
        assertEquals(BurstStatus.CORRECTED, corrected.burst.status)
        assertEquals(finalized.burstId, corrected.burst.burstId, "과거 burst ID 불변")
        assertEquals(MessageId(2), corrected.targetMessageId)
        assertEquals(3L, corrected.revision, "revision provenance 보존")
    }

    @Test
    fun `대상 메시지가 없으면 영향 없음`() {
        val open = twoFragmentBurst()
        assertEquals(BurstCorrection.NotApplicable, policy.applyEdit(open, edit(999, 1, "x")))
    }

    @Test
    fun `삭제는 content 를 비워 combined text 에 원문이 남지 않게 한다 (T015)`() {
        val open = twoFragmentBurst()
        val result = policy.applyDeletion(open, deletion(1))
        val updated = assertInstanceOf(BurstCorrection.FragmentUpdated::class.java, result)
        val frag1 = updated.burst.fragments.first { it.messageId == MessageId(1) }
        assertFalse(frag1.content is MessageContent.Available, "삭제된 조각 content 는 원문이 아니다")
        // combined text 에 원본1 비잔존, 원본2 는 남음.
        val combined =
            updated.burst.fragments
                .mapNotNull { (it.content as? MessageContent.Available)?.text }
                .joinToString(" ")
        assertFalse(combined.contains("원본1"))
        assertTrue(combined.contains("원본2"))
    }

    @Test
    fun `모든 텍스트가 삭제되면 빈 버스트로 lineage 삭제가 발생한다 (T015)`() {
        val finalized = twoFragmentBurst().finalize()
        val afterFirst = policy.applyDeletion(finalized, deletion(1))
        // 첫 삭제: finalize 후라 CORRECTED.
        val corrected = assertInstanceOf(BurstCorrection.Corrected::class.java, afterFirst)
        val afterSecond = policy.applyDeletion(corrected.burst, deletion(2))
        val emptied = assertInstanceOf(BurstCorrection.Emptied::class.java, afterSecond)
        assertEquals(MessageId(2), emptied.deletedMessageId)
        val combined = emptied.burst.fragments.mapNotNull { (it.content as? MessageContent.Available)?.text }
        assertTrue(combined.isEmpty(), "lineage 삭제 — 원문 텍스트 전부 비잔존")
        assertEquals(finalized.burstId, emptied.burst.burstId, "burstId 불변")
    }
}
