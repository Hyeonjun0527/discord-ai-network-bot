package com.discordassistant.central.conversation.adapter.outbound.persistence.burst

import com.discordassistant.central.conversation.application.port.out.BurstProjectionPort
import com.discordassistant.central.conversation.application.port.out.BurstProjectionRecord
import com.discordassistant.central.conversation.domain.model.burst.BurstId
import com.discordassistant.central.conversation.domain.model.burst.BurstStatus
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
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
 * [BurstProjectionPort] 의 JPA 구현 어댑터(NEXA-P04-T018). 버스트 projection(burstId↔fragment 연결·revision·
 * segmentation version·상태)을 영속화한다(Flyway V53).
 *
 * **upsert 멱등(acceptance T018)**: [save] 는 burst_id 유니크로 처음이면 insert, 있으면 갱신한다 — 같은 버스트를
 * N 번 저장해도 한 행으로 수렴한다(replay 재구축 안전).
 *
 * **replay 삭제·재구축(acceptance T018)**: [deleteAll] 로 두 테이블을 비운 뒤 event store 를 재생하며 [save] 를 다시
 * 호출하면 동일 projection 이 재구축된다 — 이 테이블은 event store 의 파생 읽기 모델이라 원천이 아니다.
 *
 * 원문 비저장(logging-boundary.md): fragment 는 message_id·ordinal·scrubbed 표식만 — combined text 가 아니라
 * 식별자 연결만 보관한다(원문은 event store content_cipher 에만).
 */
@Repository
class JpaBurstProjection(
    private val bursts: NexaBurstRepository,
    private val fragments: NexaBurstFragmentRepository,
) : BurstProjectionPort {
    @Transactional
    override fun save(
        burst: UtteranceBurst,
        revision: Long,
        segmentationVersion: Int,
        scrubbedMessageIds: Set<Long>,
    ) {
        val key = burst.burstId.value
        val entity = bursts.findByBurstId(key) ?: NexaBurstEntity(burstId = key)
        entity.guildId = burst.guildId.value
        entity.authorId = burst.authorId.value
        entity.channelId = burst.location.channelId.value
        entity.threadId = burst.location.threadId?.value
        entity.status = burst.status.name
        entity.segmentationVersion = segmentationVersion
        entity.revision = revision
        entity.startedAt = burst.startedAt
        entity.lastFragmentAt = burst.lastFragmentAt
        bursts.save(entity)

        // fragment 연결은 매 save 마다 재구축(교정으로 ordinal·scrubbed 가 바뀔 수 있어 가장 단순·정확).
        // 같은 트랜잭션 안에서 DELETE 가 INSERT 보다 먼저 DB 에 닿도록 flush — (burst_id, message_id) 유니크
        // 재삽입 충돌(delete-then-insert flush 순서 문제) 방지.
        fragments.deleteByBurstId(key)
        fragments.flush()
        burst.fragments.forEachIndexed { ordinal, fragment ->
            fragments.save(
                NexaBurstFragmentEntity(
                    burstId = key,
                    messageId = fragment.messageId.value,
                    ordinal = ordinal,
                    scrubbed = fragment.messageId.value in scrubbedMessageIds,
                ),
            )
        }
    }

    @Transactional(readOnly = true)
    override fun findById(burstId: BurstId): BurstProjectionRecord? = bursts.findByBurstId(burstId.value)?.toRecord()

    @Transactional(readOnly = true)
    override fun findByStatus(status: BurstStatus): List<BurstProjectionRecord> =
        bursts.findByStatusOrderByLastFragmentAtAsc(status.name).map { it.toRecord() }

    @Transactional
    override fun deleteAll() {
        fragments.deleteAllInBatch()
        bursts.deleteAllInBatch()
    }

    private fun NexaBurstEntity.toRecord(): BurstProjectionRecord {
        val links = fragments.findByBurstIdOrderByOrdinalAsc(burstId)
        return BurstProjectionRecord(
            burstId = BurstId(burstId),
            guildId = guildId,
            authorId = authorId,
            channelId = channelId,
            threadId = threadId,
            status = BurstStatus.valueOf(status),
            segmentationVersion = segmentationVersion,
            revision = revision,
            startedAtEpochMs = startedAt.toEpochMilli(),
            lastFragmentAtEpochMs = lastFragmentAt.toEpochMilli(),
            fragmentMessageIds = links.map { it.messageId },
            scrubbedMessageIds = links.filter { it.scrubbed }.map { it.messageId }.toSet(),
        )
    }
}

/**
 * 버스트 projection JPA 엔티티(T018). burst_id 유니크로 멱등 upsert. 원문을 담지 않는다 — 식별자·상태·메타만.
 * data class 가 아니며 [toString] 을 메타데이터만 노출하도록 오버라이드한다(logging-boundary.md).
 */
@Entity
@Table(name = "nexa_burst")
class NexaBurstEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "burst_id") var burstId: String = "",
    @Column(name = "guild_id") var guildId: Long = 0,
    @Column(name = "author_id") var authorId: Long = 0,
    @Column(name = "channel_id") var channelId: Long = 0,
    @Column(name = "thread_id") var threadId: Long? = null,
    @Column(name = "status") var status: String = BurstStatus.OPEN.name,
    @Column(name = "segmentation_version") var segmentationVersion: Int = 0,
    @Column(name = "revision") var revision: Long = 0,
    @Column(name = "started_at") var startedAt: Instant = Instant.EPOCH,
    @Column(name = "last_fragment_at") var lastFragmentAt: Instant = Instant.EPOCH,
) {
    override fun toString(): String = "NexaBurstEntity(burstId=$burstId, status=$status, segmentationVersion=$segmentationVersion)"
}

interface NexaBurstRepository : JpaRepository<NexaBurstEntity, Long> {
    fun findByBurstId(burstId: String): NexaBurstEntity?

    fun findByStatusOrderByLastFragmentAtAsc(status: String): List<NexaBurstEntity>
}

/**
 * 버스트↔fragment 연결 JPA 엔티티(T018). (burst_id, message_id) 유니크. 원문 없이 message_id·ordinal·scrubbed 표식만.
 */
@Entity
@Table(name = "nexa_burst_fragment")
class NexaBurstFragmentEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "burst_id") var burstId: String = "",
    @Column(name = "message_id") var messageId: Long = 0,
    @Column(name = "ordinal") var ordinal: Int = 0,
    @Column(name = "scrubbed") var scrubbed: Boolean = false,
)

interface NexaBurstFragmentRepository : JpaRepository<NexaBurstFragmentEntity, Long> {
    fun findByBurstIdOrderByOrdinalAsc(burstId: String): List<NexaBurstFragmentEntity>

    fun deleteByBurstId(burstId: String)
}
