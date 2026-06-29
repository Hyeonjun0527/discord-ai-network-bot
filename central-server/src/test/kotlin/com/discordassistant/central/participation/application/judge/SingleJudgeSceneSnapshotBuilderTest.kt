package com.discordassistant.central.participation.application.judge

import com.discordassistant.central.participation.application.feature.FeatureCatalog
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
        assertThat(features.getValue(FeatureCatalog.BURST_IS_REPLY).value).isEqualTo(1.0)
        assertThat(features.getValue(FeatureCatalog.AGENT_PENDING_ACTION_COUNT).value).isEqualTo(2.0)
    }

    @Test
    fun `원문 압력 신호는 direct address 판단을 대체하지 않고 별도 signal 로 남긴다`() {
        val addressedByContext =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(
                    triggerText = "야 이럴땐 위로하라고 ㅠㅠ",
                    directAddressed = false,
                    conversationMentionsNia = true,
                    silenceMillis = 8_000,
                ),
            )
        val pressureOnly =
            SingleJudgeSceneSnapshotBuilder.build(
                observation(
                    triggerText = "누가 좀 위로해줘",
                    directAddressed = false,
                    conversationMentionsNia = false,
                    silenceMillis = 8_000,
                ),
            )

        assertThat(addressedByContext.sceneSnapshot.directAddressed).isTrue()
        assertThat(addressedByContext.sceneSnapshot.textSignals.isQuestion).isFalse()
        assertThat(addressedByContext.sceneSnapshot.textSignals.callPressure).isGreaterThanOrEqualTo(0.8)
        assertThat(addressedByContext.sceneSnapshot.textSignals.emotionalIntensity).isGreaterThan(0.0)
        assertThat(addressedByContext.sceneSnapshot.conversationState.idleGapLikely).isTrue()
        val addressedFeatures = addressedByContext.featureVector.features
        assertThat(addressedFeatures.getValue(FeatureCatalog.BURST_HAS_MENTION).value).isEqualTo(1.0)

        assertThat(pressureOnly.sceneSnapshot.directAddressed).isFalse()
        assertThat(pressureOnly.sceneSnapshot.textSignals.callPressure).isGreaterThan(0.0)
        val pressureOnlyFeatures = pressureOnly.featureVector.features
        assertThat(pressureOnlyFeatures.getValue(FeatureCatalog.BURST_HAS_MENTION).value).isEqualTo(0.0)
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
        )
}
