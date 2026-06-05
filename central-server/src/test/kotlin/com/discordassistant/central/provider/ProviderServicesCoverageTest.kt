package com.discordassistant.central.provider

import com.discordassistant.central.provider.application.ProviderScheduleService
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** 프로바이더 서비스 핵심 로직 커버(차수 18). 차단 목록 + 가용 스케줄(고정 Clock 으로 시각 주입). */
@SpringBootTest
@Transactional
class ProviderServicesCoverageTest
    @Autowired
    constructor(
        val blocklist: BlocklistService,
        val schedule: ProviderScheduleService,
        val blocklistRepo: com.discordassistant.central.persistence.BlocklistRepository,
    ) {
        private fun clockAtHour(h: Int): Clock = Clock.fixed(Instant.parse("2026-01-01T%02d:30:00Z".format(h)), ZoneOffset.UTC)

        @Test
        fun `blocklist — DB 영속 후 재로드(재시작에도 차단 유지)`() {
            val g = 52_001L
            val u = 52_002L
            blocklist.block(g, u, adminId = 9L)
            assertTrue(blocklistRepo.findByGuildIdAndUserId(g, u) != null) // DB 저장됨
            blocklist.load() // 캐시 재적재(재시작 시뮬레이션) — 그래도 차단 유지
            assertTrue(blocklist.isBlocked(g, u))
            blocklist.unblock(g, u, adminId = 9L)
        }

        @Test
        fun `blocklist — 차단·조회·해제 라이프사이클`() {
            val g = 51_001L
            val u = 51_002L
            assertFalse(blocklist.isBlocked(g, u))
            blocklist.block(g, u, adminId = 1L)
            assertTrue(blocklist.isBlocked(g, u))
            assertTrue(blocklist.blockedUsers(g).contains(u))
            blocklist.unblock(g, u, adminId = 1L)
            assertFalse(blocklist.isBlocked(g, u))
        }

        @Test
        fun `schedule — 스케줄 미설정이면 항상 가용`() {
            assertTrue(schedule.isAvailableNow(52_001L, 52_002L, clockAtHour(3)))
        }

        @Test
        fun `schedule — 자정 넘김 구간 안·밖 판정`() {
            val p = 53_001L
            val g = 53_002L
            schedule.setSchedule(p, g, fromHour = 22, toHour = 6)
            assertTrue(schedule.isAvailableNow(p, g, clockAtHour(23)))
            assertTrue(schedule.isAvailableNow(p, g, clockAtHour(3)))
            assertFalse(schedule.isAvailableNow(p, g, clockAtHour(12)))
        }
    }
