package com.discordassistant.central.network

import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiNetworkEventRepository
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiNetworkProfileRepository
import com.discordassistant.central.ainetwork.application.AiLevelService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * REQ-XP-001(답변 성공 시 경험치 획득)·REQ-XP-002(레벨업 이벤트 기록) 단위 검증.
 * 원자 UPDATE 위임·레벨업 멱등(이벤트 1건)·비레벨업(0건)·best-effort 격리(예외 미전파)를 본다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AiLevelServiceTest
    @Autowired
    constructor(
        private val profiles: AiNetworkProfileRepository,
        private val events: AiNetworkEventRepository,
    ) {
        private val clock = Clock.fixed(Instant.parse("2026-06-04T00:00:00Z"), ZoneOffset.UTC)
        private val service = AiLevelService(profiles, events, clock)

        @Test
        fun `적립 시 totalXp 가 오르고 프로필이 없으면 생성된다`() {
            val before = service.awardAskXp(1001L)
            assertEquals(10L, before.totalXp)
            assertEquals(1, before.currentLevel)
            assertFalse(before.leveledUp)

            val profile = profiles.findByGuildId(1001L)
            assertNotNull(profile)
            assertEquals(10L, profile!!.totalXp)
            assertEquals(clock.instant(), profile.lastXpAt)
        }

        @Test
        fun `100xp 도달 시 레벨업하고 ai_level_up 이벤트가 1건 기록된다`() {
            // 9회(=90xp) 까지는 레벨업 없음.
            repeat(9) { service.awardAskXp(1002L) }
            assertEquals(0, events.findByGuildIdAndEventType(1002L, "ai_level_up").size)
            assertEquals(1, profiles.findByGuildId(1002L)!!.aiLevel)

            // 10회째(=100xp) 에 L2 레벨업.
            val change = service.awardAskXp(1002L)
            assertTrue(change.leveledUp)
            assertEquals(2, change.currentLevel)
            assertEquals(100L, change.totalXp)

            val levelUps = events.findByGuildIdAndEventType(1002L, "ai_level_up")
            assertEquals(1, levelUps.size)
            assertEquals("니아 활동 레벨 2 달성", levelUps[0].title)
            assertEquals("level=2;xp=100", levelUps[0].metadata)
            assertEquals(2, profiles.findByGuildId(1002L)!!.aiLevel)
        }

        @Test
        fun `비레벨업 적립은 이벤트를 만들지 않는다`() {
            service.awardAskXp(1003L)
            service.awardAskXp(1003L)
            assertEquals(0, events.findByGuildIdAndEventType(1003L, "ai_level_up").size)
        }

        @Test
        fun `levelView 는 진행도와 다음 레벨까지 경험치를 보고한다`() {
            repeat(15) { service.awardAskXp(1004L) } // 150xp → L2, 구간 (50, 200)
            val view = service.levelView(1004L)
            assertEquals(2, view.aiLevel)
            assertEquals(150L, view.totalXp)
            assertEquals(50L, view.progressInLevel)
            assertEquals(200L, view.levelSpan)
            assertEquals(150L, view.xpToNext) // 300 - 150
        }

        @Test
        fun `프로필이 없는 길드의 levelView 는 기본값`() {
            val view = service.levelView(9999L)
            assertEquals(1, view.aiLevel)
            assertEquals(0L, view.totalXp)
            assertEquals(100L, view.xpToNext)
        }
    }
