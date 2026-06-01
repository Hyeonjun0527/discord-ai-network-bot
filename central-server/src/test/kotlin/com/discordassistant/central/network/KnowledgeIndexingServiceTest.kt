package com.discordassistant.central.network

import com.discordassistant.central.dashboard.AddKnowledgeSourceRequest
import com.discordassistant.central.dashboard.CreateKnowledgeSpaceRequest
import com.discordassistant.central.dashboard.KnowledgeIngestionController
import com.discordassistant.central.persistence.EmbeddingIndexJobRepository
import com.discordassistant.central.persistence.KnowledgeChunkRepository
import com.discordassistant.central.persistence.KnowledgeDocumentRepository
import com.discordassistant.central.persistence.KnowledgeSourceEntity
import com.discordassistant.central.persistence.KnowledgeSourceRepository
import com.discordassistant.central.persistence.KnowledgeSpaceEntity
import com.discordassistant.central.persistence.KnowledgeSpaceRepository
import com.discordassistant.central.persistence.RetrievalPolicyRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class KnowledgeIndexingServiceTest
    @Autowired
    constructor(
        private val spaces: KnowledgeSpaceRepository,
        private val sources: KnowledgeSourceRepository,
        private val documents: KnowledgeDocumentRepository,
        private val chunks: KnowledgeChunkRepository,
        private val jobs: EmbeddingIndexJobRepository,
        private val retrievalPolicies: RetrievalPolicyRepository,
    ) {
        private val fixedClock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC)
        private val service =
            KnowledgeIndexingService(
                spaces = spaces,
                sources = sources,
                documents = documents,
                chunks = chunks,
                jobs = jobs,
                retrievalPolicies = retrievalPolicies,
                clock = fixedClock,
            )

        @Test
        fun `source parsing creates document chunks and queued index job with guild scope`() {
            val space = spaces.save(KnowledgeSpaceEntity(guildId = 100, channelId = 200, displayName = "운영 지식"))
            val source =
                sources.save(
                    KnowledgeSourceEntity(
                        knowledgeSpaceId = space.id,
                        guildId = 100,
                        sourceType = "text",
                        title = "배포 절차",
                        status = "approved",
                    ),
                )

            val doc =
                service.parseSourceToDocument(
                    guildId = 100,
                    spaceId = space.id,
                    sourceId = source.id,
                    documentText = "1단계: health check\n\n2단계: rollback plan",
                )
            val job = service.queueIndexJob(100, space.id, triggeredBy = 77)

            assertEquals(source.id, doc.knowledgeSourceId)
            assertEquals(2, chunks.findByKnowledgeDocumentIdOrderByChunkIndex(doc.id).size)
            assertEquals(2, spaces.findByGuildIdAndId(100, space.id)!!.chunkCount)
            assertEquals(1, job.sourceCount)
            assertEquals(2, job.chunkCount)
            assertEquals("queued", job.status)
            assertTrue(service.readyChunks(100, space.id).all { it.guildId == 100L && it.channelId == 200L })
        }

        @Test
        fun `index jobs can be listed queued and completed through safe facade`() {
            val space = spaces.save(KnowledgeSpaceEntity(guildId = 102, channelId = 202, displayName = "작업 지식"))
            val queued = service.queueRebuildJob(102, space.id, triggeredBy = 7)

            val listed = service.listIndexJobs(102, space.id)
            assertEquals(listOf(queued.id), listed.map { it.id })
            assertEquals("queued", listed.single().status)

            val completed = service.completeIndexJobSafely(102, queued.id, "success", "rebuilt")
            assertEquals("completed", completed.status)
            assertEquals("rebuilt", completed.failureReason)
            assertEquals("completed", service.listIndexJobs(102).single().status)
        }

        @Test
        fun `inline text source is parsed indexed and queued for embedding rebuild`() {
            val space = spaces.save(KnowledgeSpaceEntity(guildId = 101, channelId = 201, displayName = "즉시 지식"))
            val source =
                sources.save(
                    KnowledgeSourceEntity(
                        knowledgeSpaceId = space.id,
                        guildId = 101,
                        sourceType = "text",
                        title = "운영 규칙",
                        status = "pending",
                    ),
                )

            val result =
                service.indexInlineSourceIfPossible(
                    guildId = 101,
                    spaceId = space.id,
                    sourceId = source.id,
                    documentText = "actuator health 확인\n\nrollback plan 확인",
                    triggeredBy = 7,
                )

            assertEquals(true, result.indexed)
            assertEquals(2, result.chunkCount)
            assertEquals("indexed", sources.findByKnowledgeSpaceIdAndId(space.id, source.id)!!.status)
            assertEquals("ready", spaces.findByGuildIdAndId(101, space.id)!!.status)
            assertEquals(1, jobs.findTop10ByGuildIdAndKnowledgeSpaceIdOrderByQueuedAtDesc(101, space.id).size)
            assertEquals(2, service.readyChunks(101, space.id).size)
        }

        @Test
        fun `knowledge dashboard add source immediately indexes inline text for website RAG`() {
            val ingestion =
                KnowledgeIngestionService(
                    spaces = spaces,
                    sources = sources,
                    clock = fixedClock,
                )
            val search =
                KnowledgeSearchService(
                    sources = sources,
                    spaces = spaces,
                    chunks = chunks,
                    retrievalPolicies = retrievalPolicies,
                )
            val controller = KnowledgeIngestionController(ingestion, search, service)
            val spaceId =
                controller.createSpace(
                    103,
                    CreateKnowledgeSpaceRequest(channelId = 203, displayName = "웹 지식", actorUserId = 7),
                )["id"] as Long

            val result =
                controller.addSource(
                    103,
                    spaceId,
                    AddKnowledgeSourceRequest(
                        sourceType = "text",
                        title = "웹 RAG 운영 규칙",
                        contentPreview = "웹에서 등록한 지식도 즉시 검색 가능해야 합니다.\n\n운영자는 queued job으로 embedding 재빌드를 실행합니다.",
                        actorUserId = 7,
                    ),
                )

            assertEquals("indexed", result["status"])
            assertEquals(true, result["inlineIndexed"])
            assertEquals(2, result["chunkCount"])
            assertTrue((result["indexJobId"] as Long) > 0)
            val found = search.search(103, "즉시 검색", limit = 5, channelId = 203)
            assertEquals(1, found.results.size)
            assertEquals("웹 RAG 운영 규칙", found.results.single().title)
        }

        @Test
        fun `retrieval policy clamps topK and token budget and remains scoped`() {
            val space = spaces.save(KnowledgeSpaceEntity(guildId = 100, channelId = 201, displayName = "FAQ"))

            val policy =
                service.saveRetrievalPolicy(
                    guildId = 100,
                    channelId = 201,
                    knowledgeSpaceId = space.id,
                    topK = 99,
                    tokenBudget = 99_999,
                    rerankEnabled = true,
                    sourcePriority = listOf("admin", "help"),
                )

            assertEquals(20, policy.topK)
            assertEquals(8000, policy.tokenBudget)
            assertEquals("admin,help", policy.sourcePriority)
            assertEquals(
                policy.id,
                retrievalPolicies
                    .findByGuildIdAndChannelIdAndKnowledgeSpaceIdAndStatus(100, 201, space.id, "active")
                    ?.id,
            )
        }

        @Test
        fun `search uses ready chunks with retrieval policy caps and deleted sources excluded`() {
            val space = spaces.save(KnowledgeSpaceEntity(guildId = 100, channelId = 203, displayName = "검색 지식"))
            val source =
                sources.save(
                    KnowledgeSourceEntity(
                        knowledgeSpaceId = space.id,
                        guildId = 100,
                        sourceType = "text",
                        title = "Kotlin 운영 가이드",
                        status = "indexed",
                    ),
                )
            service.parseSourceToDocument(
                guildId = 100,
                spaceId = space.id,
                sourceId = source.id,
                documentText = "Kotlin 장애 대응은 actuator health 부터 확인합니다.\n\nKotlin rollback 은 이전 app.jar 로 되돌립니다.",
            )
            service.saveRetrievalPolicy(
                guildId = 100,
                channelId = 203,
                knowledgeSpaceId = space.id,
                topK = 1,
                tokenBudget = 256,
                rerankEnabled = true,
                sourcePriority = emptyList(),
            )
            val search =
                KnowledgeSearchService(
                    sources = sources,
                    spaces = spaces,
                    chunks = chunks,
                    retrievalPolicies = retrievalPolicies,
                )

            val found = search.search(100, "Kotlin", limit = 10, channelId = 203)
            assertEquals(1, found.results.size)
            assertEquals(source.id, found.results.single().sourceId)
            assertTrue(
                found
                    .results
                    .single()
                    .contentPreview!!
                    .contains("Kotlin"),
            )

            val context = search.promptContext(100, "actuator", maxChars = 8_000, channelId = 203)
            assertEquals(256, context.maxChars)
            assertTrue(context.contextText.contains("actuator health"))

            val deleted = sources.findByKnowledgeSpaceIdAndId(space.id, source.id)!!
            deleted.status = "deleted_outdated"
            sources.save(deleted)
            val afterDelete = search.search(100, "Kotlin", limit = 10, channelId = 203)
            assertTrue(afterDelete.results.isEmpty())
            assertEquals("no_indexed_knowledge_match", afterDelete.fallbackReason)
        }

        @Test
        fun `reparsing a source supersedes old documents and chunks`() {
            val space = spaces.save(KnowledgeSpaceEntity(guildId = 100, channelId = 204, displayName = "재색인 지식"))
            val source =
                sources.save(
                    KnowledgeSourceEntity(
                        knowledgeSpaceId = space.id,
                        guildId = 100,
                        sourceType = "text",
                        title = "운영 가이드",
                        status = "indexed",
                    ),
                )

            val first =
                service.parseSourceToDocument(
                    guildId = 100,
                    spaceId = space.id,
                    sourceId = source.id,
                    documentText = "old deploy\n\nold rollback",
                )
            val second =
                service.parseSourceToDocument(
                    guildId = 100,
                    spaceId = space.id,
                    sourceId = source.id,
                    documentText = "new deploy",
                )

            assertEquals("superseded", documents.findById(first.id).get().status)
            assertEquals("parsed", documents.findById(second.id).get().status)
            assertTrue(chunks.findByKnowledgeDocumentIdOrderByChunkIndex(first.id).all { it.status == "superseded" })
            assertEquals(listOf(second.id), service.readyChunks(100, space.id).map { it.knowledgeDocumentId }.distinct())
            assertEquals(1, spaces.findByGuildIdAndId(100, space.id)!!.chunkCount)
        }

        @Test
        fun `rag indexing service obeys feature kill switch`() {
            val disabled =
                KnowledgeIndexingService(
                    spaces = spaces,
                    sources = sources,
                    documents = documents,
                    chunks = chunks,
                    jobs = jobs,
                    retrievalPolicies = retrievalPolicies,
                    clock = fixedClock,
                    featureGate = AiNetworkFeatureGate(ragEnabled = false),
                )

            assertThrows(IllegalStateException::class.java) {
                disabled.readyChunks(100, 1)
            }
        }

        @Test
        fun `cross guild source and job updates are rejected`() {
            val space = spaces.save(KnowledgeSpaceEntity(guildId = 100, channelId = 202, displayName = "A"))
            val source =
                sources.save(
                    KnowledgeSourceEntity(
                        knowledgeSpaceId = space.id,
                        guildId = 999,
                        sourceType = "text",
                        title = "foreign",
                        status = "approved",
                    ),
                )

            assertThrows(IllegalArgumentException::class.java) {
                service.parseSourceToDocument(100, space.id, source.id, "foreign text")
            }

            val job = service.queueIndexJob(100, space.id, triggeredBy = 77)
            assertThrows(IllegalArgumentException::class.java) {
                service.completeIndexJob(999, job.id, "completed")
            }
        }
    }
