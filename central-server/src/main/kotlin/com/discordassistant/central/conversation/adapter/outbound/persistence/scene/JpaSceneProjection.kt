package com.discordassistant.central.conversation.adapter.outbound.persistence.scene

import com.discordassistant.central.conversation.application.port.out.SceneProjectionPort
import com.discordassistant.central.conversation.application.port.out.SceneSnapshotRecord
import com.discordassistant.central.conversation.application.port.out.SceneVersionRecord
import com.discordassistant.central.conversation.domain.model.scene.ConversationScene
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * [SceneProjectionPort] 의 JPA 구현 어댑터(NEXA-P05-T020). 현재 장면 snapshot(채널당 1행)과 version history(sceneSeq
 * 마다 1행)의 최소 메타데이터를 영속화한다(Flyway V54).
 *
 * **upsert 멱등(acceptance T020)**: [save] 는 channel_id 유니크로 처음이면 insert, 있으면 갱신한다 — 같은 채널을
 * N 번 저장해도 snapshot 한 행으로 수렴한다. history 는 (channel_id, scene_seq) 유니크라 같은 순번 재저장도 한 행.
 *
 * **snapshot 삭제·재구축(acceptance T020)**: [deleteAll] 로 두 테이블을 비운 뒤 event store 를 재생하며 [save] 를
 * 다시 호출하면 동일 snapshot 이 재구축된다 — 이 테이블은 event store 의 파생 읽기 모델이라 원천이 아니다.
 *
 * 원문 비저장(logging-boundary.md): Discord ID는 projection 전용 keyed pseudonym으로 바꾸고, 수·버전·순번 같은
 * 요약 메타만 보관한다.
 */
@Repository
class JpaSceneProjection(
    private val snapshots: NexaSceneSnapshotRepository,
    private val versions: NexaSceneVersionRepository,
) : SceneProjectionPort {
    @Transactional
    override fun save(scene: ConversationScene) {
        val channelId = ScenePersistenceIdentity.channel(scene.channelId.value)
        val entity = snapshots.findByChannelIdForUpdate(channelId) ?: NexaSceneSnapshotEntity(channelId = channelId)
        if (scene.sceneSeq >= entity.sceneSeq) {
            entity.guildId = ScenePersistenceIdentity.guild(scene.guildId.value)
            entity.sceneSeq = scene.sceneSeq
            entity.contextVersion = maxOf(entity.contextVersion, scene.contextVersion.value)
            entity.recentBurstCount = scene.recentBurstIds.size
            entity.activeThreadCount = scene.activeThreadIds.size
            entity.participantCount = scene.participants.size
            snapshots.save(entity)
        }

        // version history: (channel_id, scene_seq) 유니크 upsert — 같은 순번 재생도 한 행.
        val versionEntity =
            versions.findByChannelIdAndSceneSeq(channelId, scene.sceneSeq)
                ?: NexaSceneVersionEntity(channelId = channelId, sceneSeq = scene.sceneSeq)
        versionEntity.contextVersion = scene.contextVersion.value
        versions.save(versionEntity)
    }

    @Transactional(readOnly = true)
    override fun findByChannel(channelId: Long): SceneSnapshotRecord? =
        snapshots.findByChannelId(ScenePersistenceIdentity.channel(channelId))?.toRecord()

    @Transactional(readOnly = true)
    override fun history(channelId: Long): List<SceneVersionRecord> =
        versions.findByChannelIdOrderBySceneSeqAsc(ScenePersistenceIdentity.channel(channelId)).map {
            SceneVersionRecord(channelId = it.channelId, sceneSeq = it.sceneSeq, contextVersion = it.contextVersion)
        }

    @Transactional
    override fun deleteAll() {
        versions.deleteAllInBatch()
        snapshots.deleteAllInBatch()
    }

    private fun NexaSceneSnapshotEntity.toRecord(): SceneSnapshotRecord =
        SceneSnapshotRecord(
            guildId = guildId,
            channelId = channelId,
            sceneSeq = sceneSeq,
            contextVersion = contextVersion,
            recentBurstCount = recentBurstCount,
            activeThreadCount = activeThreadCount,
            participantCount = participantCount,
        )
}

/**
 * 장면 snapshot JPA 엔티티(T020). channel_id 유니크로 멱등 upsert — 채널당 현재 장면 1행. 원문을 담지 않는다
 * (식별자 수·버전·순번만). [toString] 을 메타데이터만 노출하도록 오버라이드한다(logging-boundary.md).
 */
@Entity
@Table(name = "nexa_scene_snapshot")
class NexaSceneSnapshotEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "channel_id") var channelId: Long = 0,
    @Column(name = "guild_id") var guildId: Long = 0,
    @Column(name = "scene_seq") var sceneSeq: Long = 0,
    @Column(name = "context_version") var contextVersion: Long = 0,
    @Column(name = "recent_burst_count") var recentBurstCount: Int = 0,
    @Column(name = "active_thread_count") var activeThreadCount: Int = 0,
    @Column(name = "participant_count") var participantCount: Int = 0,
) {
    override fun toString(): String = "NexaSceneSnapshotEntity(channelId=$channelId, sceneSeq=$sceneSeq, contextVersion=$contextVersion)"
}

interface NexaSceneSnapshotRepository : JpaRepository<NexaSceneSnapshotEntity, Long> {
    fun findByChannelId(channelId: Long): NexaSceneSnapshotEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from NexaSceneSnapshotEntity s where s.channelId = :channelId")
    fun findByChannelIdForUpdate(
        @Param("channelId") channelId: Long,
    ): NexaSceneSnapshotEntity?
}

/**
 * 장면 version history JPA 엔티티(T020). (channel_id, scene_seq) 유니크 — 각 순번의 contextVersion 메타만.
 * snapshot 삭제 후 재구축·감사 입력.
 */
@Entity
@Table(name = "nexa_scene_version")
class NexaSceneVersionEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "channel_id") var channelId: Long = 0,
    @Column(name = "scene_seq") var sceneSeq: Long = 0,
    @Column(name = "context_version") var contextVersion: Long = 0,
    @Column(name = "observation_ref") var observationRef: String? = null,
) {
    override fun toString(): String = "NexaSceneVersionEntity(channelId=$channelId, sceneSeq=$sceneSeq, contextVersion=$contextVersion)"
}

interface NexaSceneVersionRepository : JpaRepository<NexaSceneVersionEntity, Long> {
    fun findByChannelIdAndSceneSeq(
        channelId: Long,
        sceneSeq: Long,
    ): NexaSceneVersionEntity?

    fun findByChannelIdOrderBySceneSeqAsc(channelId: Long): List<NexaSceneVersionEntity>

    fun findByObservationRef(observationRef: String): NexaSceneVersionEntity?
}
