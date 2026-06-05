package com.discordassistant.central.onboarding.application

import com.discordassistant.central.routing.application.RequestOrchestrator
import com.discordassistant.central.routing.domain.model.AiRequestInput
import com.discordassistant.central.shared.RequestState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * [OnboardingLlm] 의 운영 구현. 온보딩 분석 프롬프트를 [RequestOrchestrator] 로 **동기** 호출해(/ask 와 같은 경로)
 * 응답 텍스트를 돌려준다.
 *
 * `network` → `routing` 의존은 ArchUnit 에서 허용된다([ArchitectureTest]). [RequestOrchestrator.handle] 은
 * `session.sendInfer().get()` 으로 시한(orTimeout) 내 동기 완료되므로, 게이트웨이를 무한 블로킹하지 않는다.
 *
 * **실제 길드/채널/actor 컨텍스트로 라우팅한다**(A). 프로바이더는 `registry.byGuild(guildId)` 로 길드별 풀에서 찾으므로
 * 더미 guildId(0) 로 보내면 그 풀이 비어 항상 NO_PROVIDER → 휴리스틱 폴백이 된다. 따라서 [OnboardingAnalysisContext]
 * 의 실제 값을 그대로 [AiRequestInput] 에 넣는다.
 *
 * **프로바이더 부재/거부/실패는 예외 대신 `null`** 로 변환한다 → [OnboardingAnalyzer] 가 휴리스틱으로 폴백.
 * 분석은 관리자 트리거(`/ai-onboard`)이므로 `isAdmin` 을 actor 의 관리자 여부로 전달한다. 단 `isAdmin` 은
 * [RequestOrchestrator] 에서 `RequestContext` 에만 쓰이고 무게/쿼터/채널 게이트를 우회하지는 않는다 —
 * 게이트에 막히면 기존처럼 `null`→휴리스틱으로 안전하게 degrade 한다.
 */
@Component
class RequestOrchestratorOnboardingLlm(
    private val orchestrator: RequestOrchestrator,
) : OnboardingLlm {
    override fun complete(
        prompt: String,
        context: OnboardingAnalysisContext,
    ): String? {
        val result =
            runCatching {
                orchestrator.handle(
                    AiRequestInput(
                        guildId = context.guildId,
                        channelId = context.channelId,
                        userId = context.actorUserId ?: 0L,
                        prompt = prompt,
                        roleIds = context.actorRoleIds,
                        command = "onboarding-analyze",
                        isAdmin = context.actorIsGuildAdmin,
                        responseMode = "balanced",
                    ),
                )
            }.getOrElse {
                log.warn("onboarding analyze orchestration threw: {}", it.message)
                return null
            }
        if (result.state != RequestState.COMPLETED) {
            log.info("onboarding analyze not completed: state={} reason={}", result.state, result.failReason)
            return null
        }
        return result.text?.takeIf { it.isNotBlank() }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(RequestOrchestratorOnboardingLlm::class.java)
    }
}
