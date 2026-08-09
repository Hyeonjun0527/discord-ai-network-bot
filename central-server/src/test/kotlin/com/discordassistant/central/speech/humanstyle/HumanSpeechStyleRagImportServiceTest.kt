package com.discordassistant.central.speech.humanstyle

import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStyleRagImportService
import com.discordassistant.central.speech.application.port.out.HumanSpeechStyleExampleStorePort
import com.discordassistant.central.speech.application.port.out.SpeechStyleEmbeddingPort
import com.discordassistant.central.speech.domain.model.HumanSpeechResponseMode
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleExample
import com.discordassistant.central.speech.domain.model.HumanSpeechStylePromptSurface
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleProviderStyleCue
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleQuality
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleRhythmCue
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class HumanSpeechStyleRagImportServiceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `runtime JSONL의 전체 카드 수를 적재하고 카드 자유 텍스트는 embedding 입력에서 제외한다`() {
        val responseText = "private-style-response-marker-12345"
        val file = temporaryDirectory.resolve("cards.jsonl")
        Files.writeString(file, runtimeCardJson(responseText, providerStyleCues = listOf("ALIGNMENT_SHARED_FEELING")))
        val store = CapturingStore()
        var embeddingInput: List<String>? = null
        val embedding =
            SpeechStyleEmbeddingPort { texts ->
                embeddingInput = texts
                texts.map { floatArrayOf(1f, 0f) }
            }

        val report = HumanSpeechStyleRagImportService(store, embedding).importJsonLines(file)

        assertThat(report.importedCount).isEqualTo(1)
        assertThat(report.promptEligibleCount).isEqualTo(1)
        assertThat(report.responseModeCounts).containsEntry(HumanSpeechResponseMode.ALIGNMENT, 1)
        val storedResponse =
            store.examples
                .single()
                .responseBubbles
                .single()
                .text
        assertThat(storedResponse).isEqualTo(responseText)
        assertThat(store.examples.single().responseRhythm)
            .containsExactly(HumanSpeechStyleRhythmCue.AGREE_AND_ADD, HumanSpeechStyleRhythmCue.SINGLE_BUBBLE)
        assertThat(store.examples.single().providerStyleCues)
            .containsExactly(HumanSpeechStyleProviderStyleCue.ALIGNMENT_SHARED_FEELING)
        assertThat(store.examples.single().promptSurface).isEqualTo(HumanSpeechStylePromptSurface.STYLE_PATTERN)
        assertThat(embeddingInput!!).hasSize(2)
        assertThat(embeddingInput!!).allSatisfy { input ->
            assertThat(input)
                .doesNotContain(responseText)
                .doesNotContain("private-context-marker")
                .doesNotContain("private-situation-marker")
                .doesNotContain("private-style-signal-marker")
        }
        assertThat(embeddingInput!!.first())
            .contains("반응 방식: ALIGNMENT")
            .contains("관찰된 말투 결: 해결책 대신 같은 편의 짧은 체감 한마디를 보탠다")
            .doesNotContain("앞 대화")
        assertThat(embeddingInput!!.last())
            .doesNotContain(responseText)
            .contains("반응 목표: 상대의 가벼운 불편이나 감각에 같은 편으로 맞장구치고 내 느낌을 짧게 보탠다")
            .contains("실제 답변 리듬: 맞장구친 뒤 내 느낌이나 입장을 한마디 보탠다; 한 말풍선으로 짧게 끝낸다")
    }

    @Test
    fun `formal curation approval 없는 candidate artifact는 import하지 않는다`() {
        val file = temporaryDirectory.resolve("candidate-cards.jsonl")
        Files.writeString(file, runtimeCardJson("candidate-response", quality = "USER_AUTHORIZED_CANDIDATE"))
        val store = CapturingStore()
        val embedding = SpeechStyleEmbeddingPort { texts -> texts.map { floatArrayOf(1f, 0f) } }

        assertThatThrownBy { HumanSpeechStyleRagImportService(store, embedding).importJsonLines(file) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("invalid at line 1")
        assertThat(store.examples).isEmpty()
    }

    @Test
    fun `사용자가 release한 reviewed preview artifact는 로컬 검증에는 쓰되 운영 허용 품질에는 들어가지 않는다`() {
        val file = temporaryDirectory.resolve("user-released-review.jsonl")
        Files.writeString(
            file,
            runtimeCardJson(
                "user-released-response",
                quality = "USER_RELEASED_REVIEW",
                consentRevision = "2026-08-04-user-released-human-review",
            ),
        )
        val store = CapturingStore()
        var embeddingCalls = 0
        val embedding =
            SpeechStyleEmbeddingPort { texts ->
                embeddingCalls++
                texts.map { floatArrayOf(1f, 0f) }
            }

        assertThatThrownBy {
            HumanSpeechStyleRagImportService(store, embedding).importJsonLines(file)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("disallowed quality")
        assertThat(embeddingCalls).isZero()

        val report =
            HumanSpeechStyleRagImportService(store, embedding).importJsonLines(
                file,
                allowedQualities = setOf(HumanSpeechStyleQuality.USER_RELEASED_REVIEW),
            )

        assertThat(report.importedCount).isEqualTo(1)
        assertThat(report.promptEligibleCount).isEqualTo(1)
        assertThat(store.examples.single().quality).isEqualTo(HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
    }

    @Test
    fun `비활성 카드도 감사용으로 적재하되 Speech 프롬프트 후보로 표시하지 않는다`() {
        val file = temporaryDirectory.resolve("disabled-card.jsonl")
        Files.writeString(file, runtimeCardJson("disabled-response", promptEligible = false))
        val store = CapturingStore()
        var embeddingCalls = 0
        val embedding =
            SpeechStyleEmbeddingPort { texts ->
                embeddingCalls++
                texts.map { floatArrayOf(1f, 0f) }
            }

        val report = HumanSpeechStyleRagImportService(store, embedding).importJsonLines(file)

        assertThat(report.importedCount).isEqualTo(1)
        assertThat(report.promptEligibleCount).isZero()
        assertThat(store.examples.single().promptEligible).isFalse()
        assertThat(store.examples.single().providerStyleCues)
            .containsExactly(HumanSpeechStyleProviderStyleCue.ALIGNMENT_LOW_KEY_ACK)
        assertThat(store.examples.single().embedding).containsExactly(0f)
        assertThat(embeddingCalls).isZero()
    }

    @Test
    fun `card local alias 표지는 원문을 provider에 보내지 않는 style pattern을 막지 않는다`() {
        val file = temporaryDirectory.resolve("alias-bearing-card.jsonl")
        Files.writeString(file, runtimeCardJson("alias-bearing-response", responseSurfaceHasCardLocalAlias = true))
        val store = CapturingStore()
        var embeddingCalls = 0
        val embedding =
            SpeechStyleEmbeddingPort { texts ->
                embeddingCalls++
                texts.map { floatArrayOf(1f, 0f) }
            }

        val report = HumanSpeechStyleRagImportService(store, embedding).importJsonLines(file)

        assertThat(report.promptEligibleCount).isEqualTo(1)
        assertThat(embeddingCalls).isEqualTo(1)
        assertThat(store.examples.single().promptSurface).isEqualTo(HumanSpeechStylePromptSurface.STYLE_PATTERN)
    }

    @Test
    fun `legacy raw context response pair는 prompt eligible artifact로 적재하지 않는다`() {
        val file = temporaryDirectory.resolve("pair-alias-card.jsonl")
        Files.writeString(
            file,
            runtimeCardJson(
                "pair-alias-response",
                responseSurfaceHasCardLocalAlias = true,
                promptSurface = "CONTEXT_RESPONSE_PAIR",
            ),
        )
        val store = CapturingStore()
        var embeddingCalls = 0
        val embedding =
            SpeechStyleEmbeddingPort {
                embeddingCalls++
                it.map { floatArrayOf(1f, 0f) }
            }

        assertThatThrownBy { HumanSpeechStyleRagImportService(store, embedding).importJsonLines(file) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("invalid at line 1")
            .hasRootCauseMessage("human speech style prompt-eligible card must use the closed style-pattern surface")
        assertThat(embeddingCalls).isZero()
        assertThat(store.examples).isEmpty()
    }

    @Test
    fun `response move가 없어도 같은 enum의 Speech 후보로 적재한다`() {
        val file = temporaryDirectory.resolve("legacy-audit-card.jsonl")
        Files.writeString(file, runtimeCardJson("legacy-response", responseMove = null))
        val store = CapturingStore()
        val embedding = SpeechStyleEmbeddingPort { texts -> texts.map { floatArrayOf(1f, 0f) } }

        val report = HumanSpeechStyleRagImportService(store, embedding).importJsonLines(file)

        assertThat(report.importedCount).isEqualTo(1)
        assertThat(report.promptEligibleCount).isEqualTo(1)
        assertThat(store.examples.single().promptEligible).isTrue()
        assertThat(store.examples.single().responseMove).isNull()
        assertThat(store.examples.single().embedding).containsExactly(1f, 0f)
    }

    @Test
    fun `시간 조율 card도 보조 response metadata와 함께 후보로 적재한다`() {
        val file = temporaryDirectory.resolve("unsupported-runtime-contract.jsonl")
        Files.writeString(
            file,
            runtimeCardJson(
                "time-question-response",
                responseMode = "COORDINATION",
                responseMove = "COORDINATION_TIME",
                sceneTraits = listOf("COORDINATION_TIME"),
                responseForm = "QUESTION",
                responseRhythm = listOf("COORDINATION_CHECK", "SINGLE_BUBBLE"),
            ),
        )
        val store = CapturingStore()
        val embedding = SpeechStyleEmbeddingPort { texts -> texts.map { floatArrayOf(1f, 0f) } }

        val report = HumanSpeechStyleRagImportService(store, embedding).importJsonLines(file)

        assertThat(report.importedCount).isEqualTo(1)
        assertThat(report.promptEligibleCount).isEqualTo(1)
        assertThat(store.examples.single().promptEligible).isTrue()
    }

    @Test
    fun `활성 카드만 외부 embedding 입력으로 보내고 전체 카드는 감사용으로 적재한다`() {
        val file = temporaryDirectory.resolve("mixed-cards.jsonl")
        Files.writeString(
            file,
            listOf(
                runtimeCardJson("eligible-response", exampleId = "human-style-000001"),
                runtimeCardJson(
                    "disabled-response",
                    exampleId = "human-style-000002",
                    promptEligible = false,
                ),
            ).joinToString("\n"),
        )
        val store = CapturingStore()
        var embeddingInput: List<String>? = null
        val embedding =
            SpeechStyleEmbeddingPort { texts ->
                embeddingInput = texts
                texts.map { floatArrayOf(1f, 0f) }
            }

        val report = HumanSpeechStyleRagImportService(store, embedding).importJsonLines(file)

        assertThat(report.importedCount).isEqualTo(2)
        assertThat(report.promptEligibleCount).isEqualTo(1)
        assertThat(embeddingInput).isNotNull()
        assertThat(embeddingInput!!).hasSize(2)
        assertThat(embeddingInput!!).allSatisfy { input -> assertThat(input).doesNotContain("disabled-response") }
        assertThat(store.examples).hasSize(2)
        assertThat(store.examples.single { !it.promptEligible }.embedding).containsExactly(0f, 0f)
    }

    @Test
    fun `unknown provenance field가 붙은 card는 import 전에 거절한다`() {
        val file = temporaryDirectory.resolve("unknown-field-card.jsonl")
        val mapper = jacksonObjectMapper()
        val card = mapper.readTree(runtimeCardJson("synthetic-response")) as ObjectNode
        card.put("raw_source_path", "/synthetic/private/source")
        Files.writeString(file, mapper.writeValueAsString(card))

        assertThatThrownBy {
            HumanSpeechStyleRagImportService(CapturingStore(), SpeechStyleEmbeddingPort { emptyList() })
                .importJsonLines(file)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("invalid at line 1")
            .hasRootCauseMessage("human speech style import card fields are invalid")
    }

    @Test
    fun `필수 response rhythm이 빠진 card는 import 전에 거절한다`() {
        val file = temporaryDirectory.resolve("missing-rhythm-card.jsonl")
        val mapper = jacksonObjectMapper()
        val card = mapper.readTree(runtimeCardJson("synthetic-response")) as ObjectNode
        card.remove("response_rhythm")
        Files.writeString(file, mapper.writeValueAsString(card))

        assertThatThrownBy {
            HumanSpeechStyleRagImportService(CapturingStore(), SpeechStyleEmbeddingPort { emptyList() })
                .importJsonLines(file)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("invalid at line 1")
            .hasRootCauseMessage("human speech style import card fields are invalid")
    }

    @Test
    fun `필수 prompt surface가 빠진 card는 import 전에 거절한다`() {
        val file = temporaryDirectory.resolve("missing-prompt-surface-card.jsonl")
        val mapper = jacksonObjectMapper()
        val card = mapper.readTree(runtimeCardJson("synthetic-response")) as ObjectNode
        card.remove("prompt_surface")
        Files.writeString(file, mapper.writeValueAsString(card))

        assertThatThrownBy {
            HumanSpeechStyleRagImportService(CapturingStore(), SpeechStyleEmbeddingPort { emptyList() })
                .importJsonLines(file)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("invalid at line 1")
            .hasRootCauseMessage("human speech style import card fields are invalid")
    }

    @Test
    fun `unknown 또는 response mode와 맞지 않는 provider style cue는 import 전에 거절한다`() {
        val mapper = jacksonObjectMapper()
        listOf("UNKNOWN", "CARE_SOFT_NUDGE").forEachIndexed { index, cue ->
            val file = temporaryDirectory.resolve("invalid-style-cue-$index.jsonl")
            val card = mapper.readTree(runtimeCardJson("synthetic-response")) as ObjectNode
            card.putArray("provider_style_cues").add(cue)
            Files.writeString(file, mapper.writeValueAsString(card))

            assertThatThrownBy {
                HumanSpeechStyleRagImportService(CapturingStore(), SpeechStyleEmbeddingPort { emptyList() })
                    .importJsonLines(file)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("invalid at line 1")
        }
    }

    @Test
    fun `style pattern은 provider primary style cue를 정확히 하나 가져야 한다`() {
        val file = temporaryDirectory.resolve("missing-style-cue.jsonl")
        val mapper = jacksonObjectMapper()
        val card = mapper.readTree(runtimeCardJson("synthetic-response")) as ObjectNode
        card.putArray("provider_style_cues")
        Files.writeString(file, mapper.writeValueAsString(card))

        assertThatThrownBy {
            HumanSpeechStyleRagImportService(CapturingStore(), SpeechStyleEmbeddingPort { emptyList() })
                .importJsonLines(file)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("invalid at line 1")
            .hasRootCauseMessage("human speech style style-pattern card must have exactly one provider style cue")

        val multiplePrimaryCues = mapper.readTree(runtimeCardJson("synthetic-response")) as ObjectNode
        multiplePrimaryCues
            .putArray("provider_style_cues")
            .add("ALIGNMENT_LOW_KEY_ACK")
            .add("ALIGNMENT_SHARED_FEELING")
        Files.writeString(file, mapper.writeValueAsString(multiplePrimaryCues))

        assertThatThrownBy {
            HumanSpeechStyleRagImportService(CapturingStore(), SpeechStyleEmbeddingPort { emptyList() })
                .importJsonLines(file)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("invalid at line 1")
            .hasRootCauseMessage("human speech style provider style cues has too many values")
    }

    @Test
    fun `audit only card는 provider primary style cue를 하나까지 보관할 수 있다`() {
        val file = temporaryDirectory.resolve("audit-style-cue.jsonl")
        Files.writeString(
            file,
            runtimeCardJson(
                "audit-response",
                promptEligible = false,
                providerStyleCues = listOf("ALIGNMENT_SHARED_FEELING"),
            ),
        )
        val store = CapturingStore()

        val report =
            HumanSpeechStyleRagImportService(store, SpeechStyleEmbeddingPort { emptyList() }).importJsonLines(file)

        assertThat(report.promptEligibleCount).isZero()
        assertThat(store.examples.single().providerStyleCues)
            .containsExactly(HumanSpeechStyleProviderStyleCue.ALIGNMENT_SHARED_FEELING)

        val multipleAuditCues = jacksonObjectMapper().readTree(runtimeCardJson("audit-response", promptEligible = false)) as ObjectNode
        multipleAuditCues
            .putArray("provider_style_cues")
            .add("ALIGNMENT_LOW_KEY_ACK")
            .add("ALIGNMENT_SHARED_FEELING")
        Files.writeString(file, jacksonObjectMapper().writeValueAsString(multipleAuditCues))

        assertThatThrownBy {
            HumanSpeechStyleRagImportService(CapturingStore(), SpeechStyleEmbeddingPort { emptyList() })
                .importJsonLines(file)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("invalid at line 1")
            .hasRootCauseMessage("human speech style provider style cues has too many values")
    }

    private fun runtimeCardJson(
        responseText: String,
        situation: String = "private-situation-marker",
        styleSignals: List<String> = listOf("private-style-signal-marker"),
        contextText: String = "private-context-marker",
        exampleId: String = "human-style-000001",
        responseMode: String = "ALIGNMENT",
        quality: String = "CURATION_APPROVED",
        consentRevision: String = "2026-08-04-curation-approved",
        promptEligible: Boolean = true,
        promptSurface: String? = if (promptEligible) "STYLE_PATTERN" else "AUDIT_ONLY",
        responseSurfaceHasCardLocalAlias: Boolean = false,
        responseMove: String? = "ALIGNMENT_COMPLAINT",
        sceneTraits: List<String> = emptyList(),
        providerStyleCues: List<String> = defaultProviderStyleCues(responseMode),
        responseMoveProvenance: String = if (responseMove == null) "NONE" else "HEURISTIC_OBSERVED",
        responseForm: String? = "ALIGN_AND_ADD",
        responseRhythm: List<String> = listOf("AGREE_AND_ADD", "SINGLE_BUBBLE"),
    ): String =
        jacksonObjectMapper().writeValueAsString(
            mapOf(
                "schema" to "nia-human-speech-style-import-card.v4",
                "example_id" to exampleId,
                "response_mode" to responseMode,
                "situation" to situation,
                "style_signals" to styleSignals,
                "context_bubbles" to listOf(mapOf("speaker" to "가명1", "text" to contextText)),
                "response_bubbles" to listOf(mapOf("speaker" to "가명2", "text" to responseText)),
                "quality" to quality,
                "source_fingerprint" to "sha256:${"a".repeat(64)}",
                "consent_revision" to consentRevision,
                "combined_chars" to 70,
                "prompt_eligible" to promptEligible,
                "prompt_surface" to promptSurface,
                "response_surface_has_card_local_alias" to responseSurfaceHasCardLocalAlias,
                "response_move" to responseMove,
                "scene_traits" to sceneTraits,
                "provider_style_cues" to providerStyleCues,
                "response_move_provenance" to responseMoveProvenance,
                "response_form" to responseForm,
                "response_rhythm" to responseRhythm,
                "embedding_model" to "text-embedding-3-small",
            ),
        )

    private fun defaultProviderStyleCues(responseMode: String): List<String> =
        when (responseMode) {
            "REACTION" -> listOf("REACTION_IMMEDIATE")
            "ALIGNMENT" -> listOf("ALIGNMENT_LOW_KEY_ACK")
            "PLAY" -> listOf("PLAY_COUNTERTEASE")
            "FOLLOW_UP" -> listOf("FOLLOW_UP_SOFT_CHECK")
            "SPECULATION" -> listOf("SPECULATION_LIGHT_HEDGE")
            "CARE" -> listOf("CARE_GENTLE_VALIDATE")
            "COORDINATION" -> listOf("COORDINATION_ASK_ONE")
            else -> emptyList()
        }

    private class CapturingStore : HumanSpeechStyleExampleStorePort {
        var examples: List<HumanSpeechStyleExample> = emptyList()

        override fun listEnabled(): List<HumanSpeechStyleExample> = examples

        override fun replaceAll(examples: List<HumanSpeechStyleExample>): Int {
            this.examples = examples
            return examples.size
        }
    }
}
