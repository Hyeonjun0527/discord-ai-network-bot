package com.discordassistant.central.discord

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.policy.PolicyService
import com.discordassistant.central.provider.ContributionPolicyService
import com.discordassistant.central.provider.ProviderProtectionService
import com.discordassistant.central.provider.ProviderRegistrationService
import com.discordassistant.central.provider.ProviderScheduleService
import com.discordassistant.central.relay.ConnectionRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 프로바이더 **본인 self-service** 슬래시 명령(참여/일시정지/재개/나가기/상태/모델·한도·범위·스케줄).
 * CommandService(god class) 에서 응집 단위로 분리 — 관리자 권한이 필요 없는 본인 명령만 모았다.
 * CommandService 는 같은 시그니처로 이 클래스에 위임한다(동작 불변).
 */
@Component
class ProviderSelfServiceCommands(
    private val registration: ProviderRegistrationService,
    private val protection: ProviderProtectionService,
    private val policy: PolicyService,
    private val registry: ConnectionRegistry,
    private val contributionPolicy: ContributionPolicyService,
    private val schedule: ProviderScheduleService,
    @param:Value("\${central.relay.public-url:}") private val relayPublicUrl: String,
) {
    /** 이 사용자가 ‘연동됨’(앱이 어느 서버든 연결돼 있음)인가 — 가이드 대신 자동 참여로 분기하는 기준. */
    fun providerLinked(ctx: CommandContext): Boolean = registry.isProviderLinked(ctx.userId)

    fun providerJoin(ctx: CommandContext): Reply {
        // 이미 이 서버에 연결돼 있으면 더 할 일 없음(참여 완료 상태 확인).
        if (registry.byProvider(ctx.guildId, ctx.userId) != null) {
            return Reply("✅ 이미 이 서버에 참여 중이에요. 앱에서 모델·한도를 조정할 수 있어요.", ephemeral = true)
        }
        // DM 글로벌 풀은 승인할 관리자가 없으므로 자동 승인(본인 PC 를 자발적으로 기여). 길드는 기존 정책대로.
        val auto = ctx.guildId == CommandService.DM_SCOPE || policy.isAutoApprove(ctx.guildId)
        val r = registration.requestJoin(ctx.userId, ctx.guildId, autoApprove = auto)
        // 연동된(앱 실행 중) 사용자: 등록만 보장하면 앱이 동기화로 이 서버에 **자동 연결**한다(가이드/재설치 불필요).
        if (providerLinked(ctx)) {
            return if (auto || r.state == com.discordassistant.central.domain.ProviderState.APPROVED) {
                Reply("✅ 참여 등록 완료! 실행 중인 냥시스턴트 앱이 잠시 후 이 서버에 자동으로 연결됩니다.", ephemeral = true)
            } else {
                Reply("📋 참여 신청을 접수했어요(${r.state}). 관리자 승인 후 앱이 자동으로 이 서버에 연결됩니다.", ephemeral = true)
            }
        }
        // 미연동(앱 없음/미연결): 기존 설치 가이드.
        return if (r.token != null) {
            Reply(ProviderOnboarding.message(r.token, relayPublicUrl), ephemeral = true)
        } else {
            Reply("📋 등록 요청이 접수되었습니다(${r.state}). 관리자 승인을 기다려 주세요.")
        }
    }

    /**
     * OS 선택(버튼) 후 설치 가이드(차수 19). 등록(멱등) 후 토큰을 발급해 그 OS 의 복붙 명령을 반환한다.
     * 수동 승인 길드에서 아직 미승인이면 승인 대기를 안내(승인 후 DM 으로 안내).
     */
    fun providerInstallGuide(
        ctx: CommandContext,
        os: String,
    ): Reply {
        val auto = ctx.guildId == CommandService.DM_SCOPE || policy.isAutoApprove(ctx.guildId)
        val join = registration.requestJoin(ctx.userId, ctx.guildId, autoApprove = auto)
        val token = join.token ?: registration.reissueToken(ctx.userId, ctx.guildId)
        return if (token != null) {
            Reply(ProviderOnboarding.installCommand(os, token, relayPublicUrl), ephemeral = true)
        } else {
            Reply("📋 등록 요청이 접수되었습니다(${join.state}). 관리자 승인 후 DM 으로 설치 안내를 보냅니다.", ephemeral = true)
        }
    }

    fun providerPause(ctx: CommandContext): Reply =
        if (protection.pause(ctx.userId, ctx.guildId)) Reply("⏸️ 일시정지했습니다.") else Reply("연결된 에이전트가 없습니다.")

    fun providerResume(ctx: CommandContext): Reply =
        if (protection.resume(ctx.userId, ctx.guildId)) Reply("▶️ 재개했습니다.") else Reply("연결된 에이전트가 없습니다.")

    fun providerLeave(ctx: CommandContext): Reply =
        if (protection.leave(ctx.userId, ctx.guildId)) Reply("👋 풀에서 나갔습니다.") else Reply("연결된 에이전트가 없습니다.")

    fun providerStatus(ctx: CommandContext): Reply {
        val s = registry.byProvider(ctx.guildId, ctx.userId) ?: return Reply("연결 상태: 오프라인")
        val queued = s.queueDepth().let { if (it > 0) " · 대기 $it" else "" }
        val base = "상태: ${s.state} · 처리중 ${s.activeRequests}$queued · 일일잔여 ${s.remainingDailyRequests} · 실패 ${s.failures}"
        val hint = RestHint.forStatus(s.state, s.activeRequests, s.remainingDailyRequests)
        return Reply(if (hint != null) "$base\n$hint" else base)
    }

    fun providerModels(
        ctx: CommandContext,
        models: List<String>,
    ): Reply {
        contributionPolicy.setModels(ctx.userId, models, ModelBurden.STANDARD)
        return Reply("✅ 제공 모델 설정: ${models.joinToString(", ")}")
    }

    fun providerLimit(
        ctx: CommandContext,
        model: String,
        daily: Int,
        concurrency: Int,
        seconds: Int,
    ): Reply {
        contributionPolicy.setLimit(ctx.userId, model, daily, concurrency, seconds)
        return Reply("✅ `$model` 한도: 하루 $daily · 동시 $concurrency · 최대 ${seconds}초")
    }

    fun providerScope(
        ctx: CommandContext,
        model: String,
        role: String,
    ): Reply {
        contributionPolicy.setScope(ctx.userId, model, role)
        return Reply("✅ `$model` 허용 범위: $role")
    }

    /** 가용 시간대 스케줄 설정(차수 12 #159). UTC 시 0~23, from==to 면 24시간. */
    fun providerSchedule(
        ctx: CommandContext,
        fromHour: Int,
        toHour: Int,
    ): Reply {
        if (fromHour !in 0..23 || toHour !in 0..23) return Replies.warn("시(hour)는 0~23 사이여야 합니다.")
        schedule.setSchedule(ctx.userId, ctx.guildId, fromHour, toHour)
        val span = if (fromHour == toHour) "24시간 가용" else "${fromHour}시~${toHour}시(UTC)"
        return Replies.ok("가용 시간대 설정: $span. 시간 밖에는 자동으로 일시정지됩니다.")
    }
}
