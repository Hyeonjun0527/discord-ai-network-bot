package com.discordassistant.central.speech.humanstyle

import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStyleRagImportService
import com.discordassistant.central.speech.application.port.out.HumanSpeechStyleExampleStorePort
import com.discordassistant.central.speech.application.port.out.SpeechStyleEmbeddingPort
import com.discordassistant.central.speech.domain.model.HumanSpeechResponseMode
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleExample
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
    fun `runtime JSONL의 전체 카드 수를 적재하고 실제 사람 답변은 embedding 입력에서 제외한다`() {
        val responseText = "private-style-response-marker-12345"
        val file = temporaryDirectory.resolve("cards.jsonl")
        Files.writeString(file, runtimeCardJson(responseText))
        val store = CapturingStore()
        var embeddingInput: List<String>? = null
        val embedding =
            SpeechStyleEmbeddingPort { texts ->
                embeddingInput = texts
                texts.map { floatArrayOf(1f, 0f) }
            }

        val report = HumanSpeechStyleRagImportService(store, embedding).importJsonLines(file)

        assertThat(report.importedCount).isEqualTo(1)
        assertThat(report.responseModeCounts).containsEntry(HumanSpeechResponseMode.ALIGNMENT, 1)
        val storedResponse =
            store.examples
                .single()
                .responseBubbles
                .single()
                .text
        assertThat(storedResponse).isEqualTo(responseText)
        assertThat(embeddingInput!!.single())
            .doesNotContain(responseText)
            .contains("앞 대화")
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

    private fun runtimeCardJson(
        responseText: String,
        quality: String = "CURATION_APPROVED",
    ): String =
        jacksonObjectMapper().writeValueAsString(
            mapOf(
                "schema" to "nia-human-speech-style-import-card.v1",
                "example_id" to "human-style-000001",
                "response_mode" to "ALIGNMENT",
                "situation" to "불평에 짧게 맞장구친다",
                "style_signals" to listOf("짧게"),
                "context_bubbles" to listOf(mapOf("speaker" to "가명1", "text" to "오늘 좀 답답하네")),
                "response_bubbles" to listOf(mapOf("speaker" to "가명2", "text" to responseText)),
                "quality" to quality,
                "source_fingerprint" to "sha256:${"a".repeat(64)}",
                "consent_revision" to "2026-08-04-curation-approved",
                "combined_chars" to 70,
                "embedding_model" to "text-embedding-3-small",
            ),
        )

    private class CapturingStore : HumanSpeechStyleExampleStorePort {
        var examples: List<HumanSpeechStyleExample> = emptyList()

        override fun listEnabled(): List<HumanSpeechStyleExample> = examples

        override fun replaceAll(examples: List<HumanSpeechStyleExample>): Int {
            this.examples = examples
            return examples.size
        }
    }
}
