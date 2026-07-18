package com.discordassistant.central.actionruntime.adapter.inbound.scheduler

import com.discordassistant.central.actionruntime.application.execution.ActionExecutionService
import com.discordassistant.central.actionruntime.application.execution.BackpressureGate
import com.discordassistant.central.actionruntime.application.execution.ReactionExecutionService
import com.discordassistant.central.actionruntime.application.port.out.ActionAuditPort
import com.discordassistant.central.actionruntime.application.port.out.ActionConsentPort
import com.discordassistant.central.actionruntime.application.port.out.ActionExecutionModePort
import com.discordassistant.central.actionruntime.application.port.out.ActionOutcomeObservationPort
import com.discordassistant.central.actionruntime.application.port.out.ActionReevaluationPort
import com.discordassistant.central.actionruntime.application.port.out.ActionSchedulerPort
import com.discordassistant.central.actionruntime.application.port.out.DiscordExecutorPort
import com.discordassistant.central.actionruntime.application.port.out.ExecutionPermitPort
import com.discordassistant.central.actionruntime.application.port.out.WaitReevaluationOutboxPort
import com.discordassistant.central.actionruntime.application.reevaluate.StaleActionReevaluator
import com.discordassistant.central.actionruntime.application.scheduler.DueActionPoller
import com.discordassistant.central.actionruntime.application.scheduler.SceneEvidenceProvider
import com.discordassistant.central.actionruntime.domain.service.CancellationPolicy
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
 * 재평가와 장면 evidence는 social-policy projection을 읽는 별도 어댑터가 제공한다. 장면을 읽을 수 없는 예약 행동은
 * fail-closed하므로, 오래된 문장이 새 대화 위에 그대로 전송되는 우회 경로가 없다.
 */
@Configuration
@ConditionalOnProperty(name = ["central.nexa.autonomous-send.enabled"], havingValue = "true")
class AutonomousSendConfig {
    /** due poll 한 tick 본문(claim → 취소정책 → 재평가 → TYPING). [AutonomousSendScheduler] 가 주기 호출한다. */
    @Bean
    @ConditionalOnMissingBean(DueActionPoller::class)
    fun nexaDueActionPoller(
        scheduler: ActionSchedulerPort,
        reevaluationPort: ActionReevaluationPort,
        sceneEvidenceProvider: SceneEvidenceProvider,
        waitReevaluationOutbox: WaitReevaluationOutboxPort,
    ): DueActionPoller =
        DueActionPoller(
            scheduler = scheduler,
            reevaluator = StaleActionReevaluator(reevaluationPort),
            cancellationPolicy = CancellationPolicy(),
            sceneEvidenceProvider = sceneEvidenceProvider,
            clock = Clock.systemUTC(),
            waitReevaluationOutbox = waitReevaluationOutbox,
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
        outcomeObserver: ActionOutcomeObservationPort,
        actionConsent: ActionConsentPort,
        executionPermit: ExecutionPermitPort,
    ): ActionExecutionService =
        ActionExecutionService(
            executor = executor,
            scheduler = scheduler,
            reevaluation = reevaluationPort,
            audit = audit,
            backpressure = BackpressureGate(),
            clock = Clock.systemUTC(),
            modePort = modePort,
            outcomeObserver = outcomeObserver,
            consent = actionConsent,
            executionPermit = executionPermit,
        )

    /** REACT 실행 유스케이스. 실패는 SPEAK로 바꾸지 않고 terminal 상태로 끝낸다. */
    @Bean
    @ConditionalOnMissingBean(ReactionExecutionService::class)
    fun nexaReactionExecutionService(
        executor: DiscordExecutorPort,
        scheduler: ActionSchedulerPort,
        reevaluationPort: ActionReevaluationPort,
        audit: ActionAuditPort,
        modePort: ActionExecutionModePort,
        outcomeObserver: ActionOutcomeObservationPort,
        actionConsent: ActionConsentPort,
        executionPermit: ExecutionPermitPort,
    ): ReactionExecutionService =
        ReactionExecutionService(
            executor = executor,
            scheduler = scheduler,
            reevaluation = reevaluationPort,
            audit = audit,
            clock = Clock.systemUTC(),
            modePort = modePort,
            outcomeObserver = outcomeObserver,
            consent = actionConsent,
            executionPermit = executionPermit,
        )
}
