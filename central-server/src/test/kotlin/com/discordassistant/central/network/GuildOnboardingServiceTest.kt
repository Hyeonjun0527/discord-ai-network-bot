package com.discordassistant.central.network

import com.discordassistant.central.persistence.AiAdminRoleRepository
import com.discordassistant.central.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.persistence.AiChangeProposalRepository
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.CustomizationAuditLogRepository
import com.discordassistant.central.persistence.GuildOnboardingConsentRepository
import com.discordassistant.central.persistence.GuildOnboardingRunRepository
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

        private val service =
            GuildOnboardingService(
                channelAiCustomization = customization,
                consents = consents,
                runs = runs,
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
    }
