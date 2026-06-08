package com.discordassistant.central.platform.discord.command

import com.discordassistant.central.guild.application.PolicyService
import com.discordassistant.central.platform.discord.CommandContext
import com.discordassistant.central.platform.discord.ProviderOnboarding
import com.discordassistant.central.platform.discord.Replies
import com.discordassistant.central.platform.discord.Reply
import com.discordassistant.central.provider.application.ProviderProtectionService
import com.discordassistant.central.provider.application.ProviderRegistrationService
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.requestlog.application.UsageService
import com.discordassistant.central.shared.ModelBurden
import org.springframework.stereotype.Component

/**
 * 길드 관리자 설정/프로바이더 관리 명령군(서버 기본값·자동승인·채널 허용·역할 정책·환영 메시지·
 * 프로바이더 승인/제거·사용자 차단·공정성/프로바이더 조회). CommandService 에서 응집 단위로 분리 —
 * 부작용 순서/문구/포맷 그대로 이동, 시그니처 유지·위임.
 *
 * 보존: [removeProvider] 의 2단 부작용(registration.remove 성공 시에만 protection.leave 호출) 순서 그대로.
 */
@Component
class GuildAdminCommandHandler(
    private val policy: PolicyService,
    private val registration: ProviderRegistrationService,
    private val protection: ProviderProtectionService,
    private val registry: ConnectionRegistry,
    private val usage: UsageService,
    private val blocklist: com.discordassistant.central.quota.application.BlocklistService,
    @param:org.springframework.beans.factory.annotation.Value("\${central.relay.public-url:}")
    private val relayPublicUrl: String = "",
    private val guards: SharedCommandGuards,
) {
    /** 길드 기본 모델/언어/유저 일일 한도 설정(차수 11 #146). null 값은 변경하지 않음(dailyLimit 0=무제한). */
    fun setGuildDefaults(
        ctx: CommandContext,
        defaultModel: String?,
        language: String?,
        userDailyLimit: Int? = null,
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        policy.setGuildDefaults(ctx.guildId, defaultModel, language, ctx.userId, userDailyLimit)
        val m = policy.guildDefaultModel(ctx.guildId) ?: "(자동 선택)"
        val limitNote = userDailyLimit?.let { " · 유저 하루 한도: " + (if (it <= 0) "무제한" else "$it 회") } ?: ""
        return Reply("✅ 길드 기본값 — 모델: `$m` · 언어: `${policy.guildLanguage(ctx.guildId)}`$limitNote")
    }

    /** 자동 승인 토글(차수 13 #147/#180, 설정 패널 버튼). */
    fun toggleAutoApprove(ctx: CommandContext): Reply {
        guards.adminOnly(ctx)?.let { return it }
        val now = !policy.isAutoApprove(ctx.guildId)
        policy.setAutoApprove(ctx.guildId, now, ctx.userId)
        return Replies.ok("프로바이더 자동 승인: ${if (now) "켜짐" else "꺼짐"}")
    }

    /** 자동 승인 켜기/끄기(명시적). 패널 버튼용. */
    fun setAutoApprove(
        ctx: CommandContext,
        enabled: Boolean,
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        policy.setAutoApprove(ctx.guildId, enabled, ctx.userId)
        return if (enabled) {
            Replies.ok("프로바이더 **자동 승인** — 이제 `/프로바이더참여` 한 사람은 관리자 승인 없이 바로 참여합니다.")
        } else {
            Replies.ok("프로바이더 **수동 승인** — `/프로바이더참여` 신청 후 관리자가 `/프로바이더승인` 해야 참여합니다.")
        }
    }

    /** 현재 자동 승인 상태(패널 표시용). */
    fun isAutoApprove(ctx: CommandContext): Boolean = policy.isAutoApprove(ctx.guildId)

    /** 모든 채널에서 LLM 사용 허용(채널 제한 해제). */
    fun allowAllChannels(ctx: CommandContext): Reply {
        guards.adminOnly(ctx)?.let { return it }
        policy.allowAllChannels(ctx.guildId, ctx.userId)
        return Replies.ok("이제 **모든 채널**에서 `/질문` 을 쓸 수 있습니다(채널 제한 해제).")
    }

    /** 현재 허용 채널 목록(패널 표시용). 비면 전체 허용. */
    fun allowedChannelIds(ctx: CommandContext): List<Long> = policy.allowedChannelIds(ctx.guildId)

    /** 설정 패널에서 임시 선택한 서버 언어/기본 모델/허용 채널/자동승인을 저장 버튼 한 번으로 적용한다. */
    fun saveGuildSettings(
        ctx: CommandContext,
        language: String?,
        defaultModel: String?,
        allowedChannelIds: Collection<Long>?,
        autoApprove: Boolean? = null,
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        when (defaultModel) {
            "__auto__" -> {
                policy.clearGuildDefaultModel(ctx.guildId, ctx.userId)
                language?.takeIf { it.isNotBlank() }?.let { policy.setGuildDefaults(ctx.guildId, null, it, ctx.userId) }
            }
            null -> language?.takeIf { it.isNotBlank() }?.let { policy.setGuildDefaults(ctx.guildId, null, it, ctx.userId) }
            else -> policy.setGuildDefaults(ctx.guildId, defaultModel, language, ctx.userId)
        }
        allowedChannelIds?.let { policy.replaceAllowedChannels(ctx.guildId, it, ctx.userId) }
        autoApprove?.let { policy.setAutoApprove(ctx.guildId, it, ctx.userId) }

        val model = policy.guildDefaultModel(ctx.guildId) ?: "자동 선택"
        val lang = policy.guildLanguage(ctx.guildId)
        val channels = policy.allowedChannelIds(ctx.guildId)
        val channelText = if (channels.isEmpty()) "모든 채널" else channels.joinToString(" ") { "<#$it>" }
        val autoApproveText = if (policy.isAutoApprove(ctx.guildId)) "켜짐" else "꺼짐"
        return Replies.ok(
            "서버 설정을 저장했습니다.\n" +
                "언어: `$lang`\n" +
                "기본 모델: `$model`\n" +
                "LLM 사용 채널: $channelText\n" +
                "자동 승인: `$autoApproveText`",
        )
    }

    /** 풀이 현재 제공하는 모델 목록(패널 표시용). */
    fun poolModels(ctx: CommandContext): List<String> =
        registry
            .byGuild(ctx.guildId)
            .flatMap { it.capability.models }
            .distinct()
            .sorted()
            .take(25) // Discord 자동완성 최대 25개

    /** 길드 언어(패널 표시용). */
    fun guildLanguage(ctx: CommandContext): String = policy.guildLanguage(ctx.guildId)

    /** 길드 기본 모델(패널 표시용, 미설정 시 null). */
    fun guildDefaultModel(ctx: CommandContext): String? = policy.guildDefaultModel(ctx.guildId)

    /** 길드 환영/안내 메시지 설정(차수 12 #174, 관리자). */
    fun setWelcome(
        ctx: CommandContext,
        message: String,
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        policy.setWelcomeMessage(ctx.guildId, message, ctx.userId)
        return Replies.ok("환영 메시지를 설정했습니다.")
    }

    /** 환영/안내 메시지 보기(누구나). */
    fun welcome(ctx: CommandContext): Reply {
        val msg =
            policy.guildWelcomeMessage(ctx.guildId)
                ?: return Replies.info("이 서버는 아직 환영 메시지를 설정하지 않았습니다. `/도움말` 로 사용법을 확인하세요.")
        return Reply("👋 $msg", ephemeral = false)
    }

    fun allowChannel(
        ctx: CommandContext,
        channelId: Long,
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        policy.allowChannel(ctx.guildId, channelId, ctx.userId)
        return Reply("✅ 채널 <#$channelId> 에서 LLM 사용을 허용했습니다.")
    }

    fun denyChannel(
        ctx: CommandContext,
        channelId: Long,
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        policy.denyChannel(ctx.guildId, channelId, ctx.userId)
        return Reply("🚫 채널 <#$channelId> 에서 LLM 사용을 금지했습니다.")
    }

    fun setRolePolicy(
        ctx: CommandContext,
        roleId: Long,
        maxBurden: ModelBurden,
        dailyLimit: Int,
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        policy.setRolePolicy(ctx.guildId, roleId, maxBurden, dailyLimit, ctx.userId)
        return Reply("✅ 역할 <@&$roleId> 정책: 최대 $maxBurden, 하루 $dailyLimit 회")
    }

    fun approveProvider(
        ctx: CommandContext,
        providerUserId: Long,
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        val token =
            registration.approve(providerUserId, ctx.guildId, ctx.userId)
                ?: return Reply("승인할 대기 중 프로바이더가 없습니다.")
        // 승인 안내(온보딩 가이드) — DiscordBot 이 이 내용을 대상 유저에게 DM 으로도 보낸다(#162).
        return Reply(ProviderOnboarding.message(token, relayPublicUrl), ephemeral = true)
    }

    fun removeProvider(
        ctx: CommandContext,
        providerUserId: Long,
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        return if (registration.remove(providerUserId, ctx.guildId, ctx.userId)) {
            protection.leave(providerUserId, ctx.guildId)
            Reply("🗑️ <@$providerUserId> 를 풀에서 제거했습니다.")
        } else {
            Reply("해당 프로바이더를 찾을 수 없습니다.")
        }
    }

    fun blockUser(
        ctx: CommandContext,
        targetUserId: Long,
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        blocklist.block(ctx.guildId, targetUserId, ctx.userId)
        return Reply("🚫 <@$targetUserId> 를 차단했습니다.")
    }

    fun unblockUser(
        ctx: CommandContext,
        targetUserId: Long,
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        blocklist.unblock(ctx.guildId, targetUserId, ctx.userId)
        return Reply("✅ <@$targetUserId> 차단을 해제했습니다.")
    }

    fun providers(ctx: CommandContext): Reply {
        guards.adminOnly(ctx)?.let { return it }
        val pending = registration.pending(ctx.guildId)
        val pool = registry.byGuild(ctx.guildId)
        val fairness =
            pool.joinToString("\n") {
                "· <@${it.providerId}>: 기여 ${usage.providerContributionCount(it.providerId)}회 · ${it.state}"
            }
        return Reply("승인 대기: ${pending.size} · 온라인: ${pool.size}\n$fairness\n대기 목록: $pending")
    }

    fun fairness(ctx: CommandContext): Reply {
        guards.adminOnly(ctx)?.let { return it }
        val pool = registry.byGuild(ctx.guildId)
        if (pool.isEmpty()) return Reply("연결된 프로바이더가 없습니다.")
        val counts = pool.map { it.providerId to usage.providerContributionCount(it.providerId) }
        val total = counts.sumOf { it.second }
        val lines =
            counts.sortedByDescending { it.second }.joinToString("\n") { (pid, c) ->
                val pct = if (total > 0) (c * 100 / total) else 0
                "· <@$pid>: ${c}건 ($pct%) · 실패 ${usage.providerFailures(pid)}"
            }
        return Reply("⚖️ 공정성 리포트 (총 ${total}건)\n$lines")
    }
}
