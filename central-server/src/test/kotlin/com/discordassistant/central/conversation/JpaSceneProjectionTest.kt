package com.discordassistant.central.conversation

import com.discordassistant.central.conversation.adapter.outbound.persistence.scene.JpaConversationSceneIngress
import com.discordassistant.central.conversation.adapter.outbound.persistence.scene.JpaSceneProjection
import com.discordassistant.central.conversation.adapter.outbound.persistence.scene.NexaSceneSnapshotRepository
import com.discordassistant.central.conversation.adapter.outbound.persistence.scene.NexaSceneVersionRepository
import com.discordassistant.central.conversation.application.scene.ConversationObservation
import com.discordassistant.central.conversation.domain.model.burst.BurstId
import com.discordassistant.central.conversation.domain.model.burst.BurstLocationKey
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.scene.ConversationScene
import com.discordassistant.central.conversation.domain.model.scene.SceneChange
import com.discordassistant.central.conversation.domain.model.thread.ConversationThreadId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.Instant

/**
 * NEXA-P05-T020 장면 projection JPA 어댑터 통합 테스트(H2 + Flyway V54).
 *
 * acceptance: snapshot 삭제 후 event store 재생으로 재구축 — deleteAll 후 같은 입력으로 save 재호출 시 동일 snapshot.
 * channel_id 유니크 upsert 라 같은 채널 반복 저장도 한 snapshot 으로 수렴하고, version history 는 sceneSeq 마다 누적된다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaSceneProjectionTest
    @Autowired
    constructor(
        private val snapshotRepo: NexaSceneSnapshotRepository,
        private val versionRepo: NexaSceneVersionRepository,
    ) {
        private val guild = GuildId(1L)
        private val channel = ChannelId(100L)
        private val location = BurstLocationKey(channel, threadId = null)
        private val threadA = ConversationThreadId.of(location, 0)
        private val projection = JpaSceneProjection(snapshotRepo, versionRepo)
        private val ingress = JpaConversationSceneIngress(snapshotRepo, versionRepo)

        private fun scene(): ConversationScene =
            ConversationScene
                .initial(guild, channel, "v1")
                .advance(
                    SceneChange.HUMAN_REPLIED,
                    recentBurstIds = listOf(BurstId("burst:1:1"), BurstId("burst:2:2")),
                    activeThreadIds = setOf(threadA),
                )

        @Test
        fun `현재 snapshot 과 version history 의 최소 메타데이터를 저장하고 조회한다`() {
            val s = scene()
            projection.save(s)

            val record = projection.findByChannel(channel.value)!!
            assertEquals(s.sceneSeq, record.sceneSeq)
            assertEquals(s.contextVersion.value, record.contextVersion)
            assertEquals(2, record.recentBurstCount)
            assertEquals(1, record.activeThreadCount)
            assertNotEquals(guild.value, record.guildId)
            assertNotEquals(channel.value, record.channelId)
        }

        @Test
        fun `같은 채널 upsert 는 snapshot 한 행으로 수렴한다 (멱등 재생)`() {
            val s = scene()
            repeat(5) { projection.save(s) }
            assertEquals(1, snapshotRepo.count())
        }

        @Test
        fun `sceneSeq 갱신마다 version history 가 누적된다`() {
            var s = ConversationScene.initial(guild, channel, "v1")
            projection.save(s)
            s = s.advance(SceneChange.HUMAN_REPLIED)
            projection.save(s)
            s = s.advance(SceneChange.METRICS_UPDATED)
            projection.save(s)

            val history = projection.history(channel.value)
            assertEquals(listOf(0L, 1L, 2L), history.map { it.sceneSeq })
            // sceneSeq 1 에서 사람 답함 → version 1, sceneSeq 2 는 metric 갱신 → version 유지 1.
            assertEquals(listOf(0L, 1L, 1L), history.map { it.contextVersion })
        }

        @Test
        fun `snapshot 삭제 후 재생으로 재구축할 수 있다 (acceptance)`() {
            val s = scene()
            projection.save(s)
            assertEquals(1, snapshotRepo.count())

            projection.deleteAll()
            assertEquals(0, snapshotRepo.count())
            assertEquals(0, versionRepo.count())
            assertNull(projection.findByChannel(channel.value))

            // 같은 입력 재생 → 동일 snapshot 재구축.
            projection.save(s)
            val rebuilt = projection.findByChannel(channel.value)!!
            assertEquals(s.sceneSeq, rebuilt.sceneSeq)
            assertEquals(s.contextVersion.value, rebuilt.contextVersion)
            assertEquals(2, rebuilt.recentBurstCount)
        }

        @Test
        fun `Discord observation은 projection에서 원자 증가하고 같은 ref 재처리는 멱등이다`() {
            val observedAt = Instant.parse("2026-07-17T00:00:00Z")
            val first =
                ingress.observe(
                    ConversationObservation(guild.value, channel.value, "message:1", observedAt),
                )!!
            val duplicate =
                ingress.observe(
                    ConversationObservation(guild.value, channel.value, "message:1", observedAt),
                )!!
            val next =
                ingress.observe(
                    ConversationObservation(guild.value, channel.value, "message:2", observedAt.plusSeconds(1)),
                )!!

            assertEquals(1L, first.sceneSeq)
            assertEquals(first, duplicate)
            assertEquals(2L, next.sceneSeq)
            assertEquals(2L, next.contextVersion)
            val storedObservationRefs =
                versionRepo.findByChannelIdOrderBySceneSeqAsc(first.channelId).map { it.observationRef }
            assertEquals(2, storedObservationRefs.distinct().size)
            storedObservationRefs.forEach { assertThat(it).startsWith("scene_observation:").doesNotContain("message:") }
            assertNotEquals(channel.value, first.channelId)
            assertNotEquals(guild.value, first.guildId)
        }
    }
