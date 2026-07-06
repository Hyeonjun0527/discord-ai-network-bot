package com.discordassistant.central.usage

import com.discordassistant.central.ainetwork.adapter.outbound.persistence.UserAffinityRepository
import com.discordassistant.central.ainetwork.application.NiaAffinityService
import com.discordassistant.central.requestlog.adapter.outbound.persistence.AiRequestRepository
import com.discordassistant.central.requestlog.application.UsageService
import com.discordassistant.central.routing.domain.model.AiRequestInput
import com.discordassistant.central.shared.ModelBurden
import com.discordassistant.central.shared.RequestState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UsageService::class, NiaAffinityService::class)
class UsageServiceTest
    @Autowired
    constructor(
        val svc: UsageService,
        val requests: AiRequestRepository,
        val affinities: UserAffinityRepository,
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
        fun `AiRequest 는 실효 부담을 저장한다`() {
            val input = AiRequestInput(guildId = 100, channelId = 200, userId = 5, prompt = "x", roleIds = setOf(1))
            svc.recordRequest(input, RequestState.COMPLETED, providerId = 7, failReason = null, requestId = "burden-1", requiredBurden = ModelBurden.HEAVY)
            assertEquals("HEAVY", requests.findByRequestId("burden-1")?.requiredBurden)
        }

        @Test
        fun `같은 requestId 의 재기록은 ai_request 를 중복 insert 하지 않는다`() {
            val input = AiRequestInput(guildId = 100, channelId = 200, userId = 5, prompt = "x", roleIds = setOf(1))
            svc.recordRequest(input, RequestState.COMPLETED, providerId = 7, failReason = null, requestId = "dup-req", requiredBurden = ModelBurden.LIGHT)
            svc.recordRequest(input, RequestState.COMPLETED, providerId = 7, failReason = null, requestId = "dup-req", requiredBurden = ModelBurden.HEAVY)
            assertEquals(1, requests.findAll().count { it.requestId == "dup-req" })
        }

        @Test
        fun `같은 requestId 의 recordSuccess 재전송은 기여를 중복 적립하지 않는다`() {
            svc.recordSuccess(guildId = 100, userId = 5, providerId = 10, requestId = "once")
            svc.recordSuccess(guildId = 100, userId = 5, providerId = 10, requestId = "once")
            assertEquals(1, svc.providerContributionCount(10))
            assertEquals(1, svc.userDailyCount(100, 5))
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
        fun `recordSuccess 는 요청자 니아 호감도를 적립한다`() {
            val requesterId = 505_505L
            svc.recordSuccess(guildId = 300, userId = requesterId, providerId = 10, requestId = "x1")
            val affinity = affinities.findByUserId(requesterId)
            assertNotNull(affinity)
            assertEquals(1L, affinity!!.score)
        }
        // 주의: 호감도 실패 격리(REQUIRES_NEW rollback-only 비전염)는 @DataJpaTest 의 테스트
        // 트랜잭션만으로는 재현이 약하다. 실제 프록시 propagation 검증은 별도 SpringBootTest에서 한다.
    }
