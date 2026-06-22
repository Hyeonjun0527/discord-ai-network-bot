package com.discordassistant.central.actionruntime.adapter.outbound.persistence

import com.discordassistant.central.actionruntime.application.port.out.GuildKillSwitchAction
import com.discordassistant.central.actionruntime.application.port.out.GuildKillSwitchAuditEvent
import com.discordassistant.central.actionruntime.application.port.out.GuildKillSwitchStorePort
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
 * [GuildKillSwitchStorePort] 의 JPA 구현(NEXA-P18-T013, Flyway V67).
 *
 * 현재 kill 상태(`nexa_guild_kill_switch`, 길드 가명 당 1행 토글)와 발동/해제 audit(`nexa_guild_kill_switch_audit`,
 * append-only)를 영속화한다. [activeKilledGuilds] 가 결정 코어의 SSOT 라, [engage]/[disengage] 직후 다음 조회가
 * 즉시 변경을 본다(acceptance T013 — 즉시 발효).
 *
 * 순수성: application/도메인 타입 ↔ entity 매핑만. 도메인은 이 어댑터를 모른다(헥사고날).
 */
@Repository
class JpaGuildKillSwitchStore(
    private val stateRepo: GuildKillSwitchStateRepository,
    private val auditRepo: GuildKillSwitchAuditRepository,
) : GuildKillSwitchStorePort {
    @Transactional(readOnly = true)
    override fun activeKilledGuilds(): Set<String> = stateRepo.findByActiveTrue().map { it.guildPseudonym }.toSet()

    @Transactional
    override fun engage(
        guildPseudonym: String,
        actor: String,
        reason: String,
        cancelledPending: Int,
        at: Instant,
    ) {
        upsertActive(guildPseudonym, active = true, at = at)
        auditRepo.save(
            GuildKillSwitchAuditEntity(
                guildPseudonym = guildPseudonym,
                action = GuildKillSwitchAction.ENGAGE.name,
                actor = actor,
                reason = reason,
                cancelledPending = cancelledPending,
                occurredAt = at,
            ),
        )
    }

    @Transactional
    override fun disengage(
        guildPseudonym: String,
        actor: String,
        at: Instant,
    ) {
        upsertActive(guildPseudonym, active = false, at = at)
        auditRepo.save(
            GuildKillSwitchAuditEntity(
                guildPseudonym = guildPseudonym,
                action = GuildKillSwitchAction.DISENGAGE.name,
                actor = actor,
                reason = "",
                cancelledPending = 0,
                occurredAt = at,
            ),
        )
    }

    @Transactional(readOnly = true)
    override fun auditFor(guildPseudonym: String): List<GuildKillSwitchAuditEvent> =
        auditRepo.findByGuildPseudonymOrderByOccurredAtAscIdAsc(guildPseudonym).map { it.toDomain() }

    private fun upsertActive(
        guildPseudonym: String,
        active: Boolean,
        at: Instant,
    ) {
        val existing = stateRepo.findByGuildPseudonym(guildPseudonym)
        if (existing == null) {
            stateRepo.save(GuildKillSwitchStateEntity(guildPseudonym = guildPseudonym, active = active, updatedAt = at))
        } else {
            existing.active = active
            existing.updatedAt = at
            stateRepo.save(existing)
        }
    }

    private fun GuildKillSwitchAuditEntity.toDomain(): GuildKillSwitchAuditEvent =
        GuildKillSwitchAuditEvent(
            guildPseudonym = guildPseudonym,
            action = GuildKillSwitchAction.entries.first { it.name == action },
            actor = actor,
            reason = reason,
            cancelledPending = cancelledPending,
            at = occurredAt,
        )
}

/** kill switch 현재 상태 엔티티(`nexa_guild_kill_switch`, 길드 가명 당 1행). */
@Entity
@Table(name = "nexa_guild_kill_switch")
class GuildKillSwitchStateEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "guild_pseudonym") var guildPseudonym: String = "",
    @Column(name = "active") var active: Boolean = false,
    @Column(name = "updated_at") var updatedAt: Instant = Instant.EPOCH,
)

/** kill switch 발동/해제 audit 엔티티(`nexa_guild_kill_switch_audit`, append-only). */
@Entity
@Table(name = "nexa_guild_kill_switch_audit")
class GuildKillSwitchAuditEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "guild_pseudonym") var guildPseudonym: String = "",
    @Column(name = "action") var action: String = "",
    @Column(name = "actor") var actor: String = "",
    @Column(name = "reason") var reason: String = "",
    @Column(name = "cancelled_pending") var cancelledPending: Int = 0,
    @Column(name = "occurred_at") var occurredAt: Instant = Instant.EPOCH,
)

interface GuildKillSwitchStateRepository : JpaRepository<GuildKillSwitchStateEntity, Long> {
    fun findByActiveTrue(): List<GuildKillSwitchStateEntity>

    fun findByGuildPseudonym(guildPseudonym: String): GuildKillSwitchStateEntity?
}

interface GuildKillSwitchAuditRepository : JpaRepository<GuildKillSwitchAuditEntity, Long> {
    /** 한 길드의 audit 사건을 occurred_at(동시각이면 삽입순 id) 오름차순 — 생애 재구성. */
    fun findByGuildPseudonymOrderByOccurredAtAscIdAsc(guildPseudonym: String): List<GuildKillSwitchAuditEntity>
}
