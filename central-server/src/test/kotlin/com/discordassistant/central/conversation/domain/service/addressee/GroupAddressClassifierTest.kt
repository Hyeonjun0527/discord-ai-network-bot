package com.discordassistant.central.conversation.domain.service.addressee

import com.discordassistant.central.conversation.domain.model.addressee.AddresseeEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** NEXA-P05-T008: 모호한 경우 특정 사용자에게 과도하게 귀속하지 않는다. */
class GroupAddressClassifierTest {
    @Test
    fun `broadcast mention 은 그룹 발화다`() {
        assertTrue(
            GroupAddressClassifier.isGroupAddressed(
                hasBroadcastMention = true,
                isGeneralQuestion = false,
                directMentionCount = 0,
            ),
        )
    }

    @Test
    fun `일반 질문은 그룹 발화다`() {
        assertTrue(
            GroupAddressClassifier.isGroupAddressed(
                hasBroadcastMention = false,
                isGeneralQuestion = true,
                directMentionCount = 0,
            ),
        )
    }

    @Test
    fun `임계치 이상 다중 mention 은 그룹 발화다`() {
        assertTrue(
            GroupAddressClassifier.isGroupAddressed(
                hasBroadcastMention = false,
                isGeneralQuestion = false,
                directMentionCount = 3,
            ),
        )
    }

    @Test
    fun `단일 직접 호출은 그룹이 아니다`() {
        assertFalse(
            GroupAddressClassifier.isGroupAddressed(
                hasBroadcastMention = false,
                isGeneralQuestion = false,
                directMentionCount = 1,
            ),
        )
    }

    @Test
    fun `그룹 분포는 특정인에 귀속하지 않는다 (none 1, acceptance)`() {
        val dist = GroupAddressClassifier.asGroupDistribution()
        assertTrue(dist.candidates.isEmpty())
        assertEquals(1.0, dist.noneProbability)
        assertTrue(dist.evidence.contains(AddresseeEvidence.GROUP_ADDRESSED))
    }
}
