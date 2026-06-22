package com.discordassistant.central.participation.domain.service

import com.discordassistant.central.participation.domain.model.action.SocialAct
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.decision.ActionDistribution

/**
 * 괴롭힘·모욕 안전 override(NEXA-P17-T015, 순수 도메인 서비스·무상태).
 *
 * banter-safety.md(T014) 가 정의한 행동 제한을 **하드 override** 로 강제한다 — "사람 같음" 이나 재미를 이유로
 * 괴롭힘에 가담/증폭하지 못하게, 위험한 socialAct/target 조합을 제거하거나 발화 자체를 cancel 한다.
 * [PolicySafetyConstraint](T021, action kind 게이트)와 보완 관계다: 그쪽은 동의/mute/permission/share 같은
 * 게이트로 *행동 종류* 를 막고, 이쪽은 *발화 종류(socialAct)+대상* 조합의 사회적 안전을 막는다.
 *
 * **acceptance(T015) — 안전 override 가 raw policy 와 함께 decision log 에 기록된다**:
 * [apply] 는 [SafetyOverrideResult] 로 (1) raw 분포([SafetyOverrideResult.raw]), (2) override 적용 분포
 * ([SafetyOverrideResult.overridden]), (3) 무엇이 왜 제거됐는지([SafetyOverrideResult.removals])를 함께 운반한다 —
 * decision log 가 raw 와 override 를 나란히 남길 수 있다(은폐 없는 감사성).
 *
 * override 규칙(모두 하드 — 모델 확률을 이긴다):
 * - **opt-out 대상에 대한 banter**: 사용자가 banter 를 opt-out 했으면 그 대상에게 TEASE 를 제거한다(user_opt_out).
 * - **표적 괴롭힘**: 같은 대상을 임계 이상 반복 표적화한 상태면 TEASE/DISAGREE/CORRECT 같은 공격적 act 를 제거한다.
 * - **중단 신호(stop)**: 대상이 중단 신호를 보냈으면 그 대상에 대한 모든 비-침묵 발화 act 를 제거한다(존중).
 *
 * socialAct 가 전부 제거돼 SPEAK 의 근거가 사라지면 SPEAK 를 action 분포에서 제거하고 재정규화한다(발화 취소).
 * 남는 비-침묵 행동이 없으면 IGNORE = 1.0(물러섬).
 *
 * 순수성: Spring/JPA/JDA·application 미참조. participation 도메인 타입·표준 타입만(NexaArchitectureTest).
 */
object BanterSafetyOverride {
    /**
     * [distribution] 에 banter 안전 override 를 적용한다. 위험 socialAct 를 제거·재정규화하고, 발화 근거가 사라지면
     * SPEAK 를 접는다. raw·override 분포·제거 사유를 [SafetyOverrideResult] 로 함께 돌려준다(decision log 근거).
     */
    fun apply(
        distribution: ActionDistribution,
        context: BanterSafetyContext,
    ): SafetyOverrideResult {
        val forbiddenActs = forbiddenActs(context)
        val removals = mutableListOf<SafetyOverrideRemoval>()

        // 1) 위험 socialAct 제거 + 재정규화(없으면 빈 분포 유지).
        val survivingActs =
            distribution.socialActWeights
                .filterKeys { it !in forbiddenActs }
                .filterValues { it > 0.0 }
        distribution.socialActWeights.forEach { (act, p) ->
            if (act in forbiddenActs && p > 0.0) {
                removals += SafetyOverrideRemoval(SafetyOverrideTargetKind.SOCIAL_ACT, act.name, reasonFor(act, context))
            }
        }
        val normalizedActs =
            if (survivingActs.isEmpty()) {
                emptyMap()
            } else {
                val sum = survivingActs.values.sum()
                survivingActs.mapValues { (_, p) -> p / sum }
            }

        // 2) 발화 근거(socialAct)가 사라졌는데 원래 SPEAK 가 있었으면 SPEAK 를 접는다(발화 취소).
        val speakCancelled =
            normalizedActs.isEmpty() &&
                distribution.socialActWeights.isNotEmpty() &&
                (distribution.actionWeights[SocialActionKind.SPEAK] ?: 0.0) > 0.0
        val actionWeights =
            if (speakCancelled) {
                removals +=
                    SafetyOverrideRemoval(
                        SafetyOverrideTargetKind.ACTION,
                        SocialActionKind.SPEAK.name,
                        SafetyOverrideReason.NO_SAFE_SOCIAL_ACT,
                    )
                renormalizeWithoutSpeak(distribution.actionWeights)
            } else {
                distribution.actionWeights
            }

        val overridden =
            distribution
                .withActionWeights(actionWeights)
                .copy(socialActWeights = normalizedActs)

        return SafetyOverrideResult(raw = distribution, overridden = overridden, removals = removals)
    }

    /** context 가 금지하는 socialAct 집합(하드 규칙). */
    private fun forbiddenActs(context: BanterSafetyContext): Set<SocialAct> {
        val forbidden = mutableSetOf<SocialAct>()
        if (context.targetStopRequested) {
            // 중단 신호: 그 대상에 대한 모든 비-침묵 발화 act 제거(존중).
            forbidden += NON_SILENT_ACTS
        }
        if (context.targetOptedOutOfBanter) {
            forbidden += SocialAct.TEASE
        }
        if (context.repeatedTargetingCount >= context.repeatedTargetingThreshold) {
            forbidden += AGGRESSIVE_ACTS
        }
        return forbidden
    }

    private fun reasonFor(
        act: SocialAct,
        context: BanterSafetyContext,
    ): SafetyOverrideReason =
        when {
            context.targetStopRequested -> SafetyOverrideReason.STOP_REQUESTED
            act == SocialAct.TEASE && context.targetOptedOutOfBanter -> SafetyOverrideReason.BANTER_OPT_OUT
            else -> SafetyOverrideReason.REPEATED_TARGETING
        }

    private fun renormalizeWithoutSpeak(weights: Map<SocialActionKind, Double>): Map<SocialActionKind, Double> {
        val surviving = weights.filterKeys { it != SocialActionKind.SPEAK }.filterValues { it > 0.0 }
        if (surviving.isEmpty()) return mapOf(SocialActionKind.IGNORE to 1.0)
        val sum = surviving.values.sum()
        return surviving.mapValues { (_, p) -> p / sum }
    }

    /** 공격적 발화 act(표적 괴롭힘 상태에서 제거 대상). */
    private val AGGRESSIVE_ACTS = setOf(SocialAct.TEASE, SocialAct.DISAGREE, SocialAct.CORRECT)

    /** 비-침묵 발화 act 전체(중단 신호 시 모두 제거). UNKNOWN 은 보수적으로 포함한다. */
    private val NON_SILENT_ACTS = SocialAct.entries.toSet()
}

/**
 * banter 안전 override 입력(순수 도메인 값 객체). 특정 발화 대상에 대한 **안전 신호** 만 담는다 — 모델 사회 판단이
 * 아니라 관찰/사용자 의사 사실이다(opt-out·중단 신호·표적 반복).
 */
data class BanterSafetyContext(
    /** 대상이 banter(TEASE)를 opt-out 했는가(user-opt-out.md). */
    val targetOptedOutOfBanter: Boolean = false,
    /** 대상이 명시적 중단 신호("그만"·차단 등)를 보냈는가 — 모든 비-침묵 발화 제거. */
    val targetStopRequested: Boolean = false,
    /** 최근 같은 대상을 표적화한 누적 횟수(관찰 사실). [repeatedTargetingThreshold] 이상이면 공격적 act 제거. */
    val repeatedTargetingCount: Int = 0,
    /** 표적 괴롭힘으로 보는 반복 임계. */
    val repeatedTargetingThreshold: Int = 3,
) {
    init {
        require(repeatedTargetingCount >= 0) { "repeatedTargetingCount 는 음수일 수 없다: $repeatedTargetingCount" }
        require(repeatedTargetingThreshold >= 1) { "repeatedTargetingThreshold 는 1 이상이어야 한다: $repeatedTargetingThreshold" }
    }
}

/** override 가 무엇을 제거했는지의 대상 종류(decision log 구분). */
enum class SafetyOverrideTargetKind {
    /** 발화 종류(socialAct) 제거. */
    SOCIAL_ACT,

    /** 행동 종류(action kind, 예: SPEAK 취소) 제거. */
    ACTION,
}

/** override 제거 사유 코드(decision log — 자유 텍스트 아님). */
enum class SafetyOverrideReason {
    /** 대상이 banter 를 opt-out 함. */
    BANTER_OPT_OUT,

    /** 같은 대상 반복 표적화(표적 괴롭힘 방지). */
    REPEATED_TARGETING,

    /** 대상이 중단 신호를 보냄. */
    STOP_REQUESTED,

    /** 안전 override 후 남은 안전한 발화 종류가 없어 SPEAK 를 접음. */
    NO_SAFE_SOCIAL_ACT,
}

/** override 제거 1건 — 무엇을(이름) 왜(사유) 제거했는지. raw 와 함께 decision log 에 남는다(acceptance T015). */
data class SafetyOverrideRemoval(
    val targetKind: SafetyOverrideTargetKind,
    val name: String,
    val reason: SafetyOverrideReason,
)

/**
 * banter 안전 override 결과(순수 도메인 값 객체). **raw 분포·override 분포·제거 목록을 함께** 운반한다 —
 * decision log 가 raw policy 와 안전 override 를 나란히 기록할 수 있게(acceptance T015 — 은폐 없는 감사성).
 */
data class SafetyOverrideResult(
    /** override 적용 전 원본 분포(raw policy). */
    val raw: ActionDistribution,
    /** override 적용 후 분포(위험 act 제거·SPEAK 취소 반영). */
    val overridden: ActionDistribution,
    /** 제거된 socialAct/action 과 사유(없으면 빈 목록 = override 무발생). */
    val removals: List<SafetyOverrideRemoval>,
) {
    /** override 가 실제로 무언가를 바꿨는가(decision log 기록 여부 판단). */
    val changed: Boolean
        get() = removals.isNotEmpty()
}
