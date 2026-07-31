package com.discordassistant.central.participation.application.rag

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
    fun `replace stores one local text index per conversation and keeps expected reply out of search text`() {
        val store = FakeStore()
        val service = ConversationRagService(store, Clock.fixed(now, ZoneOffset.UTC))

        val library = service.replace(listOf(example("피곤한 대화", "니아야 피곤해", "푹 자"), example("게임 대화", "스타할래", "한 판 ㄱ")))

        assertThat(library.entries).hasSize(2)
        assertThat(library.indexedCount).isEqualTo(2)
        assertThat(library.embeddingModel).isEqualTo(ConversationRagService.LOCAL_TEXT_SCORING_MODEL)
        assertThat(store.entries.map { it.searchText }).allMatch { it.isNotBlank() }
        assertThat(store.entries).allMatch { it.embedding == null && it.embeddingModel == null }
        assertThat(store.entries.first().searchText).contains("니아야 피곤해").doesNotContain("푹 자")
    }

    @Test
    fun `local text search returns only related conversations in score order`() {
        val store =
            FakeStore(
                mutableListOf(
                    entry(1, "피곤", "a: 오늘 너무 피곤하다", floatArrayOf(1f, 0f)),
                    entry(2, "몸살", "a: 오늘 피곤하고 몸이 안 좋아", floatArrayOf(0.9f, 0.1f)),
                    entry(3, "게임", "a: 스타크래프트 한 판 하자", floatArrayOf(0f, 1f)),
                ),
            )
        val service = ConversationRagService(store)

        val matches = service.search("오늘 너무 피곤해")

        assertThat(matches.map { it.entry.id }).containsExactly(1, 2)
        assertThat(matches).allMatch { it.scoringMethod == ConversationRagScoringMethod.LOCAL_TEXT }
        assertThat(matches[0].score).isGreaterThan(matches[1].score)
    }

    @Test
    fun `local text search excludes unrelated conversations`() {
        val store =
            FakeStore(
                mutableListOf(
                    entry(1, "피곤", "a: 오늘 너무 피곤하다", null),
                    entry(2, "게임", "a: 스타크래프트 하자", null),
                    entry(3, "식사", "a: 저녁 뭐 먹지", null),
                ),
            )
        val service = ConversationRagService(store)

        val matches = service.search("오늘 피곤하다", limit = 2)

        assertThat(matches.map { it.entry.id }).containsExactly(1)
        assertThat(matches).allMatch { it.scoringMethod == ConversationRagScoringMethod.LOCAL_TEXT }
    }

    @Test
    fun `stored vectors are ignored and search remains local`() {
        val store =
            FakeStore(
                mutableListOf(
                    entry(1, "피곤", "a: 오늘 너무 피곤하다", floatArrayOf(0f, 1f)),
                    entry(2, "게임", "a: 스타크래프트 하자", floatArrayOf(1f, 0f)),
                ),
            )
        val service = ConversationRagService(store)

        val matches = service.search("오늘 피곤하다")

        assertThat(matches.first().entry.id).isEqualTo(1)
        assertThat(matches).allMatch { it.scoringMethod == ConversationRagScoringMethod.LOCAL_TEXT }
    }

    @Test
    fun `global few-shot과 같은 장면만 있으면 RAG embedding과 중복 삽입을 모두 생략한다`() {
        val duplicate = example("피곤한 대화", "오늘 너무 피곤해", "얼른 자")
        val canonical = ConversationRagService.canonicalScene(duplicate)
        val stored =
            ConversationRagEntry(
                id = 1,
                example = duplicate.copy(id = 1),
                searchText = canonical,
                embedding = floatArrayOf(1f, 0f),
                embeddingModel = "test-embedding",
                createdAt = now,
                updatedAt = now,
            )
        val store = FakeStore(mutableListOf(stored))
        val service = ConversationRagService(store)

        val matches =
            service.search(
                sceneText = "오늘 너무 피곤해",
                excludedCanonicalScenes = setOf(canonical),
            )

        assertThat(matches).isEmpty()
    }

    @Test
    fun `entries can be added edited filtered and deleted independently`() {
        val store = FakeStore()
        val service = ConversationRagService(store, Clock.fixed(now, ZoneOffset.UTC))

        val first = service.create(example("피곤한 대화", "오늘 너무 피곤해", "얼른 자"))
        val second = service.create(example("게임 대화", "스타 한 판", "ㄱ"))

        assertThat(service.page("피곤", 0, 100).entries.map { it.id }).containsExactly(first.id)
        val updated = service.update(requireNotNull(second.id), example("저녁 게임", "오늘 스타 할래", "한 판 ㄱ"))
        assertThat(updated.id).isEqualTo(second.id)
        assertThat(service.entry(requireNotNull(second.id)).example.title).isEqualTo("저녁 게임")

        service.delete(requireNotNull(first.id))
        assertThat(service.library().entries.map { it.id }).containsExactly(second.id)
    }

    @Test
    fun `library page handles more than one hundred long lived examples without returning every body`() {
        val store = FakeStore()
        val service = ConversationRagService(store, Clock.fixed(now, ZoneOffset.UTC))
        service.createAll((1..120).map { index -> example("대화 $index", "장면 $index", "응답 $index") })

        val page = service.page(query = null, offset = 100, limit = 20)
        val firstEntry = page.entries.first()
        val firstTitle = firstEntry.example.title

        assertThat(page.total).isEqualTo(120)
        assertThat(page.entries).hasSize(20)
        assertThat(firstTitle).isEqualTo("대화 101")
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

        override fun find(entryId: Long): ConversationRagEntry? = entries.find { it.id == entryId }

        override fun save(entry: ConversationRagStoredEntry): ConversationRagEntry {
            val id = entry.id ?: ((entries.maxOfOrNull { it.id ?: 0 } ?: 0) + 1)
            val saved =
                ConversationRagEntry(
                    id = id,
                    example = entry.example.copy(id = id),
                    searchText = entry.searchText,
                    embedding = entry.embedding,
                    embeddingModel = entry.embeddingModel,
                    createdAt = entry.createdAt ?: entry.indexedAt,
                    updatedAt = entry.indexedAt,
                )
            entries.removeIf { it.id == id }
            entries += saved
            entries.sortBy { it.id }
            return saved
        }

        override fun delete(entryId: Long): Boolean = entries.removeIf { it.id == entryId }

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
}
