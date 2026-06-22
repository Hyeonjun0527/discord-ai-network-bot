package com.discordassistant.central.actionruntime.adapter.outbound.persistence

import com.discordassistant.central.actionruntime.application.port.out.ChannelMuteAction
import com.discordassistant.central.actionruntime.application.port.out.ChannelMuteAuditEvent
import com.discordassistant.central.actionruntime.application.port.out.ChannelMuteStorePort
import com.discordassistant.central.actionruntime.domain.ChannelMuteLevel
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
 * [ChannelMuteStorePort] 의 JPA 구현(NEXA-P18-T014, Flyway V68).
 *
 * 현재 mute 상태(`nexa_channel_mute`, 채널 가명 당 1행 — 수준 토글)와 발동/해제 audit(`nexa_channel_mute_audit`,
 * append-only)를 영속화한다. [activeMutes] 가 결정 코어의 SSOT 라, [mute]/[unmute] 직후 다음 조회가 즉시 변경을
 * 본다(acceptance T014 — 즉시 발효). [ChannelMuteLevel.NONE] 행은 저장하지 않는다(해제 = 행 삭제).
 *
 * 순수성: application/도메인 타입 ↔ entity 매핑만. 도메인은 이 어댑터를 모른다(헥사고날).
 */
@Repository
class JpaChannelMuteStore(
    private val stateRepo: ChannelMuteStateRepository,
    private val auditRepo: ChannelMuteAuditRepository,
) : ChannelMuteStorePort {
    @Transactional(readOnly = true)
    override fun activeMutes(): Map<String, ChannelMuteLevel> =
        stateRepo.findAll().associate { it.channelPseudonym to ChannelMuteLevel.valueOf(it.level) }

    @Transactional
    override fun mute(
        channelPseudonym: String,
        level: ChannelMuteLevel,
        actor: String,
        reason: String,
        cancelledPending: Int,
        at: Instant,
    ) {
        upsertLevel(channelPseudonym, level, at)
        auditRepo.save(
            ChannelMuteAuditEntity(
                channelPseudonym = channelPseudonym,
                action = ChannelMuteAction.MUTE.name,
                level = level.name,
                actor = actor,
                reason = reason,
                cancelledPending = cancelledPending,
                occurredAt = at,
            ),
        )
    }

    @Transactional
    override fun unmute(
        channelPseudonym: String,
        actor: String,
        at: Instant,
    ) {
        // 해제 = 활성 행 삭제(NONE 은 저장하지 않는다 — 행 없음 = mute 없음).
        stateRepo.findByChannelPseudonym(channelPseudonym)?.let { stateRepo.delete(it) }
        auditRepo.save(
            ChannelMuteAuditEntity(
                channelPseudonym = channelPseudonym,
                action = ChannelMuteAction.UNMUTE.name,
                level = ChannelMuteLevel.NONE.name,
                actor = actor,
                reason = "",
                cancelledPending = 0,
                occurredAt = at,
            ),
        )
    }

    @Transactional(readOnly = true)
    override fun auditFor(channelPseudonym: String): List<ChannelMuteAuditEvent> =
        auditRepo.findByChannelPseudonymOrderByOccurredAtAscIdAsc(channelPseudonym).map { it.toDomain() }

    private fun upsertLevel(
        channelPseudonym: String,
        level: ChannelMuteLevel,
        at: Instant,
    ) {
        val existing = stateRepo.findByChannelPseudonym(channelPseudonym)
        if (existing == null) {
            stateRepo.save(ChannelMuteStateEntity(channelPseudonym = channelPseudonym, level = level.name, updatedAt = at))
        } else {
            existing.level = level.name
            existing.updatedAt = at
            stateRepo.save(existing)
        }
    }

    private fun ChannelMuteAuditEntity.toDomain(): ChannelMuteAuditEvent =
        ChannelMuteAuditEvent(
            channelPseudonym = channelPseudonym,
            action = ChannelMuteAction.entries.first { it.name == action },
            level = ChannelMuteLevel.valueOf(level),
            actor = actor,
            reason = reason,
            cancelledPending = cancelledPending,
            at = occurredAt,
        )
}

/** 채널 mute 현재 상태 엔티티(`nexa_channel_mute`, 채널 가명 당 1행 — NONE 은 저장 안 함). */
@Entity
@Table(name = "nexa_channel_mute")
class ChannelMuteStateEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "channel_pseudonym") var channelPseudonym: String = "",
    @Column(name = "level") var level: String = "",
    @Column(name = "updated_at") var updatedAt: Instant = Instant.EPOCH,
)

/** 채널 mute 발동/해제 audit 엔티티(`nexa_channel_mute_audit`, append-only). */
@Entity
@Table(name = "nexa_channel_mute_audit")
class ChannelMuteAuditEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "channel_pseudonym") var channelPseudonym: String = "",
    @Column(name = "action") var action: String = "",
    @Column(name = "level") var level: String = "",
    @Column(name = "actor") var actor: String = "",
    @Column(name = "reason") var reason: String = "",
    @Column(name = "cancelled_pending") var cancelledPending: Int = 0,
    @Column(name = "occurred_at") var occurredAt: Instant = Instant.EPOCH,
)

interface ChannelMuteStateRepository : JpaRepository<ChannelMuteStateEntity, Long> {
    fun findByChannelPseudonym(channelPseudonym: String): ChannelMuteStateEntity?
}

interface ChannelMuteAuditRepository : JpaRepository<ChannelMuteAuditEntity, Long> {
    /** 한 채널의 audit 사건을 occurred_at(동시각이면 삽입순 id) 오름차순 — 생애 재구성. */
    fun findByChannelPseudonymOrderByOccurredAtAscIdAsc(channelPseudonym: String): List<ChannelMuteAuditEntity>
}
