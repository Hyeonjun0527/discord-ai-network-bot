package com.discordassistant.central.usage

import com.discordassistant.central.domain.RequestState
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
@Import(UsageService::class)
class UsageServiceTest
    @Autowired
    constructor(
        val svc: UsageService,
        val requests: AiRequestRepository,
    ) {
        @Test
        fun `AiRequest 영속화`() {
            val input = AiRequestInput(guildId = 100, channelId = 200, userId = 5, prompt = "x", roleIds = setOf(1))
            svc.recordRequest(input, RequestState.COMPLETED, providerId = 7, failReason = null)
            val all = requests.findAll().toList()
            assertEquals(1, all.size)
            assertEquals("COMPLETED", all[0].state)
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
    }
