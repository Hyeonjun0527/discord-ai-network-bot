package com.discordassistant.central.dashboard

import com.discordassistant.central.domain.RequestState
import com.discordassistant.central.persistence.AiNetworkEventEntity
import com.discordassistant.central.persistence.AiNetworkEventRepository
import com.discordassistant.central.persistence.AiRequestEntity
import com.discordassistant.central.persistence.AiRequestRepository
import com.discordassistant.central.persistence.UsageLogEntity
import com.discordassistant.central.persistence.UsageLogRepository
import com.discordassistant.central.usage.AnalyticsService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
class AnalyticsServiceTest
    @Autowired
    constructor(
        val analytics: AnalyticsService,
        val usage: UsageLogRepository,
        val requests: AiRequestRepository,
        val networkEvents: AiNetworkEventRepository,
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
            requests.save(AiRequestEntity(requestId = "r1", providerId = 50, state = RequestState.COMPLETED, requiredBurden = "LIGHT"))
            requests.save(AiRequestEntity(requestId = "r2", providerId = 50, state = RequestState.COMPLETED, requiredBurden = "HEAVY"))
            // r3 은 완료 아님 → 제외
            requests.save(AiRequestEntity(requestId = "r3", providerId = 50, state = RequestState.REJECTED, requiredBurden = "HEAVY"))
            // LIGHT(1) + HEAVY(3) = 4
            assertEquals(4L, analytics.providerComputeScore(50))
        }

        @Test
        fun `프로바이더 처리 내역 — 프롬프트·유저 미포함(#166)`() {
            requests.save(
                AiRequestEntity(requestId = "h1", providerId = 60, userId = 999, state = RequestState.COMPLETED, requiredBurden = "LIGHT"),
            )
            requests.save(
                AiRequestEntity(requestId = "h2", providerId = 60, userId = 888, state = RequestState.REJECTED, requiredBurden = "HEAVY"),
            )
            val hist = analytics.providerHistory(60)
            assertEquals(1, hist.size) // 완료만
            assertEquals("h1", hist[0]["requestId"])
            assertEquals(false, hist[0].containsKey("userId"))
            assertEquals(false, hist[0].containsKey("prompt"))
        }

        @Test
        fun `채널 사용 현황 — 채널별 요청수·고유유저수·마지막시각(Phase2 a)`() {
            val t0 = Instant.parse("2026-06-01T00:00:00Z")
            // 채널 10: 유저 1,1,2 → 요청 3건·고유 2명
            requests.save(AiRequestEntity(requestId = "c1", guildId = 900, channelId = 10, userId = 1, createdAt = t0))
            requests.save(AiRequestEntity(requestId = "c2", guildId = 900, channelId = 10, userId = 1, createdAt = t0.plusSeconds(60)))
            requests.save(AiRequestEntity(requestId = "c3", guildId = 900, channelId = 10, userId = 2, createdAt = t0.plusSeconds(120)))
            // 채널 11: 유저 3 → 요청 1건·고유 1명
            requests.save(AiRequestEntity(requestId = "c4", guildId = 900, channelId = 11, userId = 3, createdAt = t0.plusSeconds(30)))
            // 다른 길드는 제외돼야 함
            requests.save(AiRequestEntity(requestId = "c5", guildId = 901, channelId = 10, userId = 9, createdAt = t0))

            val usage = analytics.channelUsage(900)
            assertEquals(2, usage.size)
            val ch10 = usage.first { it.channelId == 10L }
            assertEquals(3L, ch10.requestCount)
            assertEquals(2L, ch10.distinctUsers)
            assertEquals(t0.plusSeconds(120).toString(), ch10.lastUsedAt)
            val ch11 = usage.first { it.channelId == 11L }
            assertEquals(1L, ch11.requestCount)
            assertEquals(1L, ch11.distinctUsers)
            // 요청 수 내림차순 정렬
            assertEquals(10L, usage[0].channelId)
        }

        @Test
        fun `채널 사용 현황 — 빈 길드는 빈 목록`() {
            assertTrue(analytics.channelUsage(99999).isEmpty())
        }

        @Test
        fun `기능 사용 유저 — userId·요청수·첫·마지막(Phase2 d, 집계만)`() {
            val t0 = Instant.parse("2026-06-02T00:00:00Z")
            requests.save(AiRequestEntity(requestId = "u1", guildId = 910, channelId = 1, userId = 7, createdAt = t0))
            requests.save(AiRequestEntity(requestId = "u2", guildId = 910, channelId = 1, userId = 7, createdAt = t0.plusSeconds(300)))
            requests.save(AiRequestEntity(requestId = "u3", guildId = 910, channelId = 2, userId = 8, createdAt = t0.plusSeconds(60)))

            val users = analytics.featureUsers(910)
            assertEquals(2, users.size)
            val u7 = users.first { it.userId == 7L }
            assertEquals(2L, u7.requestCount)
            assertEquals(t0.toString(), u7.firstUsedAt)
            assertEquals(t0.plusSeconds(300).toString(), u7.lastUsedAt)
            // 요청 수 내림차순: 유저 7(2건)이 먼저
            assertEquals(7L, users[0].userId)
        }

        @Test
        fun `기능 사용 유저 — limit 으로 상위 N 만`() {
            val t0 = Instant.parse("2026-06-02T00:00:00Z")
            // 유저 1(3건), 2(2건), 3(1건)
            repeat(3) { requests.save(AiRequestEntity(requestId = "a$it", guildId = 920, userId = 1, createdAt = t0)) }
            repeat(2) { requests.save(AiRequestEntity(requestId = "b$it", guildId = 920, userId = 2, createdAt = t0)) }
            requests.save(AiRequestEntity(requestId = "c0", guildId = 920, userId = 3, createdAt = t0))

            val top2 = analytics.featureUsers(920, limit = 2)
            assertEquals(2, top2.size)
            assertEquals(1L, top2[0].userId)
            assertEquals(2L, top2[1].userId)
        }

        @Test
        fun `기능 사용 유저 — DB 레벨 limit(유저 N greater than limit 픽스처)`() {
            val t0 = Instant.parse("2026-06-02T00:00:00Z")
            // distinct 유저 10명(각 1건씩) → limit 3 이면 3명만 반환돼야 한다(DB 절단).
            (1..10).forEach { uid ->
                requests.save(
                    AiRequestEntity(requestId = "n$uid", guildId = 921, userId = uid.toLong(), createdAt = t0.plusSeconds(uid.toLong())),
                )
            }

            val limited = analytics.featureUsers(921, limit = 3)
            assertEquals(3, limited.size)
            // 동률(각 1건)이면 userId asc 타이브레이크 → 1,2,3
            assertEquals(listOf(1L, 2L, 3L), limited.map { it.userId })
        }

        @Test
        fun `프로바이더 참여 이력 — 최신순, providerUserId 필터(Phase2 c)`() {
            val t0 = Instant.parse("2026-06-03T00:00:00Z")
            networkEvents.save(
                AiNetworkEventEntity(
                    guildId = 930,
                    eventType = "provider_joined",
                    providerUserId = 100,
                    title = "Provider 참여",
                    createdAt = t0,
                ),
            )
            networkEvents.save(
                AiNetworkEventEntity(
                    guildId = 930,
                    eventType = "provider_overload",
                    providerUserId = 100,
                    title = "과부하",
                    createdAt = t0.plusSeconds(60),
                ),
            )
            networkEvents.save(
                AiNetworkEventEntity(
                    guildId = 930,
                    eventType = "provider_joined",
                    providerUserId = 200,
                    title = "다른 Provider 참여",
                    createdAt = t0.plusSeconds(30),
                ),
            )
            // 프로바이더 무관 이벤트(providerUserId=null) — 필터 없는 조회에서 제외돼야 한다.
            networkEvents.save(
                AiNetworkEventEntity(
                    guildId = 930,
                    eventType = "ai_level_up",
                    providerUserId = null,
                    title = "레벨업",
                    createdAt = t0.plusSeconds(90),
                ),
            )

            val all = analytics.providerHistoryTimeline(930)
            assertEquals(3, all.size) // ai_level_up 제외 → provider 이벤트 3건만
            assertTrue(all.all { it.providerUserId != null })
            assertTrue(all.none { it.eventType == "ai_level_up" })
            assertEquals("과부하", all[0].title) // 최신순(provider 이벤트 중)

            val onlyProvider = analytics.providerHistoryTimeline(930, providerUserId = 100)
            assertEquals(2, onlyProvider.size)
            assertTrue(onlyProvider.all { it.providerUserId == 100L })
        }
    }
