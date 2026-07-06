package com.discordassistant.central.actionruntime.adapter.inbound.scheduler

import com.discordassistant.central.actionruntime.application.execution.ActionExecutionService
import com.discordassistant.central.actionruntime.application.execution.BackpressureGate
import com.discordassistant.central.actionruntime.application.port.out.ActionAuditPort
import com.discordassistant.central.actionruntime.application.port.out.ActionExecutionModePort
import com.discordassistant.central.actionruntime.application.port.out.ActionReevaluationPort
import com.discordassistant.central.actionruntime.application.port.out.ActionSchedulerPort
import com.discordassistant.central.actionruntime.application.port.out.DiscordExecutorPort
import com.discordassistant.central.actionruntime.application.port.out.ReevaluationTarget
import com.discordassistant.central.actionruntime.application.reevaluate.StaleActionReevaluator
import com.discordassistant.central.actionruntime.application.scheduler.DueActionPoller
import com.discordassistant.central.actionruntime.application.scheduler.SceneEvidenceProvider
import com.discordassistant.central.actionruntime.domain.service.CancellationPolicy
import com.discordassistant.central.actionruntime.domain.service.SceneEvidence
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * NEXA **자율 전송 실행 유스케이스 배선**(NEXA-P13 실행 seam, actionruntime 인바운드 어댑터).
 *
 * 그동안 dormant 였던 actionruntime 실행 파이프라인(due poll → 재평가 → 실제 전송)을 실 production 빈으로 조립해,
 * [AutonomousSendScheduler] tick 이 예약된 SPEAK 를 전송 경로에 태울 수 있게 한다. 순수 application 유스케이스
 * ([DueActionPoller]/[ActionExecutionService], 그 자체는 @Component 아님)를 협력자와 함께 이 어댑터에서 조립한다.
 * 전송 어댑터([DiscordExecutorPort])는 platform 경계([com.discordassistant.central.platform.discord.nexa
 * .JdaDiscordExecutorConfig])가 제공하는 빈을 주입받는다(헥사고날 — actionruntime 은 포트만 안다).
 *
 * 전체가 단일 flag `central.nexa.autonomous-send.enabled` 뒤에 있고 기본 부재(=OFF)라, 운영자가 명시로 켜기 전에는
 * 이 빈들이 생성되지 않는다(canary 안전 모델·기존 흐름 100% 무영향).
 *
 * **다층 안전(flag ON 이어도)**: 실제 Discord 전송은 실행 직전 [ActionExecutionModePort]
 * ([com.discordassistant.central.actionruntime.adapter.outbound.participation.ParticipationActionExecutionModeAdapter])
 * 가 현재 shadow/canary/live 모드를 다시 읽어 게이팅한다 — 길드/채널이 명시로 CANARY/LIVE 로 승격되지 않았으면
 * [ActionExecutionService] 가 executor 를 한 번도 호출하지 않는다(전송 0). flag 는 "파이프라인을 살릴지", ShadowMode 는
 * "그 채널에 실제로 보낼지" 의 **직교** 게이트다.
 *
 * 보수적 기본 포트([ActionReevaluationPort]·[SceneEvidenceProvider]) — participation-backed 실 어댑터가 아직 없어
 * canary 등급 기본값을 둔다(항상 진행·취소 안 함). 실 전송은 위 ShadowMode 게이트가 별도로 막으므로 안전하다. 실
 * 어댑터가 뒤에 등록되면 [ConditionalOnMissingBean] 으로 이 기본값이 물러난다.
 */
@Configuration
@ConditionalOnProperty(name = ["central.nexa.autonomous-send.enabled"], havingValue = "true")
class AutonomousSendConfig {
    /**
     * canary 등급 기본 재평가 포트 — 항상 "진행"(stale 취소 안 함). participation-backed 실 어댑터가 없을 때만 쓰인다.
     * 실 전송 여부는 [ActionExecutionModePort] 의 ShadowMode 가 별도로 게이팅하므로, 이 기본값이 전송을 유발하지 않는다.
     */
    @Bean
    @ConditionalOnMissingBean(ActionReevaluationPort::class)
    fun nexaAutonomousReevaluationPort(): ActionReevaluationPort =
        object : ActionReevaluationPort {
            override fun currentContextVersion(target: ReevaluationTarget): Long? = PROCEED_CONTEXT_VERSION

            override fun stillValid(
                decisionId: String,
                target: ReevaluationTarget,
                scheduledContextVersion: Long,
                currentContextVersion: Long,
            ): Boolean = true
        }

    /** canary 등급 기본 scene evidence — 취소 근거 없음([CancellationPolicy] KEEP). 실 어댑터가 있으면 물러난다. */
    @Bean
    @ConditionalOnMissingBean(SceneEvidenceProvider::class)
    fun nexaAutonomousSceneEvidenceProvider(): SceneEvidenceProvider =
        SceneEvidenceProvider {
            SceneEvidence(humanRepliesSinceSchedule = 0, currentFocusThreadId = null, targetExpired = false)
        }

    /** due poll 한 tick 본문(claim → 취소정책 → 재평가 → TYPING). [AutonomousSendScheduler] 가 주기 호출한다. */
    @Bean
    @ConditionalOnMissingBean(DueActionPoller::class)
    fun nexaDueActionPoller(
        scheduler: ActionSchedulerPort,
        reevaluationPort: ActionReevaluationPort,
        sceneEvidenceProvider: SceneEvidenceProvider,
    ): DueActionPoller =
        DueActionPoller(
            scheduler = scheduler,
            reevaluator = StaleActionReevaluator(reevaluationPort),
            cancellationPolicy = CancellationPolicy(),
            sceneEvidenceProvider = sceneEvidenceProvider,
            clock = Clock.systemUTC(),
        )

    /** 실제 실행 유스케이스(typing → 버블 전송 → 완료). shadow/OFF 채널은 executor 미호출(P09 hard block). */
    @Bean
    @ConditionalOnMissingBean(ActionExecutionService::class)
    fun nexaActionExecutionService(
        executor: DiscordExecutorPort,
        scheduler: ActionSchedulerPort,
        reevaluationPort: ActionReevaluationPort,
        audit: ActionAuditPort,
        modePort: ActionExecutionModePort,
    ): ActionExecutionService =
        ActionExecutionService(
            executor = executor,
            scheduler = scheduler,
            reevaluation = reevaluationPort,
            audit = audit,
            backpressure = BackpressureGate(),
            clock = Clock.systemUTC(),
            modePort = modePort,
        )

    private companion object {
        /** 기본 재평가 포트가 돌려주는 현재 버전 — 어떤 예약 버전과도 다르면 stillValid=true 로 진행(항상 PROCEED). */
        const val PROCEED_CONTEXT_VERSION: Long = Long.MIN_VALUE
    }
}
