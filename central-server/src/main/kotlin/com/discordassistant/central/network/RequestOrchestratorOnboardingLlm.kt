package com.discordassistant.central.network

import com.discordassistant.central.domain.RequestState
import com.discordassistant.central.routing.AiRequestInput
import com.discordassistant.central.routing.RequestOrchestrator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * [OnboardingLlm] 의 운영 구현. 온보딩 분석 프롬프트를 [RequestOrchestrator] 로 **동기** 호출해(/ask 와 같은 경로)
 * 응답 텍스트를 돌려준다.
 *
 * `network` → `routing` 의존은 ArchUnit 에서 허용된다([ArchitectureTest]). [RequestOrchestrator.handle] 은
 * `session.sendInfer().get()` 으로 시한(orTimeout) 내 동기 완료되므로, 게이트웨이를 무한 블로킹하지 않는다.
 *
 * **프로바이더 부재/거부/실패는 예외 대신 `null`** 로 변환한다 → [OnboardingAnalyzer] 가 휴리스틱으로 폴백.
 * 분석은 관리자 트리거(`/ai-onboard`)이므로 `isAdmin=true` 로 보낸다(요청 무게/쿼터 우회는 /ask 관리자 경로와 동일).
 */
@Component
class RequestOrchestratorOnboardingLlm(
    private val orchestrator: RequestOrchestrator,
) : OnboardingLlm {
    override fun complete(prompt: String): String? {
        val result =
            runCatching {
                orchestrator.handle(
                    AiRequestInput(
                        guildId = 0L,
                        channelId = 0L,
                        userId = 0L,
                        prompt = prompt,
                        roleIds = emptySet(),
                        command = "onboarding-analyze",
                        isAdmin = true,
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
