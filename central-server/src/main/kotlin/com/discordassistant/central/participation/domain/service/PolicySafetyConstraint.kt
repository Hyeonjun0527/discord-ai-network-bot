package com.discordassistant.central.participation.domain.service

import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.decision.ActionDistribution

/**
 * 정책 안전 후처리(NEXA-P08-T021, 순수 도메인 서비스·무상태). 모델 분포에 **하드 제약** 만 적용해 금지 행동을
 * 제거한다 — 동의, 채널 mute, 점유율(share) cap, 전송 permission, kill switch. 사회적 적절성 판단(끼어들지/물러설지,
 * 농담 허용 등)은 **여기 들어오지 않는다**(그건 모델·calibration 의 몫).
 *
 * **acceptance(T021) — 사회적 판단 휴리스틱은 이 클래스에 들어가지 않는다**:
 * 입력([SafetyConstraintInput])은 전부 **불리언 게이트 + 수치 cap** 이다(동의/mute/permission/kill switch/share).
 * familiarity·banter·talkativeness 같은 사회 신호를 읽지 않는다. 제약은 "확률을 이긴다" — 모델이 SPEAK 0.9 라도
 * 게이트가 막으면 0 으로 만들고 분포를 재정규화한다. 막힌 뒤 남는 확률이 없으면 IGNORE 로 접는다.
 *
 * 적용 규칙(모두 하드):
 * - **kill switch** 또는 **동의 없음**: 모든 비-침묵 행동(REACT/SPEAK/CANCEL) 제거 → IGNORE 만 남는다.
 * - **채널 mute**: REACT·SPEAK 제거(관찰/대기만 허용).
 * - **전송 permission 없음**: SPEAK 제거(외부 전송 불가 — 발화해도 보낼 수 없음).
 * - **share cap 초과**(NEXA 점유율 ≥ cap): SPEAK 제거(과점 차단). REACT 는 허용(가벼운 신호).
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 타입만 쓴다.
 */
object PolicySafetyConstraint {
    /**
     * [response] 분포에 하드 제약을 적용해 금지된 action kind 의 확률을 0 으로 만들고 재정규화한다. 남는 확률이
     * 없으면 IGNORE = 1.0. socialAct/target/delay/burst 형태는 그대로 둔다(action kind 게이팅만 — 사회 판단 없음).
     */
    fun apply(
        distribution: ActionDistribution,
        input: SafetyConstraintInput,
    ): SafetyConstraintResult {
        val forbidden = forbiddenKinds(input)
        val removed = forbidden.filter { (distribution.actionWeights[it] ?: 0.0) > 0.0 }.toSet()

        val surviving =
            distribution.actionWeights
                .filterKeys { it !in forbidden }
                .filterValues { it > 0.0 }

        val constrainedWeights =
            if (surviving.isEmpty()) {
                // 모두 막혔으면 IGNORE 로 접는다(IGNORE 도 정상 경로 — quota 무소모).
                mapOf(SocialActionKind.IGNORE to 1.0)
            } else {
                val sum = surviving.values.sum()
                surviving.mapValues { (_, p) -> p / sum }
            }

        return SafetyConstraintResult(
            constrained = distribution.withActionWeights(constrainedWeights),
            removedKinds = removed,
        )
    }

    /** 입력 게이트로 금지되는 action kind 집합(하드 규칙만 — 사회 판단 없음). */
    private fun forbiddenKinds(input: SafetyConstraintInput): Set<SocialActionKind> {
        // kill switch·동의 없음: 침묵만 허용.
        if (input.killSwitchEngaged || !input.consentGranted) {
            return setOf(SocialActionKind.REACT, SocialActionKind.SPEAK, SocialActionKind.CANCEL_PENDING)
        }
        val forbidden = mutableSetOf<SocialActionKind>()
        if (input.channelMuted) {
            forbidden += SocialActionKind.REACT
            forbidden += SocialActionKind.SPEAK
        }
        if (!input.hasSendPermission) {
            forbidden += SocialActionKind.SPEAK
        }
        if (input.nexaShare >= input.shareCap) {
            // 점유율 cap 초과: 발화 차단(과점 방지). REACT 는 허용.
            forbidden += SocialActionKind.SPEAK
        }
        return forbidden
    }
}

/**
 * 안전 후처리 입력(순수 도메인 값 객체). **하드 게이트 + 수치 cap 만**(사회 신호 없음 — acceptance T021).
 */
data class SafetyConstraintInput(
    /** 동의가 있는가 — 없으면 침묵만. */
    val consentGranted: Boolean,
    /** 채널이 mute 됐는가 — true 면 REACT/SPEAK 차단. */
    val channelMuted: Boolean,
    /** Discord 전송 권한이 있는가 — 없으면 SPEAK 차단(보낼 수 없음). */
    val hasSendPermission: Boolean,
    /** 운영 kill switch 가 걸렸는가 — true 면 침묵만. */
    val killSwitchEngaged: Boolean,
    /** NEXA 의 현재 burst 점유율 [0,1](관찰 사실, 사회 판단 아님). [shareCap] 이상이면 SPEAK 차단. */
    val nexaShare: Double,
    /** 점유율 상한 [0,1] — 이 값 이상이면 과점으로 보고 SPEAK 차단. */
    val shareCap: Double,
) {
    init {
        require(nexaShare in 0.0..1.0) { "nexaShare 는 [0,1] 범위여야 한다: $nexaShare" }
        require(shareCap in 0.0..1.0) { "shareCap 는 [0,1] 범위여야 한다: $shareCap" }
    }
}

/**
 * 안전 후처리 결과(순수 도메인 값 객체). 제약 적용된 분포와 **제거된 action kind**(decision log 의 applied
 * constraints 근거, T022/T023)를 함께 운반한다.
 */
data class SafetyConstraintResult(
    /** 하드 제약 적용 후 재정규화된 분포(남는 게 없으면 IGNORE=1.0). */
    val constrained: ActionDistribution,
    /** 제약으로 0 이 된(원래 확률 > 0 이었던) action kind 들 — 무엇이 막혔는지의 근거. */
    val removedKinds: Set<SocialActionKind>,
)
