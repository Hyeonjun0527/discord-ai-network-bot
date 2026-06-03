package com.discordassistant.central.network

import com.discordassistant.central.persistence.AiAdminRoleRepository
import com.discordassistant.central.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.persistence.AiChangeProposalRepository
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.CustomizationAuditLogRepository
import com.discordassistant.central.persistence.EmbeddingIndexJobRepository
import com.discordassistant.central.persistence.GuildOnboardingConsentRepository
import com.discordassistant.central.persistence.GuildOnboardingRunRepository
import com.discordassistant.central.persistence.KnowledgeChunkRepository
import com.discordassistant.central.persistence.KnowledgeDocumentRepository
import com.discordassistant.central.persistence.KnowledgeSourceRepository
import com.discordassistant.central.persistence.KnowledgeSpaceRepository
import com.discordassistant.central.persistence.RetrievalPolicyRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
class GuildOnboardingServiceTest
    @Autowired
    constructor(
        private val channelAis: ChannelAiRepository,
        private val versions: AiBehaviorVersionRepository,
        private val proposals: AiChangeProposalRepository,
        private val audits: CustomizationAuditLogRepository,
        private val aiAdminRoles: AiAdminRoleRepository,
        private val consents: GuildOnboardingConsentRepository,
        private val runs: GuildOnboardingRunRepository,
        private val spaces: KnowledgeSpaceRepository,
        private val sources: KnowledgeSourceRepository,
        private val documents: KnowledgeDocumentRepository,
        private val chunks: KnowledgeChunkRepository,
        private val embeddingJobs: EmbeddingIndexJobRepository,
        private val retrievalPolicies: RetrievalPolicyRepository,
    ) {
        private val clock = Clock.fixed(Instant.parse("2026-06-04T00:00:00Z"), ZoneOffset.UTC)

        private val customization =
            ChannelAiCustomizationService(
                channelAis = channelAis,
                versions = versions,
                proposals = proposals,
                audits = audits,
                aiAdminRoles = aiAdminRoles,
                clock = clock,
            )

        private val knowledgeIngestion =
            KnowledgeIngestionService(
                spaces = spaces,
                sources = sources,
                clock = clock,
                audits = audits,
            )

        private val knowledgeIndexing =
            KnowledgeIndexingService(
                spaces = spaces,
                sources = sources,
                documents = documents,
                chunks = chunks,
                jobs = embeddingJobs,
                retrievalPolicies = retrievalPolicies,
                clock = clock,
            )

        private val backfillIndexer = OnboardingBackfillIndexer(knowledgeIngestion, knowledgeIndexing)

        /** 색인 단계에서 항상 예외를 던지는 fake — 색인 실패가 온보딩 본체를 막지 않음을 검증(S3). */
        private val throwingIndexer =
            object : OnboardingBackfillIndexer(knowledgeIngestion, knowledgeIndexing) {
                override fun indexBackfill(
                    guildId: Long,
                    channelId: Long,
                    channelAiId: Long,
                    actorUserId: Long?,
                    indexText: String,
                ): BackfillIndexResult? = throw IllegalStateException("simulated indexing failure")
            }

        private val service =
            GuildOnboardingService(
                channelAiCustomization = customization,
                consents = consents,
                runs = runs,
                backfillIndexer = backfillIndexer,
                clock = clock,
            )

        private val serviceWithFailingIndexer =
            GuildOnboardingService(
                channelAiCustomization = customization,
                consents = consents,
                runs = runs,
                backfillIndexer = throwingIndexer,
                clock = clock,
            )

        private fun disabledService() =
            GuildOnboardingService(
                channelAiCustomization =
                    ChannelAiCustomizationService(
                        channelAis = channelAis,
                        versions = versions,
                        proposals = proposals,
                        audits = audits,
                        aiAdminRoles = aiAdminRoles,
                        clock = clock,
                        featureGate = AiNetworkFeatureGate(channelAiEnabled = false),
                    ),
                consents = consents,
                runs = runs,
                backfillIndexer = backfillIndexer,
                clock = clock,
                featureGate = AiNetworkFeatureGate(channelAiEnabled = false),
            )

        // REQ-ONBOARD-001
        @Test
        fun `start records consent and creates pending heuristic proposal`() {
            val result =
                service.startOnboarding(
                    guildId = 100,
                    channelId = 300,
                    actorUserId = 77,
                    actorIsGuildAdmin = true,
                    channelName = "dev-help",
                )

            assertEquals("pending", result.status)
            // 채널명 dev-help → development job preset 휴리스틱
            assertEquals("코드냥", result.name)
            assertTrue(result.job.contains("개발"))

            // consent 기록(메시지 백필 미동의)
            val consent = consents.findByGuildIdOrderByCreatedAtDesc(100).single()
            assertEquals(77L, consent.actorUserId)
            assertFalse(consent.messageBackfillOptedIn)
            assertNull(consent.channelWhitelist)

            // PENDING 제안 생성 + 아직 active 전환 안 됨
            assertEquals(1, customization.pendingProposals(100).size)
            assertNull(channelAis.findByGuildIdAndChannelId(100, 300)!!.activeBehaviorVersionId)

            // run 추적(proposed)
            val run = runs.findByProposalId(result.proposalId)
            assertNotNull(run)
            assertEquals("proposed", run!!.status)
            assertEquals("heuristic", run.analysisSource)
            assertEquals(consent.id, run.consentId)
            assertEquals(result.channelAiId, run.channelAiId)
        }

        // REQ-ONBOARD-002
        @Test
        fun `approve activates behavior version and marks run approved`() {
            val started =
                service.startOnboarding(
                    guildId = 100,
                    channelId = 301,
                    actorUserId = 77,
                    actorIsGuildAdmin = true,
                    channelName = "번역방",
                )
            assertTrue(started.job.contains("번역"))

            val review =
                service.approveOnboarding(
                    proposalId = started.proposalId,
                    reviewerUserId = 88,
                    reviewerIsGuildAdmin = true,
                )

            assertEquals("approved", review.status)
            assertEquals(
                started.behaviorVersionId,
                channelAis.findByGuildIdAndChannelId(100, 301)!!.activeBehaviorVersionId,
            )
            assertEquals("approved", runs.findByProposalId(started.proposalId)!!.status)
        }

        // REQ-ONBOARD-002 (reject path)
        @Test
        fun `reject keeps channel inactive and marks run rejected`() {
            val started =
                service.startOnboarding(
                    guildId = 100,
                    channelId = 302,
                    actorUserId = 77,
                    actorIsGuildAdmin = true,
                    channelName = "잡담",
                )
            // 매칭되는 힌트 없으면 custom
            assertEquals("채널냥", started.name)

            val review =
                service.rejectOnboarding(
                    proposalId = started.proposalId,
                    reviewerUserId = 88,
                    reviewerIsGuildAdmin = true,
                )

            assertEquals("rejected", review.status)
            assertNull(channelAis.findByGuildIdAndChannelId(100, 302)!!.activeBehaviorVersionId)
            assertEquals("rejected", runs.findByProposalId(started.proposalId)!!.status)
        }

        // REQ-ONBOARD-003
        @Test
        fun `non admin without ai admin role cannot start onboarding`() {
            customization.replaceAiAdminRoles(
                guildId = 100,
                roleIds = setOf(9001),
                actorUserId = 1,
                actorIsGuildAdmin = true,
            )

            assertThrows(IllegalStateException::class.java) {
                service.startOnboarding(
                    guildId = 100,
                    channelId = 303,
                    actorUserId = 5,
                    actorRoleIds = setOf(1000),
                    actorIsGuildAdmin = true,
                )
            }
            // consent/run/proposal 모두 만들어지지 않아야 한다(권한 게이트가 먼저).
            assertTrue(consents.findByGuildIdOrderByCreatedAtDesc(100).isEmpty())
            assertTrue(runs.findByGuildIdOrderByCreatedAtDesc(100).isEmpty())
            assertNull(channelAis.findByGuildIdAndChannelId(100, 303))
        }

        // REQ-ONBOARD-003 (feature gate off)
        @Test
        fun `feature gate off blocks onboarding start`() {
            assertThrows(IllegalStateException::class.java) {
                disabledService().startOnboarding(
                    guildId = 100,
                    channelId = 304,
                    actorUserId = 77,
                    actorIsGuildAdmin = true,
                )
            }
            assertTrue(consents.findByGuildIdOrderByCreatedAtDesc(100).isEmpty())
        }

        // REQ-ONBOARD-005 — 백필 텍스트가 RAG knowledge_space/source 로 색인되고 run 에 카운트가 기록된다.
        @Test
        fun `backfill text creates indexed knowledge space and records counts`() {
            val result =
                service.startOnboarding(
                    guildId = 100,
                    channelId = 305,
                    actorUserId = 77,
                    actorIsGuildAdmin = true,
                    channelName = "dev-help",
                    channelWhitelist = setOf(305L, 306L),
                    historyLimit = 50,
                    backfill =
                        GuildOnboardingService.BackfillInput(
                            indexText = "[익명1] 빌드는 gradlew build 로 합니다.\n\n[익명2] PR 은 main 에 직접 금지.",
                            backfilledMessageCount = 2,
                            scrubbedCount = 1,
                        ),
                )

            // 지식공간 생성 + run 에 색인 메타 기록
            assertNotNull(result.knowledgeSpaceId)
            assertTrue(result.knowledgeIndexed)
            val run = runs.findByProposalId(result.proposalId)!!
            assertEquals(result.knowledgeSpaceId, run.knowledgeSpaceId)
            assertEquals(2, run.backfilledMessageCount)
            assertEquals(1, run.scrubbedCount)

            // source 가 색인됨(INDEXED) — RAG 지식으로 사용 가능
            val space = spaces.findByGuildIdAndId(100, result.knowledgeSpaceId!!)!!
            val indexedSources = sources.findByKnowledgeSpaceId(space.id)
            assertEquals(1, indexedSources.size)
            assertEquals("indexed", indexedSources.single().status.wire)

            // 화이트리스트 동의 직렬화
            val consent = consents.findByGuildIdOrderByCreatedAtDesc(100).single()
            assertTrue(consent.messageBackfillOptedIn)
            assertEquals("305,306", consent.channelWhitelist)
        }

        // REQ-ONBOARD-005 (회귀) — 백필 없으면 Phase 1 동작 유지(지식공간 생성 안 됨·미동의).
        @Test
        fun `no backfill keeps phase1 behavior`() {
            val result =
                service.startOnboarding(
                    guildId = 100,
                    channelId = 307,
                    actorUserId = 77,
                    actorIsGuildAdmin = true,
                    channelName = "dev-help",
                )

            assertNull(result.knowledgeSpaceId)
            assertFalse(result.knowledgeIndexed)
            assertEquals(0, result.backfilledMessageCount)
            val run = runs.findByProposalId(result.proposalId)!!
            assertNull(run.knowledgeSpaceId)
            assertEquals(0, run.backfilledMessageCount)
            val consent = consents.findByGuildIdOrderByCreatedAtDesc(100).single()
            assertFalse(consent.messageBackfillOptedIn)
            assertNull(consent.channelWhitelist)
        }

        // REQ-ONBOARD-006 — 색인 실패가 consent/proposal/run 생성을 롤백하지 않는다(S3 격리).
        @Test
        fun `backfill indexing failure does not roll back onboarding core`() {
            val result =
                serviceWithFailingIndexer.startOnboarding(
                    guildId = 100,
                    channelId = 308,
                    actorUserId = 77,
                    actorIsGuildAdmin = true,
                    channelName = "dev-help",
                    channelWhitelist = setOf(308L),
                    historyLimit = 30,
                    backfill =
                        GuildOnboardingService.BackfillInput(
                            indexText = "[익명1] 색인은 실패하지만 제안은 살아남아야 한다.",
                            backfilledMessageCount = 1,
                            scrubbedCount = 0,
                        ),
                )

            // 색인은 실패해 지식공간 없음·미색인이지만, 온보딩 본체(제안/consent/run)는 보존된다.
            assertNull(result.knowledgeSpaceId)
            assertFalse(result.knowledgeIndexed)
            assertEquals("pending", result.status)
            assertEquals(1, customization.pendingProposals(100).size)
            val run = runs.findByProposalId(result.proposalId)!!
            assertEquals("proposed", run.status)
            assertNull(run.knowledgeSpaceId)
            // 색인 실패해도 consent 는 기록되고 카운트는 입력값 그대로 남는다.
            val consent = consents.findByGuildIdOrderByCreatedAtDesc(100).single()
            assertTrue(consent.messageBackfillOptedIn)
            assertEquals(1, run.backfilledMessageCount)
        }
    }
