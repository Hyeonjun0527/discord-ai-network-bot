package com.discordassistant.central.participation.application.context

import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextContent
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextEntry
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextScope
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSnapshot
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSourceType
import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.judge.JudgeDecisionConstraints
import com.discordassistant.central.participation.application.judge.JudgeFewShotBadAlternativePayload
import com.discordassistant.central.participation.application.judge.JudgeFewShotExamplePayload
import com.discordassistant.central.participation.application.judge.JudgeFewShotRawMessagePayload
import com.discordassistant.central.participation.application.judge.JudgeFewShotSetPayload
import com.discordassistant.central.participation.application.judge.JudgeMemoryRef
import com.discordassistant.central.participation.application.judge.SingleJudgeSceneObservation
import com.discordassistant.central.participation.application.port.out.SceneSnapshotRef
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotAction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class NiaJudgeContextAssemblerTest {
    private val scope = RawContextScope(guildId = 1L, channelId = 2L)
    private val now = Instant.parse("2026-06-29T00:00:00Z")

    @Test
    fun `assembler 는 raw window 를 primary 로 두고 memory 와 constraints 를 부가 context 로 붙인다`() {
        val request =
            NiaJudgeContextAssembler(JudgeContextWindowBuilder(maxRawChars = 1_000))
                .assemble(
                    NiaJudgeContextInput(
                        rawContextSnapshot =
                            RawContextSnapshot(
                                scope,
                                listOf(
                                    entry(10L, "내 답장을 안봐"),
                                    entry(11L, "야 이럴땐 위로해줘", now.plusSeconds(1), replyToMessageId = 10L),
                                ),
                            ),
                        sceneObservation =
                            SingleJudgeSceneObservation(
                                ref = SceneSnapshotRef("guild_a", "channel_a", sceneSeq = 7L, contextVersion = 3L),
                                triggerText = "야 이럴땐 위로해줘",
                                directAddressed = true,
                                replyToNia = false,
                                replyToHuman = true,
                                conversationMentionsNia = true,
                                recentAgentBurstCount = 0,
                                silenceMillis = 8_000,
                                lastNiaSpokeAgeSeconds = 120.0,
                            ),
                        memoryRefs =
                            listOf(
                                JudgeMemoryRef(
                                    refId = "mem-1",
                                    claim = "대화 상대는 니아의 짧은 반응을 기대한다",
                                    provenance = "synthetic",
                                    confidence = 0.7,
                                ),
                            ),
                        constraints = constraints(),
                        seed = 42L,
                        fewShotSet = fewShotSet(),
                    ),
                )

        assertThat(request.rawContextWindow.messages.map { it.ref }).containsExactly("msg_1", "msg_2")
        assertThat(request.rawContextWindow.messages[1].replyToRef).isEqualTo("msg_1")
        assertThat(request.rawContextWindow.quotedSceneData).contains("야 이럴땐 위로해줘")
        assertThat(request.memoryRefs.single().refId).isEqualTo("mem-1")
        assertThat(request.fewShotSet.setId).isEqualTo(1L)
        assertThat(request.fewShotSet.version).isEqualTo(2)
        val examples = request.fewShotSet.examples
        val example = examples.single()
        assertThat(example.expectedAction).isEqualTo(NiaFewShotAction.SPEAK)
        assertThat(request.constraints.allowedActions).containsExactlyInAnyOrderElementsOf(SocialActionKind.entries)
        assertThat(request.featureVector.version).isEqualTo(FeatureCatalog.VERSION)
    }

    private fun entry(
        messageId: Long,
        text: String,
        occurredAt: Instant = now,
        replyToMessageId: Long? = null,
    ): RawContextEntry =
        RawContextEntry(
            scope = scope,
            messageId = messageId,
            authorPseudonym = "user_a",
            occurredAt = occurredAt,
            replyToMessageId = replyToMessageId,
            sourceType = RawContextSourceType.HUMAN,
            content = RawContextContent.Available(text),
        )

    private fun constraints(): JudgeDecisionConstraints =
        JudgeDecisionConstraints(
            allowedActions = SocialActionKind.entries.toSet(),
            speechAllowed = true,
            reactionAllowed = true,
            maxDelayMillis = 30_000,
        )

    private fun fewShotSet(): JudgeFewShotSetPayload =
        JudgeFewShotSetPayload(
            setId = 1L,
            version = 2,
            examples =
                listOf(
                    JudgeFewShotExamplePayload(
                        exampleId = "fs_direct_reply",
                        title = "direct reply request",
                        rawMessages =
                            listOf(
                                JudgeFewShotRawMessagePayload(
                                    ref = "m1",
                                    authorRole = "member",
                                    offsetMs = 0,
                                    text = "야 이럴땐 위로해줘",
                                ),
                            ),
                        expectedAction = NiaFewShotAction.SPEAK,
                        reason = "Direct continuation with NIA should be judged from raw scene evidence.",
                        evidenceRefs = setOf("m1"),
                        badAlternative =
                            JudgeFewShotBadAlternativePayload(
                                action = NiaFewShotAction.WAIT,
                                whyBad = "Waiting would make the direct request look ignored.",
                            ),
                    ),
                ),
        )
}
