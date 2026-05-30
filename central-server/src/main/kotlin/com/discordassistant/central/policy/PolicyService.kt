package com.discordassistant.central.policy

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.persistence.AllowedChannelEntity
import com.discordassistant.central.persistence.AllowedChannelRepository
import com.discordassistant.central.persistence.GuildEntity
import com.discordassistant.central.persistence.GuildRepository
import com.discordassistant.central.persistence.RolePolicyEntity
import com.discordassistant.central.persistence.RolePolicyRepository
import com.discordassistant.central.provider.AuditLog
import com.discordassistant.central.routing.RoutingPolicy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 서버(길드) 정책 (K-차수 7, specs §6/§18). 허용 채널·역할별 허용 모델 수준·승인 방식.
 * 라우팅이 쓰는 부분은 [RoutingPolicy] 로 노출한다.
 */
@Service
class PolicyService(
    private val channels: AllowedChannelRepository,
    private val roles: RolePolicyRepository,
    private val guilds: GuildRepository,
    private val audit: AuditLog,
) : RoutingPolicy {
    // ── 채널 정책 ───────────────────────────────────────────────────────
    @Transactional
    fun allowChannel(
        guildId: Long,
        channelId: Long,
        adminId: Long,
    ) {
        if (!channels.existsByGuildIdAndChannelId(guildId, channelId)) {
            channels.save(AllowedChannelEntity(guildId = guildId, channelId = channelId))
            audit.record("llm_allow_channel", "admin:$adminId", "guild:$guildId", "channel:$channelId")
        }
    }

    @Transactional
    fun denyChannel(
        guildId: Long,
        channelId: Long,
        adminId: Long,
    ) {
        channels.deleteByGuildIdAndChannelId(guildId, channelId)
        audit.record("llm_deny_channel", "admin:$adminId", "guild:$guildId", "channel:$channelId")
    }

    /** 채널이 LLM 사용 허용인가. 허용 채널이 하나도 설정 안 됐으면 제한 없음(true). */
    override fun isChannelAllowed(
        guildId: Long,
        channelId: Long,
    ): Boolean {
        val allowed = channels.findByGuildId(guildId)
        return allowed.isEmpty() || allowed.any { it.channelId == channelId }
    }

    // ── 역할 정책 ───────────────────────────────────────────────────────
    @Transactional
    fun setRolePolicy(
        guildId: Long,
        roleId: Long,
        maxBurden: ModelBurden,
        dailyLimit: Int,
        adminId: Long,
    ) {
        val existing = roles.findByGuildIdAndRoleId(guildId, roleId)
        if (existing != null) {
            existing.maxBurden = maxBurden.name
            existing.dailyLimit = dailyLimit
            roles.save(existing)
        } else {
            roles.save(RolePolicyEntity(guildId = guildId, roleId = roleId, maxBurden = maxBurden.name, dailyLimit = dailyLimit))
        }
        audit.record("llm_role_policy", "admin:$adminId", "guild:$guildId", "role:$roleId $maxBurden/$dailyLimit")
    }

    /** 멤버의 역할들로부터 허용되는 최대 모델 부담 수준(다중 역할 합집합 = 가장 높은 등급). */
    override fun maxAllowedBurden(
        guildId: Long,
        memberRoleIds: Collection<Long>,
    ): ModelBurden {
        val policies = roles.findByGuildId(guildId).filter { it.roleId in memberRoleIds }
        if (policies.isEmpty()) return ModelBurden.LIGHT // 기본(일반 멤버)
        return policies
            .map { ModelBurden.valueOf(it.maxBurden) }
            .filter { it != ModelBurden.RESTRICTED }
            .maxByOrNull { it.ordinal } ?: ModelBurden.LIGHT
    }

    /** 멤버 역할들의 일일 한도(최대값). 정책 없으면 base 기본값. */
    fun dailyLimit(
        guildId: Long,
        memberRoleIds: Collection<Long>,
        base: Int = 20,
    ): Int {
        val policies = roles.findByGuildId(guildId).filter { it.roleId in memberRoleIds }
        return policies.maxOfOrNull { it.dailyLimit } ?: base
    }

    /** 필요한 부담 수준을 이 멤버가 쓸 수 있는가(RESTRICTED 는 별도 정책 — 여기선 false). */
    fun isBurdenAllowed(
        memberMax: ModelBurden,
        required: ModelBurden,
    ): Boolean = required != ModelBurden.RESTRICTED && required.ordinal <= memberMax.ordinal

    // ── 승인 방식 ───────────────────────────────────────────────────────
    @Transactional
    fun setAutoApprove(
        guildId: Long,
        value: Boolean,
        adminId: Long,
    ) {
        val g = guilds.findById(guildId).orElseGet { GuildEntity(id = guildId) }
        g.autoApprove = value
        guilds.save(g)
        audit.record("set_auto_approve", "admin:$adminId", "guild:$guildId", value.toString())
    }

    fun isAutoApprove(guildId: Long): Boolean = guilds.findById(guildId).map { it.autoApprove }.orElse(false)

    /** 길드 기본 모델/언어 설정(차수 11 #146). null/blank 인 항목은 변경하지 않는다. */
    fun setGuildDefaults(
        guildId: Long,
        defaultModel: String?,
        language: String?,
        adminId: Long,
    ) {
        val g = guilds.findById(guildId).orElseGet { GuildEntity(id = guildId) }
        defaultModel?.takeIf { it.isNotBlank() }?.let { g.defaultModel = it }
        language?.takeIf { it.isNotBlank() }?.let { g.language = it }
        guilds.save(g)
        audit.record("set_guild_defaults", "admin:$adminId", "guild:$guildId", "model=${g.defaultModel},lang=${g.language}")
    }

    /** 길드 기본 모델(미설정 시 null → 라우터가 풀에서 자동 선택). */
    fun guildDefaultModel(guildId: Long): String? = guilds.findById(guildId).map { it.defaultModel }.orElse(null)

    /** 길드 언어(기본 ko). */
    fun guildLanguage(guildId: Long): String = guilds.findById(guildId).map { it.language }.orElse("ko")

    /** 길드 환영/안내 메시지 설정(차수 12 #174). */
    fun setWelcomeMessage(
        guildId: Long,
        message: String,
        adminId: Long,
    ) {
        val g = guilds.findById(guildId).orElseGet { GuildEntity(id = guildId) }
        g.welcomeMessage = message.take(1000)
        guilds.save(g)
        audit.record("set_welcome", "admin:$adminId", "guild:$guildId", "len=${g.welcomeMessage?.length}")
    }

    /** 길드 환영 메시지(미설정 시 null). */
    fun guildWelcomeMessage(guildId: Long): String? = guilds.findById(guildId).map { it.welcomeMessage }.orElse(null)
}
