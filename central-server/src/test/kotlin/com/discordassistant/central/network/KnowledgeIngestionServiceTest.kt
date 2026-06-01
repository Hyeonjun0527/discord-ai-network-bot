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
        private val searchService = KnowledgeSearchService(sources)
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
    }
