package com.discordassistant.central.network

import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import com.discordassistant.central.knowledge.adapter.inbound.web.KnowledgeIngestionController
import com.discordassistant.central.knowledge.adapter.inbound.web.dto.AddKnowledgeSourceRequest
import com.discordassistant.central.knowledge.adapter.inbound.web.dto.CreateKnowledgeSpaceRequest
import com.discordassistant.central.knowledge.adapter.inbound.web.dto.DeleteKnowledgeSourceRequest
import com.discordassistant.central.knowledge.adapter.outbound.persistence.EmbeddingIndexJobRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeChunkRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeDocumentRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSourceEntity
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSourceRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSpaceEntity
import com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSpaceRepository
import com.discordassistant.central.knowledge.adapter.outbound.persistence.RetrievalPolicyRepository
import com.discordassistant.central.knowledge.application.KnowledgeIndexingService
import com.discordassistant.central.knowledge.application.KnowledgeIngestionService
import com.discordassistant.central.knowledge.application.KnowledgeSearchService
import com.discordassistant.central.knowledge.domain.model.EmbeddingJobStatus
import com.discordassistant.central.knowledge.domain.model.KnowledgeChunkStatus
import com.discordassistant.central.knowledge.domain.model.KnowledgeDocumentStatus
import com.discordassistant.central.knowledge.domain.model.KnowledgeSourceStatus
import com.discordassistant.central.knowledge.domain.model.RetrievalPolicyStatus
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
                        status = KnowledgeSourceStatus.fromWire("approved"),
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
            assertEquals("queued", job.status.wire)
            assertTrue(service.readyChunks(100, space.id).all { it.guildId == 100L && it.channelId == 200L })
        }

        @Test
        fun `document without blank-line breaks is split so all content stays searchable`() {
            val space = spaces.save(KnowledgeSpaceEntity(guildId = 110, channelId = 210, displayName = "긴 문서"))
            val source =
                sources.save(
                    KnowledgeSourceEntity(
                        knowledgeSpaceId = space.id,
                        guildId = 110,
                        sourceType = "text",
                        title = "긴 가이드",
                        status = KnowledgeSourceStatus.fromWire("approved"),
                    ),
                )
            // 빈 줄 문단 경계가 전혀 없는 문서 → 이전엔 한 청크로 뭉쳐 미리보기 2000자만 검색 가능했다.
            val longText = (1..600).joinToString(" ") { "문장$it." }
            assertTrue(longText.length > 2000)

            val doc =
                service.parseSourceToDocument(
                    guildId = 110,
                    spaceId = space.id,
                    sourceId = source.id,
                    documentText = longText,
                )

            val docChunks = chunks.findByKnowledgeDocumentIdOrderByChunkIndex(doc.id)
            assertTrue(docChunks.size > 1)
            assertTrue(docChunks.all { it.contentPreview.length <= 2000 })
            // 모든 내용이 색인됨: 청크 미리보기 총 길이가 원문 길이(경계 공백 손실만큼 여유)를 덮는다.
            assertTrue(docChunks.sumOf { it.contentPreview.length } >= longText.length - docChunks.size)
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
                        status = KnowledgeSourceStatus.PENDING,
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
            assertEquals("indexed", sources.findByKnowledgeSpaceIdAndId(space.id, source.id)!!.status.wire)
            assertEquals("ready", spaces.findByGuildIdAndId(101, space.id)!!.status.wire)
            assertEquals(1, jobs.findTop10ByGuildIdAndKnowledgeSpaceIdOrderByQueuedAtDesc(101, space.id).size)
            assertEquals(2, service.readyChunks(101, space.id).size)
        }

        @Test
        fun `secret material is blocked before document chunks are created`() {
            val space = spaces.save(KnowledgeSpaceEntity(guildId = 104, channelId = 204, displayName = "보안 지식"))
            val source =
                sources.save(
                    KnowledgeSourceEntity(
                        knowledgeSpaceId = space.id,
                        guildId = 104,
                        sourceType = "text",
                        title = "운영 env",
                        status = KnowledgeSourceStatus.PENDING,
                        riskLevel = "normal",
                    ),
                )

            assertThrows(IllegalArgumentException::class.java) {
                service.parseSourceToDocument(
                    guildId = 104,
                    spaceId = space.id,
                    sourceId = source.id,
                    documentText = "DISCORD_BOT_TOKEN=secret-value",
                )
            }

            val blocked = sources.findByKnowledgeSpaceIdAndId(space.id, source.id)!!
            assertEquals("blocked_sensitive", blocked.status.wire)
            assertEquals("sensitive", blocked.riskLevel)
            assertTrue(documents.findByKnowledgeSourceId(source.id).isEmpty())
            assertTrue(chunks.findByKnowledgeSpaceIdAndStatus(space.id, KnowledgeChunkStatus.READY).isEmpty())
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
            val space =
                controller.createSpace(
                    103,
                    CreateKnowledgeSpaceRequest(channelId = 203, displayName = "웹 지식", actorUserId = 7),
                )
            val spaceId = space.id

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

            assertEquals("indexed", result.status)
            assertEquals(true, result.inlineIndexed)
            assertEquals(2, result.chunkCount)
            assertTrue(result.indexJobId!! > 0)
            val found = search.search(103, "즉시 검색", limit = 5, channelId = 203)
            assertEquals(1, found.results.size)
            assertEquals("웹 RAG 운영 규칙", found.results.single().title)
        }

        @Test
        fun `knowledge dashboard delete source tombstones chunks and queues delete index job`() {
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
            val space =
                controller.createSpace(
                    105,
                    CreateKnowledgeSpaceRequest(channelId = 205, displayName = "삭제 전파 지식", actorUserId = 7),
                )
            val spaceId = space.id
            val added =
                controller.addSource(
                    105,
                    spaceId,
                    AddKnowledgeSourceRequest(
                        sourceType = "text",
                        title = "삭제 테스트 지식",
                        contentPreview = "삭제 전 검색 가능한 문서입니다.\n\n삭제 후에는 검색되면 안 됩니다.",
                        actorUserId = 7,
                    ),
                )
            val sourceId = added.id
            assertEquals(2, service.readyChunks(105, spaceId).size)

            val deleted =
                controller.removeSource(
                    105,
                    spaceId,
                    sourceId,
                    DeleteKnowledgeSourceRequest(reason = "obsolete", actorUserId = 7),
                )

            val deletionJobId = deleted.deletionIndexJobId!!
            assertTrue(deletionJobId > 0)
            assertEquals(1, deleted.tombstonedDocumentCount)
            assertEquals(2, deleted.tombstonedChunkCount)
            assertEquals(0, deleted.remainingReadyChunkCount)
            assertTrue(service.readyChunks(105, spaceId).isEmpty())
            assertTrue(documents.findByKnowledgeSourceId(sourceId).all { it.status == KnowledgeDocumentStatus.DELETED })
            assertTrue(chunks.findByKnowledgeSpaceIdAndStatus(spaceId, KnowledgeChunkStatus.DELETED).size >= 2)
            assertEquals("delete_source", jobs.findById(deletionJobId).get().jobType)
            val afterDelete = search.search(105, "삭제", limit = 5, channelId = 205)
            assertTrue(afterDelete.results.isEmpty())
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
                    .findByGuildIdAndChannelIdAndKnowledgeSpaceIdAndStatus(100, 201, space.id, RetrievalPolicyStatus.ACTIVE)
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
                        status = KnowledgeSourceStatus.INDEXED,
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
            deleted.status = KnowledgeSourceStatus.deleted("outdated")
            sources.save(deleted)
            val afterDelete = search.search(100, "Kotlin", limit = 10, channelId = 203)
            assertTrue(afterDelete.results.isEmpty())
            assertEquals("no_indexed_knowledge_match", afterDelete.fallbackReason)
        }

        @Test
        fun `search exposes match signals and applies source priority weight`() {
            val space = spaces.save(KnowledgeSpaceEntity(guildId = 100, channelId = 204, displayName = "우선순위 지식"))
            val adminText =
                sources.save(
                    KnowledgeSourceEntity(
                        knowledgeSpaceId = space.id,
                        guildId = 100,
                        sourceType = "text",
                        title = "Kotlin 운영 메모",
                        status = KnowledgeSourceStatus.INDEXED,
                        addedBy = 77,
                    ),
                )
            val linkedGuide =
                sources.save(
                    KnowledgeSourceEntity(
                        knowledgeSpaceId = space.id,
                        guildId = 100,
                        sourceType = "link",
                        sourceUri = "https://example.com/kotlin-help.md",
                        title = "Kotlin 도움말",
                        status = KnowledgeSourceStatus.INDEXED,
                    ),
                )
            service.parseSourceToDocument(100, space.id, adminText.id, "Kotlin 운영 메모입니다.", title = adminText.title)
            service.parseSourceToDocument(100, space.id, linkedGuide.id, "Kotlin 도움말 문서입니다.", title = linkedGuide.title)
            service.saveRetrievalPolicy(
                guildId = 100,
                channelId = 204,
                knowledgeSpaceId = space.id,
                topK = 10,
                tokenBudget = 1800,
                rerankEnabled = true,
                sourcePriority = listOf("link", "text"),
            )
            val search =
                KnowledgeSearchService(
                    sources = sources,
                    spaces = spaces,
                    chunks = chunks,
                    retrievalPolicies = retrievalPolicies,
                )

            val found = search.search(100, "Kotlin", limit = 10, channelId = 204)

            assertEquals(listOf(linkedGuide.id, adminText.id), found.results.map { it.sourceId })
            val top = found.results.first()
            assertTrue(top.sourceWeight > 0)
            assertTrue(top.matchSignals.contains("chunk"))
            assertTrue(top.matchSignals.contains("term_title"))
            assertTrue(top.matchSignals.contains("source_type:link"))
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
                        status = KnowledgeSourceStatus.INDEXED,
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

            assertEquals(
                "superseded",
                documents
                    .findById(first.id)
                    .get()
                    .status.wire,
            )
            assertEquals(
                "parsed",
                documents
                    .findById(second.id)
                    .get()
                    .status.wire,
            )
            assertTrue(chunks.findByKnowledgeDocumentIdOrderByChunkIndex(first.id).all { it.status == KnowledgeChunkStatus.SUPERSEDED })
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
                        status = KnowledgeSourceStatus.fromWire("approved"),
                    ),
                )

            assertThrows(IllegalArgumentException::class.java) {
                service.parseSourceToDocument(100, space.id, source.id, "foreign text")
            }

            val job = service.queueIndexJob(100, space.id, triggeredBy = 77)
            assertThrows(IllegalArgumentException::class.java) {
                service.completeIndexJob(999, job.id, EmbeddingJobStatus.COMPLETED)
            }
        }
    }
