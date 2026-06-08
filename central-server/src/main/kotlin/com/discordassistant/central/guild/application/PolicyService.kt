package com.discordassistant.central.guild.application

import com.discordassistant.central.global.audit.AuditLog
import com.discordassistant.central.guild.adapter.outbound.persistence.AiAdminRoleRepository
import com.discordassistant.central.guild.adapter.outbound.persistence.AllowedChannelEntity
import com.discordassistant.central.guild.adapter.outbound.persistence.AllowedChannelRepository
import com.discordassistant.central.guild.adapter.outbound.persistence.GuildEntity
import com.discordassistant.central.guild.adapter.outbound.persistence.GuildRepository
import com.discordassistant.central.guild.adapter.outbound.persistence.RolePolicyEntity
import com.discordassistant.central.guild.adapter.outbound.persistence.RolePolicyRepository
import com.discordassistant.central.routing.application.port.RoutingPolicy
import com.discordassistant.central.shared.ModelBurden
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/** 길드별 프로바이더 자동 승인 여부(웹 ‘토큰 받기’ 서버 선택에서 즉시 발급 가능 여부 판단). */
fun interface AutoApprovePolicy {
    fun isAutoApprove(guildId: Long): Boolean
}

/**
 * 채널 AI 허용 목록 관리(데스크톱 앱 관리채널 브리지용 좁은 포트). [PolicyService] 가 구현한다.
 * 빈 목록 = 전체 채널 허용(제한 없음)이라는 도메인 의미를 그대로 노출한다.
 */
interface GuildChannelPolicy {
    fun allowedChannelIds(guildId: Long): List<Long>

    fun replaceAllowedChannels(
        guildId: Long,
        channelIds: Collection<Long>,
        adminId: Long,
    )

    fun allowAllChannels(
        guildId: Long,
        adminId: Long,
    )
}

/**
 * 서버(길드) 정책 (K-차수 7, specs §6/§18). 허용 채널·역할별 허용 모델 수준·승인 방식.
 * 라우팅이 쓰는 부분은 [RoutingPolicy] 로 노출한다.
 *
 * 성능(감사 2026-06-03 B-5): /ask 핫패스가 매 호출 `roles/channels/guild-settings` 를
 * 반복 조회하던 것을 **짧은 TTL 인메모리 읽기 캐시**로 접는다. 캐시는 길드별 불변 스냅샷을
 * 담고, 모든 쓰기 메서드 끝에서 해당 길드 엔트리를 즉시 무효화해 단일 인스턴스에서 관리자
 * 변경이 즉시 반영되게 한다(정확성 우선). DB 조회 메서드에는 `@Transactional(readOnly = true)`.
 */
@Service
class PolicyService(
    private val channels: AllowedChannelRepository,
    private val roles: RolePolicyRepository,
    private val guilds: GuildRepository,
    private val audit: AuditLog,
    private val aiAdminRoles: AiAdminRoleRepository? = null,
    @param:Value("\${central.policy.cache-ttl-ms:5000}") private val cacheTtlMs: Long = 5000,
    private val clock: Clock = Clock.systemUTC(),
) : RoutingPolicy,
    AutoApprovePolicy,
    GuildChannelPolicy {
    // ── 읽기 캐시 ────────────────────────────────────────────────────────
    // 길드별 불변 스냅샷 + 로드시각. TTL 경과 또는 무효화 시 재조회. 스레드 안전(ConcurrentHashMap).
    private val channelCache = ConcurrentHashMap<Long, CacheEntry<List<Long>>>()
    private val roleCache = ConcurrentHashMap<Long, CacheEntry<List<RoleSnapshot>>>()
    private val guildCache = ConcurrentHashMap<Long, CacheEntry<GuildSettings>>()

    private class CacheEntry<T>(
        val loadedAtMs: Long,
        val value: T,
    )

    /** 역할 정책 불변 스냅샷(엔티티를 트랜잭션 밖으로 노출하지 않기 위함). */
    private data class RoleSnapshot(
        val roleId: Long,
        val maxBurden: String,
        val dailyLimit: Int,
    )

    /** 길드 설정 불변 스냅샷. 길드 미존재 시 기본값. */
    private data class GuildSettings(
        val autoApprove: Boolean,
        val defaultModel: String?,
        val language: String,
        val welcomeMessage: String?,
        val defaultDailyLimit: Int?, // 유저별 일일 한도 길드 기본값. null=하드코딩 base, 0=무제한
    )

    private fun <T> read(
        cache: ConcurrentHashMap<Long, CacheEntry<T>>,
        guildId: Long,
        loader: (Long) -> T,
    ): T {
        val now = clock.millis()
        val hit = cache[guildId]
        if (hit != null && now - hit.loadedAtMs < cacheTtlMs) return hit.value
        val fresh = loader(guildId)
        cache[guildId] = CacheEntry(now, fresh)
        return fresh
    }

    private fun evict(guildId: Long) {
        channelCache.remove(guildId)
        roleCache.remove(guildId)
        guildCache.remove(guildId)
    }

    // 캐시 미스 시에만 DB 조회. 트랜잭션 경계는 호출하는 public read 메서드의 readOnly 트랜잭션이 잡는다.
    private fun loadChannelIds(guildId: Long): List<Long> = channels.findByGuildId(guildId).map { it.channelId }

    private fun loadRoles(guildId: Long): List<RoleSnapshot> =
        roles.findByGuildId(guildId).map { RoleSnapshot(it.roleId, it.maxBurden, it.dailyLimit) }

    private fun loadGuildSettings(guildId: Long): GuildSettings =
        guilds
            .findById(guildId)
            .map { GuildSettings(it.autoApprove, it.defaultModel, it.language, it.welcomeMessage, it.defaultDailyLimit) }
            // 설정 안 한 길드의 기본값: 자동 승인 ON(유입 마찰 최소화). 관리자가 /서버기본값·설정으로 끌 수 있음.
            .orElse(
                GuildSettings(autoApprove = true, defaultModel = null, language = "ko", welcomeMessage = null, defaultDailyLimit = null),
            )

    private fun cachedChannelIds(guildId: Long): List<Long> = read(channelCache, guildId, ::loadChannelIds)

    private fun cachedRoles(guildId: Long): List<RoleSnapshot> = read(roleCache, guildId, ::loadRoles)

    private fun cachedGuildSettings(guildId: Long): GuildSettings = read(guildCache, guildId, ::loadGuildSettings)

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
        evict(guildId)
    }

    @Transactional
    fun denyChannel(
        guildId: Long,
        channelId: Long,
        adminId: Long,
    ) {
        channels.deleteByGuildIdAndChannelId(guildId, channelId)
        audit.record("llm_deny_channel", "admin:$adminId", "guild:$guildId", "channel:$channelId")
        evict(guildId)
    }

    /** 채널 제한 전체 해제 = 모든 채널에서 사용 허용(허용 목록 비움). */
    @Transactional
    override fun allowAllChannels(
        guildId: Long,
        adminId: Long,
    ) {
        channels.deleteByGuildId(guildId)
        audit.record("llm_allow_all_channels", "admin:$adminId", "guild:$guildId", "all")
        evict(guildId)
    }

    /** 허용 채널 목록을 한 번에 교체한다. 빈 목록은 전체 채널 허용이다. */
    @Transactional
    override fun replaceAllowedChannels(
        guildId: Long,
        channelIds: Collection<Long>,
        adminId: Long,
    ) {
        channels.deleteByGuildId(guildId)
        channelIds.distinct().forEach { channelId ->
            channels.save(AllowedChannelEntity(guildId = guildId, channelId = channelId))
        }
        audit.record("llm_replace_allowed_channels", "admin:$adminId", "guild:$guildId", channelIds.joinToString(",").ifBlank { "all" })
        evict(guildId)
    }

    /** 허용 채널 ID 목록(비면 전체 허용). */
    @Transactional(readOnly = true)
    override fun allowedChannelIds(guildId: Long): List<Long> = cachedChannelIds(guildId)

    /** 채널이 LLM 사용 허용인가. 허용 채널이 하나도 설정 안 됐으면 제한 없음(true). */
    @Transactional(readOnly = true)
    override fun isChannelAllowed(
        guildId: Long,
        channelId: Long,
    ): Boolean {
        val allowed = cachedChannelIds(guildId)
        return allowed.isEmpty() || allowed.any { it == channelId }
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
        evict(guildId)
    }

    /** 멤버의 역할들로부터 허용되는 최대 모델 부담 수준(다중 역할 합집합 = 가장 높은 등급). */
    @Transactional(readOnly = true)
    override fun maxAllowedBurden(
        guildId: Long,
        memberRoleIds: Collection<Long>,
    ): ModelBurden {
        val policies = cachedRoles(guildId).filter { it.roleId in memberRoleIds }
        if (policies.isEmpty()) return ModelBurden.LIGHT // 기본(일반 멤버)
        return policies
            .mapNotNull { ModelBurden.fromName(it.maxBurden) }
            .filter { it != ModelBurden.RESTRICTED }
            .maxByOrNull { it.rank } ?: ModelBurden.LIGHT
    }

    /** 멤버의 일일 한도(요청자 쿼터). 0=무제한. 우선순위: 역할정책(0=무제한 우선, 아니면 최대) → 길드 기본값 → base.
     *
     * 0(무제한)은 가장 관대하므로 maxOf 로 묻히지 않게 **먼저** 판정한다(예전엔 역할 0 이 다른 역할 20 에 가려 한도가
     * 걸렸다). 역할 정책이 없으면 길드 기본값(default_daily_limit, 관리자가 /서버기본값 으로 설정, 0=무제한)을 쓰고,
     * 그것도 없으면 하드코딩 base(20).
     */
    @Transactional(readOnly = true)
    fun dailyLimit(
        guildId: Long,
        memberRoleIds: Collection<Long>,
        base: Int = 20,
    ): Int {
        val policies = cachedRoles(guildId).filter { it.roleId in memberRoleIds }
        if (policies.any { it.dailyLimit <= 0 }) return 0 // 무제한 역할이 하나라도 있으면 무제한
        val fromRoles = policies.maxOfOrNull { it.dailyLimit }
        if (fromRoles != null) return fromRoles
        return cachedGuildSettings(guildId).defaultDailyLimit ?: base // 길드 기본값(0=무제한) 또는 하드코딩 20
    }

    /** 필요한 부담 수준을 이 멤버가 쓸 수 있는가(RESTRICTED 는 별도 정책 — 여기선 false). */
    fun isBurdenAllowed(
        memberMax: ModelBurden,
        required: ModelBurden,
    ): Boolean = required != ModelBurden.RESTRICTED && required.rank <= memberMax.rank

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
        evict(guildId)
    }

    @Transactional(readOnly = true)
    override fun isAutoApprove(guildId: Long): Boolean = cachedGuildSettings(guildId).autoApprove

    /** 길드 기본 모델/언어/유저 일일 한도 설정(차수 11 #146). null 인 항목은 변경하지 않는다(dailyLimit 0=무제한). */
    @Transactional
    fun setGuildDefaults(
        guildId: Long,
        defaultModel: String?,
        language: String?,
        adminId: Long,
        defaultDailyLimit: Int? = null,
    ) {
        val g = guilds.findById(guildId).orElseGet { GuildEntity(id = guildId) }
        defaultModel?.takeIf { it.isNotBlank() }?.let { g.defaultModel = it }
        language?.takeIf { it.isNotBlank() }?.let { g.language = it }
        defaultDailyLimit?.let { g.defaultDailyLimit = it.coerceAtLeast(0) } // 0=무제한, 음수 방지
        guilds.save(g)
        audit.record(
            "set_guild_defaults",
            "admin:$adminId",
            "guild:$guildId",
            "model=${g.defaultModel},lang=${g.language},dailyLimit=${g.defaultDailyLimit}",
        )
        evict(guildId)
    }

    @Transactional
    fun clearGuildDefaultModel(
        guildId: Long,
        adminId: Long,
    ) {
        val g = guilds.findById(guildId).orElseGet { GuildEntity(id = guildId) }
        g.defaultModel = null
        guilds.save(g)
        audit.record("clear_guild_default_model", "admin:$adminId", "guild:$guildId", "model=auto")
        evict(guildId)
    }

    /** 길드 기본 모델(미설정 시 null → 라우터가 풀에서 자동 선택). */
    @Transactional(readOnly = true)
    fun guildDefaultModel(guildId: Long): String? = cachedGuildSettings(guildId).defaultModel

    /** 길드 언어(기본 ko). */
    @Transactional(readOnly = true)
    fun guildLanguage(guildId: Long): String = cachedGuildSettings(guildId).language

    /** 길드 환영/안내 메시지 설정(차수 12 #174). */
    @Transactional
    fun setWelcomeMessage(
        guildId: Long,
        message: String,
        adminId: Long,
    ) {
        val g = guilds.findById(guildId).orElseGet { GuildEntity(id = guildId) }
        g.welcomeMessage = message.take(1000)
        guilds.save(g)
        audit.record("set_welcome", "admin:$adminId", "guild:$guildId", "len=${g.welcomeMessage?.length}")
        evict(guildId)
    }

    /** 길드 환영 메시지(미설정 시 null). */
    @Transactional(readOnly = true)
    fun guildWelcomeMessage(guildId: Long): String? = cachedGuildSettings(guildId).welcomeMessage

    @Transactional
    fun cleanupChannel(
        guildId: Long,
        channelId: Long,
    ) {
        channels.deleteByGuildIdAndChannelId(guildId, channelId)
        audit.record("guild_channel_policy_cleanup", "system", "guild:$guildId", "channel:$channelId")
        evict(guildId)
    }

    /** 봇이 길드에서 제거될 때 서버별 정책/환영 설정을 정리한다. */
    @Transactional
    fun cleanupGuild(guildId: Long) {
        channels.deleteByGuildId(guildId)
        roles.deleteByGuildId(guildId)
        aiAdminRoles?.deleteByGuildId(guildId)
        if (guilds.existsById(guildId)) {
            guilds.deleteById(guildId)
        }
        audit.record("guild_policy_cleanup", "system", "guild:$guildId", "removed")
        evict(guildId)
    }
}
