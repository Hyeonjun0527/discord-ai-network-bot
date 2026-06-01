package com.discordassistant.central.network

import com.discordassistant.central.dashboard.AddKnowledgeSourceRequest
import com.discordassistant.central.dashboard.CreateKnowledgeSpaceRequest
import com.discordassistant.central.dashboard.DeleteKnowledgeSourceRequest
import com.discordassistant.central.dashboard.KnowledgeIngestionController
import com.discordassistant.central.dashboard.MarkKnowledgeSourceIndexedRequest
import com.discordassistant.central.dashboard.RejectKnowledgeSourceRequest
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
    ) {
        private val service =
            KnowledgeIngestionService(
                spaces = spaces,
                sources = sources,
                clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC),
            )
        private val searchService = KnowledgeSearchService(sources, spaces)
        private val controller = KnowledgeIngestionController(service, searchService)

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
    }
