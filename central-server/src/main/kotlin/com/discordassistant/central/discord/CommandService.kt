package com.discordassistant.central.discord

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.domain.RequestState
import com.discordassistant.central.policy.PolicyService
import com.discordassistant.central.provider.ProviderProtectionService
import com.discordassistant.central.provider.ProviderRegistrationService
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.routing.AiRequestInput
import com.discordassistant.central.routing.RequestOrchestrator
import com.discordassistant.central.usage.UsageService
import org.springframework.stereotype.Service

/** 슬래시 명령 응답(내용 + ephemeral 여부). */
data class Reply(val content: String, val ephemeral: Boolean = true)

/** 명령 호출 컨텍스트(JDA 이벤트에서 추출). */
data class CommandContext(
    val guildId: Long,
    val channelId: Long,
    val userId: Long,
    val roleIds: Set<Long>,
    val isAdmin: Boolean,
)

/**
 * 슬래시 명령 비즈니스 로직 (K-차수 13). JDA 이벤트와 분리된 순수 로직이라 단위 테스트 가능하다.
 * JDA 리스너는 이벤트→CommandContext 변환만 담당한다.
 */
@Service
class CommandService(
    private val orchestrator: RequestOrchestrator,
    private val registration: ProviderRegistrationService,
    private val protection: ProviderProtectionService,
    private val policy: PolicyService,
    private val usage: UsageService,
    private val registry: ConnectionRegistry,
    private val privacy: PrivacyService,
    private val rateLimiter: RateLimiter,
) {
    companion object {
        const val PRIVACY_NOTICE =
            "이 서버는 커뮤니티 로컬 AI Provider Pool 을 사용합니다. 질문 내용은 요청을 처리하는 " +
                "커뮤니티 프로바이더의 PC 로 전송될 수 있습니다. 비밀번호·API 키·개인정보·비공개 문서 등 " +
                "민감한 정보는 입력하지 마세요."
    }

    private fun adminOnly(ctx: CommandContext): Reply? =
        if (!ctx.isAdmin) Reply("⛔ 이 명령은 관리자만 사용할 수 있습니다.") else null

    // ── 일반 유저 ───────────────────────────────────────────────────────
    fun ask(ctx: CommandContext, prompt: String): Reply {
        if (!rateLimiter.tryAcquire("ask:${ctx.guildId}:${ctx.userId}")) {
            return Reply("⏳ 요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.")
        }
        val result = orchestrator.handle(
            AiRequestInput(ctx.guildId, ctx.channelId, ctx.userId, prompt, ctx.roleIds),
        )
        return when (result.state) {
            RequestState.COMPLETED -> Reply(
                "${result.text}\n\n_${privacy.processedNotice(ctx.guildId, result.effectiveBurden, result.providerId, ctx.isAdmin)}_",
                ephemeral = false,
            )
            RequestState.REJECTED -> Reply("⛔ ${result.failReason}")
            else -> Reply("⚠️ ${result.failReason}")
        }
    }

    fun models(ctx: CommandContext): Reply {
        val max = policy.maxAllowedBurden(ctx.guildId, ctx.roleIds)
        val pool = registry.byGuild(ctx.guildId).size
        return Reply(
            "사용 가능한 최대 모델 수준: **$max**\n현재 풀 프로바이더: ${pool}명\n" +
                "수준: ${ModelBurden.entries.filter { it != ModelBurden.RESTRICTED }.joinToString(" < ")}",
        )
    }

    fun myUsage(ctx: CommandContext): Reply {
        val used = usage.userDailyCount(ctx.guildId, ctx.userId)
        val limit = policy.dailyLimit(ctx.guildId, ctx.roleIds)
        return Reply("오늘 사용량: $used / $limit")
    }

    fun privacy(): Reply = Reply(PRIVACY_NOTICE)

    // ── 프로바이더 ──────────────────────────────────────────────────────
    fun providerJoin(ctx: CommandContext): Reply {
        val auto = policy.isAutoApprove(ctx.guildId)
        val r = registration.requestJoin(ctx.userId, ctx.guildId, autoApprove = auto)
        return if (r.token != null) {
            Reply(
                "✅ 승인되었습니다. 아래 토큰으로 에이전트를 실행하세요(10분 내 1회용):\n" +
                    "```\n${r.token}\n```\n프롬프트가 당신 PC 로 전송됨에 동의한 것으로 간주됩니다.",
            )
        } else {
            Reply("📋 등록 요청이 접수되었습니다(${r.state}). 관리자 승인을 기다려 주세요.")
        }
    }

    fun providerPause(ctx: CommandContext): Reply =
        if (protection.pause(ctx.userId)) Reply("⏸️ 일시정지했습니다.") else Reply("연결된 에이전트가 없습니다.")

    fun providerResume(ctx: CommandContext): Reply =
        if (protection.resume(ctx.userId)) Reply("▶️ 재개했습니다.") else Reply("연결된 에이전트가 없습니다.")

    fun providerLeave(ctx: CommandContext): Reply =
        if (protection.leave(ctx.userId)) Reply("👋 풀에서 나갔습니다.") else Reply("연결된 에이전트가 없습니다.")

    fun providerStatus(ctx: CommandContext): Reply {
        val s = registry.byProvider(ctx.userId) ?: return Reply("연결 상태: 오프라인")
        return Reply("상태: ${s.state} · 처리중 ${s.activeRequests} · 일일잔여 ${s.remainingDailyRequests} · 실패 ${s.failures}")
    }

    // ── 관리자 ──────────────────────────────────────────────────────────
    fun allowChannel(ctx: CommandContext, channelId: Long): Reply {
        adminOnly(ctx)?.let { return it }
        policy.allowChannel(ctx.guildId, channelId, ctx.userId)
        return Reply("✅ 채널 <#$channelId> 에서 LLM 사용을 허용했습니다.")
    }

    fun denyChannel(ctx: CommandContext, channelId: Long): Reply {
        adminOnly(ctx)?.let { return it }
        policy.denyChannel(ctx.guildId, channelId, ctx.userId)
        return Reply("🚫 채널 <#$channelId> 에서 LLM 사용을 금지했습니다.")
    }

    fun setRolePolicy(ctx: CommandContext, roleId: Long, maxBurden: ModelBurden, dailyLimit: Int): Reply {
        adminOnly(ctx)?.let { return it }
        policy.setRolePolicy(ctx.guildId, roleId, maxBurden, dailyLimit, ctx.userId)
        return Reply("✅ 역할 <@&$roleId> 정책: 최대 $maxBurden, 하루 $dailyLimit 회")
    }

    fun approveProvider(ctx: CommandContext, providerUserId: Long): Reply {
        adminOnly(ctx)?.let { return it }
        val token = registration.approve(providerUserId, ctx.userId)
            ?: return Reply("승인할 대기 중 프로바이더가 없습니다.")
        return Reply("✅ <@$providerUserId> 승인. 토큰을 해당 유저에게 전달하세요:\n```\n$token\n```")
    }

    fun removeProvider(ctx: CommandContext, providerUserId: Long): Reply {
        adminOnly(ctx)?.let { return it }
        return if (registration.remove(providerUserId, ctx.userId)) {
            protection.leave(providerUserId)
            Reply("🗑️ <@$providerUserId> 를 풀에서 제거했습니다.")
        } else {
            Reply("해당 프로바이더를 찾을 수 없습니다.")
        }
    }

    fun providers(ctx: CommandContext): Reply {
        adminOnly(ctx)?.let { return it }
        val pending = registration.pending(ctx.guildId)
        val pool = registry.byGuild(ctx.guildId)
        val fairness = pool.joinToString("\n") { "· provider #${it.providerId}: 기여 ${usage.providerContributionCount(it.providerId)}회 · ${it.state}" }
        return Reply("승인 대기: ${pending.size} · 온라인: ${pool.size}\n$fairness\n대기 목록: $pending")
    }
}
