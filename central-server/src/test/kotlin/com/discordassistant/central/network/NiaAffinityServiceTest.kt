package com.discordassistant.central.network

import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiNetworkEventRepository
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.UserAffinityRepository
import com.discordassistant.central.ainetwork.application.NiaAffinityService
import com.discordassistant.central.ainetwork.domain.model.AffinityStage
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

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class NiaAffinityServiceTest
    @Autowired
    constructor(
        private val affinities: UserAffinityRepository,
        private val events: AiNetworkEventRepository,
    ) {
        private val clock = Clock.fixed(Instant.parse("2026-06-04T00:00:00Z"), ZoneOffset.UTC)
        private val service = NiaAffinityService(affinities, events, clock)

        @Test
        fun `적립 시 유저 호감도 행을 만들고 점수를 올린다`() {
            val change = service.awardInteraction(guildId = 1001L, userId = 501L)

            assertEquals(1L, change.score)
            assertEquals(AffinityStage.STRANGER, change.currentStage)
            assertFalse(change.stageUp)
            val row = affinities.findByUserId(501L)
            assertNotNull(row)
            assertEquals(1L, row!!.score)
            assertEquals(clock.instant(), row.lastInteractionAt)
        }

        @Test
        fun `임계 통과 시 단계 상승 이벤트를 한 번 기록한다`() {
            repeat(9) { service.awardInteraction(guildId = 1002L, userId = 502L) }
            assertEquals(0, events.findByGuildIdAndEventType(1002L, "nia_affinity_stage_up").size)

            val change = service.awardInteraction(guildId = 1002L, userId = 502L)

            assertTrue(change.stageUp)
            assertEquals(AffinityStage.GETTING_TO_KNOW, change.currentStage)
            val stageUps = events.findByGuildIdAndEventType(1002L, "nia_affinity_stage_up")
            assertEquals(1, stageUps.size)
            assertEquals(502L, stageUps[0].actorUserId)
            assertEquals("stage=GETTING_TO_KNOW;score=10", stageUps[0].metadata)
        }

        @Test
        fun `view 는 행이 없어도 기본 호감도 상태를 반환한다`() {
            val view = service.view(9999L)

            assertEquals(AffinityStage.STRANGER, view.stage)
            assertEquals(0L, view.score)
            assertEquals(10L, view.scoreToNext)
        }
    }
