package com.discordassistant.central.speech.humanstyle

import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStyleRagMetrics
import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStyleRagOutcome
import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStyleRagService
import com.discordassistant.central.speech.application.port.out.HumanSpeechStyleExampleStorePort
import com.discordassistant.central.speech.application.port.out.SpeechStyleEmbeddingPort
import com.discordassistant.central.speech.domain.model.ConversationTurn
import com.discordassistant.central.speech.domain.model.HumanSpeechResponseMode
import com.discordassistant.central.speech.domain.model.HumanSpeechSceneTrait
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleBubble
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleExample
import com.discordassistant.central.speech.domain.model.HumanSpeechStylePromptSurface
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleProviderStyleCue
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleQuality
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleResponseForm
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleResponseMove
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleRhythmCue
import com.discordassistant.central.speech.domain.model.SpeechBurstShape
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import com.discordassistant.central.speech.generation.SpeechGenerationFixtures
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class HumanSpeechStyleRagServiceTest {
    @Test
    fun `현재 사회적 발화와 의미 벡터가 맞는 카드만 최대 두 개 검색한다`() {
        val matching = example("human-style-000001", HumanSpeechResponseMode.ALIGNMENT, floatArrayOf(1f, 0f))
        val unrelated = example("human-style-000002", HumanSpeechResponseMode.PLAY, floatArrayOf(0f, 1f))
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val service = HumanSpeechStyleRagService(FakeStore(listOf(matching, unrelated)), embedding)

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    socialAct = SpeechSocialAct.ACKNOWLEDGE,
                    styleResponseMode = HumanSpeechResponseMode.ALIGNMENT,
                    speechIntent = "대화 흐름에 맞춰 짧게 말한다",
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId }).containsExactly("human-style-000001")
        assertThat(embedding.calls).isEqualTo(1)
    }

    @Test
    fun `Judge의 Speech 전용 enum 밖 카드는 의미 벡터가 더 가까워도 검색하지 않는다`() {
        val care = example("human-style-000001", HumanSpeechResponseMode.CARE, floatArrayOf(0.9f, 0.4358899f))
        val alignment = example("human-style-000002", HumanSpeechResponseMode.ALIGNMENT, floatArrayOf(1f, 0f))
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val service = HumanSpeechStyleRagService(FakeStore(listOf(care, alignment)), embedding)

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    socialAct = SpeechSocialAct.ACKNOWLEDGE,
                    styleResponseMode = HumanSpeechResponseMode.CARE,
                    speechIntent = "대화 흐름에 맞춰 짧게 말한다",
                ),
            )

        assertThat(
            selection.matches
                .first()
                .example.responseMode,
        ).isEqualTo(HumanSpeechResponseMode.CARE)
        assertThat(selection.matches.map { it.example.exampleId }).containsExactly("human-style-000001")
        assertThat(embedding.inputs).allSatisfy { input -> assertThat(input).doesNotContain("현재 필요한 반응 방식") }
    }

    @Test
    fun `같은 반응 방식 안에서는 현재 대화와 더 가까운 카드가 먼저 나온다`() {
        val relevant = example("human-style-000001", HumanSpeechResponseMode.ALIGNMENT, floatArrayOf(1f, 0f))
        val unrelated = example("human-style-000002", HumanSpeechResponseMode.ALIGNMENT, floatArrayOf(0.1f, 0.995f))
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val service = HumanSpeechStyleRagService(FakeStore(listOf(unrelated, relevant)), embedding)

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    socialAct = SpeechSocialAct.ACKNOWLEDGE,
                    styleResponseMode = HumanSpeechResponseMode.ALIGNMENT,
                    speechIntent = "대화 흐름에 맞춰 짧게 말한다",
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId }).containsExactly("human-style-000001")
    }

    @Test
    fun `장면이 충분히 가까우면 실제 답변 리듬이 맞는 카드를 보조적으로 앞세운다`() {
        val sceneOnly =
            example(
                "human-style-000001",
                embedding = floatArrayOf(1f, 0f),
                sourceFingerprint = "sha256:${"a".repeat(64)}",
            ).copy(rhythmEmbedding = floatArrayOf(0f, 1f))
        val rhythmMatch =
            example(
                "human-style-000002",
                embedding = floatArrayOf(0.98f, 0.199f),
                sourceFingerprint = "sha256:${"b".repeat(64)}",
            ).copy(rhythmEmbedding = floatArrayOf(1f, 0f))
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)))
        val service = HumanSpeechStyleRagService(FakeStore(listOf(sceneOnly, rhythmMatch)), embedding)

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    socialAct = SpeechSocialAct.ACKNOWLEDGE,
                    styleResponseMode = HumanSpeechResponseMode.ALIGNMENT,
                    speechIntent = "대화 흐름에 맞춰 짧게 말한다",
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId })
            .containsExactly("human-style-000002", "human-style-000001")
        assertThat(embedding.inputs).hasSize(2)
    }

    @Test
    fun `실제 답변 형식이 있어도 의미 점수 차이가 크면 더 가까운 카드를 먼저 참고한다`() {
        val unknownForm =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.SPECULATION,
                floatArrayOf(1f, 0f),
                responseForm = null,
                responseRhythm = listOf(HumanSpeechStyleRhythmCue.SINGLE_BUBBLE),
            )
        val observedForm =
            example(
                "human-style-000002",
                HumanSpeechResponseMode.SPECULATION,
                floatArrayOf(0.8f, 0.6f),
                responseForm = HumanSpeechStyleResponseForm.HEDGED_GUESS,
                sourceFingerprint = "sha256:${"b".repeat(64)}",
            )
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)))
        val service = HumanSpeechStyleRagService(FakeStore(listOf(unknownForm, observedForm)), embedding)

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    socialAct = SpeechSocialAct.ASK,
                    styleResponseMode = HumanSpeechResponseMode.SPECULATION,
                    speechIntent = "확신하지 않고 가능성을 남겨 가볍게 짐작한다",
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId }).containsExactly("human-style-000001", "human-style-000002")
    }

    @Test
    fun `실제 답변 형식은 의미 점수가 근접할 때만 동률을 푼다`() {
        val unknownForm =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.SPECULATION,
                floatArrayOf(1f, 0f),
                responseForm = null,
                responseRhythm = listOf(HumanSpeechStyleRhythmCue.SINGLE_BUBBLE),
            )
        val observedForm =
            example(
                "human-style-000002",
                HumanSpeechResponseMode.SPECULATION,
                floatArrayOf(0.999f, 0.0447f),
                responseForm = HumanSpeechStyleResponseForm.HEDGED_GUESS,
                sourceFingerprint = "sha256:${"b".repeat(64)}",
            )
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)))
        val service = HumanSpeechStyleRagService(FakeStore(listOf(unknownForm, observedForm)), embedding)

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    socialAct = SpeechSocialAct.ASK,
                    styleResponseMode = HumanSpeechResponseMode.SPECULATION,
                    speechIntent = "확신하지 않고 가능성을 남겨 가볍게 짐작한다",
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId }).containsExactly("human-style-000002", "human-style-000001")
    }

    @Test
    fun `관찰된 리듬도 의미 점수가 근접할 때만 동률을 푼다`() {
        val unknownRhythm =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.SPECULATION,
                floatArrayOf(1f, 0f),
                responseForm = null,
                responseRhythm = listOf(HumanSpeechStyleRhythmCue.SINGLE_BUBBLE),
            )
        val observedRhythm =
            example(
                "human-style-000002",
                HumanSpeechResponseMode.SPECULATION,
                floatArrayOf(0.999f, 0.0447f),
                responseForm = null,
                responseRhythm = listOf(HumanSpeechStyleRhythmCue.HEDGED_GUESS, HumanSpeechStyleRhythmCue.SINGLE_BUBBLE),
                sourceFingerprint = "sha256:${"b".repeat(64)}",
            )
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)))
        val service = HumanSpeechStyleRagService(FakeStore(listOf(unknownRhythm, observedRhythm)), embedding)

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    socialAct = SpeechSocialAct.ASK,
                    styleResponseMode = HumanSpeechResponseMode.SPECULATION,
                    speechIntent = "확신하지 않고 가능성을 남겨 가볍게 짐작한다",
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId }).containsExactly("human-style-000002", "human-style-000001")
    }

    @Test
    fun `현재 대화의 짧은 구어체 전달 표지는 근접 동점에서만 같은 말투 호흡 카드를 앞세운다`() {
        val generic =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.PLAY,
                responseMove = null,
                responseRhythm = listOf(HumanSpeechStyleRhythmCue.PLAYFUL_RETURN, HumanSpeechStyleRhythmCue.SINGLE_BUBBLE),
            )
        val casualShortForm =
            example(
                "human-style-000002",
                HumanSpeechResponseMode.PLAY,
                responseMove = null,
                responseRhythm =
                    listOf(
                        HumanSpeechStyleRhythmCue.PLAYFUL_RETURN,
                        HumanSpeechStyleRhythmCue.CASUAL_SHORT_FORM,
                        HumanSpeechStyleRhythmCue.SINGLE_BUBBLE,
                    ),
                sourceFingerprint = "sha256:${"b".repeat(64)}",
            )
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)))
        val service = HumanSpeechStyleRagService(FakeStore(listOf(generic, casualShortForm)), embedding)

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    styleResponseMode = HumanSpeechResponseMode.PLAY,
                    turns = listOf(ConversationTurn("member", "ㅇㅇ")),
                    speechIntent = "친한 사이의 가벼운 장난을 한 번 되받는다",
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId })
            .containsExactly("human-style-000002", "human-style-000001")
        assertThat(embedding.inputs.last())
            .contains("친한 대화의 짧은 구어체·축약을 자연스럽게 쓴다")
            .doesNotContain("ㅇㅇ")
    }

    @Test
    fun `현재 대화 전달 표지는 의미 점수 차이가 크면 더 가까운 카드를 뒤집지 않는다`() {
        val semanticallyCloser =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.PLAY,
                embedding = floatArrayOf(1f, 0f),
                responseMove = null,
                responseRhythm = listOf(HumanSpeechStyleRhythmCue.PLAYFUL_RETURN, HumanSpeechStyleRhythmCue.SINGLE_BUBBLE),
            )
        val casualButDistant =
            example(
                "human-style-000002",
                HumanSpeechResponseMode.PLAY,
                embedding = floatArrayOf(0.7f, 0.71414286f),
                responseMove = null,
                responseRhythm =
                    listOf(
                        HumanSpeechStyleRhythmCue.PLAYFUL_RETURN,
                        HumanSpeechStyleRhythmCue.CASUAL_SHORT_FORM,
                        HumanSpeechStyleRhythmCue.SINGLE_BUBBLE,
                    ),
                sourceFingerprint = "sha256:${"b".repeat(64)}",
            )
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)))
        val service = HumanSpeechStyleRagService(FakeStore(listOf(semanticallyCloser, casualButDistant)), embedding)

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    styleResponseMode = HumanSpeechResponseMode.PLAY,
                    turns = listOf(ConversationTurn("member", "ㅇㅇ")),
                    speechIntent = "친한 사이의 가벼운 장난을 한 번 되받는다",
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId })
            .containsExactly("human-style-000001", "human-style-000002")
    }

    @Test
    fun `검색 임베딩은 닫힌 metadata만 보내고 live speech intent와 최근 turn 원문을 보내지 않는다`() {
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)))
        val liveSpeechIntent = "live-speech-intent-marker-12345"
        val earlierLiveTurn = "live-earlier-turn-marker-12345"
        val latestLiveTurn = "live-latest-turn-marker-12345"
        val service =
            HumanSpeechStyleRagService(
                FakeStore(listOf(example("human-style-000001"))),
                embedding,
            )

        service.retrieve(
            SpeechGenerationFixtures.packet(
                styleResponseMode = HumanSpeechResponseMode.ALIGNMENT,
                turns =
                    listOf(
                        ConversationTurn("member", earlierLiveTurn),
                        ConversationTurn("member", latestLiveTurn),
                    ),
                speechIntent = liveSpeechIntent,
            ),
        )

        assertThat(embedding.inputs).hasSize(2)
        assertThat(embedding.inputs.first())
            .startsWith("반응 방식: ALIGNMENT")
            .contains("원하는 말풍선 수:")
        assertThat(embedding.inputs.last())
            .contains("반응 목표: 상대의 가벼운 불편이나 감각에 같은 편으로 맞장구치고 내 느낌을 짧게 보탠다")
        assertThat(embedding.inputs).allSatisfy { input ->
            assertThat(input).doesNotContain(liveSpeechIntent, earlierLiveTurn, latestLiveTurn, "member")
        }
    }

    @Test
    fun `해당 enum 카드가 없거나 feature가 꺼졌거나 Judge enum이 없으면 현재 대화를 임베딩으로 보내지 않는다`() {
        val emptyEmbedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val noCards = HumanSpeechStyleRagService(FakeStore(emptyList()), emptyEmbedding)
        val wrongModeEmbedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val wrongMode =
            HumanSpeechStyleRagService(
                FakeStore(
                    listOf(
                        example("human-style-000001", HumanSpeechResponseMode.CARE),
                    ),
                ),
                wrongModeEmbedding,
            )
        val disabledEmbedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val disabled = HumanSpeechStyleRagService(FakeStore(listOf(example("human-style-000001"))), disabledEmbedding, enabled = false)
        val noModeEmbedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val noMode = HumanSpeechStyleRagService(FakeStore(listOf(example("human-style-000001"))), noModeEmbedding)

        assertThat(noCards.retrieve(SpeechGenerationFixtures.packet()).matches).isEmpty()
        val wrongModeSelection =
            wrongMode.retrieve(
                SpeechGenerationFixtures.packet(styleResponseMode = HumanSpeechResponseMode.ALIGNMENT),
            )
        assertThat(wrongModeSelection.matches).isEmpty()
        assertThat(disabled.retrieve(SpeechGenerationFixtures.packet()).matches).isEmpty()
        assertThat(noMode.retrieve(SpeechGenerationFixtures.packet()).matches).isEmpty()
        assertThat(emptyEmbedding.calls).isZero()
        assertThat(wrongModeEmbedding.calls).isZero()
        assertThat(disabledEmbedding.calls).isZero()
        assertThat(noModeEmbedding.calls).isZero()
    }

    @Test
    fun `사전 중단 사유를 카드 내용 없이 검색 outcome으로 기록한다`() {
        val disabledMetrics = CapturingRagMetrics()
        val disabledEmbedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val disabled =
            HumanSpeechStyleRagService(
                FakeStore(listOf(example("human-style-000001"))),
                disabledEmbedding,
                enabled = false,
                metrics = disabledMetrics,
            )
        val missingModeMetrics = CapturingRagMetrics()
        val missingModeEmbedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val missingMode =
            HumanSpeechStyleRagService(
                FakeStore(listOf(example("human-style-000001"))),
                missingModeEmbedding,
                metrics = missingModeMetrics,
            )
        val emptyMetrics = CapturingRagMetrics()
        val emptyEmbedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val empty = HumanSpeechStyleRagService(FakeStore(emptyList()), emptyEmbedding, metrics = emptyMetrics)

        disabled.retrieve(SpeechGenerationFixtures.packet())
        missingMode.retrieve(SpeechGenerationFixtures.packet())
        empty.retrieve(SpeechGenerationFixtures.packet(styleResponseMode = HumanSpeechResponseMode.ALIGNMENT))

        assertThat(disabledMetrics.outcomes).containsExactly(HumanSpeechStyleRagOutcome.DISABLED)
        assertThat(missingModeMetrics.outcomes).containsExactly(HumanSpeechStyleRagOutcome.MISSING_RESPONSE_MODE)
        assertThat(emptyMetrics.outcomes).containsExactly(HumanSpeechStyleRagOutcome.NO_ENABLED_EXAMPLES)
        assertThat(disabledEmbedding.calls).isZero()
        assertThat(missingModeEmbedding.calls).isZero()
        assertThat(emptyEmbedding.calls).isZero()
    }

    @Test
    fun `response move 표지가 부족해도 같은 enum 검색과 OpenAI 장애 결과를 구분해 기록한다`() {
        val selectedMetrics = CapturingRagMetrics()
        val selectedEmbedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val selectedService =
            HumanSpeechStyleRagService(
                FakeStore(
                    listOf(
                        example("human-style-000001"),
                        example("human-style-000002", sourceFingerprint = "sha256:${"b".repeat(64)}"),
                    ),
                ),
                selectedEmbedding,
                metrics = selectedMetrics,
            )
        val unavailableMetrics = CapturingRagMetrics()
        val unavailableEmbedding = CapturingEmbeddingPort(null)
        val unavailableService =
            HumanSpeechStyleRagService(
                FakeStore(listOf(example("human-style-000003", responseMove = null))),
                unavailableEmbedding,
                metrics = unavailableMetrics,
            )
        val malformedMetrics = CapturingRagMetrics()
        val malformedEmbedding = CapturingEmbeddingPort(emptyList())
        val malformedService =
            HumanSpeechStyleRagService(
                FakeStore(listOf(example("human-style-000006", responseMove = null))),
                malformedEmbedding,
                metrics = malformedMetrics,
            )
        val noMatchMetrics = CapturingRagMetrics()
        val noMatchEmbedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val noMatchService =
            HumanSpeechStyleRagService(
                FakeStore(listOf(example("human-style-000004", embedding = floatArrayOf(0f, 1f), responseMove = null))),
                noMatchEmbedding,
                metrics = noMatchMetrics,
            )
        selectedService.retrieve(
            SpeechGenerationFixtures.packet(
                styleResponseMode = HumanSpeechResponseMode.ALIGNMENT,
                speechIntent = "불만을 짧게 보탠다",
            ),
        )
        unavailableService.retrieve(SpeechGenerationFixtures.packet(styleResponseMode = HumanSpeechResponseMode.ALIGNMENT))
        malformedService.retrieve(SpeechGenerationFixtures.packet(styleResponseMode = HumanSpeechResponseMode.ALIGNMENT))
        noMatchService.retrieve(SpeechGenerationFixtures.packet(styleResponseMode = HumanSpeechResponseMode.ALIGNMENT))
        assertThat(selectedMetrics.outcomes).containsExactly(HumanSpeechStyleRagOutcome.SELECTED)
        assertThat(unavailableMetrics.outcomes).containsExactly(HumanSpeechStyleRagOutcome.EMBEDDING_UNAVAILABLE)
        assertThat(malformedMetrics.outcomes).containsExactly(HumanSpeechStyleRagOutcome.EMBEDDING_UNAVAILABLE)
        assertThat(noMatchMetrics.outcomes).containsExactly(HumanSpeechStyleRagOutcome.NO_MATCH)
        assertThat(selectedEmbedding.calls).isEqualTo(1)
        assertThat(unavailableEmbedding.calls).isEqualTo(1)
        assertThat(malformedEmbedding.calls).isEqualTo(1)
        assertThat(noMatchEmbedding.calls).isEqualTo(1)
    }

    @Test
    fun `예상 밖 embedding port 오류는 Speech 경계가 기록하고 fallback 하도록 그대로 전달한다`() {
        val metrics = CapturingRagMetrics()
        val service =
            HumanSpeechStyleRagService(
                FakeStore(listOf(example("human-style-000007", responseMove = null))),
                ThrowingEmbeddingPort(),
                metrics = metrics,
            )

        assertThatThrownBy {
            service.retrieve(SpeechGenerationFixtures.packet(styleResponseMode = HumanSpeechResponseMode.ALIGNMENT))
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("embedding endpoint contract failure")
        assertThat(metrics.outcomes).isEmpty()
    }

    @Test
    fun `다른 embedding model로 만든 카드는 검색 후보에서 제외한다`() {
        val obsolete =
            example("human-style-000001", HumanSpeechResponseMode.ALIGNMENT, floatArrayOf(1f, 0f))
                .copy(embeddingModel = "other-embedding-model")
        val current = example("human-style-000002", HumanSpeechResponseMode.ALIGNMENT, floatArrayOf(1f, 0f))
        val store = FakeStore(listOf(obsolete, current))
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val service = HumanSpeechStyleRagService(store, embedding)

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    socialAct = SpeechSocialAct.ACKNOWLEDGE,
                    styleResponseMode = HumanSpeechResponseMode.ALIGNMENT,
                    speechIntent = "대화 흐름에 맞춰 짧게 말한다",
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId }).containsExactly("human-style-000002")
        assertThat(store.requestedModes).containsExactly(HumanSpeechResponseMode.ALIGNMENT)
        assertThat(embedding.calls).isEqualTo(1)
    }

    @Test
    fun `감지된 보조 반응 리듬도 더 가까운 같은 enum 카드를 막지 않는다`() {
        val wrongMove =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.PLAY,
                floatArrayOf(1f, 0f),
                responseMove = HumanSpeechStyleResponseMove.PLAY_FRIENDLY_TEASE,
            )
        val correctMove =
            example(
                "human-style-000002",
                HumanSpeechResponseMode.PLAY,
                floatArrayOf(0.8f, 0.6f),
                responseMove = HumanSpeechStyleResponseMove.PLAY_COMPETITIVE_TEASE,
            )
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val service = HumanSpeechStyleRagService(FakeStore(listOf(wrongMove, correctMove)), embedding)

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    socialAct = SpeechSocialAct.TEASE,
                    styleResponseMode = HumanSpeechResponseMode.PLAY,
                    speechIntent = "친한 친구의 가벼운 장난을 자연스럽게 받는다",
                    turns = listOf(ConversationTurn("member", "누가 더 빠른지 보자")),
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId }).containsExactly("human-style-000001")
        assertThat(embedding.calls).isEqualTo(1)
    }

    @Test
    fun `보조 반응 리듬이 맞아도 더 가까운 같은 enum 카드를 제외하지 않는다`() {
        val wrongMove =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.PLAY,
                floatArrayOf(1f, 0f),
                responseMove = HumanSpeechStyleResponseMove.PLAY_FRIENDLY_TEASE,
            )
        val compatibleOne =
            example(
                "human-style-000002",
                HumanSpeechResponseMode.PLAY,
                floatArrayOf(0.8f, 0.6f),
                responseMove = HumanSpeechStyleResponseMove.PLAY_COMPETITIVE_TEASE,
                sourceFingerprint = "sha256:${"b".repeat(64)}",
            )
        val compatibleTwo =
            example(
                "human-style-000003",
                HumanSpeechResponseMode.PLAY,
                floatArrayOf(0.6f, 0.8f),
                responseMove = HumanSpeechStyleResponseMove.PLAY_COMPETITIVE_TEASE,
                sourceFingerprint = "sha256:${"c".repeat(64)}",
            )
        val compatibleThree =
            example(
                "human-style-000004",
                HumanSpeechResponseMode.PLAY,
                floatArrayOf(0.4f, 0.9f),
                responseMove = HumanSpeechStyleResponseMove.PLAY_COMPETITIVE_TEASE,
                sourceFingerprint = "sha256:${"d".repeat(64)}",
            )
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val service = HumanSpeechStyleRagService(FakeStore(listOf(wrongMove, compatibleOne, compatibleTwo, compatibleThree)), embedding)

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    socialAct = SpeechSocialAct.TEASE,
                    styleResponseMode = HumanSpeechResponseMode.PLAY,
                    speechIntent = "가벼운 경쟁을 장난으로 받아친다",
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId })
            .containsExactly("human-style-000001", "human-style-000002")
    }

    @Test
    fun `audit-only raw surface는 검색 후보에서 제외하고 원문 말풍선은 embedding에 보내지 않는다`() {
        val rawContextMarker = "audit-only-private-context-marker-98765"
        val auditOnlyCard =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.CARE,
                floatArrayOf(1f, 0f),
                sourceFingerprint = "sha256:${"a".repeat(64)}",
            ).copy(
                promptEligible = false,
                promptSurface = HumanSpeechStylePromptSurface.AUDIT_ONLY,
                providerStyleCues = emptyList(),
                contextBubbles = listOf(HumanSpeechStyleBubble("가명1", rawContextMarker)),
            )
        val safePattern =
            example(
                "human-style-000002",
                HumanSpeechResponseMode.CARE,
                floatArrayOf(1f, 0f),
                sourceFingerprint = "sha256:${"b".repeat(64)}",
            )
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val service = HumanSpeechStyleRagService(FakeStore(listOf(auditOnlyCard, safePattern)), embedding)

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    socialAct = SpeechSocialAct.ACKNOWLEDGE,
                    styleResponseMode = HumanSpeechResponseMode.CARE,
                    turns = listOf(ConversationTurn("member", "병원 다녀왔는데 머리가 너무 아파")),
                    speechIntent = "아픈 상태를 부담 없이 챙긴다",
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId }).containsExactly("human-style-000002")
        assertThat(embedding.inputs).allSatisfy { input -> assertThat(input).doesNotContain(rawContextMarker) }
    }

    @Test
    fun `style pattern 카드의 원문 앞 대화도 검색 점수와 embedding 입력에 쓰지 않는다`() {
        val cardContextMarker = "local-card-context-marker-98765"
        val pattern =
            example("human-style-000001").copy(
                promptSurface = HumanSpeechStylePromptSurface.STYLE_PATTERN,
                contextBubbles = listOf(HumanSpeechStyleBubble("가명1", cardContextMarker)),
            )
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val service = HumanSpeechStyleRagService(FakeStore(listOf(pattern)), embedding)

        service.retrieve(
            SpeechGenerationFixtures.packet(
                styleResponseMode = HumanSpeechResponseMode.ALIGNMENT,
                turns = listOf(ConversationTurn("member", "현재 장면 표식")),
                speechIntent = "가벼운 불평에 맞춘다",
            ),
        )

        assertThat(embedding.inputs).allSatisfy { input -> assertThat(input).doesNotContain(cardContextMarker) }
    }

    @Test
    fun `다음 행동 조율은 같은 내부 반응 움직임 안에서 의미 벡터를 고른다`() {
        val wrongForm =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.COORDINATION,
                floatArrayOf(1f, 0f),
                responseMove = HumanSpeechStyleResponseMove.COORDINATION_ACTION,
                responseForm = HumanSpeechStyleResponseForm.QUESTION,
            )
        val correctForm =
            example(
                "human-style-000002",
                HumanSpeechResponseMode.COORDINATION,
                floatArrayOf(0.8f, 0.6f),
                responseMove = HumanSpeechStyleResponseMove.COORDINATION_ACTION,
                responseForm = HumanSpeechStyleResponseForm.PROPOSAL,
                sourceFingerprint = "sha256:${"b".repeat(64)}",
            )
        val thirdAction =
            example(
                "human-style-000003",
                HumanSpeechResponseMode.COORDINATION,
                floatArrayOf(0.6f, 0.8f),
                responseMove = HumanSpeechStyleResponseMove.COORDINATION_ACTION,
                responseForm = HumanSpeechStyleResponseForm.PROPOSAL,
                sourceFingerprint = "sha256:${"c".repeat(64)}",
            )
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val service = HumanSpeechStyleRagService(FakeStore(listOf(wrongForm, correctForm, thirdAction)), embedding)

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    socialAct = SpeechSocialAct.ACKNOWLEDGE,
                    styleResponseMode = HumanSpeechResponseMode.COORDINATION,
                    speechIntent = "같이 할 일을 가볍게 제안한다",
                    turns = listOf(ConversationTurn("member", "같이 갈까")),
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId })
            .containsExactly("human-style-000001", "human-style-000002")
        assertThat(embedding.calls).isEqualTo(1)
    }

    @Test
    fun `시간 조율은 서로 다른 세 source의 시간 카드만 검색한다`() {
        val timeQuestion =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.COORDINATION,
                responseMove = HumanSpeechStyleResponseMove.COORDINATION_TIME,
                responseForm = HumanSpeechStyleResponseForm.QUESTION,
            )
        val secondTimeQuestion =
            example(
                "human-style-000002",
                HumanSpeechResponseMode.COORDINATION,
                responseMove = HumanSpeechStyleResponseMove.COORDINATION_TIME,
                responseForm = HumanSpeechStyleResponseForm.QUESTION,
                sourceFingerprint = "sha256:${"b".repeat(64)}",
            )
        val thirdTimeQuestion =
            example(
                "human-style-000003",
                HumanSpeechResponseMode.COORDINATION,
                responseMove = HumanSpeechStyleResponseMove.COORDINATION_TIME,
                responseForm = HumanSpeechStyleResponseForm.QUESTION,
                sourceFingerprint = "sha256:${"c".repeat(64)}",
            )
        val store = FakeStore(listOf(timeQuestion, secondTimeQuestion, thirdTimeQuestion))
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val service = HumanSpeechStyleRagService(store, embedding)

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    styleResponseMode = HumanSpeechResponseMode.COORDINATION,
                    speechIntent = "만날 시간을 정한다",
                    turns = listOf(ConversationTurn("member", "내일 몇 시에 만날래?")),
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId })
            .containsExactly("human-style-000001", "human-style-000002")
        assertThat(store.requestedModes).containsExactly(HumanSpeechResponseMode.COORDINATION)
        assertThat(embedding.calls).isEqualTo(1)
    }

    @Test
    fun `선택 조율 표지는 근접한 점수에서만 우선한다`() {
        val action =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.COORDINATION,
                floatArrayOf(1f, 0f),
                responseMove = HumanSpeechStyleResponseMove.COORDINATION_ACTION,
            )
        val firstChoice =
            example(
                "human-style-000002",
                HumanSpeechResponseMode.COORDINATION,
                floatArrayOf(0.8f, 0.6f),
                responseMove = HumanSpeechStyleResponseMove.COORDINATION_CHOICE,
                sourceFingerprint = "sha256:${"b".repeat(64)}",
            )
        val secondChoice =
            example(
                "human-style-000003",
                HumanSpeechResponseMode.COORDINATION,
                floatArrayOf(0.6f, 0.8f),
                responseMove = HumanSpeechStyleResponseMove.COORDINATION_CHOICE,
                sourceFingerprint = "sha256:${"c".repeat(64)}",
            )
        val thirdChoice =
            example(
                "human-style-000004",
                HumanSpeechResponseMode.COORDINATION,
                floatArrayOf(0.4f, 0.9f),
                responseMove = HumanSpeechStyleResponseMove.COORDINATION_CHOICE,
                sourceFingerprint = "sha256:${"d".repeat(64)}",
            )
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val service = HumanSpeechStyleRagService(FakeStore(listOf(action, firstChoice, secondChoice, thirdChoice)), embedding)

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    styleResponseMode = HumanSpeechResponseMode.COORDINATION,
                    speechIntent = "같이 할 것을 고르며 선택을 확인한다",
                    turns = listOf(ConversationTurn("member", "어디 갈까?")),
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId })
            .containsExactly("human-style-000001", "human-style-000002")
        assertThat(embedding.calls).isEqualTo(1)
    }

    @Test
    fun `보조 반응 리듬 카드가 두 source에 없어도 같은 enum 검색을 계속한다`() {
        val firstAction =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.COORDINATION,
                responseMove = HumanSpeechStyleResponseMove.COORDINATION_ACTION,
            )
        val secondAction =
            example(
                "human-style-000002",
                HumanSpeechResponseMode.COORDINATION,
                responseMove = HumanSpeechStyleResponseMove.COORDINATION_ACTION,
                sourceFingerprint = "sha256:${"b".repeat(64)}",
            )
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val service = HumanSpeechStyleRagService(FakeStore(listOf(firstAction, secondAction)), embedding)

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    styleResponseMode = HumanSpeechResponseMode.COORDINATION,
                    speechIntent = "누가 먼저 맡을지 정한다",
                    turns = listOf(ConversationTurn("member", "누가 먼저 할래?")),
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId })
            .containsExactly("human-style-000001", "human-style-000002")
        assertThat(embedding.calls).isEqualTo(1)
    }

    @Test
    fun `보조 반응 리듬 카드가 부족해도 response enum 안에서만 검색한다`() {
        val firstStatus =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.FOLLOW_UP,
                responseMove = HumanSpeechStyleResponseMove.FOLLOW_UP_STATUS,
            )
        val secondStatus =
            example(
                "human-style-000002",
                HumanSpeechResponseMode.FOLLOW_UP,
                responseMove = HumanSpeechStyleResponseMove.FOLLOW_UP_STATUS,
                sourceFingerprint = "sha256:${"b".repeat(64)}",
            )
        val genericCause =
            example(
                "human-style-000003",
                HumanSpeechResponseMode.FOLLOW_UP,
                responseMove = HumanSpeechStyleResponseMove.FOLLOW_UP_CAUSE,
                sourceFingerprint = "sha256:${"c".repeat(64)}",
            )
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val service = HumanSpeechStyleRagService(FakeStore(listOf(firstStatus, secondStatus, genericCause)), embedding)

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    styleResponseMode = HumanSpeechResponseMode.FOLLOW_UP,
                    speechIntent = "상대의 현재 상태를 짧게 확인한다",
                    turns = listOf(ConversationTurn("member", "오늘 몸이 안 좋다고 했어")),
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId })
            .containsExactly("human-style-000001", "human-style-000002")
        assertThat(selection.matches.map { it.example.responseMode }).containsOnly(HumanSpeechResponseMode.FOLLOW_UP)
        assertThat(embedding.calls).isEqualTo(1)
    }

    @Test
    fun `보조 반응 리듬은 의미 점수가 근접할 때만 순위를 정한다`() {
        val generic =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.SPECULATION,
                floatArrayOf(0.9999f, 0.0141418f),
                responseMove = null,
            )
        val firstFuture =
            example(
                "human-style-000002",
                HumanSpeechResponseMode.SPECULATION,
                floatArrayOf(1f, 0f),
                responseMove = HumanSpeechStyleResponseMove.SPECULATION_FUTURE,
                sourceFingerprint = "sha256:${"b".repeat(64)}",
            )
        val secondFuture =
            example(
                "human-style-000003",
                HumanSpeechResponseMode.SPECULATION,
                floatArrayOf(0.8f, 0.6f),
                responseMove = HumanSpeechStyleResponseMove.SPECULATION_FUTURE,
                sourceFingerprint = "sha256:${"c".repeat(64)}",
            )
        val thirdFuture =
            example(
                "human-style-000004",
                HumanSpeechResponseMode.SPECULATION,
                floatArrayOf(0.6f, 0.8f),
                responseMove = HumanSpeechStyleResponseMove.SPECULATION_FUTURE,
                sourceFingerprint = "sha256:${"d".repeat(64)}",
            )
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val service = HumanSpeechStyleRagService(FakeStore(listOf(generic, firstFuture, secondFuture, thirdFuture)), embedding)

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    styleResponseMode = HumanSpeechResponseMode.SPECULATION,
                    speechIntent = "내일 일어날 일을 확신하지 않고 가볍게 짐작한다",
                    turns = listOf(ConversationTurn("member", "내일 올까?")),
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId })
            .containsExactly("human-style-000002", "human-style-000001")
        assertThat(embedding.calls).isEqualTo(1)
    }

    @Test
    fun `같은 enum 안에서 반응 움직임이 맞는 카드는 가까운 의미 점수일 때만 앞선다`() {
        val generic =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.FOLLOW_UP,
                floatArrayOf(1f, 0f),
                responseMove = null,
            )
        val matchingStatus =
            example(
                "human-style-000002",
                HumanSpeechResponseMode.FOLLOW_UP,
                floatArrayOf(0.9997f, 0.0245f),
                responseMove = HumanSpeechStyleResponseMove.FOLLOW_UP_STATUS,
                sourceFingerprint = "sha256:${"b".repeat(64)}",
            )
        val distantStatus =
            example(
                "human-style-000003",
                HumanSpeechResponseMode.FOLLOW_UP,
                floatArrayOf(0.8f, 0.6f),
                responseMove = HumanSpeechStyleResponseMove.FOLLOW_UP_STATUS,
                sourceFingerprint = "sha256:${"c".repeat(64)}",
            )
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val service = HumanSpeechStyleRagService(FakeStore(listOf(generic, matchingStatus, distantStatus)), embedding)

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    styleResponseMode = HumanSpeechResponseMode.FOLLOW_UP,
                    speechIntent = "상대의 현재 상태를 짧게 확인한다",
                    turns = listOf(ConversationTurn("member", "오늘 감기 때문에 병원 갔어")),
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId })
            .containsExactly("human-style-000002", "human-style-000001")
        assertThat(selection.matches.map { it.example.exampleId }).doesNotContain("human-style-000003")
        assertThat(selection.matches.first().sceneSupportedResponseMove)
            .isEqualTo(HumanSpeechStyleResponseMove.FOLLOW_UP_STATUS)
    }

    @Test
    fun `세부 반응 행동은 현재 마지막 발화의 하나뿐인 명시 단서에서만 provider에 전달한다`() {
        val cause =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.FOLLOW_UP,
                responseMove = HumanSpeechStyleResponseMove.FOLLOW_UP_CAUSE,
            )
        val service = HumanSpeechStyleRagService(FakeStore(listOf(cause)), CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f))))

        val supported =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    styleResponseMode = HumanSpeechResponseMode.FOLLOW_UP,
                    turns = listOf(ConversationTurn("member", "왜 그렇게 됐대?")),
                    speechIntent = "이유를 짧게 물어본다",
                ),
            )
        val unsupported =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    styleResponseMode = HumanSpeechResponseMode.FOLLOW_UP,
                    turns =
                        listOf(
                            ConversationTurn("member", "왜 그렇게 됐대?"),
                            ConversationTurn("member", "나중에 얘기해 줄게"),
                        ),
                    speechIntent = "이유를 짧게 물어본다",
                ),
            )

        assertThat(supported.matches.single().sceneSupportedResponseMove)
            .isEqualTo(HumanSpeechStyleResponseMove.FOLLOW_UP_CAUSE)
        assertThat(unsupported.matches.single().sceneSupportedResponseMove).isNull()
    }

    @Test
    fun `여러 세부 행동 단서가 함께 있으면 provider에는 enum 기본 안내만 남긴다`() {
        val physical =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.CARE,
                responseMove = HumanSpeechStyleResponseMove.CARE_PHYSICAL,
            )
        val service = HumanSpeechStyleRagService(FakeStore(listOf(physical)), CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f))))

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    styleResponseMode = HumanSpeechResponseMode.CARE,
                    turns = listOf(ConversationTurn("member", "감기라서 너무 피곤해")),
                ),
            )

        assertThat(selection.matches.single().sceneSupportedResponseMove).isNull()
    }

    @Test
    fun `시간이라는 일반 단어만으로 시간 조율 submove를 provider에 넣지 않는다`() {
        val time =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.COORDINATION,
                responseMove = HumanSpeechStyleResponseMove.COORDINATION_TIME,
                responseForm = HumanSpeechStyleResponseForm.QUESTION,
            )
        val service = HumanSpeechStyleRagService(FakeStore(listOf(time)), CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f))))

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    styleResponseMode = HumanSpeechResponseMode.COORDINATION,
                    turns = listOf(ConversationTurn("member", "시간 괜찮아?")),
                ),
            )

        assertThat(selection.matches.single().sceneSupportedResponseMove).isNull()
    }

    @Test
    fun `장면 단서는 더 관련도 높은 같은 mode 카드를 뒤집지 않는다`() {
        val generic =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.CARE,
                floatArrayOf(1f, 0f),
                responseMove = HumanSpeechStyleResponseMove.CARE_FATIGUE,
                sourceFingerprint = "sha256:${"a".repeat(64)}",
            )
        val physical =
            example(
                "human-style-000002",
                HumanSpeechResponseMode.CARE,
                floatArrayOf(0.8f, 0.6f),
                responseMove = HumanSpeechStyleResponseMove.CARE_FATIGUE,
                sceneTraits = listOf(HumanSpeechSceneTrait.CARE_PHYSICAL_CONDITION),
                sourceFingerprint = "sha256:${"b".repeat(64)}",
            )
        val service = HumanSpeechStyleRagService(FakeStore(listOf(generic, physical)), CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f))))

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    styleResponseMode = HumanSpeechResponseMode.CARE,
                    turns = listOf(ConversationTurn("member", "오늘 병원 다녀왔어")),
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId }).containsExactly("human-style-000001", "human-style-000002")
        assertThat(selection.matches.first().sceneSupportedSceneTrait).isNull()
        assertThat(selection.matches.last().sceneSupportedSceneTrait)
            .isEqualTo(HumanSpeechSceneTrait.CARE_PHYSICAL_CONDITION)
        assertThat(selection.matches.last().sceneSupportedResponseMove).isNull()
    }

    @Test
    fun `장면 단서는 의미 점수가 가까울 때만 카드별 리듬 후보를 앞세운다`() {
        val generic =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.CARE,
                floatArrayOf(1f, 0f),
                sourceFingerprint = "sha256:${"a".repeat(64)}",
            )
        val physical =
            example(
                "human-style-000002",
                HumanSpeechResponseMode.CARE,
                floatArrayOf(0.999f, 0.04471018f),
                sceneTraits = listOf(HumanSpeechSceneTrait.CARE_PHYSICAL_CONDITION),
                sourceFingerprint = "sha256:${"b".repeat(64)}",
            )
        val service = HumanSpeechStyleRagService(FakeStore(listOf(generic, physical)), CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f))))

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    styleResponseMode = HumanSpeechResponseMode.CARE,
                    turns = listOf(ConversationTurn("member", "오늘 병원 다녀왔어")),
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId }).containsExactly("human-style-000002", "human-style-000001")
        assertThat(selection.matches.first().sceneSupportedSceneTrait)
            .isEqualTo(HumanSpeechSceneTrait.CARE_PHYSICAL_CONDITION)
    }

    @Test
    fun `여러 장면 단서가 함께 있으면 카드별 행동 리듬을 provider에 승격하지 않는다`() {
        val card =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.CARE,
                sceneTraits = listOf(HumanSpeechSceneTrait.CARE_PHYSICAL_CONDITION),
            )
        val service = HumanSpeechStyleRagService(FakeStore(listOf(card)), CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f))))

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    styleResponseMode = HumanSpeechResponseMode.CARE,
                    turns = listOf(ConversationTurn("member", "감기라서 너무 피곤해")),
                ),
            )

        assertThat(selection.matches.single().sceneSupportedSceneTrait).isNull()
        assertThat(selection.matches.single().sceneSupportedResponseMove).isNull()
    }

    @Test
    fun `이전 메시지의 장면 단서는 최신 메시지가 뒷받침하지 않으면 쓰지 않는다`() {
        val card =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.FOLLOW_UP,
                sceneTraits = listOf(HumanSpeechSceneTrait.FOLLOW_UP_CAUSE),
                responseMove = HumanSpeechStyleResponseMove.FOLLOW_UP_CAUSE,
            )
        val service = HumanSpeechStyleRagService(FakeStore(listOf(card)), CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f))))

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    styleResponseMode = HumanSpeechResponseMode.FOLLOW_UP,
                    turns =
                        listOf(
                            ConversationTurn("member", "왜 그랬대?"),
                            ConversationTurn("member", "나중에 얘기해 줄게"),
                        ),
                ),
            )

        assertThat(selection.matches.single().sceneSupportedSceneTrait).isNull()
        assertThat(selection.matches.single().sceneSupportedResponseMove).isNull()
    }

    @Test
    fun `후보의 의미 점수가 같으면 현재 말풍선 수와 가까운 답변을 먼저 둔다`() {
        val oneBubble =
            example("human-style-000001").copy(
                responseBubbles = listOf(HumanSpeechStyleBubble("가명2", "맞아")),
            )
        val threeBubbles =
            example(
                "human-style-000002",
                sourceFingerprint = "sha256:" + "b".repeat(64),
            ).copy(
                responseBubbles =
                    listOf(
                        HumanSpeechStyleBubble("가명2", "그러게"),
                        HumanSpeechStyleBubble("가명2", "나도"),
                        HumanSpeechStyleBubble("가명2", "답답함"),
                    ),
            )
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val service = HumanSpeechStyleRagService(FakeStore(listOf(threeBubbles, oneBubble)), embedding)

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    styleResponseMode = HumanSpeechResponseMode.ALIGNMENT,
                    burstShape = SpeechBurstShape(1, 280, false),
                    speechIntent = "대화 흐름에 맞춰 짧게 말한다",
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId })
            .containsExactly("human-style-000001", "human-style-000002")
    }

    @Test
    fun `보조 반응 metadata가 없어도 Judge enum이 있으면 현재 대화를 검색한다`() {
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val service =
            HumanSpeechStyleRagService(
                FakeStore(
                    listOf(
                        example(
                            "human-style-000001",
                            responseMove = null,
                            responseForm = null,
                        ),
                    ),
                ),
                embedding,
            )

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    styleResponseMode = HumanSpeechResponseMode.ALIGNMENT,
                    speechIntent = "지금 장면에 자연스럽게 반응한다",
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId }).containsExactly("human-style-000001")
        assertThat(embedding.calls).isEqualTo(1)
    }

    @Test
    fun `두 개의 참고 카드는 서로 다른 source fingerprint에서 고른다`() {
        val first = example("human-style-000001", embedding = floatArrayOf(1f, 0f))
        val sameSourceSecond = example("human-style-000002", embedding = floatArrayOf(0.99f, 0.01f))
        val distinctSourceSecond =
            example(
                "human-style-000003",
                embedding = floatArrayOf(0.8f, 0.6f),
                sourceFingerprint = "sha256:" + "b".repeat(64),
            )
        val distinctSourceThird =
            example(
                "human-style-000004",
                embedding = floatArrayOf(0.6f, 0.8f),
                sourceFingerprint = "sha256:" + "c".repeat(64),
            )
        val embedding = CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f)))
        val service =
            HumanSpeechStyleRagService(
                FakeStore(listOf(first, sameSourceSecond, distinctSourceSecond, distinctSourceThird)),
                embedding,
            )

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    styleResponseMode = HumanSpeechResponseMode.ALIGNMENT,
                    speechIntent = "상대 불평에 내 불만을 짧게 보탠다",
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId })
            .containsExactly("human-style-000001", "human-style-000003")
        assertThat(selection.matches.map { it.example.sourceFingerprint }).doesNotHaveDuplicates()
    }

    @Test
    fun `primary style cue가 최신 turn metadata와 맞으면 semantic 동점 창 안에서만 앞선다`() {
        val semanticallyFirstButDifferentCue =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.CARE,
                floatArrayOf(1f, 0f),
                providerStyleCues = listOf(HumanSpeechStyleProviderStyleCue.CARE_SOFT_NUDGE),
                sourceFingerprint = "sha256:${"a".repeat(64)}",
            )
        val slightlyLowerButDesiredCue =
            example(
                "human-style-000002",
                HumanSpeechResponseMode.CARE,
                floatArrayOf(0.999f, 0.04471018f),
                providerStyleCues = listOf(HumanSpeechStyleProviderStyleCue.CARE_GENTLE_VALIDATE),
                sourceFingerprint = "sha256:${"b".repeat(64)}",
            )
        val service =
            HumanSpeechStyleRagService(
                FakeStore(listOf(semanticallyFirstButDifferentCue, slightlyLowerButDesiredCue)),
                CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f))),
            )

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    styleResponseMode = HumanSpeechResponseMode.CARE,
                    turns = listOf(ConversationTurn("member", "오늘 병원 다녀왔어")),
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId })
            .containsExactly("human-style-000002", "human-style-000001")
    }

    @Test
    fun `primary style cue는 semantic 동점 창 밖의 더 가까운 카드를 뒤집지 않는다`() {
        val semanticallyFirstButDifferentCue =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.CARE,
                floatArrayOf(1f, 0f),
                providerStyleCues = listOf(HumanSpeechStyleProviderStyleCue.CARE_SOFT_NUDGE),
                sourceFingerprint = "sha256:${"a".repeat(64)}",
            )
        val distantDesiredCue =
            example(
                "human-style-000002",
                HumanSpeechResponseMode.CARE,
                floatArrayOf(0.8f, 0.6f),
                providerStyleCues = listOf(HumanSpeechStyleProviderStyleCue.CARE_GENTLE_VALIDATE),
                sourceFingerprint = "sha256:${"b".repeat(64)}",
            )
        val service =
            HumanSpeechStyleRagService(
                FakeStore(listOf(semanticallyFirstButDifferentCue, distantDesiredCue)),
                CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f))),
            )

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(
                    styleResponseMode = HumanSpeechResponseMode.CARE,
                    turns = listOf(ConversationTurn("member", "오늘 병원 다녀왔어")),
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId })
            .containsExactly("human-style-000001", "human-style-000002")
    }

    @Test
    fun `두 번째 참고는 같은 semantic 동점 창에서 첫 primary style cue와 다른 카드를 우선한다`() {
        val firstCue =
            example(
                "human-style-000001",
                HumanSpeechResponseMode.ALIGNMENT,
                floatArrayOf(1f, 0f),
                providerStyleCues = listOf(HumanSpeechStyleProviderStyleCue.ALIGNMENT_LOW_KEY_ACK),
                sourceFingerprint = "sha256:${"a".repeat(64)}",
            )
        val sameCue =
            example(
                "human-style-000002",
                HumanSpeechResponseMode.ALIGNMENT,
                floatArrayOf(0.999f, 0.04471018f),
                providerStyleCues = listOf(HumanSpeechStyleProviderStyleCue.ALIGNMENT_LOW_KEY_ACK),
                sourceFingerprint = "sha256:${"b".repeat(64)}",
            )
        val contrastingCue =
            example(
                "human-style-000003",
                HumanSpeechResponseMode.ALIGNMENT,
                floatArrayOf(0.998f, 0.06321392f),
                providerStyleCues = listOf(HumanSpeechStyleProviderStyleCue.ALIGNMENT_SHARED_FEELING),
                sourceFingerprint = "sha256:${"c".repeat(64)}",
            )
        val service =
            HumanSpeechStyleRagService(
                FakeStore(listOf(firstCue, sameCue, contrastingCue)),
                CapturingEmbeddingPort(listOf(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f))),
            )

        val selection =
            service.retrieve(
                SpeechGenerationFixtures.packet(styleResponseMode = HumanSpeechResponseMode.ALIGNMENT),
            )

        assertThat(selection.matches.map { it.example.exampleId })
            .containsExactly("human-style-000001", "human-style-000003")
    }

    private class FakeStore(
        private val examples: List<HumanSpeechStyleExample>,
    ) : HumanSpeechStyleExampleStorePort {
        val requestedModes = mutableListOf<HumanSpeechResponseMode>()

        override fun listEnabled(): List<HumanSpeechStyleExample> = examples

        override fun listEnabled(responseMode: HumanSpeechResponseMode): List<HumanSpeechStyleExample> {
            requestedModes += responseMode
            return examples.filter { it.responseMode == responseMode }
        }

        override fun replaceAll(examples: List<HumanSpeechStyleExample>): Int = examples.size
    }

    private class CapturingEmbeddingPort(
        private val vectors: List<FloatArray>?,
    ) : SpeechStyleEmbeddingPort {
        var calls: Int = 0
        val inputs = mutableListOf<String>()

        override fun embedAll(texts: List<String>): List<FloatArray>? {
            calls++
            inputs += texts
            return vectors?.let { configured ->
                if (configured.isEmpty()) configured else texts.indices.map { index -> configured.getOrElse(index) { configured.first() } }
            }
        }
    }

    private class CapturingRagMetrics : HumanSpeechStyleRagMetrics {
        val outcomes = mutableListOf<HumanSpeechStyleRagOutcome>()

        override fun record(outcome: HumanSpeechStyleRagOutcome) {
            outcomes += outcome
        }
    }

    private class ThrowingEmbeddingPort : SpeechStyleEmbeddingPort {
        override fun embedAll(texts: List<String>): List<FloatArray>? = throw IllegalStateException("embedding endpoint contract failure")
    }
}

internal fun example(
    exampleId: String,
    responseMode: HumanSpeechResponseMode = HumanSpeechResponseMode.ALIGNMENT,
    embedding: FloatArray = floatArrayOf(1f, 0f),
    responseText: String = "private-style-response-marker-12345",
    responseMove: HumanSpeechStyleResponseMove? = defaultResponseMove(responseMode),
    sceneTraits: List<HumanSpeechSceneTrait> = emptyList(),
    providerStyleCues: List<HumanSpeechStyleProviderStyleCue> = listOf(defaultProviderStyleCue(responseMode)),
    responseForm: HumanSpeechStyleResponseForm? = defaultResponseForm(responseMode),
    responseRhythm: List<HumanSpeechStyleRhythmCue> = defaultResponseRhythm(responseMode),
    sourceFingerprint: String = "sha256:" + "a".repeat(64),
): HumanSpeechStyleExample =
    HumanSpeechStyleExample(
        exampleId = exampleId,
        responseMode = responseMode,
        situation = "가벼운 불평에 맞장구친다",
        styleSignals = listOf("짧게", "친한 말투"),
        contextBubbles = listOf(HumanSpeechStyleBubble("가명1", "오늘 좀 답답하네")),
        responseBubbles = listOf(HumanSpeechStyleBubble("가명2", responseText)),
        quality = HumanSpeechStyleQuality.CURATION_APPROVED,
        sourceFingerprint = sourceFingerprint,
        consentRevision = "2026-08-04-curation-approved",
        combinedChars = 60,
        responseMove = responseMove,
        sceneTraits = sceneTraits,
        providerStyleCues = providerStyleCues,
        responseForm = responseForm,
        responseRhythm = responseRhythm,
        embedding = embedding,
        embeddingModel = "text-embedding-3-small",
    )

private fun defaultResponseMove(responseMode: HumanSpeechResponseMode): HumanSpeechStyleResponseMove =
    when (responseMode) {
        HumanSpeechResponseMode.REACTION -> HumanSpeechStyleResponseMove.REACTION_SURPRISE
        HumanSpeechResponseMode.ALIGNMENT -> HumanSpeechStyleResponseMove.ALIGNMENT_COMPLAINT
        HumanSpeechResponseMode.PLAY -> HumanSpeechStyleResponseMove.PLAY_FRIENDLY_TEASE
        HumanSpeechResponseMode.FOLLOW_UP -> HumanSpeechStyleResponseMove.FOLLOW_UP_CAUSE
        HumanSpeechResponseMode.SPECULATION -> HumanSpeechStyleResponseMove.SPECULATION_CAUSE
        HumanSpeechResponseMode.CARE -> HumanSpeechStyleResponseMove.CARE_PHYSICAL
        HumanSpeechResponseMode.COORDINATION -> HumanSpeechStyleResponseMove.COORDINATION_ACTION
    }

private fun defaultResponseForm(responseMode: HumanSpeechResponseMode): HumanSpeechStyleResponseForm =
    when (responseMode) {
        HumanSpeechResponseMode.REACTION -> HumanSpeechStyleResponseForm.EXPRESSIVE
        HumanSpeechResponseMode.ALIGNMENT -> HumanSpeechStyleResponseForm.ALIGN_AND_ADD
        HumanSpeechResponseMode.PLAY -> HumanSpeechStyleResponseForm.PLAYFUL_RETURN
        HumanSpeechResponseMode.FOLLOW_UP -> HumanSpeechStyleResponseForm.QUESTION
        HumanSpeechResponseMode.SPECULATION -> HumanSpeechStyleResponseForm.HEDGED_GUESS
        HumanSpeechResponseMode.CARE -> HumanSpeechStyleResponseForm.SUPPORTIVE
        HumanSpeechResponseMode.COORDINATION -> HumanSpeechStyleResponseForm.PROPOSAL
    }

private fun defaultProviderStyleCue(responseMode: HumanSpeechResponseMode): HumanSpeechStyleProviderStyleCue =
    when (responseMode) {
        HumanSpeechResponseMode.REACTION -> HumanSpeechStyleProviderStyleCue.REACTION_IMMEDIATE
        HumanSpeechResponseMode.ALIGNMENT -> HumanSpeechStyleProviderStyleCue.ALIGNMENT_LOW_KEY_ACK
        HumanSpeechResponseMode.PLAY -> HumanSpeechStyleProviderStyleCue.PLAY_COUNTERTEASE
        HumanSpeechResponseMode.FOLLOW_UP -> HumanSpeechStyleProviderStyleCue.FOLLOW_UP_SOFT_CHECK
        HumanSpeechResponseMode.SPECULATION -> HumanSpeechStyleProviderStyleCue.SPECULATION_LIGHT_HEDGE
        HumanSpeechResponseMode.CARE -> HumanSpeechStyleProviderStyleCue.CARE_GENTLE_VALIDATE
        HumanSpeechResponseMode.COORDINATION -> HumanSpeechStyleProviderStyleCue.COORDINATION_CONFIRM
    }

private fun defaultResponseRhythm(responseMode: HumanSpeechResponseMode): List<HumanSpeechStyleRhythmCue> =
    when (responseMode) {
        HumanSpeechResponseMode.REACTION -> listOf(HumanSpeechStyleRhythmCue.SHORT_REACTION, HumanSpeechStyleRhythmCue.SINGLE_BUBBLE)
        HumanSpeechResponseMode.ALIGNMENT -> listOf(HumanSpeechStyleRhythmCue.AGREE_AND_ADD, HumanSpeechStyleRhythmCue.SINGLE_BUBBLE)
        HumanSpeechResponseMode.PLAY -> listOf(HumanSpeechStyleRhythmCue.PLAYFUL_RETURN, HumanSpeechStyleRhythmCue.SINGLE_BUBBLE)
        HumanSpeechResponseMode.FOLLOW_UP -> listOf(HumanSpeechStyleRhythmCue.DIRECT_QUESTION, HumanSpeechStyleRhythmCue.SINGLE_BUBBLE)
        HumanSpeechResponseMode.SPECULATION -> listOf(HumanSpeechStyleRhythmCue.HEDGED_GUESS, HumanSpeechStyleRhythmCue.SINGLE_BUBBLE)
        HumanSpeechResponseMode.CARE -> listOf(HumanSpeechStyleRhythmCue.GENTLE_CARE, HumanSpeechStyleRhythmCue.SINGLE_BUBBLE)
        HumanSpeechResponseMode.COORDINATION ->
            listOf(
                HumanSpeechStyleRhythmCue.COORDINATION_CHECK,
                HumanSpeechStyleRhythmCue.SINGLE_BUBBLE,
            )
    }
