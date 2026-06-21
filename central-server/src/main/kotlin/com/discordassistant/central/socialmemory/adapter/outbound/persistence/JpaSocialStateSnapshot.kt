package com.discordassistant.central.socialmemory.adapter.outbound.persistence

import com.discordassistant.central.socialmemory.application.port.out.SocialStateSnapshotPort
import com.discordassistant.central.socialmemory.domain.model.relationship.InteractionOutcome
import com.discordassistant.central.socialmemory.domain.model.relationship.MemberInteractionState
import com.discordassistant.central.socialmemory.domain.model.relationship.MemberKey
import com.discordassistant.central.socialmemory.domain.model.snapshot.SocialStateSnapshot
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * [SocialStateSnapshotPort] 의 JPA 구현 어댑터(NEXA-P06-T018). 관계 키별 현재 snapshot(키당 1행)과 결과 코드별
 * 카운트 자식 행의 최소 메타데이터를 영속화한다(Flyway V55).
 *
 * **upsert 멱등(acceptance T018)**: [save] 는 (guild, member) 유니크로 처음이면 insert, 있으면 갱신한다 — 같은
 * 키를 N 번 저장해도 한 snapshot 으로 수렴한다. 결과 카운트는 코드별 upsert 라 같은 코드 재저장도 한 행.
 *
 * **원문 비저장(observable-state-policy, data-categories.md)**: 카운트·결과 코드·식별자 가명·watermark 만 보관한다 —
 * 원본 content·호감도 score 를 담지 않는다(ADR 0010). [toString] 을 메타데이터만 노출하도록 오버라이드한다(원문 누출 방지).
 *
 * **삭제 전파(T023)**: [deleteByKey] 는 그 키의 snapshot 1행을 제거하고, 자식 카운트는 DB CASCADE 로 함께 사라진다
 * (deletion-propagation 불변식 1·2). 삭제 후 [findByKey] 는 null 이라 정책 feature builder 가 과거 상태를 읽지 못한다.
 */
@Repository
class JpaSocialStateSnapshot(
    private val snapshots: NexaSocialStateSnapshotRepository,
    private val outcomeCounts: NexaSocialOutcomeCountRepository,
) : SocialStateSnapshotPort {
    @Transactional
    override fun save(snapshot: SocialStateSnapshot) {
        val key = snapshot.key
        val entity =
            snapshots.findByGuildPseudonymAndMemberPseudonym(key.guildPseudonym, key.memberPseudonym)
                ?: NexaSocialStateSnapshotEntity(guildPseudonym = key.guildPseudonym, memberPseudonym = key.memberPseudonym)
        entity.projectionVersion = snapshot.projectionVersion
        entity.nexaToMemberBursts = snapshot.interaction.nexaToMemberBursts
        entity.memberToNexaBursts = snapshot.interaction.memberToNexaBursts
        entity.observedReactions = snapshot.interaction.observedReactions
        entity.lastInteractionAt = snapshot.interaction.lastInteractionAt
        entity.lastSourceEventId = snapshot.lastSourceEventId
        entity.lastObservedAt = snapshot.lastObservedAt
        val saved = snapshots.save(entity)

        // 결과 카운트: 코드별 upsert(0 인 코드는 저장하지 않는다 — 표본 부족 표현).
        snapshot.outcomeCounts.forEach { (code, count) ->
            if (count <= 0) return@forEach
            val child =
                outcomeCounts.findBySnapshotIdAndOutcomeCode(saved.id, code.wireName)
                    ?: NexaSocialOutcomeCountEntity(snapshotId = saved.id, outcomeCode = code.wireName)
            child.count = count
            outcomeCounts.save(child)
        }
    }

    @Transactional(readOnly = true)
    override fun findByKey(key: MemberKey): SocialStateSnapshot? =
        snapshots.findByGuildPseudonymAndMemberPseudonym(key.guildPseudonym, key.memberPseudonym)?.toDomain()

    @Transactional(readOnly = true)
    override fun findByGuild(guildPseudonym: String): List<SocialStateSnapshot> =
        snapshots.findByGuildPseudonym(guildPseudonym).map { it.toDomain() }

    @Transactional
    override fun deleteByKey(key: MemberKey): Boolean {
        val existing =
            snapshots.findByGuildPseudonymAndMemberPseudonym(key.guildPseudonym, key.memberPseudonym) ?: return false
        // 자식 카운트는 FK ON DELETE CASCADE 로 함께 제거된다(삭제 전파, T023). 명시 삭제로 H2/Postgres 모두 안전.
        outcomeCounts.deleteBySnapshotId(existing.id)
        snapshots.delete(existing)
        return true
    }

    @Transactional
    override fun deleteAll() {
        outcomeCounts.deleteAllInBatch()
        snapshots.deleteAllInBatch()
    }

    private fun NexaSocialStateSnapshotEntity.toDomain(): SocialStateSnapshot {
        val key = MemberKey(guildPseudonym = guildPseudonym, memberPseudonym = memberPseudonym)
        val counts =
            outcomeCounts
                .findBySnapshotId(id)
                .mapNotNull { row -> InteractionOutcome.fromWireName(row.outcomeCode)?.let { it to row.count } }
                .toMap()
        return SocialStateSnapshot(
            key = key,
            interaction =
                MemberInteractionState(
                    key = key,
                    nexaToMemberBursts = nexaToMemberBursts,
                    memberToNexaBursts = memberToNexaBursts,
                    observedReactions = observedReactions,
                    lastInteractionAt = lastInteractionAt,
                ),
            outcomeCounts = counts,
            projectionVersion = projectionVersion,
            lastSourceEventId = lastSourceEventId,
            lastObservedAt = lastObservedAt,
        )
    }
}

/**
 * 관계 snapshot JPA 엔티티(T018). (guild_pseudonym, member_pseudonym) 유니크로 멱등 upsert — 관계당 현재 1행.
 * 원문·호감도 score 를 담지 않는다(가명·카운트·watermark 만). [toString] 을 메타데이터만 노출하도록 오버라이드한다.
 */
@Entity
@Table(name = "nexa_social_state_snapshot")
class NexaSocialStateSnapshotEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "guild_pseudonym") var guildPseudonym: String = "",
    @Column(name = "member_pseudonym") var memberPseudonym: String = "",
    @Column(name = "projection_version") var projectionVersion: Long = 0,
    @Column(name = "nexa_to_member_bursts") var nexaToMemberBursts: Int = 0,
    @Column(name = "member_to_nexa_bursts") var memberToNexaBursts: Int = 0,
    @Column(name = "observed_reactions") var observedReactions: Int = 0,
    @Column(name = "last_interaction_at") var lastInteractionAt: Instant? = null,
    @Column(name = "last_source_event_id") var lastSourceEventId: String? = null,
    @Column(name = "last_observed_at") var lastObservedAt: Instant? = null,
) {
    override fun toString(): String =
        "NexaSocialStateSnapshotEntity(guild=$guildPseudonym, member=$memberPseudonym, projectionVersion=$projectionVersion)"
}

interface NexaSocialStateSnapshotRepository : JpaRepository<NexaSocialStateSnapshotEntity, Long> {
    fun findByGuildPseudonymAndMemberPseudonym(
        guildPseudonym: String,
        memberPseudonym: String,
    ): NexaSocialStateSnapshotEntity?

    fun findByGuildPseudonym(guildPseudonym: String): List<NexaSocialStateSnapshotEntity>
}

/**
 * 관찰된 결과 코드 카운트 JPA 엔티티(T018). (snapshot_id, outcome_code) 유니크 — 코드당 1행. 닫힌 코드·카운트만
 * (원문/심리 라벨 없음). snapshot 삭제 시 FK CASCADE 로 함께 제거된다(삭제 전파).
 */
@Entity
@Table(name = "nexa_social_outcome_count")
class NexaSocialOutcomeCountEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "snapshot_id") var snapshotId: Long = 0,
    @Column(name = "outcome_code") var outcomeCode: String = "",
    @Column(name = "count") var count: Int = 0,
) {
    override fun toString(): String = "NexaSocialOutcomeCountEntity(snapshotId=$snapshotId, outcomeCode=$outcomeCode, count=$count)"
}

interface NexaSocialOutcomeCountRepository : JpaRepository<NexaSocialOutcomeCountEntity, Long> {
    fun findBySnapshotId(snapshotId: Long): List<NexaSocialOutcomeCountEntity>

    fun findBySnapshotIdAndOutcomeCode(
        snapshotId: Long,
        outcomeCode: String,
    ): NexaSocialOutcomeCountEntity?

    fun deleteBySnapshotId(snapshotId: Long)
}
