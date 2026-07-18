package com.discordassistant.central.platform.discord.admin

import com.discordassistant.central.routing.application.CloudLlm
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * AI 관리 비서의 **JDA-free 계획층**. 어드민의 자연어 /질문 을 GLM tool calling 으로 보내, 관리 의도가 있으면
 * [AdminActionPlan] 으로 파싱해 위험도에 따라 즉시 실행/확인 게이트를 결정한다([AdminAssistantDecision]).
 * 실제 Discord 변경(JDA)은 [DiscordBot] 이 [JdaAdminGuildGateway] 로 수행한다 — 이 서비스는 GLM 호출/파싱만.
 *
 * **권한 게이트**: 호출자(DiscordBot)는 ctx.isAdmin 일 때만 [plan] 을 호출한다. 비어드민에게는 도구를 절대 첨부하지
 * 않는다(기존 /질문 동작 100% 보존). 이 서비스 자체도 cloudLlm 비활성이면 NotAdminAction 으로 비켜선다.
 */
@Service
class AdminAssistantService(
    private val cloudLlm: CloudLlm,
    private val pendingActions: PendingAdminActionStore,
    private val audit: AdminActionAuditLog,
) {
    private val log = LoggerFactory.getLogger(AdminAssistantService::class.java)

    /** 관리 비서를 쓸 수 있는 환경인지(클라우드 LLM 활성). 비활성이면 어드민도 일반 /질문 으로 처리. */
    fun isAvailable(): Boolean = cloudLlm.isEnabled()

    /**
     * 어드민 프롬프트를 GLM tool calling 으로 해석한다. 관리 의도가 없으면 [AdminAssistantDecision.NotAdminAction]
     * (호출자는 일반 /질문 으로 폴백), SAFE 면 [ReadyToRun], CONFIRM 이면 [NeedsConfirm](토큰 발급).
     * GLM 호출/파싱 실패는 NotAdminAction 으로 비켜서 일반 /질문 을 깨지 않는다(graceful).
     */
    fun plan(
        guildId: Long,
        requesterUserId: Long,
        prompt: String,
    ): AdminAssistantDecision {
        if (!cloudLlm.isEnabled()) return AdminAssistantDecision.NotAdminAction
        val response =
            runCatching {
                cloudLlm.generateWithTools(
                    systemPrompt = AdminToolCatalog.SYSTEM_PROMPT,
                    userPrompt = prompt,
                    toolsJson = AdminToolCatalog.toolsJson,
                    model = ADMIN_TOOL_MODEL,
                )
            }.getOrElse {
                log.warn("관리 비서 GLM 호출 실패(guild={}): {}", guildId, it.message)
                return AdminAssistantDecision.NotAdminAction
            }
        // 도구를 호출하지 않았으면 일반 질문 — 호출자가 기존 /질문 경로로 처리(회귀 0).
        val call = response.toolCalls.firstOrNull() ?: return AdminAssistantDecision.NotAdminAction
        val plan = AdminToolCatalog.toPlan(call) ?: return AdminAssistantDecision.NotAdminAction
        return when (plan.risk) {
            AdminActionRisk.SAFE -> AdminAssistantDecision.ReadyToRun(plan)
            AdminActionRisk.CONFIRM -> {
                val token = pendingActions.put(plan, requesterUserId, guildId)
                AdminAssistantDecision.NeedsConfirm(plan, token)
            }
        }
    }

    /**
     * 확인 버튼 클릭 시 토큰으로 pending 을 꺼내 실행한다(JDA 변경은 호출자가 만든 [gateway] 로). 권한 재검증
     * ([clickerIsAdmin])·요청자 일치는 호출자가 보장하되, 여기서도 다른 사람 클릭/만료를 방어한다(안전장치 1·5).
     */
    fun confirmAndRun(
        token: String,
        clickerUserId: Long,
        clickerIsAdmin: Boolean,
        gatewayFactory: (PendingAdminActionStore.Pending) -> AdminGuildGateway,
    ): AdminActionResult {
        if (!clickerIsAdmin) return AdminActionResult.Rejected("⛔ 관리자만 실행할 수 있어요.")
        val pending =
            pendingActions.consume(token)
                ?: return AdminActionResult.Rejected("⏰ 확인 시간이 지났거나 이미 처리된 작업이에요. 다시 시도해 주세요.")
        if (pending.requesterUserId != clickerUserId) {
            return AdminActionResult.Rejected("⛔ 이 작업을 요청한 관리자만 실행할 수 있어요.")
        }
        val gateway = gatewayFactory(pending)
        val executor = AdminActionExecutor(gateway, pending.requesterUserId)
        val result = executor.execute(pending.plan)
        audit.record(pending.guildId, clickerUserId, pending.plan, result, confirmed = true)
        return result
    }

    /** SAFE 액션 즉시 실행(JDA 변경은 [gateway] 로). 감사 로그 기록 포함. */
    fun runSafe(
        guildId: Long,
        requesterUserId: Long,
        plan: AdminActionPlan,
        gateway: AdminGuildGateway,
    ): AdminActionResult {
        val executor = AdminActionExecutor(gateway, requesterUserId)
        val result = executor.execute(plan)
        audit.record(guildId, requesterUserId, plan, result, confirmed = false)
        return result
    }

    /** 확인 게이트 안내 문구(버튼 위에 보일 질문). 위험 액션 종류별 사람 친화 한국어. */
    fun confirmPrompt(plan: AdminActionPlan): String {
        val target = plan.arg("userId") ?: plan.arg("channelId") ?: plan.arg("name") ?: "대상"
        val action =
            when (plan.type) {
                AdminActionType.BAN_MEMBER -> "<@$target> 님을 **차단**할까요?"
                AdminActionType.KICK_MEMBER -> "<@$target> 님을 **추방**할까요?"
                AdminActionType.TIMEOUT_MEMBER -> "<@$target> 님을 ${plan.arg("minutes") ?: "?"}분 **타임아웃**할까요?"
                AdminActionType.DELETE_CHANNEL -> "<#$target> 채널을 **삭제**할까요?"
                AdminActionType.SET_CHANNEL_PERMISSION -> "<#$target> 채널의 **권한을 변경**할까요?"
                else -> "이 작업을 실행할까요?"
            }
        return "⚠️ $action"
    }

    companion object {
        const val ADMIN_TOOL_MODEL = "gpt-5.6-luna"
    }
}

/** 어드민 프롬프트 해석 결과: 관리 의도 없음 / SAFE 즉시 실행 / CONFIRM 확인 필요. */
sealed interface AdminAssistantDecision {
    /** 관리 의도가 없다(또는 비활성·실패) — 호출자는 기존 /질문 경로로 처리한다. */
    data object NotAdminAction : AdminAssistantDecision

    /** SAFE 액션 — 즉시 실행 가능. */
    data class ReadyToRun(
        val plan: AdminActionPlan,
    ) : AdminAssistantDecision

    /** CONFIRM 액션 — 확인 버튼([token])을 거쳐 실행. */
    data class NeedsConfirm(
        val plan: AdminActionPlan,
        val token: String,
    ) : AdminAssistantDecision
}
