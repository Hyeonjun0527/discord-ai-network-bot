package com.discordassistant.central.actionruntime.application.reevaluate

import com.discordassistant.central.actionruntime.application.port.out.ActionReevaluationPort
import com.discordassistant.central.actionruntime.application.port.out.ReevaluationTarget
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction

/**
 * contextVersion 재평가 유스케이스(NEXA-P13-T011, application 레이어).
 *
 * claim 된(REEVALUATING) 예약 행동을 **실행 직전에** 다시 본다: 예약 당시 contextVersion 과 due 시점 현재 scene
 * version 이 다르면(stale), participation 에 재평가를 요청한다. 재평가가 "여전히 유효" 면 진행(PROCEED), "더는
 * 아님" 이면 취소(CANCEL). 현재 장면 자체가 사라졌으면(version null) stale 로 보고 취소한다.
 *
 * **acceptance(T011) — stale action 을 그대로 실행하는 코드 경로가 없다**: 모든 due 행동은 반드시 이 재평가를 거친다
 * (실행 경로가 [decide] 결과로만 TYPING 으로 진행). version 이 같으면 participation 재호출 없이 바로 PROCEED(캐시
 * 재사용 — ContextVersion 의 설계 의도)이지만, 다르면 반드시 재판단한다.
 *
 * 순수성 경계: application 레이어 — 포트·도메인 타입만. Spring/JPA/JDA 미참조.
 */
class StaleActionReevaluator(
    private val reevaluation: ActionReevaluationPort,
) {
    fun currentContextVersion(target: ReevaluationTarget): Long? = reevaluation.currentContextVersion(target)

    fun currentSceneContextVersion(target: ReevaluationTarget): Long? = reevaluation.currentSceneContextVersion(target)

    /**
     * [action] 을 [target] 기준으로 재평가한다.
     * - 현재 버전 == 예약 버전: stale 아님 → [ReevaluationOutcome.PROCEED](직전 판단 재사용).
     * - 현재 버전 == null(장면 소멸): stale → [ReevaluationOutcome.CANCEL].
     * - 현재 버전 != 예약 버전: participation 재평가 → 유효면 PROCEED, 아니면 CANCEL.
     */
    fun decide(
        action: ScheduledSocialAction,
        target: ReevaluationTarget,
    ): ReevaluationOutcome {
        val current = currentContextVersion(target) ?: return ReevaluationOutcome.CANCEL
        if (!action.isStale(current)) return ReevaluationOutcome.PROCEED
        val stillValid =
            reevaluation.stillValid(
                decisionId = action.decisionId,
                target = target,
                scheduledContextVersion = action.contextVersion,
                currentContextVersion = current,
            )
        return if (stillValid) ReevaluationOutcome.PROCEED else ReevaluationOutcome.CANCEL
    }
}

/**
 * 재평가 결과(application enum). 실행 경로가 이 값으로만 TYPING 진행/취소를 결정한다 — stale 직행 경로 없음(T011).
 */
enum class ReevaluationOutcome {
    /** 유효(또는 stale 아님) — TYPING 으로 진행. */
    PROCEED,

    /** stale·무효 — 취소(CANCELLED). */
    CANCEL,
}
