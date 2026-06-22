package com.discordassistant.central.participation.adapter.outbound.persistence

import com.discordassistant.central.participation.application.port.out.ShadowModeState
import com.discordassistant.central.participation.application.port.out.ShadowModeStorePort
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import com.discordassistant.central.participation.domain.model.shadow.ShadowModeAudit
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * [ShadowModeStorePort] 의 JPA 구현 어댑터(NEXA-P09-T007, Flyway V60). 길드별 현재 shadow 단계(길드당 1행)와
 * 전이 audit(append-only)를 영속화한다.
 *
 * **기본값 OFF(acceptance T007)**: [currentMode] 는 행이 없으면 [ShadowMode.OFF] 를 돌려준다. [applyTransition]
 * 은 현재 단계 행을 upsert 하고 audit 한 건을 insert 한다(전이마다 누가·언제·무엇에서·무엇으로·왜 기록).
 *
 * 원문 비저장: 가명·안정 코드·사유 텍스트(운영 기록)만. snowflake/토큰 원문 비저장.
 */
@Repository
class JpaShadowModeStore(
    private val modes: NexaShadowModeRepository,
    private val audits: NexaShadowModeAuditRepository,
) : ShadowModeStorePort {
    @Transactional(readOnly = true)
    override fun currentMode(guildPseudonym: String): ShadowMode =
        modes.findByGuildPseudonym(guildPseudonym)?.let { ShadowMode.valueOf(it.mode) } ?: ShadowMode.OFF

    @Transactional
    override fun applyTransition(audit: ShadowModeAudit) {
        val entity =
            modes.findByGuildPseudonym(audit.guildPseudonym)
                ?: NexaShadowModeEntity(guildPseudonym = audit.guildPseudonym)
        entity.mode = audit.to.name
        entity.updatedAt = audit.at
        entity.updatedBy = audit.actorId
        modes.save(entity)
        audits.save(
            NexaShadowModeAuditEntity(
                guildPseudonym = audit.guildPseudonym,
                actorId = audit.actorId,
                fromMode = audit.from.name,
                toMode = audit.to.name,
                reason = audit.reason,
                enabledRealSend = audit.enabledRealSend,
                at = audit.at,
            ),
        )
    }

    @Transactional(readOnly = true)
    override fun auditTrail(guildPseudonym: String): List<ShadowModeAudit> =
        audits.findByGuildPseudonymOrderByAtDesc(guildPseudonym).map {
            ShadowModeAudit(
                guildPseudonym = it.guildPseudonym,
                actorId = it.actorId,
                from = ShadowMode.valueOf(it.fromMode),
                to = ShadowMode.valueOf(it.toMode),
                reason = it.reason,
                enabledRealSend = it.enabledRealSend,
                at = it.at,
            )
        }

    @Transactional(readOnly = true)
    override fun listModes(): List<ShadowModeState> =
        modes.findAll().map {
            ShadowModeState(guildPseudonym = it.guildPseudonym, mode = ShadowMode.valueOf(it.mode), updatedAt = it.updatedAt)
        }
}

/** 길드별 현재 shadow 단계(길드당 1행). */
@Entity
@Table(name = "nexa_shadow_mode")
class NexaShadowModeEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "guild_pseudonym") var guildPseudonym: String = "",
    @Column(name = "mode") var mode: String = ShadowMode.OFF.name,
    @Column(name = "updated_at") var updatedAt: java.time.Instant = java.time.Instant.EPOCH,
    @Column(name = "updated_by") var updatedBy: String = "",
) {
    override fun toString(): String = "NexaShadowModeEntity(guildPseudonym=$guildPseudonym, mode=$mode)"
}

/** 단계 전이 audit(append-only). */
@Entity
@Table(name = "nexa_shadow_mode_audit")
class NexaShadowModeAuditEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "guild_pseudonym") var guildPseudonym: String = "",
    @Column(name = "actor_id") var actorId: String = "",
    @Column(name = "from_mode") var fromMode: String = "",
    @Column(name = "to_mode") var toMode: String = "",
    @Column(name = "reason") var reason: String = "",
    @Column(name = "enabled_real_send") var enabledRealSend: Boolean = false,
    @Column(name = "at") var at: java.time.Instant = java.time.Instant.EPOCH,
) {
    override fun toString(): String = "NexaShadowModeAuditEntity(guildPseudonym=$guildPseudonym, from=$fromMode, to=$toMode, at=$at)"
}

interface NexaShadowModeRepository : JpaRepository<NexaShadowModeEntity, Long> {
    fun findByGuildPseudonym(guildPseudonym: String): NexaShadowModeEntity?
}

interface NexaShadowModeAuditRepository : JpaRepository<NexaShadowModeAuditEntity, Long> {
    fun findByGuildPseudonymOrderByAtDesc(guildPseudonym: String): List<NexaShadowModeAuditEntity>
}
