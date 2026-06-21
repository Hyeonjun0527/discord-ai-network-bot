package com.discordassistant.central.participation.domain.service

import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.config.TalkativenessMultiplier
import kotlin.math.exp
import kotlin.math.ln

/**
 * talkativeness hazard scaling(NEXA-P12-T009, 순수 도메인 서비스). P12 는 "언제 말할지"를 연속시간 생존분석의
 * **hazard(위험률)** 로 모델링한다. 서버별 [TalkativenessMultiplier] 를 **speak/react hazard 에만** 적용해
 * 발화 빈도를 미세 조정한다 — 메시지 수 곱이 아니라 hazard logit(log-odds)에 `ln(multiplier)` 가산 보정이다
 * (P08-T017 [TalkativenessMultiplier] 경계와 일관).
 *
 * **acceptance(T009) — 0.5/1.0/1.5/2.0 에서 순서 보존과 cap 이 테스트된다**:
 * - [scaleHazard]: 같은 base hazard 에 대해 multiplier 가 클수록 보정 hazard 가 크다(순서 보존·단조).
 * - cap: hazard 는 [0, HAZARD_CAP] 로 클램프해 1 을 넘거나 폭주하지 않는다(과도 끼어들기 방지 — T010 대비).
 * - [scaleActionHazards]: SPEAK/REACT hazard 에만 적용하고 다른 action 은 건드리지 않는다.
 *
 * ml `ml/social-policy/src/nexa_policy/inference/talkativeness.py` 와 **같은 수식**을 쓴다
 * (학습·runtime 정합). 이 클래스는 P12 신규이고 기존 [PolicyCalibration]·
 * [TalkativenessMultiplier] 를 무변경으로 재사용한다(central 무변경 원칙 — 새 코드만 추가).
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 kotlin.math 만 쓴다.
 */
object TalkativenessHazardScaling {
    /** hazard 상한(폭주·과도 끼어들기 방지). 1.0 미만으로 둬 확정 사건이 되지 않게 한다(ml HAZARD_CAP 동일). */
    const val HAZARD_CAP: Double = 0.999

    /** hazard logit 계산용 확률 클램프 하한/상한(±∞ 방지). */
    private const val EPSILON: Double = 1e-12

    /** hazard scaling 을 적용하는 시간축 사건 action(ml TIMED_ACTIONS 와 동일). */
    private val TIMED_ACTIONS = setOf(SocialActionKind.SPEAK, SocialActionKind.REACT)

    /**
     * 단일 hazard 값에 multiplier 를 적용한다. hazard logit 에 [TalkativenessMultiplier.logitAdjustment]
     * (=ln(multiplier))를 가산한 뒤 [0, HAZARD_CAP] 로 클램프한다.
     *
     * multiplier 1.0 = 보정 없음(원 hazard, cap 만). >1 = hazard 증가(더 말 많음), <1 = 감소.
     * 순서 보존: multiplier 가 클수록 (cap 전까지) 보정 hazard 가 단조 증가한다(acceptance T009).
     */
    fun scaleHazard(
        baseHazard: Double,
        talkativeness: TalkativenessMultiplier,
    ): Double {
        require(baseHazard in 0.0..1.0) { "hazard 는 [0, 1] 확률이어야 한다: $baseHazard" }
        val h = baseHazard.coerceIn(EPSILON, 1.0 - EPSILON)
        val logit = ln(h / (1.0 - h)) + talkativeness.logitAdjustment()
        val scaled = 1.0 / (1.0 + exp(-logit))
        return scaled.coerceIn(0.0, HAZARD_CAP)
    }

    /**
     * action 별 hazard 맵에서 **SPEAK/REACT hazard 에만** multiplier 를 적용한다(다른 action 은 그대로 통과).
     * 메시지 수 곱이 아니라 hazard 보정이라는 T017 경계를 유지한다.
     */
    fun scaleActionHazards(
        hazards: Map<SocialActionKind, Double>,
        talkativeness: TalkativenessMultiplier,
    ): Map<SocialActionKind, Double> =
        hazards.mapValues { (kind, hazard) ->
            if (kind in TIMED_ACTIONS) scaleHazard(hazard, talkativeness) else hazard
        }
}
