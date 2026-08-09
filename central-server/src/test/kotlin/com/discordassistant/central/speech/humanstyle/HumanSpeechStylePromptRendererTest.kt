package com.discordassistant.central.speech.humanstyle

import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStylePromptRenderer
import com.discordassistant.central.speech.domain.model.HumanSpeechResponseMode
import com.discordassistant.central.speech.domain.model.HumanSpeechSceneTrait
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleBubble
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleMatch
import com.discordassistant.central.speech.domain.model.HumanSpeechStylePromptSurface
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleProviderStyleCue
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleResponseMove
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleRhythmCue
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleSelection
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class HumanSpeechStylePromptRendererTest {
    @Test
    fun `style pattern은 카드 원문 대화와 실제 답변을 provider와 trace 모두에서 분리한다`() {
        val responseText = "private-style-response-marker-12345"
        val contextText = "private-style-context-marker-67890"
        val selection =
            HumanSpeechStyleSelection(
                listOf(
                    HumanSpeechStyleMatch(
                        example("human-style-000001", responseText = responseText).copy(
                            contextBubbles = listOf(HumanSpeechStyleBubble("가명1", contextText)),
                            promptSurface = HumanSpeechStylePromptSurface.STYLE_PATTERN,
                        ),
                        0.9,
                        HumanSpeechStyleResponseMove.ALIGNMENT_COMPLAINT,
                    ),
                ),
            )

        val payload = HumanSpeechStylePromptRenderer().appendTo("현재 장면", selection)

        assertThat(payload.providerUserPrompt)
            .contains("사람 말투 리듬 참고", "비식별 추출 패턴", "반응 순서:", "세부 초점:", "말풍선 형식:")
            .doesNotContain(responseText, contextText, "가명1", "실제 사람 반응:", "앞 대화:")
        assertThat(payload.traceUserPrompt).contains("private human-style examples omitted")
        assertThat(payload.traceUserPrompt).doesNotContain(responseText, contextText, "가명1")
    }

    @Test
    fun `현재 장면이 뒷받침하지 않은 카드 submove와 행동 리듬은 provider에 넣지 않는다`() {
        val card =
            example(
                "human-style-000001",
                responseMode = HumanSpeechResponseMode.CARE,
            ).copy(
                responseMove = HumanSpeechStyleResponseMove.CARE_PHYSICAL,
                responseRhythm =
                    listOf(
                        HumanSpeechStyleRhythmCue.GENTLE_CARE,
                        HumanSpeechStyleRhythmCue.SINGLE_BUBBLE,
                    ),
            )
        val payload =
            HumanSpeechStylePromptRenderer().appendTo(
                "현재 장면",
                HumanSpeechStyleSelection(listOf(HumanSpeechStyleMatch(card, 0.9))),
            )

        assertThat(payload.providerUserPrompt)
            .contains("반응 순서:", "호흡: 한 말풍선으로 짧게 끝낸다")
            .doesNotContain("세부 초점:", "말풍선 형식:", "몸 상태는 걱정부터")
    }

    @Test
    fun `선택된 카드의 비의미적 말투 결은 현재 장면 trait 없이도 provider에 보낸다`() {
        val card =
            example(
                "human-style-000001",
                responseMode = HumanSpeechResponseMode.CARE,
            ).copy(
                responseMove = HumanSpeechStyleResponseMove.CARE_PHYSICAL,
                providerStyleCues = listOf(HumanSpeechStyleProviderStyleCue.CARE_SOFT_NUDGE),
                responseRhythm =
                    listOf(
                        HumanSpeechStyleRhythmCue.GENTLE_CARE,
                        HumanSpeechStyleRhythmCue.SINGLE_BUBBLE,
                    ),
            )

        val payload =
            HumanSpeechStylePromptRenderer().appendTo(
                "현재 장면",
                HumanSpeechStyleSelection(listOf(HumanSpeechStyleMatch(card, 0.9))),
            )

        assertThat(payload.providerUserPrompt)
            .contains("말투 결: 강요하지 않고 쉬거나 조심할 여지를 작게 남긴다")
            .doesNotContain("세부 초점:", "말풍선 형식:", "몸 상태는 걱정부터")
    }

    @Test
    fun `현재 장면 단서가 카드 앞 장면과 맞으면 submove 없이도 카드별 형식과 리듬을 쓴다`() {
        val card =
            example(
                "human-style-000001",
                responseMode = HumanSpeechResponseMode.CARE,
            ).copy(
                responseMove = HumanSpeechStyleResponseMove.CARE_FATIGUE,
                sceneTraits = listOf(HumanSpeechSceneTrait.CARE_PHYSICAL_CONDITION),
                responseRhythm =
                    listOf(
                        HumanSpeechStyleRhythmCue.GENTLE_CARE,
                        HumanSpeechStyleRhythmCue.SINGLE_BUBBLE,
                    ),
            )

        val payload =
            HumanSpeechStylePromptRenderer().appendTo(
                "현재 장면",
                HumanSpeechStyleSelection(
                    listOf(
                        HumanSpeechStyleMatch(
                            example = card,
                            score = 0.9,
                            sceneSupportedSceneTrait = HumanSpeechSceneTrait.CARE_PHYSICAL_CONDITION,
                        ),
                    ),
                ),
            )

        assertThat(payload.providerUserPrompt)
            .contains("장면 결: 몸 상태가 좋지 않아 짧게 챙기는 장면", "말풍선 형식:", "걱정부터 짧게")
            .doesNotContain("세부 초점:")
    }

    @Test
    fun `두 reference의 같은 primary style cue는 provider prompt에 한 번만 넣는다`() {
        val first =
            example(
                "human-style-000001",
                responseMode = HumanSpeechResponseMode.CARE,
            ).copy(
                providerStyleCues = listOf(HumanSpeechStyleProviderStyleCue.CARE_GENTLE_VALIDATE),
            )
        val second =
            example(
                "human-style-000002",
                responseMode = HumanSpeechResponseMode.CARE,
            ).copy(
                providerStyleCues = listOf(HumanSpeechStyleProviderStyleCue.CARE_GENTLE_VALIDATE),
                sourceFingerprint = "sha256:${"b".repeat(64)}",
            )

        val payload =
            HumanSpeechStylePromptRenderer().appendTo(
                "현재 장면",
                HumanSpeechStyleSelection(
                    listOf(
                        HumanSpeechStyleMatch(first, 0.9),
                        HumanSpeechStyleMatch(second, 0.8),
                    ),
                ),
            )

        assertThat(payload.providerUserPrompt)
            .containsOnlyOnce("말투 결: 걱정을 먼저 보이고 길게 해결하려 들지 않는다")
    }

    @Test
    fun `두 reference의 상충하는 primary style cue는 함께 provider prompt에 넣지 않는다`() {
        val softCheck =
            example(
                "human-style-000001",
                responseMode = HumanSpeechResponseMode.FOLLOW_UP,
            ).copy(
                providerStyleCues = listOf(HumanSpeechStyleProviderStyleCue.FOLLOW_UP_SOFT_CHECK),
            )
        val directCheck =
            example(
                "human-style-000002",
                responseMode = HumanSpeechResponseMode.FOLLOW_UP,
            ).copy(
                providerStyleCues = listOf(HumanSpeechStyleProviderStyleCue.FOLLOW_UP_DIRECT_CHECK),
                sourceFingerprint = "sha256:${"b".repeat(64)}",
            )

        val payload =
            HumanSpeechStylePromptRenderer().appendTo(
                "현재 장면",
                HumanSpeechStyleSelection(
                    listOf(
                        HumanSpeechStyleMatch(softCheck, 0.9),
                        HumanSpeechStyleMatch(directCheck, 0.8),
                    ),
                ),
            )

        assertThat(payload.providerUserPrompt)
            .contains("말투 결: 캐묻지 않고 질문 하나로 부드럽게 확인한다")
            .doesNotContain("말투 결: 핵심 한 가지만 또렷하게 묻고 멈춘다")
    }

    @Test
    fun `audit-only raw surface는 renderer가 fail closed로 거부한다`() {
        val selection =
            HumanSpeechStyleSelection(
                listOf(
                    HumanSpeechStyleMatch(
                        example("human-style-000001").copy(
                            promptEligible = false,
                            promptSurface = HumanSpeechStylePromptSurface.AUDIT_ONLY,
                            providerStyleCues = emptyList(),
                        ),
                        0.9,
                    ),
                ),
            )

        assertThatThrownBy { HumanSpeechStylePromptRenderer().appendTo("현재 장면", selection) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("legacy human speech style surface")
    }
}
