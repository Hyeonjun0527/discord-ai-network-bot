package com.discordassistant.central.conversation

import com.discordassistant.central.conversation.adapter.outbound.persistence.burst.JpaBurstProjection
import com.discordassistant.central.conversation.adapter.outbound.persistence.burst.NexaBurstFragmentRepository
import com.discordassistant.central.conversation.adapter.outbound.persistence.burst.NexaBurstRepository
import com.discordassistant.central.conversation.domain.model.burst.BurstStatus
import com.discordassistant.central.conversation.domain.model.burst.BurstTestFragments
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.MessageContent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest

/**
 * NEXA-P04-T018 버스트 projection JPA 어댑터 통합 테스트(H2 + Flyway V53).
 *
 * acceptance: event store 를 재생해 projection 을 삭제·재구축할 수 있다 — deleteAll 후 같은 입력으로 save 재호출 시
 * 동일 projection 이 재구축된다. burst_id 유니크 upsert 라 중복 재생도 한 행으로 수렴한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaBurstProjectionTest
    @Autowired
    constructor(
        private val burstRepo: NexaBurstRepository,
        private val fragmentRepo: NexaBurstFragmentRepository,
    ) {
        private val guild = GuildId(1L)
        private val projection = JpaBurstProjection(burstRepo, fragmentRepo)

        private fun finalizedBurst(): UtteranceBurst =
            UtteranceBurst
                .open(guild, BurstTestFragments.fragment(1, seq = 1, content = MessageContent.Available("가")))
                .append(
                    BurstTestFragments.fragment(
                        2,
                        seq = 2,
                        at = BurstTestFragments.T0.plusSeconds(1),
                        content = MessageContent.Available("나"),
                    ),
                ).finalize()

        @Test
        fun `버스트와 fragment 연결·revision·segmentation version 을 저장하고 조회한다`() {
            val burst = finalizedBurst()
            projection.save(burst, revision = 5, segmentationVersion = 3)

            val record = projection.findById(burst.burstId)!!
            assertEquals(BurstStatus.FINALIZED, record.status)
            assertEquals(3, record.segmentationVersion)
            assertEquals(5L, record.revision)
            assertEquals(listOf(1L, 2L), record.fragmentMessageIds)
            assertEquals(guild.value, record.guildId)
        }

        @Test
        fun `같은 burstId upsert 는 한 행으로 수렴한다 (멱등 재생)`() {
            val burst = finalizedBurst()
            repeat(5) { projection.save(burst, revision = 0, segmentationVersion = 1) }
            assertEquals(1, burstRepo.count())
            assertEquals(2, fragmentRepo.count(), "fragment 도 중복 없이 2개")
        }

        @Test
        fun `deleteAll 후 재생으로 projection 을 재구축할 수 있다 (acceptance)`() {
            val burst = finalizedBurst()
            projection.save(burst, revision = 1, segmentationVersion = 2)
            assertEquals(1, burstRepo.count())

            // event store 재생 전 projection 초기화.
            projection.deleteAll()
            assertEquals(0, burstRepo.count())
            assertEquals(0, fragmentRepo.count())
            assertNull(projection.findById(burst.burstId))

            // 같은 입력 재생 → 동일 projection 재구축.
            projection.save(burst, revision = 1, segmentationVersion = 2)
            val rebuilt = projection.findById(burst.burstId)!!
            assertEquals(listOf(1L, 2L), rebuilt.fragmentMessageIds)
            assertEquals(2, rebuilt.segmentationVersion)
        }

        @Test
        fun `삭제 교정 scrubbed 표식이 combined text 재구성 제외용으로 저장된다 (T015 정합)`() {
            val burst = finalizedBurst()
            projection.save(burst, revision = 0, segmentationVersion = 1, scrubbedMessageIds = setOf(1L))
            val record = projection.findById(burst.burstId)!!
            assertEquals(setOf(1L), record.scrubbedMessageIds)
        }

        @Test
        fun `상태별 조회가 OPEN 버스트 deadline 복구 입력을 제공한다 (T019 정합)`() {
            val open = UtteranceBurst.open(guild, BurstTestFragments.fragment(10, seq = 10))
            projection.save(open, revision = 0, segmentationVersion = 1)
            projection.save(finalizedBurst(), revision = 0, segmentationVersion = 1)

            val openOnly = projection.findByStatus(BurstStatus.OPEN)
            assertEquals(1, openOnly.size)
            assertTrue(openOnly.single().fragmentMessageIds.contains(10L))
        }
    }
