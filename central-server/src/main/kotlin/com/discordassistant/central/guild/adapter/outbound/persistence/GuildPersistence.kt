package com.discordassistant.central.guild.adapter.outbound.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * guild 도메인 JPA(adapter/out): 길드 설정(guild)·허용 채널(allowed_channel)·역할 정책(role_policy)·
 * AI 관리자 역할(ai_admin_role). 스키마는 Flyway 소유(ddl-auto=none). 엔티티명 불변 → JPQL/스캔 영향 없음.
 */

@Entity
@Table(name = "guild")
class GuildEntity(
    @Id var id: Long = 0, // Discord guild_id
    @Column(name = "privacy_mode") var privacyMode: String = "C_ADMIN_ONLY",
    @Column(name = "auto_approve") var autoApprove: Boolean = true, // 기본 자동 승인(유입 마찰 최소화). /서버기본값·설정으로 끌 수 있음
    @Column(name = "default_model") var defaultModel: String? = null,
    @Column(name = "language") var language: String = "ko",
    @Column(name = "welcome_message") var welcomeMessage: String? = null,
    // 유저별 일일 사용 한도(요청자 쿼터). null=기본(20), 0=무제한. 역할 정책(role daily limit)과 별개의 길드 기본값.
    @Column(name = "default_daily_limit") var defaultDailyLimit: Int? = null,
    // 레거시: ComfyUI 웹 생성물 자동 전송 채널. 현재 안전 정책상 central 에서 사용하지 않는다.
    @Column(name = "expert_forward_channel_id") var expertForwardChannelId: Long? = null,
)

@Entity
@Table(name = "allowed_channel")
class AllowedChannelEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var channelId: Long = 0,
)

@Entity
@Table(name = "role_policy")
class RolePolicyEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var roleId: Long = 0,
    var maxBurden: String = "LIGHT",
    var dailyLimit: Int = 0,
)

@Entity
@Table(name = "ai_admin_role")
class AiAdminRoleEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var roleId: Long = 0,
    var createdBy: Long? = null,
    var createdAt: Instant = Instant.EPOCH,
)

interface GuildRepository : JpaRepository<GuildEntity, Long> {
    /**
     * privacy_mode 컬럼만 targeted UPDATE 한다(다른 컬럼 미변경). 엔티티를 통째로 load-modify-save 하면
     * stale 스냅샷이 동시에 쓰인 auto_approve·기본값 등을 되돌리는 lost update 가 발생하므로, 이 좁은 갱신으로
     * 그 컬럼만 건드린다. 대상 행이 없으면 0 을 반환한다(호출자가 신규 생성).
     */
    @Modifying
    @Query("update GuildEntity g set g.privacyMode = :mode where g.id = :guildId")
    fun updatePrivacyMode(
        @Param("guildId") guildId: Long,
        @Param("mode") mode: String,
    ): Int
}

interface AllowedChannelRepository : JpaRepository<AllowedChannelEntity, Long> {
    fun findByGuildId(guildId: Long): List<AllowedChannelEntity>

    fun deleteByGuildId(guildId: Long)

    fun existsByGuildIdAndChannelId(
        guildId: Long,
        channelId: Long,
    ): Boolean

    fun deleteByGuildIdAndChannelId(
        guildId: Long,
        channelId: Long,
    )
}

interface RolePolicyRepository : JpaRepository<RolePolicyEntity, Long> {
    fun findByGuildId(guildId: Long): List<RolePolicyEntity>

    fun deleteByGuildId(guildId: Long)

    fun findByGuildIdAndRoleId(
        guildId: Long,
        roleId: Long,
    ): RolePolicyEntity?
}

interface AiAdminRoleRepository : JpaRepository<AiAdminRoleEntity, Long> {
    fun findByGuildId(guildId: Long): List<AiAdminRoleEntity>

    fun existsByGuildIdAndRoleId(
        guildId: Long,
        roleId: Long,
    ): Boolean

    fun deleteByGuildId(guildId: Long)
}
