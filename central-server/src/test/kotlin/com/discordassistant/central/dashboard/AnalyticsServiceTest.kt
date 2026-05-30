package com.discordassistant.central.dashboard

import com.discordassistant.central.persistence.AiRequestEntity
import com.discordassistant.central.persistence.AiRequestRepository
import com.discordassistant.central.persistence.UsageLogEntity
import com.discordassistant.central.persistence.UsageLogRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AnalyticsService::class)
class AnalyticsServiceTest @Autowired constructor(
    val analytics: AnalyticsService,
    val usage: UsageLogRepository,
    val requests: AiRequestRepository,
) {
    // 고정 시계: 2026-01-10T12:00Z
    private val fixed = Clock.fixed(Instant.parse("2026-01-10T12:00:00Z"), ZoneOffset.UTC)
    private val today = Instant.parse("2026-01-10T12:00:00Z").truncatedTo(ChronoUnit.DAYS)

    @Test
    fun `사용량 트렌드 — 일자별 집계(#227)`() {
        // 오늘 2건, 어제 1건
        usage.save(UsageLogEntity(guildId = 800, requestId = "a", createdAt = today.plus(1, ChronoUnit.HOURS)))
        usage.save(UsageLogEntity(guildId = 800, requestId = "b", createdAt = today.plus(2, ChronoUnit.HOURS)))
        usage.save(UsageLogEntity(guildId = 800, requestId = "c", createdAt = today.minus(20, ChronoUnit.HOURS)))

        val trend = analytics.usageTrend(800, days = 3, clock = fixed)
        assertEquals(3, trend.size)
        assertEquals(0L, trend[0].count) // 2일 전
        assertEquals(1L, trend[1].count) // 어제
        assertEquals(2L, trend[2].count) // 오늘
    }

    @Test
    fun `처리 부하 회계 — 부담 가중 합(#228)`() {
        requests.save(AiRequestEntity(requestId = "r1", providerId = 50, state = "COMPLETED", requiredBurden = "LIGHT"))
        requests.save(AiRequestEntity(requestId = "r2", providerId = 50, state = "COMPLETED", requiredBurden = "HEAVY"))
        requests.save(AiRequestEntity(requestId = "r3", providerId = 50, state = "REJECTED", requiredBurden = "HEAVY")) // 완료 아님 → 제외
        // LIGHT(1) + HEAVY(3) = 4
        assertEquals(4L, analytics.providerComputeScore(50))
    }

    @Test
    fun `프로바이더 처리 내역 — 프롬프트·유저 미포함(#166)`() {
        requests.save(AiRequestEntity(requestId = "h1", providerId = 60, userId = 999, state = "COMPLETED", requiredBurden = "LIGHT"))
        requests.save(AiRequestEntity(requestId = "h2", providerId = 60, userId = 888, state = "REJECTED", requiredBurden = "HEAVY"))
        val hist = analytics.providerHistory(60)
        assertEquals(1, hist.size) // 완료만
        assertEquals("h1", hist[0]["requestId"])
        assertEquals(false, hist[0].containsKey("userId"))
        assertEquals(false, hist[0].containsKey("prompt"))
    }
}
