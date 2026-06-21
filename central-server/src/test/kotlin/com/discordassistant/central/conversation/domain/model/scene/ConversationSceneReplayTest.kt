package com.discordassistant.central.conversation.domain.model.scene

import com.discordassistant.central.conversation.domain.model.burst.BurstId
import com.discordassistant.central.conversation.domain.model.burst.BurstLocationKey
import com.discordassistant.central.conversation.domain.model.event.AuthorId
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.thread.ConversationThreadId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P05-T024: scene replay 결정론 테스트.
 *
 * **acceptance(T024)**: 같은 canonical event stream 은 byte-equivalent snapshot 을 만든다. 중복·역순·수정·삭제
 * 이벤트를 여러 도착 순서로 넣어도, eventId 로 중복을 제거하고 canonical sequence 로 정렬한 뒤 재생하면 항상
 * 같은 장면 projection(같은 직렬화 바이트)이 나온다.
 *
 * canonical event stream 은 (sequence, eventId, SceneChange) 레코드의 정렬·중복제거된 시퀀스다. 이 시퀀스를
 * [ConversationScene.advance] 로 접으면 결정론적 projection 이 만들어진다([ConversationScene] 의 불변 advance·
 * [ContextVersion.apply] 의 결정론 규칙에 의해). snapshot 은 그 projection 의 관찰 가능한 상태(sceneSeq·
 * contextVersion·recent burst·active thread·참여자·발행된 SceneUpdated)를 정렬된 UTF-8 바이트로 직렬화한 것이다.
 *
 * 순수성: 도메인 모델만 사용한다(Spring/JPA/JDA 미참조 — NexaArchitectureTest.nexaDomainsArePure).
 */
class ConversationSceneReplayTest {
    private val guild = GuildId(1L)
    private val channel = ChannelId(100L)
    private val location = BurstLocationKey(channel, threadId = null)
    private val threadA = ConversationThreadId.of(location, 0)
    private val threadB = ConversationThreadId.of(location, 1)
    private val ruleVersion = "scene-replay-v1"

    /**
     * canonical event 한 건. [sequence] 는 채널 내 전역 정렬 키(단조 증가), [eventId] 는 멱등 dedup 키다.
     * 도착 순서와 무관하게 (dedup by eventId, sort by sequence) 로 canonical 화한다.
     */
    private data class CanonicalEvent(
        val sequence: Long,
        val eventId: String,
        val change: SceneChange,
        val recentBurstIds: List<BurstId>,
        val activeThreadIds: Set<ConversationThreadId>,
        val participants: List<SceneParticipant>,
    )

    private fun event(
        sequence: Long,
        eventId: String,
        change: SceneChange,
        recentBurstIds: List<BurstId> = emptyList(),
        activeThreadIds: Set<ConversationThreadId> = emptySet(),
        participants: List<SceneParticipant> = emptyList(),
    ) = CanonicalEvent(sequence, eventId, change, recentBurstIds, activeThreadIds, participants)

    /** 도착 순서가 어떻든 canonical 순서로 정규화한다: eventId 중복 제거 후 sequence 오름차순. */
    private fun canonicalize(arrived: List<CanonicalEvent>): List<CanonicalEvent> =
        arrived.associateBy { it.eventId }.values.sortedBy { it.sequence }

    /** canonical event stream 을 접어 최종 장면과 발행된 SceneUpdated 이벤트 목록을 만든다(결정론적 reducer). */
    private fun project(events: List<CanonicalEvent>): Projection {
        var scene = ConversationScene.initial(guild, channel, ruleVersion)
        val emitted = mutableListOf<com.discordassistant.central.conversation.domain.event.SceneUpdated>()
        canonicalize(events).forEach { e ->
            val previous = scene
            scene =
                scene.advance(
                    change = e.change,
                    recentBurstIds = e.recentBurstIds,
                    activeThreadIds = e.activeThreadIds,
                    participants = e.participants,
                )
            emitted += scene.toSceneUpdated(previous = previous, change = e.change)
        }
        return Projection(scene, emitted)
    }

    private data class Projection(
        val scene: ConversationScene,
        val emitted: List<com.discordassistant.central.conversation.domain.event.SceneUpdated>,
    )

    /** projection 의 관찰 상태를 결정론적(정렬된) UTF-8 바이트로 직렬화한 snapshot. */
    private fun snapshot(projection: Projection): ByteArray {
        val scene = projection.scene
        val sb = StringBuilder()
        sb.append("sceneSeq=").append(scene.sceneSeq).append('\n')
        sb.append("contextVersion=").append(scene.contextVersion.value).append('\n')
        sb.append("recentBurstIds=").append(scene.recentBurstIds.map { it.value }).append('\n')
        sb.append("activeThreadIds=").append(scene.activeThreadIds.map { it.value }.sorted()).append('\n')
        sb
            .append("participants=")
            .append(scene.participants.map { it.authorId.value }.sorted())
            .append('\n')
        scene.participants.sortedBy { it.authorId.value }.forEach {
            sb
                .append("  participant:")
                .append(it.authorId.value)
                .append(" lastSpokeAt=")
                .append(it.lastSpokeAt)
                .append(" open=")
                .append(it.openBurst?.value)
                .append('\n')
        }
        projection.emitted.forEach { e ->
            sb
                .append("event seq=")
                .append(e.sceneSeq)
                .append(" type=")
                .append(e.changeType)
                .append(" v=")
                .append(e.previousContextVersion)
                .append("->")
                .append(e.newContextVersion)
                .append(" affected=")
                .append(e.affectedThreadIds.map { it.value }.sorted())
                .append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private val burst1 = BurstId("burst:100:1")
    private val burst2 = BurstId("burst:100:2")
    private val t0 = Instant.parse("2026-01-01T12:00:00Z")
    private val alice = SceneParticipant(AuthorId(11L), lastSpokeAt = t0)
    private val bob = SceneParticipant(AuthorId(12L), lastSpokeAt = t0.plusSeconds(5))

    /** 기준 canonical stream: 사람 답변→주제 전환→메시지 삭제→메트릭 갱신. */
    private fun baselineStream(): List<CanonicalEvent> =
        listOf(
            event(1, "e1", SceneChange.HUMAN_REPLIED, listOf(burst1), setOf(threadA), listOf(alice)),
            event(2, "e2", SceneChange.TOPIC_SWITCHED, listOf(burst1, burst2), setOf(threadA, threadB), listOf(alice, bob)),
            event(3, "e3", SceneChange.MESSAGE_DELETED, listOf(burst1, burst2), setOf(threadB), listOf(alice, bob)),
            event(4, "e4", SceneChange.METRICS_UPDATED, listOf(burst1, burst2), setOf(threadB), listOf(alice, bob)),
        )

    @Test
    fun `같은 canonical stream 을 두 번 재생하면 byte-equivalent snapshot 이다`() {
        val first = snapshot(project(baselineStream()))
        val second = snapshot(project(baselineStream()))
        assertEquals(
            first.toList(),
            second.toList(),
            "동일 canonical stream 은 같은 바이트 snapshot 을 만들어야 한다",
        )
    }

    @Test
    fun `중복 이벤트는 canonical 화에서 제거돼 snapshot 이 변하지 않는다`() {
        val canonical = baselineStream()
        // 같은 eventId 를 여러 번(중복 전송) 섞어 넣는다.
        val withDuplicates =
            listOf(canonical[0], canonical[0], canonical[1], canonical[1], canonical[2], canonical[3], canonical[3])
        assertEquals(
            snapshot(project(canonical)).toList(),
            snapshot(project(withDuplicates)).toList(),
            "중복 eventId 는 멱등 — snapshot 불변",
        )
    }

    @Test
    fun `역순·뒤섞인 도착 순서도 canonical sequence 로 정렬돼 같은 snapshot 이다`() {
        val canonical = baselineStream()
        val reversed = canonical.reversed()
        val shuffled = listOf(canonical[2], canonical[0], canonical[3], canonical[1])
        val base = snapshot(project(canonical)).toList()
        assertEquals(base, snapshot(project(reversed)).toList(), "역순 도착도 같은 snapshot")
        assertEquals(base, snapshot(project(shuffled)).toList(), "뒤섞인 도착도 같은 snapshot")
    }

    @Test
    fun `중복+역순+뒤섞임을 한꺼번에 넣어도 같은 snapshot 이다`() {
        val canonical = baselineStream()
        val messy =
            listOf(
                canonical[3],
                canonical[1],
                canonical[1],
                canonical[0],
                canonical[2],
                canonical[0],
                canonical[3],
                canonical[2],
            )
        assertEquals(
            snapshot(project(canonical)).toList(),
            snapshot(project(messy)).toList(),
            "중복·역순·뒤섞임 조합도 canonical 화 후 같은 snapshot",
        )
    }

    @Test
    fun `수정·삭제 이벤트는 canonical stream 의 일부로 결정론적 snapshot 을 만든다`() {
        // 삭제(MESSAGE_DELETED)·수정(TOPIC_SWITCHED 로 주제가 바뀐 갱신)을 포함한 stream 을 여러 순서로 재생.
        val canonical = baselineStream()
        val arrival1 = listOf(canonical[1], canonical[3], canonical[0], canonical[2])
        val arrival2 = listOf(canonical[2], canonical[0], canonical[1], canonical[3], canonical[2])
        assertEquals(
            snapshot(project(arrival1)).toList(),
            snapshot(project(arrival2)).toList(),
            "수정·삭제 포함 stream 도 canonical 화 후 같은 snapshot",
        )
    }

    @Test
    fun `다른 canonical stream 은 다른 snapshot 을 만든다 (snapshot 이 상태를 실제로 반영)`() {
        val canonical = baselineStream()
        // 마지막 이벤트를 정책 무효화(MESSAGE_DELETED)로 바꾼 다른 canonical stream.
        val different =
            canonical.dropLast(1) +
                event(5, "e5", SceneChange.MESSAGE_DELETED, listOf(burst1, burst2), emptySet(), listOf(alice))
        assertNotEquals(
            snapshot(project(canonical)).toList(),
            snapshot(project(different)).toList(),
            "내용이 다른 canonical stream 은 다른 snapshot 이어야 한다(snapshot 이 무의미하지 않음)",
        )
    }
}
