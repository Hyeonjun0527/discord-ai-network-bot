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
        fun `requests — 최근 로그, 프롬프트 본문 미포함`() {
            requests.save(AiRequestEntity(requestId = "r3", guildId = 701, state = "COMPLETED", providerId = 9, createdAt = Instant.EPOCH))
            val log = dashboard.requests(701)
            assertEquals(1, log.size)
            assertEquals("r3", log[0]["requestId"])
            assertEquals(9L, log[0]["providerId"])
            assertTrue(!log[0].containsKey("prompt"), "프롬프트 본문은 노출하지 않는다")
        }
    }
