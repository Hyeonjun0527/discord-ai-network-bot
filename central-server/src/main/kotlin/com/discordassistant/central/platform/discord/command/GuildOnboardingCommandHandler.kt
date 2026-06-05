package com.discordassistant.central.platform.discord.command

import com.discordassistant.central.onboarding.application.GuildOnboardingService
import com.discordassistant.central.onboarding.application.OnboardingAnalysisContext
import com.discordassistant.central.platform.discord.CommandContext
import com.discordassistant.central.platform.discord.CommandService
import com.discordassistant.central.platform.discord.OnboardingStartOutcome
import com.discordassistant.central.platform.discord.Replies
import com.discordassistant.central.platform.discord.Reply
import org.springframework.stereotype.Component

/**
 * 서버 AI 자동 온보딩 명령군(startAutoOnboarding, approveOnboarding, rejectOnboarding, setOnboardingOptOut).
 * CommandService 에서 응집 단위로 분리 — 권한가드/문구/경계 그대로 이동, 시그니처 유지·위임.
 *
 * B1 불변식(가장 민감): [startAutoOnboarding] 은 analyze(트랜잭션 밖·느린 LLM)를 먼저 호출하고 그 결과만
 * startOnboarding(짧은 TX)에 넘긴다. 두 호출의 순서·비TX·경계를 그대로 유지하며, 절대 하나의 @Transactional
 * 로 감싸지 않는다(LLM 이 TX 안에 들어가면 위반). 별도 @Component 라 self-invocation 프록시 우회 위험도 원천차단된다.
 */
@Component
class GuildOnboardingCommandHandler(
    private val guildOnboarding: GuildOnboardingService,
    private val onboardingOptOuts: com.discordassistant.central.onboarding.adapter.outbound.persistence.GuildOnboardingOptOutRepository,
    private val channelAiIdentity: ChannelAiIdentityCommandHandler,
) {
    /**
     * `/ai-onboard` 또는 입장 배너 버튼 → 동의 기록 + 휴리스틱 draft + PENDING 제안 생성.
     * 성공하면 [OnboardingStartOutcome.Started](제안 카드용 데이터), 권한/기능 게이트 실패하면 [OnboardingStartOutcome.Rejected].
     */
    fun startAutoOnboarding(
        ctx: CommandContext,
        channelName: String? = null,
        channelWhitelist: Set<Long> = emptySet(),
        historyLimit: Int = 0,
        backfill: GuildOnboardingService.BackfillInput? = null,
    ): OnboardingStartOutcome {
        channelAiIdentity.channelAiAdminOnly(ctx, "auto_onboard_start")?.let { return OnboardingStartOutcome.Rejected(it) }
        return runCatching {
            // LLM 분석은 DB 트랜잭션 밖(여기 — slow 명령 실행 풀)에서 먼저 수행한다(B1). analyze 는 비트랜잭션
            // 메서드라 프록시를 거쳐도 트랜잭션/커넥션을 열지 않는다. startOnboarding 은 그 결과만 받아 짧은 트랜잭션으로 처리.
            // (analyze/startOnboarding 을 여기서 각각 부르므로 self-invocation 프록시 우회 함정도 없다.)
            // 분석은 **실제 길드/채널/actor** 컨텍스트로 라우팅한다(A) — 프로바이더가 길드별 풀이라 실제 guildId 가 필수.
            val analysis =
                guildOnboarding.analyze(
                    backfill,
                    OnboardingAnalysisContext(
                        guildId = ctx.guildId,
                        channelId = ctx.channelId,
                        actorUserId = ctx.userId,
                        actorRoleIds = ctx.roleIds,
                        actorIsGuildAdmin = ctx.isAdmin,
                    ),
                )
            val result =
                guildOnboarding.startOnboarding(
                    guildId = ctx.guildId,
                    channelId = ctx.channelId,
                    actorUserId = ctx.userId,
                    actorRoleIds = ctx.roleIds,
                    actorIsGuildAdmin = ctx.isAdmin,
                    channelName = channelName,
                    channelWhitelist = channelWhitelist,
                    historyLimit = historyLimit,
                    backfill = backfill,
                    analysis = analysis,
                )
            OnboardingStartOutcome.Started(result)
        }.getOrElse {
            OnboardingStartOutcome.Rejected(Replies.warn("AI 자동 설정을 시작하지 못했어요. ${it.message ?: "잠시 후 다시 시도해 주세요."}"))
        }
    }

    fun approveOnboarding(
        ctx: CommandContext,
        proposalId: Long,
    ): Reply {
        channelAiIdentity.channelAiAdminOnly(ctx, "auto_onboard_approve")?.let { return it }
        return runCatching {
            val review =
                guildOnboarding.approveOnboarding(
                    proposalId = proposalId,
                    reviewerUserId = ctx.userId,
                    reviewerRoleIds = ctx.roleIds,
                    reviewerIsGuildAdmin = ctx.isAdmin,
                    reason = "auto onboarding approved",
                )
            Replies.ok("✅ 이 채널 AI 자동 설정을 승인했습니다. 이제 `/ask` 답변에 적용됩니다. (제안 `${review.id}`)")
        }.getOrElse {
            Replies.warn("AI 자동 설정 승인에 실패했어요. ${it.message ?: "이미 처리된 제안인지 확인해 주세요."}")
        }
    }

    fun rejectOnboarding(
        ctx: CommandContext,
        proposalId: Long,
    ): Reply {
        channelAiIdentity.channelAiAdminOnly(ctx, "auto_onboard_reject")?.let { return it }
        return runCatching {
            val review =
                guildOnboarding.rejectOnboarding(
                    proposalId = proposalId,
                    reviewerUserId = ctx.userId,
                    reviewerRoleIds = ctx.roleIds,
                    reviewerIsGuildAdmin = ctx.isAdmin,
                    reason = "auto onboarding rejected",
                )
            Replies.ok("🚫 이 채널 AI 자동 설정 제안을 거절했습니다. 제안은 적용되지 않습니다. (제안 `${review.id}`)")
        }.getOrElse {
            Replies.warn("AI 자동 설정 거절에 실패했어요. ${it.message ?: "이미 처리된 제안인지 확인해 주세요."}")
        }
    }

    /**
     * `/ai-onboard-optout` — 누구나 **본인에 한해** 자신의 메시지를 자동 온보딩 백필 RAG 색인에서 제외/해제한다(관리자 권한 불필요).
     * [enable] = true 면 제외 등록, false 면 해제, null 이면 현재 상태를 토글한다. 길드 단위로 격리된다.
     * 이미 색인된 과거 데이터는 row 삭제(소스 삭제)로 잊을 수 있고, 이 설정은 이후 백필부터 본인 메시지를 색인하지 않게 한다.
     */
    fun setOnboardingOptOut(
        ctx: CommandContext,
        enable: Boolean? = null,
    ): Reply {
        if (ctx.guildId == CommandService.DM_SCOPE) {
            return Replies.warn("이 명령은 서버에서만 사용할 수 있어요.")
        }
        return runCatching {
            val currentlyOptedOut = onboardingOptOuts.existsByGuildIdAndUserId(ctx.guildId, ctx.userId)
            val target = enable ?: !currentlyOptedOut
            if (target == currentlyOptedOut) {
                if (target) {
                    Reply("이미 이 서버의 AI 자동 학습(백필 색인)에서 내 메시지를 제외하고 있어요.")
                } else {
                    Reply("이미 제외 설정이 없어요. 내 메시지는 (관리자가 백필을 실행하면) 색인 대상이 될 수 있어요.")
                }
            } else if (target) {
                // 유니크 인덱스로 중복 방지 — 경합 시 예외는 runCatching 이 잡는다(아래 getOrElse).
                onboardingOptOuts.save(
                    com.discordassistant.central.onboarding.adapter.outbound.persistence.GuildOnboardingOptOutEntity(
                        guildId = ctx.guildId,
                        userId = ctx.userId,
                        createdAt = java.time.Instant.now(),
                    ),
                )
                Replies.ok("✅ 이 서버의 AI 자동 학습(백필 색인)에서 내 메시지를 제외했어요. 이후 백필부터 내 메시지는 색인되지 않습니다.")
            } else {
                onboardingOptOuts.deleteByGuildIdAndUserId(ctx.guildId, ctx.userId)
                Replies.ok("✅ 제외 설정을 해제했어요. 내 메시지가 다시 백필 색인 대상이 될 수 있어요(관리자가 백필을 실행할 때).")
            }
        }.getOrElse {
            Replies.warn("opt-out 설정을 변경하지 못했어요. 잠시 후 다시 시도해 주세요.")
        }
    }
}
