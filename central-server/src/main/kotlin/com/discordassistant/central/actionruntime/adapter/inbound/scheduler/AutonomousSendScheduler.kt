package com.discordassistant.central.actionruntime.adapter.inbound.scheduler

import com.discordassistant.central.actionruntime.adapter.outbound.multiresponse.MultiResponseBurstAdapter
import com.discordassistant.central.actionruntime.application.execution.ActionExecutionService
import com.discordassistant.central.actionruntime.application.execution.ExecutionOutcome
import com.discordassistant.central.actionruntime.application.scheduler.DueActionDisposition
import com.discordassistant.central.actionruntime.application.scheduler.DueActionPoller
import com.discordassistant.central.actionruntime.domain.model.ScheduledActionType
import com.discordassistant.central.participation.application.rollout.CanarySignalCollector
import com.discordassistant.central.participation.application.shadow.ShadowStatusService
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * NEXA **자율 전송 poll→execute 오케스트레이터**(NEXA-P13 실행 seam, actionruntime 인바운드 어댑터).
 *
 * DB polling scheduler 의 실제 트리거다: 주기적으로 [DueActionPoller.pollOnce] 를 호출해 due 예약을 claim·재평가하고,
 * 통과(READY_TO_TYPE)한 SPEAK 를 [ActionExecutionService] 로 실제 전송 경로에 태운다. 단일 flag
 * `central.nexa.autonomous-send.enabled` 뒤에 있고 기본 부재(=OFF)라, 운영자가 켜기 전에는 이 빈이 생성되지 않아 tick
 * 자체가 돌지 않는다(니아 자율 발화 없음).
 *
 * **안전 불변식**:
 *  - **shadow hard block**: [ActionExecutionService.execute] 는 실행 직전 [com.discordassistant.central.actionruntime
 *    .application.port.out.ActionExecutionModePort] 로 현재 모드를 다시 읽는다. 여기서 [ShadowMode.LIVE] 를 넘기는 것은
 *    "요청 상한" 일 뿐이며, 채널이 OFF/OBSERVE_ONLY/SHADOW_PREDICT 면 executor 를 한 번도 호출하지 않는다(전송 0).
 *  - **단일 버블**: 아직 멀티 버블 burst 는 만들지 않는다 — [MultiResponseBurstAdapter.fromPseudoStream] 으로 버블 1개
 *    ([com.discordassistant.central.actionruntime.domain.model.BurstPlan.single]) 계획만 세운다.
 *  - **SPEAK 만**: content 가 있는 것은 SPEAK 뿐이다. REACT 는 아직 미지원이라 로그만 남기고 건너뛴다(별도 실행 경로 필요).
 *  - **graceful**: poll·개별 execute 실패는 흡수한다(runCatching + 로그) — 한 건 실패가 tick 전체나 다른 예약을 막지 않는다.
 *
 * 스케줄링은 앱 전역 [org.springframework.scheduling.annotation.EnableScheduling](RelayWebSocketConfig)로 이미 켜져 있다.
 */
@Component
@ConditionalOnProperty(name = ["central.nexa.autonomous-send.enabled"], havingValue = "true")
class AutonomousSendScheduler(
    private val dueActionPoller: DueActionPoller,
    private val actionExecutionService: ActionExecutionService,
    private val burstAdapter: MultiResponseBurstAdapter,
    private val shadowStatus: ShadowStatusService,
    private val canarySignals: CanarySignalCollector,
) {
    private val log = LoggerFactory.getLogger(AutonomousSendScheduler::class.java)

    /**
     * 한 tick: due 예약을 claim·재평가하고, READY_TO_TYPE SPEAK 를 실제 전송 경로에 태운다. poll 실패나 개별 실행 실패는
     * 흡수한다(다른 예약·다음 tick 에 영향 없음). REACT 등 비 SPEAK 는 미지원으로 로그만 남기고 건너뛴다.
     */
    @Scheduled(fixedDelayString = "\${central.nexa.autonomous-send.poll-interval-ms:2000}")
    fun tick() {
        val outcomes =
            try {
                dueActionPoller.pollOnce()
            } catch (e: Exception) {
                // poll 실패는 다음 tick 에 다시 시도한다 — 흡수 후 로그(스케줄러 스레드 크래시 방지).
                log.warn("NEXA 자율 전송 poll 실패 — 다음 tick 에 재시도: {}", e.message)
                return
            }
        for (outcome in outcomes) {
            if (outcome.disposition != DueActionDisposition.READY_TO_TYPE) continue
            val action = outcome.action // 재평가 통과 → TYPING 상태(ActionExecutionService 진입 요건).
            if (action.type != ScheduledActionType.SPEAK) {
                // REACT 등은 별도 실행 경로(ReactionExecutionService) 필요 — 현재 자율 전송은 SPEAK 만 지원.
                log.info("NEXA 자율 전송: {} 는 아직 미지원 — skip(action={})", action.type, action.identity.value)
                continue
            }
            runCatching {
                // 단일 버블 계획(참조=예약 identity — content 저장 키와 동일). execute 의 modePort 가 실제 전송을 게이팅한다.
                val plan = burstAdapter.fromPseudoStream(action.identity.value)
                actionExecutionService.execute(ShadowMode.LIVE, action, plan)
            }.onSuccess { outcome ->
                // 실행 결과를 canary 신호로 집계한다(자동 중단 SAFETY NET 의 입력). 완료=발화, 동의 철회 mid-burst=
                // privacy error, backpressure staleness=stale send. 그 외(Suppressed/Retry/Failed 등)는 무집계.
                recordCanarySignal(action.target.guildPseudonym, outcome)
            }.onFailure { e ->
                // 실패를 shadow 상태 관측에 기록한다 → 대시보드 errorRate 가 실제 파이프라인 실패를 반영(카나리 자동
                // 강등 판단·운영자 가시성의 근거). 이게 없으면 errorRate 가 항상 0 으로 "건강함"을 오판한다.
                runCatching { shadowStatus.recordError(action.target.guildPseudonym) }
                log.warn("NEXA 자율 전송 실행 실패(action={}) — 이 예약만 건너뜀: {}", action.identity.value, e.message)
            }
        }
    }

    /**
     * 한 실행 결과를 길드별 canary 신호로 집계한다(자동 중단 판정 입력). 신호 기록 실패가 tick 을 막지 않도록 흡수한다.
     *  - [ExecutionOutcome.Completed] → 발화(over_talk 신호).
     *  - 동의 철회 mid-burst 취소([ActionExecutionService.REASON_REVOKED_MID_BURST]) → privacy error 신호.
     *  - backpressure staleness 취소([ActionExecutionService.REASON_TOO_STALE]) → stale send 신호.
     * 그 외(Suppressed/RetryScheduled/Failed·기타 사유)는 집계하지 않는다.
     */
    private fun recordCanarySignal(
        guildPseudonym: String,
        outcome: ExecutionOutcome,
    ) {
        runCatching {
            when (outcome) {
                is ExecutionOutcome.Completed -> canarySignals.recordUtterance(guildPseudonym)
                is ExecutionOutcome.Cancelled -> recordByReason(guildPseudonym, outcome.reason)
                is ExecutionOutcome.PartiallyCancelled -> recordByReason(guildPseudonym, outcome.reason)
                else -> Unit
            }
        }
    }

    private fun recordByReason(
        guildPseudonym: String,
        reason: String,
    ) {
        when (reason) {
            ActionExecutionService.REASON_REVOKED_MID_BURST -> canarySignals.recordPrivacyError(guildPseudonym)
            ActionExecutionService.REASON_TOO_STALE -> canarySignals.recordStaleSend(guildPseudonym)
            else -> Unit
        }
    }
}
