package com.discordassistant.central.socialmemory

import com.discordassistant.central.socialmemory.adapter.outbound.persistence.JpaSocialStateSnapshot
import com.discordassistant.central.socialmemory.adapter.outbound.persistence.NexaSocialOutcomeCountRepository
import com.discordassistant.central.socialmemory.adapter.outbound.persistence.NexaSocialStateSnapshotRepository
import com.discordassistant.central.socialmemory.application.privacy.ForgetMemberSocialStateService
import com.discordassistant.central.socialmemory.application.rebuild.RebuildSocialStateService
import com.discordassistant.central.socialmemory.domain.event.HumanOutcomeObserved
import com.discordassistant.central.socialmemory.domain.event.NexaActionKind
import com.discordassistant.central.socialmemory.domain.event.NexaActionObserved
import com.discordassistant.central.socialmemory.domain.event.SocialStateUpdate
import com.discordassistant.central.socialmemory.domain.model.relationship.InteractionOutcome
import com.discordassistant.central.socialmemory.domain.model.relationship.MemberKey
import com.discordassistant.central.socialmemory.domain.model.snapshot.SocialStateSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.Instant

/**
 * NEXA-P06-T018/T019/T023 사회 상태 snapshot JPA 어댑터·재구축·삭제 통합 테스트(H2 + Flyway V55).
 *
 * - T018: 관계 snapshot upsert 멱등 + source watermark 보존(원문 없음).
 * - T019: snapshot 삭제 후 재구축 시 같은 canonical hash(event store 가 진실원).
 * - T023: 사용자 삭제 후 정책 feature builder 가 과거 상태를 읽지 못한다(findByKey == null).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaSocialStateSnapshotTest
    @Autowired
    constructor(
        private val snapshotRepo: NexaSocialStateSnapshotRepository,
        private val outcomeRepo: NexaSocialOutcomeCountRepository,
    ) {
        private val key = MemberKey("g1", "m1")
        private val t0 = Instant.parse("2026-01-01T00:00:00Z")
        private val port = JpaSocialStateSnapshot(snapshotRepo, outcomeRepo)

        private fun stream(): List<SocialStateUpdate> =
            listOf(
                NexaActionObserved(key, NexaActionKind.ADDRESSED_MEMBER, 1, listOf("a1"), t0),
                NexaActionObserved(key, NexaActionKind.RESPONDED_TO_MEMBER, 1, listOf("a2"), t0.plusSeconds(1)),
                HumanOutcomeObserved(key, InteractionOutcome.CONTINUED, 1, listOf("o1"), t0.plusSeconds(2)),
                HumanOutcomeObserved(key, InteractionOutcome.CONTINUED, 1, listOf("o2"), t0.plusSeconds(3)),
                HumanOutcomeObserved(key, InteractionOutcome.REACTED, 1, listOf("o3"), t0.plusSeconds(4)),
            )

        @Test
        fun `snapshot 과 결과 카운트와 watermark 를 저장하고 조회한다 (T018)`() {
            val snapshot = SocialStateSnapshot.rebuild(key, 1, stream())
            port.save(snapshot)

            val loaded = port.findByKey(key)!!
            assertEquals(1, loaded.interaction.nexaToMemberBursts)
            assertEquals(1, loaded.interaction.memberToNexaBursts)
            assertEquals(2, loaded.outcomeCounts[InteractionOutcome.CONTINUED])
            assertEquals(1, loaded.outcomeCounts[InteractionOutcome.REACTED])
            assertEquals("o3", loaded.lastSourceEventId, "source watermark 보존")
            assertEquals(snapshot.canonicalHash(), loaded.canonicalHash(), "round-trip hash 동일")
        }

        @Test
        fun `같은 키 upsert 는 한 행으로 수렴한다 (멱등)`() {
            val snapshot = SocialStateSnapshot.rebuild(key, 1, stream())
            repeat(4) { port.save(snapshot) }
            assertEquals(1, snapshotRepo.count())
            assertEquals(2, outcomeRepo.count(), "결과 코드 2종(continued/reacted)만 1행씩")
        }

        @Test
        fun `삭제 후 재구축하면 같은 hash 다 (acceptance T019)`() {
            val rebuild = RebuildSocialStateService(port)
            val matches = rebuild.deleteThenRebuildHashMatches(key, 1, stream())
            assertTrue(matches, "snapshot 삭제 후 같은 update 재생은 같은 hash")
        }

        @Test
        fun `사용자 삭제 후 정책 feature builder 가 과거 상태를 읽지 못한다 (acceptance T023)`() {
            port.save(SocialStateSnapshot.rebuild(key, 1, stream()))
            assertEquals(1, snapshotRepo.count())

            val forget = ForgetMemberSocialStateService(port)
            val result = forget.forget(key)

            assertTrue(result.wasPresent, "삭제 전 상태가 존재했다")
            assertNull(port.findByKey(key), "삭제 후 관계 상태가 조회되지 않는다")
            assertEquals(0, snapshotRepo.count())
            assertEquals(0, outcomeRepo.count(), "자식 결과 카운트도 함께 제거된다(CASCADE)")
        }

        @Test
        fun `없는 키 삭제는 false 를 반환한다 (멱등)`() {
            val forget = ForgetMemberSocialStateService(port)
            assertFalse(forget.forget(MemberKey("g1", "ghost")).wasPresent)
        }

        @Test
        fun `guild 단위로 모든 관계 snapshot 을 조회한다`() {
            port.save(SocialStateSnapshot.rebuild(key, 1, stream()))
            val key2 = MemberKey("g1", "m2")
            port.save(
                SocialStateSnapshot.rebuild(
                    key2,
                    1,
                    listOf(NexaActionObserved(key2, NexaActionKind.ADDRESSED_MEMBER, 1, listOf("b1"), t0)),
                ),
            )
            assertEquals(2, port.findByGuild("g1").size)
            assertTrue(port.findByGuild("other").isEmpty())
        }
    }
