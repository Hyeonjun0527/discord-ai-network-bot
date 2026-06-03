package com.discordassistant.central.usage

import com.discordassistant.central.domain.RequestState
import com.discordassistant.central.network.AiLevelService
import com.discordassistant.central.persistence.AiNetworkProfileRepository
import com.discordassistant.central.persistence.AiRequestRepository
import com.discordassistant.central.routing.AiRequestInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UsageService::class, AiLevelService::class)
class UsageServiceTest
    @Autowired
    constructor(
        val svc: UsageService,
        val requests: AiRequestRepository,
        val networkProfiles: AiNetworkProfileRepository,
    ) {
        @Test
        fun `AiRequest 영속화`() {
            val input = AiRequestInput(guildId = 100, channelId = 200, userId = 5, prompt = "x", roleIds = setOf(1))
            svc.recordRequest(input, RequestState.COMPLETED, providerId = 7, failReason = null)
            val all = requests.findAll().toList()
            assertEquals(1, all.size)
            assertEquals(RequestState.COMPLETED, all[0].state)
            assertEquals(7L, all[0].providerId)
        }

        @Test
        fun `ProviderHealth 실패 누적`() {
            svc.recordProviderFailure(9)
            svc.recordProviderFailure(9)
            assertEquals(2, svc.providerFailures(9))
            assertNotNull(svc.providerFailures(9))
        }

        @Test
        fun `기여 집계는 길드별 모든 기여자를 영구 집계한다`() {
            svc.recordSuccess(guildId = 100, userId = 5, providerId = 10, requestId = "r1")
            svc.recordSuccess(guildId = 100, userId = 6, providerId = 10, requestId = "r2")
            svc.recordSuccess(guildId = 100, userId = 7, providerId = 20, requestId = "r3")
            svc.recordSuccess(guildId = 200, userId = 8, providerId = 10, requestId = "r4")

            assertEquals(listOf(10L to 2L, 20L to 1L), svc.providerContributions(100))
            assertEquals(3, svc.totalContributions(100))
        }

        @Test
        fun `recordSuccess 는 길드 AI 경험치를 적립한다`() {
            svc.recordSuccess(guildId = 300, userId = 5, providerId = 10, requestId = "x1")
            val profile = networkProfiles.findByGuildId(300)
            assertNotNull(profile)
            assertEquals(10L, profile!!.totalXp) // XP_PER_ASK_SUCCESS = 10
        }
        // 주의: XP 실패 격리(REQUIRES_NEW rollback-only 비전염)는 @DataJpaTest(커밋 없는 단일
        // 테스트 트랜잭션 + 프록시 미경유)로는 재현 불가하다. 실제 커밋 경계/프록시 propagation
        // 검증은 AiLevelXpIsolationTest(@SpringBootTest)에서 한다.
    }
