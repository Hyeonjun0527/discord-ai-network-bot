package com.discordassistant.central.speech.humanstyle

import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStyleRagService
import com.discordassistant.central.speech.application.port.out.HumanSpeechStyleExampleStorePort
import com.discordassistant.central.speech.application.port.out.SpeechStyleEmbeddingPort
import com.discordassistant.central.speech.domain.model.HumanSpeechResponseMode
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleBubble
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleExample
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleQuality
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import com.discordassistant.central.speech.generation.SpeechGenerationFixtures
import org.assertj.core.api.Assertions.assertThat
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
                ),
            )

        assertThat(
            selection.matches
                .first()
                .example.responseMode,
        ).isEqualTo(HumanSpeechResponseMode.CARE)
        assertThat(selection.matches.map { it.example.exampleId }).containsExactly("human-style-000001")
        assertThat(embedding.inputs.single()).doesNotContain("현재 필요한 반응 방식")
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
                ),
            )

        assertThat(selection.matches.map { it.example.exampleId }).containsExactly("human-style-000001")
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

    private class FakeStore(
        private val examples: List<HumanSpeechStyleExample>,
    ) : HumanSpeechStyleExampleStorePort {
        override fun listEnabled(): List<HumanSpeechStyleExample> = examples

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
            return vectors
        }
    }
}

internal fun example(
    exampleId: String,
    responseMode: HumanSpeechResponseMode = HumanSpeechResponseMode.ALIGNMENT,
    embedding: FloatArray = floatArrayOf(1f, 0f),
    responseText: String = "private-style-response-marker-12345",
): HumanSpeechStyleExample =
    HumanSpeechStyleExample(
        exampleId = exampleId,
        responseMode = responseMode,
        situation = "가벼운 불평에 맞장구친다",
        styleSignals = listOf("짧게", "친한 말투"),
        contextBubbles = listOf(HumanSpeechStyleBubble("가명1", "오늘 좀 답답하네")),
        responseBubbles = listOf(HumanSpeechStyleBubble("가명2", responseText)),
        quality = HumanSpeechStyleQuality.CURATION_APPROVED,
        sourceFingerprint = "sha256:" + "a".repeat(64),
        consentRevision = "2026-08-04-curation-approved",
        combinedChars = 60,
        embedding = embedding,
        embeddingModel = "text-embedding-3-small",
    )
