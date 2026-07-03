package com.discordassistant.central.participation.application.judge

import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextContent
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextEntry
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextScope
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSnapshot
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSourceType
import com.discordassistant.central.participation.application.context.JudgeContextWindowBuilder
import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.port.out.FeatureVectorView
import com.discordassistant.central.participation.application.port.out.SceneSnapshotRef
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotAction
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotPrivacyClass
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.reflect.full.memberProperties

class SingleParticipationJudgeContractTest {
    private val scope = RawContextScope(guildId = 1L, channelId = 2L)
    private val now = Instant.parse("2026-06-29T00:00:00Z")

    @Test
    fun `single judge request 는 원문 window 를 evidence 로 포함한다`() {
        val request = sampleRequest()
        val props = SingleJudgeDecisionRequest::class.memberProperties.map { it.name }

        assertThat(props)
            .contains("rawContextWindow", "sceneSnapshot", "featureVector", "fewShotSet", "memoryRefs", "constraints")
        assertThat(request.rawContextWindow.quotedSceneData).contains("위로하라고")
        assertThat(request.rawContextWindow.quotedSceneData).contains(JudgeContextWindowBuilder.REASSERT)
    }

    @Test
    fun `single judge request 는 active few-shot constitution 을 함께 운반한다`() {
        val request = sampleRequest()
        val fewShotSet = request.fewShotSet
        val example = fewShotSet.examples.single()

        assertThat(fewShotSet.setId).isEqualTo(1L)
        assertThat(fewShotSet.version).isEqualTo(3)
        assertThat(example.expectedAction).isEqualTo(NiaFewShotAction.SPEAK)
        assertThat(example.evidenceRefs).containsExactly("m2", "m3")
        assertThat(example.badAlternative.action).isEqualTo(NiaFewShotAction.WAIT)
        assertThat(fewShotSet.toString()).doesNotContain("위로하라고")
        assertThat(example.toString()).doesNotContain("위로하라고")
        assertThat(example.rawMessages.joinToString()).doesNotContain("위로하라고")
    }

    @Test
    fun `SPEAK 출력은 speech text 가 아니라 intent 와 scene direction 만 담는다`() {
        val decision =
            SingleJudgeDecision(
                action = SocialActionKind.SPEAK,
                confidence = 0.82,
                delay = JudgeDecisionDelay.IMMEDIATE,
                reactionCandidate = null,
                speechIntent =
                    JudgeSpeechIntent(
                        intentSummary = "사용자가 반응 부재를 지적했으니 짧게 인정하고 다가간다",
                        sceneDirection = "한 문장으로 가볍게 받아주고 과한 위로는 피한다",
                        actHint = "acknowledge",
                    ),
                toneAxes =
                    JudgeToneAxes(
                        warmth = 0.7,
                        playfulness = 0.1,
                        directness = 0.6,
                        emotionalIntensity = 0.2,
                    ),
                reasonCode = JudgeReasonCode("direct_address_pressure"),
            )

        val intentProps = JudgeSpeechIntent::class.memberProperties.map { it.name.lowercase() }
        assertThat(intentProps).noneMatch { name ->
            name.contains("text") || name.contains("utterance") || name.contains("content") || name.contains("message")
        }
        assertThat(decision.speechIntent!!.sceneDirection).contains("한 문장")
    }

    @Test
    fun `SPEAK 와 REACT 는 필요한 payload 없이는 유효하지 않다`() {
        assertThatThrownBy {
            SingleJudgeDecision(
                action = SocialActionKind.SPEAK,
                confidence = 0.8,
                delay = JudgeDecisionDelay.IMMEDIATE,
                reactionCandidate = null,
                speechIntent = null,
                toneAxes = JudgeToneAxes.NEUTRAL,
                reasonCode = JudgeReasonCode("missing_intent"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            SingleJudgeDecision(
                action = SocialActionKind.REACT,
                confidence = 0.8,
                delay = JudgeDecisionDelay.IMMEDIATE,
                reactionCandidate = null,
                speechIntent = null,
                toneAxes = JudgeToneAxes.NEUTRAL,
                reasonCode = JudgeReasonCode("missing_reaction"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `constraints 는 speech 와 reaction hard gate 충돌을 fail fast 한다`() {
        assertThatThrownBy {
            JudgeDecisionConstraints(
                allowedActions = SocialActionKind.entries.toSet(),
                speechAllowed = false,
                reactionAllowed = true,
                maxDelayMillis = 30_000,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            JudgeDecisionConstraints(
                allowedActions = setOf(SocialActionKind.IGNORE, SocialActionKind.REACT),
                speechAllowed = true,
                reactionAllowed = false,
                maxDelayMillis = 30_000,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `tone axes 는 상황 enum 폭증 대신 연속 축으로 검증된다`() {
        assertThatThrownBy {
            JudgeToneAxes(
                warmth = 1.2,
                playfulness = 0.0,
                directness = 0.5,
                emotionalIntensity = 0.0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun sampleRequest(): SingleJudgeDecisionRequest {
        val window =
            JudgeContextWindowBuilder(maxRawChars = 1_000)
                .build(
                    RawContextSnapshot(
                        scope = scope,
                        entries =
                            listOf(
                                RawContextEntry(
                                    scope = scope,
                                    messageId = 10L,
                                    authorPseudonym = "user_a",
                                    occurredAt = now,
                                    replyToMessageId = null,
                                    sourceType = RawContextSourceType.HUMAN,
                                    content = RawContextContent.Available("야 이럴땐 위로하라고"),
                                ),
                            ),
                    ),
                )
        return SingleJudgeDecisionRequest(
            rawContextWindow = window,
            sceneSnapshot =
                SingleJudgeSceneSnapshot(
                    ref = SceneSnapshotRef("guild_a", "2", sceneSeq = 3L, contextVersion = 1L),
                    directAddressed = true,
                    replyToNia = false,
                    conversationMentionsNia = true,
                    recentAgentBurstCount = 0,
                    silenceMillis = 8_000,
                ),
            featureVector = FeatureVectorView.empty(version = FeatureCatalog.VERSION),
            fewShotSet = sampleFewShotSet(),
            memoryRefs =
                listOf(
                    JudgeMemoryRef(
                        refId = "mem-1",
                        claim = "user_a 는 니아에게 짧은 반응을 기대한다",
                        provenance = "observed",
                        confidence = 0.7,
                    ),
                ),
            constraints =
                JudgeDecisionConstraints(
                    allowedActions = SocialActionKind.entries.toSet(),
                    speechAllowed = true,
                    reactionAllowed = true,
                    maxDelayMillis = 30_000,
                ),
            schemaVersion = SingleJudgeDecisionRequest.CURRENT_SCHEMA_VERSION,
            seed = 99L,
        )
    }

    private fun sampleFewShotSet(): JudgeFewShotSetPayload =
        JudgeFewShotSetPayload(
            setId = 1L,
            version = 3,
            examples =
                listOf(
                    JudgeFewShotExamplePayload(
                        exampleId = "fs_example_direct_reply",
                        title = "direct reply request after ignored NIA turn",
                        rawMessages =
                            listOf(
                                JudgeFewShotRawMessagePayload(
                                    ref = "m2",
                                    authorRole = "nia",
                                    offsetMs = -120_000,
                                    text = "그래, 그게 내가 뭘 잘못한 거지?",
                                ),
                                JudgeFewShotRawMessagePayload(
                                    ref = "m3",
                                    authorRole = "member",
                                    offsetMs = 0,
                                    text = "야 이럴땐 위로하라고",
                                ),
                            ),
                        expectedAction = NiaFewShotAction.SPEAK,
                        reason = "The user is continuing a direct exchange with NIA.",
                        evidenceRefs = setOf("m2", "m3"),
                        badAlternative =
                            JudgeFewShotBadAlternativePayload(
                                action = NiaFewShotAction.WAIT,
                                whyBad = "Waiting longer would read as ignoring the direct request.",
                            ),
                        tags = setOf("direct-address"),
                        priority = 100,
                        privacyClass = NiaFewShotPrivacyClass.SYNTHETIC,
                    ),
                ),
        )
}
