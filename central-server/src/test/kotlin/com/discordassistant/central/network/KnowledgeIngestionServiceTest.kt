package com.discordassistant.central.network

import com.discordassistant.central.dashboard.AddKnowledgeSourceRequest
import com.discordassistant.central.dashboard.ApproveKnowledgeSourceRequest
import com.discordassistant.central.dashboard.CreateKnowledgeSpaceRequest
import com.discordassistant.central.dashboard.DeleteKnowledgeSourceRequest
import com.discordassistant.central.dashboard.KnowledgeEvalRequest
import com.discordassistant.central.dashboard.KnowledgeIngestionController
import com.discordassistant.central.dashboard.MarkKnowledgeSourceIndexedRequest
import com.discordassistant.central.dashboard.RejectKnowledgeSourceRequest
import com.discordassistant.central.persistence.CustomizationAuditLogRepository
import com.discordassistant.central.persistence.KnowledgeSourceRepository
import com.discordassistant.central.persistence.KnowledgeSpaceRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
class KnowledgeIngestionServiceTest
    @Autowired
    constructor(
        private val spaces: KnowledgeSpaceRepository,
        private val sources: KnowledgeSourceRepository,
        private val audits: CustomizationAuditLogRepository,
    ) {
        private val service =
            KnowledgeIngestionService(
                spaces = spaces,
                sources = sources,
                clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC),
                audits = audits,
            )
        private val searchService = KnowledgeSearchService(sources, spaces)
        private val controller = KnowledgeIngestionController(service, searchService)

        @Test
        fun `indexing plan emits Dailyting style rag rebuild command and excludes unsafe sources`() {
            val space =
                service.createSpace(
                    guildId = 100,
                    channelId = 200,
                    channelAiId = null,
                    displayName = "개발 지식",
                    createdBy = 77,
                    embeddingModel = null,
                    indexName = null,
                )
            val safe =
                service.addSource(
                    guildId = 100,
                    spaceId = space.id,
                    sourceType = "link",
                    title = "Kotlin Spring 운영 가이드",
                    sourceUri = "https://example.com/kotlin.md",
                    contentPreview = "운영",
                    addedBy = 77,
                )
            service.addSource(
                guildId = 100,
                spaceId = space.id,
                sourceType = "text",
                title = "secret env",
                sourceUri = null,
                contentPreview = "token=secret",
                addedBy = 77,
            )

            val plan = controller.indexingPlan(100, space.id)

            assertEquals("discord_ai__guild_100__channel_200__space_${space.id}", plan.collectionName)
            assertEquals("text-embedding-3-large", plan.embeddingModel)
            assertTrue(plan.runtime.contains("qdrant"))
            assertTrue(plan.command.contains("scripts/rag.sh rebuild"))
            assertTrue(plan.command.contains("--guild 100"))
            assertEquals(listOf(safe.id), plan.indexableSources.map { it.id })
            assertEquals(1, plan.blockedSources.size)
            assertEquals(false, plan.ready)
            assertTrue(plan.warnings.contains("sensitive_source_blocked"))

            val forced = controller.indexingPlan(100, space.id, force = true)
            assertEquals(true, forced.force)
            assertTrue(forced.command.endsWith("--force"))
        }

        @Test
        fun `indexing plan uses explicit collection and custom embedding model`() {
            val space = service.createSpace(100, 201, null, "검색 지식", 77, "custom-embedding", "custom_collection")
            val safe =
                service.addSource(
                    guildId = 100,
                    spaceId = space.id,
                    sourceType = "faq",
                    title = "FAQ",
                    sourceUri = null,
                    contentPreview = "자주 묻는 질문",
                    addedBy = 77,
                )

            val plan = controller.indexingPlan(100, space.id)

            assertEquals("custom_collection", plan.collectionName)
            assertEquals("custom-embedding", plan.embeddingModel)
            assertEquals(true, plan.ready)
            assertEquals(listOf(safe.id), plan.indexableSources.map { it.id })
            assertTrue(plan.warnings.isEmpty())
        }

        @Test
        fun `knowledge source lifecycle stores only metadata and indexing state`() {
            val spaceResponse =
                controller.createSpace(
                    100,
                    CreateKnowledgeSpaceRequest(
                        channelId = 200,
                        displayName = "개발 지식",
                        actorUserId = 77,
                        embeddingModel = "text-embedding-3-large",
                        indexName = "guild-100-channel-200",
                    ),
                )
            val spaceId = spaceResponse["id"] as Long

            val sourceResponse =
                controller.addSource(
                    100,
                    spaceId,
                    AddKnowledgeSourceRequest(
                        sourceType = "link",
                        title = "README",
                        sourceUri = "https://example.com/readme.md",
                        contentPreview = "project overview",
                        actorUserId = 77,
                    ),
                )
            val sourceId = sourceResponse["id"] as Long
            assertEquals("normal", sourceResponse["riskLevel"])
            assertNotNull(sources.findByKnowledgeSpaceId(spaceId).single().contentHash)

            val indexed =
                controller.markIndexed(
                    100,
                    spaceId,
                    sourceId,
                    MarkKnowledgeSourceIndexedRequest(chunkCount = 12),
                )
            assertEquals("indexed", indexed["status"])
            assertEquals(12, spaces.findByGuildIdAndId(100, spaceId)!!.chunkCount)
            assertEquals("ready", spaces.findByGuildIdAndId(100, spaceId)!!.status)
        }

        @Test
        fun `knowledge changes write sanitized customization audit events`() {
            val space =
                service.createSpace(
                    guildId = 100,
                    channelId = 205,
                    channelAiId = null,
                    displayName = "감사 지식",
                    createdBy = 77,
                    embeddingModel = null,
                    indexName = null,
                )
            val source =
                service.addSource(
                    guildId = 100,
                    spaceId = space.id,
                    sourceType = "link",
                    title = "운영 가이드",
                    sourceUri = "https://example.com/ops.md",
                    contentPreview = "운영",
                    addedBy = 77,
                )
            service.markSourceIndexed(100, space.id, source.id, chunkCount = 3)
            service.removeSource(100, space.id, source.id, "DISCORD_BOT_TOKEN=secret")

            val entries = audits.findTop10ByGuildIdAndChannelIdOrderByCreatedAtDesc(100, 205)
            assertTrue(entries.any { it.action == "knowledge_space_create" })
            assertTrue(entries.any { it.action == "knowledge_source_add" })
            assertTrue(entries.any { it.action == "knowledge_source_indexed" })
            assertTrue(entries.any { it.action == "knowledge_source_delete" })
            assertTrue(entries.none { it.summary?.contains("secret") == true })
            assertTrue(entries.none { it.summary?.contains("DISCORD_BOT_TOKEN") == true })
        }

        @Test
        fun `knowledge space status summarizes readiness and source risks`() {
            val space = service.createSpace(100, 200, null, "운영 지식", 77, null, null)
            val safe =
                service.addSource(
                    guildId = 100,
                    spaceId = space.id,
                    sourceType = "link",
                    title = "README",
                    sourceUri = "https://example.com/readme.md",
                    contentPreview = "운영 규칙",
                    addedBy = 77,
                )
            service.addSource(
                guildId = 100,
                spaceId = space.id,
                sourceType = "text",
                title = "env",
                sourceUri = null,
                contentPreview = "token=secret",
                addedBy = 77,
            )
            service.markSourceIndexed(100, space.id, safe.id, chunkCount = 4)

            val status = controller.spaceStatus(100, space.id)

            assertEquals("partial", status.readiness)
            assertEquals(2, status.sourceCount)
            assertEquals(1, status.indexedSourceCount)
            assertEquals(1, status.blockedSourceCount)
            assertEquals(4, status.chunkCount)
            assertEquals(200, status.channelId)
            assertEquals(1, status.riskLevels["sensitive"])
        }

        @Test
        fun `sensitive-looking source is marked for review or rejection`() {
            val space = service.createSpace(100, 200, null, "운영 지식", 77, null, null)
            val source =
                service.addSource(
                    guildId = 100,
                    spaceId = space.id,
                    sourceType = "text",
                    title = "env",
                    sourceUri = null,
                    contentPreview = "DISCORD_BOT_TOKEN=secret",
                    addedBy = 77,
                )

            assertEquals("sensitive", source.riskLevel)
            assertEquals("blocked_sensitive", source.status)
            assertThrows(IllegalArgumentException::class.java) {
                service.markSourceIndexed(100, space.id, source.id, chunkCount = 1)
            }
            val rejected =
                controller.reject(
                    100,
                    space.id,
                    source.id,
                    RejectKnowledgeSourceRequest("secret detected"),
                )
            assertTrue(rejected["status"].toString().startsWith("rejected"))
        }

        @Test
        fun `knowledge search is guild scoped and excludes deleted or unsafe sources`() {
            val guildOneSpace = service.createSpace(100, 200, null, "개발 지식", 77, null, null)
            val guildTwoSpace = service.createSpace(999, 200, null, "다른 서버 지식", 88, null, null)
            val guildOneSource =
                service.addSource(
                    guildId = 100,
                    spaceId = guildOneSpace.id,
                    sourceType = "link",
                    title = "Kotlin Spring 운영 가이드",
                    sourceUri = "https://example.com/kotlin-spring.md",
                    contentPreview = "운영 가이드",
                    addedBy = 77,
                )
            val guildTwoSource =
                service.addSource(
                    guildId = 999,
                    spaceId = guildTwoSpace.id,
                    sourceType = "link",
                    title = "Kotlin Spring 비밀 문서",
                    sourceUri = "https://example.com/other.md",
                    contentPreview = "다른 서버",
                    addedBy = 88,
                )
            service.markSourceIndexed(100, guildOneSpace.id, guildOneSource.id, chunkCount = 3)
            service.markSourceIndexed(999, guildTwoSpace.id, guildTwoSource.id, chunkCount = 3)

            val found = controller.search(100, query = "Kotlin Spring", limit = 10)

            assertEquals(1, found.results.size)
            assertEquals(guildOneSource.id, found.results.single().sourceId)

            controller.removeSource(100, guildOneSpace.id, guildOneSource.id, DeleteKnowledgeSourceRequest("outdated"))
            val afterDelete = controller.search(100, query = "Kotlin Spring", limit = 10)

            assertTrue(afterDelete.results.isEmpty())
            assertEquals("no_indexed_knowledge_match", afterDelete.fallbackReason)
        }

        @Test
        fun `knowledge search can be narrowed to channel or knowledge space`() {
            val channelOne = service.createSpace(100, 201, null, "개발 지식", 77, null, null)
            val channelTwo = service.createSpace(100, 202, null, "번역 지식", 77, null, null)
            val sourceOne =
                service.addSource(
                    guildId = 100,
                    spaceId = channelOne.id,
                    sourceType = "link",
                    title = "Kotlin Spring 운영 가이드",
                    sourceUri = "https://example.com/kotlin.md",
                    contentPreview = "운영",
                    addedBy = 77,
                )
            val sourceTwo =
                service.addSource(
                    guildId = 100,
                    spaceId = channelTwo.id,
                    sourceType = "link",
                    title = "Kotlin 번역 가이드",
                    sourceUri = "https://example.com/translation.md",
                    contentPreview = "번역",
                    addedBy = 77,
                )
            service.markSourceIndexed(100, channelOne.id, sourceOne.id, chunkCount = 1)
            service.markSourceIndexed(100, channelTwo.id, sourceTwo.id, chunkCount = 1)

            val channelOneResults = controller.search(100, query = "Kotlin", limit = 10, channelId = 201, knowledgeSpaceId = null)
            assertEquals(listOf(sourceOne.id), channelOneResults.results.map { it.sourceId })

            val spaceTwoResults = controller.search(100, query = "Kotlin", limit = 10, channelId = null, knowledgeSpaceId = channelTwo.id)
            assertEquals(listOf(sourceTwo.id), spaceTwoResults.results.map { it.sourceId })
        }

        @Test
        fun `prompt context requires explicit scope and trims searchable snippets`() {
            val channelOne = service.createSpace(100, 201, null, "개발 지식", 77, null, null)
            val channelTwo = service.createSpace(100, 202, null, "번역 지식", 77, null, null)
            val sourceOne =
                service.addSource(
                    guildId = 100,
                    spaceId = channelOne.id,
                    sourceType = "link",
                    title = "Kotlin Spring 운영 가이드",
                    sourceUri = "https://example.com/kotlin-spring-guide.md",
                    contentPreview = "운영",
                    addedBy = 77,
                )
            val sourceTwo =
                service.addSource(
                    guildId = 100,
                    spaceId = channelTwo.id,
                    sourceType = "link",
                    title = "Kotlin 번역 가이드",
                    sourceUri = "https://example.com/translation.md",
                    contentPreview = "번역",
                    addedBy = 77,
                )
            service.markSourceIndexed(100, channelOne.id, sourceOne.id, chunkCount = 1)
            service.markSourceIndexed(100, channelTwo.id, sourceTwo.id, chunkCount = 1)

            assertThrows(IllegalArgumentException::class.java) {
                controller.promptContext(100, query = "Kotlin", maxChars = 500, channelId = null, knowledgeSpaceId = null)
            }

            val context =
                controller.promptContext(
                    guildId = 100,
                    query = "Kotlin",
                    maxChars = 500,
                    channelId = 201,
                    knowledgeSpaceId = null,
                )

            assertEquals(null, context.fallbackReason)
            assertEquals(listOf(sourceOne.id), context.entries.map { it.sourceId })
            assertTrue(context.contextText.contains("source:${sourceOne.id}"))
            assertTrue(context.contextText.contains("Kotlin Spring"))
            assertTrue(context.contextText.contains("번역").not())
            assertTrue(context.usedChars <= context.maxChars)
        }

        @Test
        fun `sensitive-looking query disables RAG search and prompt context`() {
            val space = service.createSpace(100, 201, null, "보안 지식", 77, null, null)
            val source =
                service.addSource(
                    guildId = 100,
                    spaceId = space.id,
                    sourceType = "link",
                    title = "Kotlin API 운영 가이드",
                    sourceUri = "https://example.com/kotlin-api-guide.md",
                    contentPreview = "운영",
                    addedBy = 77,
                )
            service.markSourceIndexed(100, space.id, source.id, chunkCount = 1)

            val query = "Kotlin api_key=sk-test-secret-1234567890 처리 방법"
            val search = controller.search(100, query = query, limit = 5, channelId = 201, knowledgeSpaceId = null)
            assertEquals("blocked_sensitive_query", search.fallbackReason)
            assertTrue(search.results.isEmpty())

            val context =
                controller.promptContext(
                    guildId = 100,
                    query = query,
                    maxChars = 500,
                    channelId = 201,
                    knowledgeSpaceId = null,
                )
            assertEquals("blocked_sensitive_query", context.fallbackReason)
            assertTrue(context.entries.isEmpty())
            assertEquals("", context.contextText)

            val plan =
                controller.contextPlan(
                    guildId = 100,
                    query = query,
                    responseMode = "deep",
                    maxChars = null,
                    channelId = 201,
                    knowledgeSpaceId = null,
                )
            assertEquals(false, plan.enabled)
            assertEquals("blocked_sensitive_query", plan.fallbackReason)
            assertTrue(plan.warnings.contains("blocked_sensitive_query"))
            assertEquals(0, plan.maxChars)
        }

        @Test
        fun `context plan applies response mode budgets and graceful fallback`() {
            val channelOne = service.createSpace(100, 201, null, "개발 지식", 77, null, null)
            val sourceOne =
                service.addSource(
                    guildId = 100,
                    spaceId = channelOne.id,
                    sourceType = "link",
                    title = "Kotlin Spring 운영 가이드",
                    sourceUri = "https://example.com/kotlin-spring-guide.md",
                    contentPreview = "운영",
                    addedBy = 77,
                )
            service.markSourceIndexed(100, channelOne.id, sourceOne.id, chunkCount = 1)

            val missingScope =
                controller.contextPlan(
                    guildId = 100,
                    query = "Kotlin Spring",
                    responseMode = "deep",
                    maxChars = null,
                    channelId = null,
                    knowledgeSpaceId = null,
                )
            assertEquals(false, missingScope.enabled)
            assertEquals("rag_scope_required", missingScope.fallbackReason)
            assertTrue(missingScope.entries.isEmpty())

            val off =
                controller.contextPlan(
                    guildId = 100,
                    query = "Kotlin Spring",
                    responseMode = "off",
                    maxChars = null,
                    channelId = 201,
                    knowledgeSpaceId = null,
                )
            assertEquals(false, off.enabled)
            assertEquals("rag_disabled_by_response_mode", off.fallbackReason)
            assertEquals(0, off.maxChars)

            val fast =
                controller.contextPlan(
                    guildId = 100,
                    query = "Kotlin Spring",
                    responseMode = "fast",
                    maxChars = 8_000,
                    channelId = 201,
                    knowledgeSpaceId = null,
                )
            assertEquals(true, fast.enabled)
            assertEquals("fast", fast.responseMode)
            assertEquals(800, fast.maxChars)
            assertTrue(fast.warnings.contains("requested_budget_capped_by_response_mode"))
            assertEquals(listOf(sourceOne.id), fast.entries.map { it.sourceId })

            val deep =
                controller.contextPlan(
                    guildId = 100,
                    query = "Kotlin Spring",
                    responseMode = "deep",
                    maxChars = null,
                    channelId = 201,
                    knowledgeSpaceId = null,
                )
            assertEquals(true, deep.enabled)
            assertEquals("deep", deep.responseMode)
            assertEquals(2_400, deep.maxChars)
            assertTrue(deep.maxChars > fast.maxChars)
        }

        @Test
        fun `retrieval evaluation reports hit mrr and recall for golden set`() {
            val channelOne = service.createSpace(100, 201, null, "개발 지식", 77, null, null)
            val channelTwo = service.createSpace(100, 202, null, "번역 지식", 77, null, null)
            val sourceOne =
                service.addSource(
                    guildId = 100,
                    spaceId = channelOne.id,
                    sourceType = "link",
                    title = "Kotlin Spring 운영 가이드",
                    sourceUri = "https://example.com/kotlin-spring-guide.md",
                    contentPreview = "운영",
                    addedBy = 77,
                )
            val sourceTwo =
                service.addSource(
                    guildId = 100,
                    spaceId = channelTwo.id,
                    sourceType = "link",
                    title = "영어 번역 스타일 가이드",
                    sourceUri = "https://example.com/translation-style.md",
                    contentPreview = "번역",
                    addedBy = 77,
                )
            service.markSourceIndexed(100, channelOne.id, sourceOne.id, chunkCount = 1)
            service.markSourceIndexed(100, channelTwo.id, sourceTwo.id, chunkCount = 1)

            val evaluation =
                controller.evaluateRetrieval(
                    100,
                    KnowledgeEvalRequest(
                        k = 5,
                        cases =
                            listOf(
                                KnowledgeGoldenCase("kotlin", "Kotlin Spring", listOf(sourceOne.id), channelId = 201),
                                KnowledgeGoldenCase("translation", "영어 번역", listOf(sourceTwo.id), channelId = 202),
                            ),
                    ),
                )

            assertEquals(true, evaluation.passed)
            assertEquals(2, evaluation.caseCount)
            assertEquals(1.0, evaluation.hitAtK)
            assertEquals(1.0, evaluation.mrr)
            assertEquals(1.0, evaluation.recallAtK)
            assertTrue(evaluation.cases.all { it.firstHitRank == 1 })
        }

        @Test
        fun `retrieval evaluation requires scoped golden cases`() {
            val space = service.createSpace(100, 201, null, "개발 지식", 77, null, null)
            val source =
                service.addSource(
                    guildId = 100,
                    spaceId = space.id,
                    sourceType = "link",
                    title = "Kotlin Spring 운영 가이드",
                    sourceUri = "https://example.com/kotlin.md",
                    contentPreview = "운영",
                    addedBy = 77,
                )
            service.markSourceIndexed(100, space.id, source.id, chunkCount = 1)

            assertThrows(IllegalArgumentException::class.java) {
                controller.evaluateRetrieval(
                    100,
                    KnowledgeEvalRequest(
                        cases = listOf(KnowledgeGoldenCase("unscoped", "Kotlin", listOf(source.id))),
                    ),
                )
            }
        }

        @Test
        fun `review-risk source can be listed approved indexed or rejected with redacted reason`() {
            val space = service.createSpace(100, 200, null, "검토 지식", 77, null, null)
            val nonHttps =
                service.addSource(
                    guildId = 100,
                    spaceId = space.id,
                    sourceType = "link",
                    title = "Legacy docs",
                    sourceUri = "http://example.com/legacy.md",
                    contentPreview = "legacy guide",
                    addedBy = 77,
                )
            val badType =
                service.addSource(
                    guildId = 100,
                    spaceId = space.id,
                    sourceType = "binary",
                    title = "Binary dump",
                    sourceUri = null,
                    contentPreview = "manual review",
                    addedBy = 77,
                )

            val listed = controller.listSources(100, space.id)
            assertEquals(setOf(nonHttps.id, badType.id), listed.map { it.id }.toSet())
            assertEquals("review", listed.first { it.id == nonHttps.id }.riskLevel)
            assertEquals("blocked_non_https", listed.first { it.id == nonHttps.id }.status)

            val approved =
                controller.approveSource(
                    100,
                    space.id,
                    nonHttps.id,
                    ApproveKnowledgeSourceRequest("trusted internal migration note"),
                )
            assertEquals("pending", approved["status"])
            controller.markIndexed(100, space.id, nonHttps.id, MarkKnowledgeSourceIndexedRequest(chunkCount = 2))
            assertEquals("indexed", sources.findByKnowledgeSpaceIdAndId(space.id, nonHttps.id)?.status)

            val rejected = controller.reject(100, space.id, badType.id, RejectKnowledgeSourceRequest("token=secret should hide"))
            assertEquals("rejected:[redacted] should hide", rejected["status"])

            val afterReview = controller.spaceStatus(100, space.id)
            assertEquals(1, afterReview.indexedSourceCount)
            assertEquals(1, afterReview.rejectedSourceCount)
        }

        @Test
        fun `link source blocks ssrf and non https before indexing`() {
            val space = service.createSpace(100, 200, null, "보안 지식", 77, null, null)
            val source =
                service.addSource(
                    guildId = 100,
                    spaceId = space.id,
                    sourceType = "link",
                    title = "metadata",
                    sourceUri = "https://169.254.169.254/latest/meta-data",
                    contentPreview = "metadata",
                    addedBy = 77,
                )

            assertEquals("ssrf", source.riskLevel)
            assertEquals("blocked_ssrf", source.status)
            assertThrows(IllegalArgumentException::class.java) {
                service.markSourceIndexed(100, space.id, source.id, chunkCount = 1)
            }
        }

        @Test
        fun `link source blocks private ipv4 and ipv6 address literals`() {
            val space = service.createSpace(100, 200, null, "주소 보안 지식", 77, null, null)
            val blockedUris =
                listOf(
                    "https://10.0.0.2/internal",
                    "https://172.16.0.1/internal",
                    "https://172.31.255.254/internal",
                    "https://192.168.0.10/internal",
                    "https://[::1]/internal",
                    "https://[fd00::1]/internal",
                    "https://[fe80::1]/internal",
                )

            val created =
                blockedUris.mapIndexed { index, uri ->
                    service.addSource(
                        guildId = 100,
                        spaceId = space.id,
                        sourceType = "link",
                        title = "private-$index",
                        sourceUri = uri,
                        contentPreview = "internal",
                        addedBy = 77,
                    )
                }

            assertEquals(blockedUris.size, created.size)
            assertTrue(created.all { it.riskLevel == "ssrf" })
            assertTrue(created.all { it.status == "blocked_ssrf" })
        }
    }
