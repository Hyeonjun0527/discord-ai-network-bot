package com.discordassistant.central.channelai.adapter.outbound.persistence

import com.discordassistant.central.channelai.domain.model.ProposalStatus
import com.discordassistant.central.global.crypto.EncryptedStringConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
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
import java.time.Instant

/**
 * channel-ai 도메인 JPA(adapter/out): 채널 AI(channel_ai)·행동 버전(ai_behavior_version)·변경 제안
 * (ai_change_proposal)·커스터마이징 감사(customization_audit_log). 스키마는 Flyway 소유.
 * 동시성 불변식: behavior version 채번·제안 승인은 PESSIMISTIC_WRITE(findByIdForUpdate)로 직렬화한다.
 */

@Entity
@Table(name = "channel_ai")
class ChannelAiEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var channelId: Long = 0,
    @Column(name = "display_name") var displayName: String = "니아",
    @Column(name = "avatar_url") var avatarUrl: String? = null,
    @Column(name = "active_behavior_version_id") var activeBehaviorVersionId: Long? = null,
    @Column(name = "auto_respond") var autoRespond: Boolean = false,
    var source: String = "manual",
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "ai_behavior_version")
class AiBehaviorVersionEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var channelAiId: Long = 0,
    var version: Int = 1,
    var purpose: String = "general_assistant",
    var tone: String = "friendly",
    var answerLength: String = "balanced",
    @Convert(converter = EncryptedStringConverter::class) var constitution: String? = null,
    var safetyLevel: String = "standard",
    @Column(name = "custom_instruction") @Convert(converter = EncryptedStringConverter::class) var customInstruction: String? = null,
    var createdBy: Long? = null,
    var createdAt: Instant = Instant.EPOCH,
    var changeSummary: String? = null,
)

@Entity
@Table(name = "ai_change_proposal")
class AiChangeProposalEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var channelId: Long = 0,
    var channelAiId: Long? = null,
    var proposedBehaviorId: Long? = null,
    @Convert(converter = ProposalStatusConverter::class)
    var status: ProposalStatus = ProposalStatus.APPROVED,
    var requestedBy: Long? = null,
    var reviewedBy: Long? = null,
    var reason: String? = null,
    @Column(name = "payload_hash") var payloadHash: String? = null,
    @Column(name = "routing_snapshot", length = 2000) var routingSnapshot: String? = null,
    var createdAt: Instant = Instant.EPOCH,
    var reviewedAt: Instant? = null,
)

@Entity
@Table(name = "customization_audit_log")
class CustomizationAuditLogEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var channelId: Long = 0,
    var actorId: Long? = null,
    var action: String = "",
    var targetType: String = "",
    var targetId: Long? = null,
    var summary: String? = null,
    var createdAt: Instant = Instant.EPOCH,
)

interface ChannelAiRepository : JpaRepository<ChannelAiEntity, Long> {
    fun findByGuildId(guildId: Long): List<ChannelAiEntity>

    /** 자동응답이 켜진 채널 id 목록(인메모리 캐시 로드용 — 한 길드당 1회 조회로 핫패스 DB 조회 회피). */
    fun findByGuildIdAndAutoRespondTrue(guildId: Long): List<ChannelAiEntity>

    fun findByGuildIdAndChannelId(
        guildId: Long,
        channelId: Long,
    ): ChannelAiEntity?

    /**
     * 채널 AI 행을 PESSIMISTIC_WRITE 로 잠근 채 조회한다(트랜잭션 필요).
     * behavior version 채번(`MAX(version)+1`)을 같은 채널 안에서 직렬화해
     * `uk_ai_behavior_version` 유니크 위반 race(동시 두 요청이 같은 version 으로 insert)를 막는 데 쓴다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ChannelAiEntity c where c.id = :id")
    fun findByIdForUpdate(
        @Param("id") id: Long,
    ): ChannelAiEntity?

    fun deleteByGuildIdAndChannelId(
        guildId: Long,
        channelId: Long,
    )

    fun deleteByGuildId(guildId: Long)
}

interface AiBehaviorVersionRepository : JpaRepository<AiBehaviorVersionEntity, Long> {
    fun findTopByChannelAiIdOrderByVersionDesc(channelAiId: Long): AiBehaviorVersionEntity?

    fun findByChannelAiIdAndId(
        channelAiId: Long,
        id: Long,
    ): AiBehaviorVersionEntity?

    fun findByChannelAiIdOrderByVersionDesc(channelAiId: Long): List<AiBehaviorVersionEntity>

    fun deleteByChannelAiId(channelAiId: Long)
}

interface AiChangeProposalRepository : JpaRepository<AiChangeProposalEntity, Long> {
    fun findByGuildIdAndStatus(
        guildId: Long,
        status: ProposalStatus,
    ): List<AiChangeProposalEntity>

    /**
     * 제안 행을 PESSIMISTIC_WRITE 로 잠근 채 조회한다(트랜잭션 필요).
     * 동시 승인/거절(`approveProposal`/`rejectProposal`)을 직렬화해 이중 APPROVED·lost update 를 막는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from AiChangeProposalEntity p where p.id = :id")
    fun findByIdForUpdate(
        @Param("id") id: Long,
    ): AiChangeProposalEntity?

    fun findByGuildIdOrderByCreatedAtDesc(guildId: Long): List<AiChangeProposalEntity>

    fun findByGuildIdAndChannelIdOrderByCreatedAtDesc(
        guildId: Long,
        channelId: Long,
    ): List<AiChangeProposalEntity>

    fun deleteByGuildId(guildId: Long)

    fun deleteByChannelAiId(channelAiId: Long)
}

interface CustomizationAuditLogRepository : JpaRepository<CustomizationAuditLogEntity, Long> {
    fun findTop10ByGuildIdAndChannelIdOrderByCreatedAtDesc(
        guildId: Long,
        channelId: Long,
    ): List<CustomizationAuditLogEntity>

    fun deleteByGuildId(guildId: Long)

    fun deleteByGuildIdAndChannelId(
        guildId: Long,
        channelId: Long,
    )
}
