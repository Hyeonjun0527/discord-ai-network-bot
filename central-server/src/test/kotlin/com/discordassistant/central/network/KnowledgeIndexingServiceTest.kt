package com.discordassistant.central.network

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
