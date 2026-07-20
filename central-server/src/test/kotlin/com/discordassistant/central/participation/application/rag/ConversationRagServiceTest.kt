package com.discordassistant.central.participation.application.rag

import com.discordassistant.central.participation.application.port.out.ConversationEmbeddingPort
import com.discordassistant.central.participation.application.port.out.ConversationRagStorePort
import com.discordassistant.central.participation.application.port.out.ConversationRagStoredEntry
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotAction
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotBadAlternative
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotDeliveryMode
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotExample
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotRawMessage
import com.discordassistant.central.participation.domain.model.rag.ConversationRagEntry
import com.discordassistant.central.participation.domain.model.rag.ConversationRagScoringMethod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ConversationRagServiceTest {
    private val now = Instant.parse("2026-07-21T00:00:00Z")

    @Test
    fun `replace stores one semantic index per conversation and keeps expected reply out of search text`() {
        val store = FakeStore()
        val embeddings = FakeEmbeddings { texts -> texts.mapIndexed { index, _ -> floatArrayOf(index + 1f, 1f) } }
        val service = ConversationRagService(store, embeddings, Clock.fixed(now, ZoneOffset.UTC))

        val library = service.replace(listOf(example("피곤한 대화", "니아야 피곤해", "푹 자"), example("게임 대화", "스타할래", "한 판 ㄱ")))

        assertThat(library.entries).hasSize(2)
        assertThat(library.indexedCount).isEqualTo(2)
        assertThat(store.entries.map { it.searchText }).allMatch { it.isNotBlank() }
        assertThat(store.entries.first().searchText).contains("니아야 피곤해").doesNotContain("푹 자")
        assertThat(embeddings.inputs).containsExactly(listOf("피곤한 대화\na: 니아야 피곤해", "게임 대화\na: 스타할래"))
    }

    @Test
    fun `semantic search returns only the two closest conversations in score order`() {
        val store =
            FakeStore(
                mutableListOf(
                    entry(1, "피곤", "피곤하다", floatArrayOf(1f, 0f)),
                    entry(2, "몸살", "몸이 안 좋아", floatArrayOf(0.9f, 0.1f)),
                    entry(3, "게임", "스타 한 판", floatArrayOf(0f, 1f)),
                ),
            )
        val embeddings = FakeEmbeddings { listOf(floatArrayOf(1f, 0f)) }
        val service = ConversationRagService(store, embeddings)

        val matches = service.search("오늘 너무 피곤해")

        assertThat(matches.map { it.entry.id }).containsExactly(1, 2)
        assertThat(matches).allMatch { it.scoringMethod == ConversationRagScoringMethod.EMBEDDING }
        assertThat(matches[0].score).isGreaterThan(matches[1].score)
    }

    @Test
    fun `text fallback still retrieves the closest conversations when embeddings are unavailable`() {
        val store =
            FakeStore(
                mutableListOf(
                    entry(1, "피곤", "a: 오늘 너무 피곤하다", null),
                    entry(2, "게임", "a: 스타크래프트 하자", null),
                    entry(3, "식사", "a: 저녁 뭐 먹지", null),
                ),
            )
        val service = ConversationRagService(store, FakeEmbeddings(configured = false) { emptyList() })

        val matches = service.search("오늘 피곤하다", limit = 2)

        assertThat(matches).hasSize(2)
        assertThat(matches.first().entry.id).isEqualTo(1)
        assertThat(matches).allMatch { it.scoringMethod == ConversationRagScoringMethod.TEXT_FALLBACK }
    }

    private fun example(
        title: String,
        message: String,
        reply: String,
    ): NiaFewShotExample =
        NiaFewShotExample(
            title = title,
            rawMessages = listOf(NiaFewShotRawMessage("m1", "a", 0, message)),
            expectedAction = NiaFewShotAction.SPEAK,
            expectedDeliveryMode = NiaFewShotDeliveryMode.CHANNEL,
            expectedReplies = listOf(reply),
            reason = "현재 장면에 맞는 반응",
            evidenceRefs = setOf("m1"),
            badAlternative = NiaFewShotBadAlternative(NiaFewShotAction.IGNORE, "직접 말을 걸었는데 무시한다"),
        )

    private fun entry(
        id: Long,
        title: String,
        searchText: String,
        embedding: FloatArray?,
    ): ConversationRagEntry =
        ConversationRagEntry(
            id = id,
            example = example(title, searchText, "응"),
            searchText = searchText,
            embedding = embedding,
            embeddingModel = embedding?.let { "test-embedding" },
            createdAt = now,
            updatedAt = now,
        )

    private class FakeStore(
        var entries: MutableList<ConversationRagEntry> = mutableListOf(),
    ) : ConversationRagStorePort {
        override fun list(): List<ConversationRagEntry> = entries.toList()

        override fun replaceAll(entries: List<ConversationRagStoredEntry>): List<ConversationRagEntry> {
            this.entries =
                entries
                    .mapIndexed { index, stored ->
                        ConversationRagEntry(
                            id = index + 1L,
                            example = stored.example.copy(id = index + 1L),
                            searchText = stored.searchText,
                            embedding = stored.embedding,
                            embeddingModel = stored.embeddingModel,
                            createdAt = stored.indexedAt,
                            updatedAt = stored.indexedAt,
                        )
                    }.toMutableList()
            return list()
        }
    }

    private class FakeEmbeddings(
        private val configured: Boolean = true,
        private val answer: (List<String>) -> List<FloatArray>,
    ) : ConversationEmbeddingPort {
        val inputs = mutableListOf<List<String>>()
        override val model: String = "test-embedding"

        override fun isConfigured(): Boolean = configured

        override fun embed(texts: List<String>): List<FloatArray> {
            inputs += texts
            return answer(texts)
        }
    }
}
