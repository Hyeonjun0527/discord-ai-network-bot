package com.discordassistant.central.dashboard

import com.discordassistant.central.persistence.AiRequestEntity
import com.discordassistant.central.persistence.AiRequestRepository
import com.discordassistant.central.policy.PolicyService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 대시보드 백엔드 API(차수 14 #195/#211) 통합 테스트. */
@SpringBootTest
@Transactional
class DashboardControllerTest
    @Autowired
    constructor(
        val dashboard: DashboardController,
        val policy: PolicyService,
        val requests: AiRequestRepository,
    ) {
        @Test
        fun `overview — 정책·요청 수 집계`() {
            policy.setGuildDefaults(700, defaultModel = "llama3", language = "en", adminId = 1)
            requests.save(AiRequestEntity(requestId = "r1", guildId = 700, state = "COMPLETED", createdAt = Instant.EPOCH))
            requests.save(AiRequestEntity(requestId = "r2", guildId = 700, state = "REJECTED", createdAt = Instant.EPOCH))

            val o = dashboard.overview(700)
            assertEquals(700L, o["guildId"])
            assertEquals("llama3", o["defaultModel"])
            assertEquals("en", o["language"])
            assertEquals(2L, o["totalRequests"])
            assertEquals(0, o["activeProviders"]) // 연결 세션 없음
        }

        @Test
        fun `requests — 공개 기본값은 프롬프트와 provider snowflake 를 노출하지 않는다`() {
            requests.save(AiRequestEntity(requestId = "r3", guildId = 701, state = "COMPLETED", providerId = 9, createdAt = Instant.EPOCH))
            val log = dashboard.requests(701)
            assertEquals(1, log.size)
            assertEquals("r3", log[0]["requestId"])
            assertTrue(log[0]["providerLabel"].toString().startsWith("Provider "))
            assertTrue(!log[0].containsKey("providerId"), "공개 대시보드는 provider snowflake 를 직접 노출하지 않는다")
            assertTrue(!log[0].containsKey("prompt"), "프롬프트 본문은 노출하지 않는다")
        }

        @Test
        fun `requests — 관리자 audience 에서만 providerId 를 볼 수 있다`() {
            requests.save(AiRequestEntity(requestId = "r4", guildId = 702, state = "COMPLETED", providerId = 99, createdAt = Instant.EPOCH))
            val log = dashboard.requests(702, audience = "admin")
            assertEquals(1, log.size)
            assertEquals("r4", log[0]["requestId"])
            assertEquals(99L, log[0]["providerId"])
            assertTrue(log[0]["providerLabel"].toString().startsWith("Provider "))
            assertTrue(!log[0].containsKey("prompt"), "관리자 대시보드에도 프롬프트 본문은 노출하지 않는다")
        }

        @Test
        fun `provider history — 공개 기본값은 provider id 를 노출하지 않는다`() {
            requests.save(
                AiRequestEntity(
                    requestId = "p1",
                    guildId = 703,
                    state = "COMPLETED",
                    providerId = 123,
                    createdAt = Instant.EPOCH,
                ),
            )

            val publicHistory = dashboard.providerHistory(123)
            val adminHistory = dashboard.providerHistory(123, audience = "admin")

            assertEquals("Provider", publicHistory["providerLabel"])
            assertTrue(!publicHistory.containsKey("providerId"), "공개 provider history 는 provider snowflake 를 노출하지 않는다")
            assertEquals("provider:123", adminHistory["providerLabel"])
            assertEquals(123L, adminHistory["providerId"])
            assertTrue(!publicHistory.toString().contains("prompt"), "처리 내역은 프롬프트 본문을 노출하지 않는다")
        }
    }
