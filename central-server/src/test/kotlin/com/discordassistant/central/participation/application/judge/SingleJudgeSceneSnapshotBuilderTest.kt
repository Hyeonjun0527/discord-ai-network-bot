package com.discordassistant.central.participation.application.judge

import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.feature.MemoryObservation
import com.discordassistant.central.participation.application.feature.RelationshipFeatures
import com.discordassistant.central.participation.application.feature.RelationshipObservation
import com.discordassistant.central.participation.application.port.out.SceneSnapshotRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SingleJudgeSceneSnapshotBuilderTest {
    private val ref = SceneSnapshotRef("guild_a", "channel_a", sceneSeq = 7L, contextVersion = 2L)

    @Test
    fun `같은 관찰 입력은 같은 scene snapshot 과 feature vector 를 만든다`() {
        val observation =
            observation(
                triggerText = "니아야 지금 뭐라고 답해야 할까?",
                directAddressed = true,
                replyToNia = true,
                recentAgentBurstCount = 2,
                silenceMillis = 4_000,
                lastNiaSpokeAgeSeconds = 14.5,
                pendingActionIds = listOf("pending-1", "pending-2"),
                humanLikelyAnswering = true,
                directAddressPressure = 0.7,
                replyChainDepth = 2,
                nicknameCall = true,
                previousIgnoredRequestCount = 3,
                rateLimitPressure = 0.4,
                antiSpamPressure = 0.6,
            )

        val first = SingleJudgeSceneSnapshotBuilder.build(observation)
        val second = SingleJudgeSceneSnapshotBuilder.build(observation)
        val features = first.featureVector.features

        assertThat(second).isEqualTo(first)
        assertThat(first.sceneSnapshot.textSignals.isQuestion).isTrue()
        assertThat(first.sceneSnapshot.textSignals.replyTargetKind).isEqualTo("nia")
        assertThat(first.sceneSnapshot.agentState.recentSpeechCount).isEqualTo(2)
        assertThat(first.sceneSnapshot.agentState.lastSpokeAgeSeconds).isEqualTo(14.5)
        assertThat(first.sceneSnapshot.conversationState.humanLikelyAnswering).isTrue()
        assertThat(first.sceneSnapshot.conversationState.idleGapLikely).isFalse()
        assertThat(first.sceneSnapshot.turnTakingState.directAddressPressure).isEqualTo(0.7)
        assertThat(first.sceneSnapshot.turnTakingState.replyChainDepth).isEqualTo(2)
        assertThat(first.sceneSnapshot.turnTakingState.nicknameCall).isTrue()
        assertThat(first.sceneSnapshot.turnTakingState.previousIgnoredRequestCount).isEqualTo(3)
        assertThat(first.sceneSnapshot.runtimeGuardState.rateLimitPressure).isEqualTo(0.4)
        assertThat(first.sceneSnapshot.runtimeGuardState.antiSpamPressure).isEqualTo(0.6)
        assertThat(features.getValue(FeatureCatalog.BURST_IS_REPLY).value).isEqualTo(1.0)
        assertThat(features.getValue(FeatureCatalog.THREAD_DIRECT_ADDRESS_PRESSURE).value).isEqualTo(0.7)
        assertThat(features.getValue(FeatureCatalog.THREAD_REPLY_CHAIN_DEPTH).value).isEqualTo(2.0)
        assertThat(features.getValue(FeatureCatalog.THREAD_PREVIOUS_IGNORED_REQUEST_COUNT).value).isEqualTo(3.0)
        assertThat(features.getValue(FeatureCatalog.TEMPO_RATE_LIMIT_PRESSURE).value).isEqualTo(0.4)
        assertThat(features.getValue(FeatureCatalog.TEMPO_ANTI_SPAM_PRESSURE).value).isEqualTo(0.6)
        assertThat(features.getValue(FeatureCatalog.AGENT_PENDING_ACTION_COUNT).value).isEqualTo(2.0)
    }

    @Test
    fun `원문 압력 신호는 direct address 판단을 대체하지 않고 별도 signal 로 남긴다`() {
        val pressureOnly =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(
                    triggerText = "누가 좀 위로해줘",
                    directAddressed = false,
                    conversationMentionsNia = false,
                    silenceMillis = 8_000,
                ),
            )

        assertThat(pressureOnly.sceneSnapshot.directAddressed).isFalse()
        assertThat(pressureOnly.sceneSnapshot.textSignals.callPressure).isGreaterThan(0.0)
        val pressureOnlyFeatures = pressureOnly.featureVector.features
        assertThat(pressureOnlyFeatures.getValue(FeatureCatalog.BURST_HAS_MENTION).value).isEqualTo(0.0)
    }

    @Test
    fun `과거 니아 언급은 context 로 남지만 현재 직접 호명과 call pressure 가 되지 않는다`() {
        val result =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(
                    triggerText = "서연아 나 고민이 있어",
                    conversationMentionsNia = true,
                ),
            )
        val features = result.featureVector.features

        assertThat(result.sceneSnapshot.conversationMentionsNia).isTrue()
        assertThat(result.sceneSnapshot.directAddressed).isFalse()
        assertThat(result.sceneSnapshot.textSignals.callPressure).isEqualTo(0.0)
        assertThat(result.sceneSnapshot.conversationState.niaAddressedOrIdleOpportunity).isFalse()
        assertThat(features.getValue(FeatureCatalog.BURST_HAS_MENTION).value).isEqualTo(0.0)
    }

    @Test
    fun `과거 니아 언급은 현재 사람 간 reply 를 직접 호명으로 뒤집지 않는다`() {
        val result =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(
                    triggerText = "서연아 내 고민 좀 들어줘",
                    replyToHuman = true,
                    conversationMentionsNia = true,
                ),
            )
        val features = result.featureVector.features

        assertThat(result.sceneSnapshot.conversationMentionsNia).isTrue()
        assertThat(result.sceneSnapshot.directAddressed).isFalse()
        assertThat(result.sceneSnapshot.textSignals.replyTargetKind).isEqualTo("human")
        assertThat(result.sceneSnapshot.textSignals.callPressure).isEqualTo(0.0)
        assertThat(result.sceneSnapshot.conversationState.humansTalkingToEachOtherLikely).isTrue()
        assertThat(result.sceneSnapshot.conversationState.niaAddressedOrIdleOpportunity).isFalse()
        assertThat(features.getValue(FeatureCatalog.BURST_HAS_MENTION).value).isEqualTo(0.0)
    }

    @Test
    fun `nickname call is part of direct address and turn taking scene state`() {
        val result =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(
                    triggerText = "서연아 잠깐만",
                    directAddressed = false,
                    nicknameCall = true,
                ),
            )

        assertThat(result.sceneSnapshot.directAddressed).isTrue()
        assertThat(result.sceneSnapshot.turnTakingState.nicknameCall).isTrue()
        assertThat(result.sceneSnapshot.conversationState.niaAddressedOrIdleOpportunity).isTrue()
    }

    @Test
    fun `colloquial follow-up question and nia turn continuity remain separate evidence`() {
        val result =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(
                    triggerText = "머하노",
                    directAddressed = false,
                    niaTurnContinuationLikely = true,
                    lastNiaSpokeAgeSeconds = 193.0,
                ),
            )

        assertThat(result.sceneSnapshot.directAddressed).isFalse()
        assertThat(result.sceneSnapshot.textSignals.isQuestion).isTrue()
        assertThat(result.sceneSnapshot.conversationState.niaTurnContinuationLikely).isTrue()
        assertThat(result.sceneSnapshot.agentState.lastSpokeAgeSeconds).isEqualTo(193.0)
    }

    @Test
    fun `explicit stop request remains distinct from call pressure`() {
        val stopRequest =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(
                    triggerText = "대답하지 마",
                    directAddressed = false,
                    niaTurnContinuationLikely = true,
                ),
            )
        val ordinaryStatement =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(triggerText = "대답하지만 확신은 없어"),
            )
        val responseComplaint =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(triggerText = "왜 반응 안 해?"),
            )
        val quietRequest =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(triggerText = "조용히 말해줘"),
            )
        val similarWord =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(triggerText = "그만큼 좋아"),
            )
        val responseOptional =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(triggerText = "대답 안 해도 돼"),
            )
        val compactStop =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(triggerText = "니아야 그만해", directAddressed = true),
            )
        val shortStop =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(triggerText = "니아야 답하지 마", directAddressed = true),
            )

        assertThat(stopRequest.sceneSnapshot.textSignals.callPressure).isGreaterThan(0.0)
        assertThat(stopRequest.sceneSnapshot.textSignals.stopRequested).isTrue()
        assertThat(ordinaryStatement.sceneSnapshot.textSignals.stopRequested).isFalse()
        assertThat(responseComplaint.sceneSnapshot.textSignals.stopRequested).isFalse()
        assertThat(quietRequest.sceneSnapshot.textSignals.stopRequested).isFalse()
        assertThat(similarWord.sceneSnapshot.textSignals.stopRequested).isFalse()
        assertThat(responseOptional.sceneSnapshot.textSignals.stopRequested).isTrue()
        assertThat(compactStop.sceneSnapshot.textSignals.stopRequested).isTrue()
        assertThat(shortStop.sceneSnapshot.textSignals.stopRequested).isTrue()
    }

    @Test
    fun `named human addressee is explicit handoff evidence`() {
        val namedHandoff =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(
                    triggerText = "서연아 뭐해?",
                    niaTurnContinuationLikely = true,
                    knownHumanDisplayNames = setOf("서연"),
                ),
            )
        val embeddedNamedHandoff =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(
                    triggerText = "근데 서연아 뭐해?",
                    niaTurnContinuationLikely = true,
                    knownHumanDisplayNames = setOf("서연"),
                ),
            )
        val laterNamedHandoff =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(
                    triggerText = "진짜야? 서연아 너는?",
                    niaTurnContinuationLikely = true,
                    knownHumanDisplayNames = setOf("서연"),
                ),
            )
        val correctedHandoff =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(
                    triggerText = "아니 니아 말고 서연이한테 한 말이야",
                    directAddressed = true,
                    knownHumanDisplayNames = setOf("서연"),
                ),
            )
        val ordinaryQuestion =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(triggerText = "뭐야 이거", niaTurnContinuationLikely = true),
            )
        val adjectiveQuestion =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(
                    triggerText = "괜찮아?",
                    niaTurnContinuationLikely = true,
                    knownHumanDisplayNames = setOf("서연"),
                ),
            )
        val confirmationQuestion =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(
                    triggerText = "진짜야?",
                    niaTurnContinuationLikely = true,
                    knownHumanDisplayNames = setOf("서연"),
                ),
            )

        assertThat(namedHandoff.sceneSnapshot.textSignals.otherAddresseeLikely).isTrue()
        assertThat(embeddedNamedHandoff.sceneSnapshot.textSignals.otherAddresseeLikely).isTrue()
        assertThat(laterNamedHandoff.sceneSnapshot.textSignals.otherAddresseeLikely).isTrue()
        assertThat(correctedHandoff.sceneSnapshot.textSignals.otherAddresseeLikely).isTrue()
        assertThat(ordinaryQuestion.sceneSnapshot.textSignals.otherAddresseeLikely).isFalse()
        assertThat(adjectiveQuestion.sceneSnapshot.textSignals.otherAddresseeLikely).isFalse()
        assertThat(confirmationQuestion.sceneSnapshot.textSignals.otherAddresseeLikely).isFalse()
    }

    @Test
    fun `human to human flow and idle opportunity are explicit scene signals`() {
        val humanThread =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(
                    triggerText = "그건 내가 답할게",
                    replyToHuman = true,
                    directAddressed = false,
                ),
            )
        val idleOpportunity =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(
                    triggerText = "아무도 답이 없네",
                    directAddressed = false,
                    silenceMillis = 8_000,
                ),
            )

        assertThat(humanThread.sceneSnapshot.conversationState.humansTalkingToEachOtherLikely).isTrue()
        assertThat(humanThread.sceneSnapshot.conversationState.niaAddressedOrIdleOpportunity).isFalse()
        assertThat(idleOpportunity.sceneSnapshot.conversationState.niaAddressedOrIdleOpportunity).isTrue()
    }

    @Test
    fun `본문 부재는 missing 으로 남기고 관측된 0 은 present zero 로 남긴다`() {
        val result =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(
                    triggerText = null,
                    directAddressed = false,
                    recentAgentBurstCount = 0,
                    silenceMillis = null,
                    lastNiaSpokeAgeSeconds = null,
                    pendingActionIds = emptyList(),
                ),
            )

        val features = result.featureVector.features
        assertThat(result.sceneSnapshot.textSignals.contentAvailable).isFalse()
        assertThat(features.getValue(FeatureCatalog.BURST_IS_QUESTION).missing).isTrue()
        assertThat(features.getValue(FeatureCatalog.AGENT_LAST_SPOKE_AGE_SECONDS).missing).isTrue()
        assertThat(features.getValue(FeatureCatalog.AGENT_RECENT_BURST_COUNT).missing).isFalse()
        assertThat(features.getValue(FeatureCatalog.AGENT_RECENT_BURST_COUNT).value).isEqualTo(0.0)
        assertThat(features.getValue(FeatureCatalog.AGENT_PENDING_ACTION_COUNT).missing).isFalse()
        assertThat(features.getValue(FeatureCatalog.AGENT_PENDING_ACTION_COUNT).value).isEqualTo(0.0)
        assertThat(features.getValue(FeatureCatalog.REL_SAMPLE_CONFIDENCE).missing).isTrue()
        assertThat(features.getValue(FeatureCatalog.MEMORY_RELEVANT_CONFIDENCE).missing).isTrue()
    }

    @Test
    fun `relationship 와 memory confidence 는 낮은 값 그대로 judge 입력에 남긴다`() {
        val result =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(
                    relationshipObservation =
                        RelationshipObservation(
                            familiarity = 0.85,
                            reciprocity = 0.25,
                            banterAcceptance = 0.70,
                            sampleSize = 1,
                            observed = true,
                        ),
                    memoryObservation =
                        MemoryObservation(
                            relevantPresent = true,
                            topConfidence = 0.20,
                            freshestAgeSeconds = 120.0,
                            pendingIntentActive = true,
                        ),
                ),
            )

        val features = result.featureVector.features
        val expectedRelConfidence = RelationshipFeatures.sampleConfidence(1)
        assertThat(result.sceneSnapshot.relationshipState.familiarity).isEqualTo(0.85)
        assertThat(result.sceneSnapshot.relationshipState.sampleConfidence).isEqualTo(expectedRelConfidence)
        assertThat(result.sceneSnapshot.memoryState.topConfidence).isEqualTo(0.20)
        assertThat(result.sceneSnapshot.memoryState.pendingIntentActive).isTrue()
        assertThat(features.getValue(FeatureCatalog.REL_FAMILIARITY).value).isEqualTo(0.85)
        assertThat(features.getValue(FeatureCatalog.REL_SAMPLE_CONFIDENCE).value).isEqualTo(expectedRelConfidence)
        assertThat(features.getValue(FeatureCatalog.MEMORY_RELEVANT_PRESENT).value).isEqualTo(1.0)
        assertThat(features.getValue(FeatureCatalog.MEMORY_RELEVANT_CONFIDENCE).value).isEqualTo(0.20)
        assertThat(features.getValue(FeatureCatalog.MEMORY_PENDING_INTENT_ACTIVE).value).isEqualTo(1.0)
    }

    @Test
    fun `잘못된 scene 관찰값은 fail fast 한다`() {
        assertThatThrownBy { observation(recentAgentBurstCount = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { observation(silenceMillis = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { observation(lastNiaSpokeAgeSeconds = -0.1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { observation(pendingActionIds = listOf(" ")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { observation(directAddressPressure = 1.1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { observation(replyChainDepth = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { observation(previousIgnoredRequestCount = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { observation(rateLimitPressure = -0.1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { observation(antiSpamPressure = 1.1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun observation(
        triggerText: String? = "니아야?",
        directAddressed: Boolean = false,
        replyToNia: Boolean = false,
        replyToHuman: Boolean = false,
        conversationMentionsNia: Boolean = false,
        recentAgentBurstCount: Int = 0,
        silenceMillis: Long? = 0,
        lastNiaSpokeAgeSeconds: Double? = null,
        pendingActionIds: List<String> = emptyList(),
        humanLikelyAnswering: Boolean = false,
        resolvedLikely: Boolean = false,
        niaTurnContinuationLikely: Boolean = false,
        directAddressPressure: Double = 0.0,
        replyChainDepth: Int = 0,
        nicknameCall: Boolean = false,
        previousIgnoredRequestCount: Int = 0,
        humansTalkingToEachOtherLikely: Boolean = false,
        rateLimitPressure: Double = 0.0,
        antiSpamPressure: Double = 0.0,
        knownHumanDisplayNames: Set<String> = emptySet(),
        relationshipObservation: RelationshipObservation? = null,
        memoryObservation: MemoryObservation? = null,
    ): SingleJudgeSceneObservation =
        SingleJudgeSceneObservation(
            ref = ref,
            triggerText = triggerText,
            directAddressed = directAddressed,
            replyToNia = replyToNia,
            replyToHuman = replyToHuman,
            conversationMentionsNia = conversationMentionsNia,
            recentAgentBurstCount = recentAgentBurstCount,
            silenceMillis = silenceMillis,
            lastNiaSpokeAgeSeconds = lastNiaSpokeAgeSeconds,
            pendingActionIds = pendingActionIds,
            humanLikelyAnswering = humanLikelyAnswering,
            resolvedLikely = resolvedLikely,
            niaTurnContinuationLikely = niaTurnContinuationLikely,
            directAddressPressure = directAddressPressure,
            replyChainDepth = replyChainDepth,
            nicknameCall = nicknameCall,
            previousIgnoredRequestCount = previousIgnoredRequestCount,
            humansTalkingToEachOtherLikely = humansTalkingToEachOtherLikely,
            rateLimitPressure = rateLimitPressure,
            antiSpamPressure = antiSpamPressure,
            knownHumanDisplayNames = knownHumanDisplayNames,
            relationshipObservation = relationshipObservation,
            memoryObservation = memoryObservation,
        )
}
