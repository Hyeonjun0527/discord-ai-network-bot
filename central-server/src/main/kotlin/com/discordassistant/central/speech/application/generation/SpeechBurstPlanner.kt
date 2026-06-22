package com.discordassistant.central.speech.application.generation

import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.domain.model.SpeechBurstShape
import java.time.Duration

/**
 * 발화 버스트 계획기(NEXA-P14-T022, application).
 *
 * 선택된 후보([SpeechCandidate])를 policy [SpeechBurstShape] 에 맞춰 **speech 자기** 버스트 계획([SpeechBurstPlan])
 * 으로 변환한다. 실제 전송은 actionruntime executor 가 하고 OBSERVE_ONLY hard block(OutboundGuard)이 유지된다 — 이
 * 계획기는 **계획만** 만든다(전송 0).
 *
 * **module-dag 경계(불변식)**: speech 는 actionruntime 을 **모른다**. actionruntime 이 전송 직전 speech 읽기 포트로
 * 이 계획을 조회해 자기 P13 BurstPlan 으로 매핑한다(actionruntime→speech 단방향). 따라서 이 계획기는 speech 자기
 * 어휘([SpeechBurstPlan]/[SpeechBubble])만 쓴다 — actionruntime.domain 타입을 import 하지 않는다.
 *
 * **acceptance(T022) — 빈 버블, Discord 길이 초과, 과도한 메시지 수를 거부한다**: [plan] 은 검증 실패 시 계획을
 * 만들지 않고 [PlanResult.Rejected](사유 코드)를 돌려준다 — 빈 버블/길이 초과/조각 수 초과를 구조적으로 거른다.
 * 각 버블은 [SpeechBubble.speechPlanRef] 로 본문 참조만 담는다(원문 비포함).
 *
 * 순수성: application — speech 도메인 + application 의 SpeechCandidate + 표준 java.time 만.
 * Spring/JPA/JDA·glm/zai·actionruntime 미참조.
 */
class SpeechBurstPlanner(
    /** 버블 간 기본 간격(scheduler 가 시각으로 환산 — 여기선 상대값만). */
    private val interBubbleGap: Duration = DEFAULT_INTER_BUBBLE_GAP,
) {
    /**
     * [candidate] 를 [shape] 에 맞춰 버스트 계획으로 변환한다. 빈 버블·길이 초과·조각 수 초과면 [PlanResult.Rejected].
     * [planRefPrefix] 는 각 버블 본문 참조 키 접두사(원문 대신 참조 — 로그/추적용).
     */
    fun plan(
        candidate: SpeechCandidate,
        shape: SpeechBurstShape,
        planRefPrefix: String = candidate.candidateId,
    ): PlanResult {
        if (shape.reactionOnly) return PlanResult.Rejected(PlanRejection.REACTION_ONLY)

        val cleaned = candidate.bubbles.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return PlanResult.Rejected(PlanRejection.EMPTY_BUBBLE)

        // 정책이 정한 조각 수를 넘으면 거부(participation 형태를 speech 가 늘리지 않는다 — T010 불변식).
        if (cleaned.size > shape.fragmentCount) return PlanResult.Rejected(PlanRejection.TOO_MANY_BUBBLES)
        if (cleaned.size > MAX_BUBBLES) return PlanResult.Rejected(PlanRejection.TOO_MANY_BUBBLES)

        // 각 버블 길이: 정책 형태 상한과 Discord 하드 리밋을 모두 만족해야 한다.
        val maxLen = minOf(shape.maxFragmentLength, DISCORD_MAX_MESSAGE_LENGTH)
        if (cleaned.any { it.length > maxLen }) return PlanResult.Rejected(PlanRejection.LENGTH_OVERFLOW)

        val bubbles =
            cleaned.mapIndexed { index, _ ->
                val isLast = index == cleaned.lastIndex
                SpeechBubble(
                    index = index,
                    speechPlanRef = "$planRefPrefix#$index",
                    gapAfter = if (isLast) Duration.ZERO else interBubbleGap,
                )
            }
        return PlanResult.Planned(SpeechBurstPlan(bubbles))
    }

    companion object {
        /** Discord 단일 메시지 하드 리밋(초과 시 전송 불가 — 거부). */
        const val DISCORD_MAX_MESSAGE_LENGTH: Int = 2000

        /** 버스트 조각 수 절대 상한(정책 형태와 별개 안전망 — 과도한 메시지 폭주 방지). */
        const val MAX_BUBBLES: Int = 5

        /** 버블 간 기본 간격(사람다운 타이핑 흉내 — P12 간격 모델 기본값). */
        val DEFAULT_INTER_BUBBLE_GAP: Duration = Duration.ofMillis(1200)
    }
}

/**
 * speech 의 **버스트 전송 계획**(NEXA-P14-T022, 순수 값 객체·불변). P13 BurstPlan 과 동형이지만 speech 자기
 * 어휘다 — actionruntime 이 전송 직전 이 계획을 자기 BurstPlan 으로 매핑한다(actionruntime→speech 단방향).
 */
data class SpeechBurstPlan(
    /** 순차 전송할 버블들(최소 1개). 각 버블은 한 번의 전송 단위. */
    val bubbles: List<SpeechBubble>,
) {
    init {
        require(bubbles.isNotEmpty()) { "SpeechBurstPlan 은 최소 1개의 버블을 가져야 한다" }
    }

    /** 전송 단위 수(= 버블 수). */
    val bubbleCount: Int
        get() = bubbles.size

    /** 멀티 버블인가(2개 이상). */
    val isMultiBubble: Boolean
        get() = bubbles.size > 1
}

/**
 * 버스트의 한 조각(NEXA-P14-T022, 순수 값 객체·불변). [index] 는 0-기반 순서, [speechPlanRef] 는 본문 참조(원문
 * 비포함), [gapAfter] 는 다음 버블까지 상대 간격(마지막 버블은 ZERO).
 */
data class SpeechBubble(
    val index: Int,
    val speechPlanRef: String,
    val gapAfter: Duration = Duration.ZERO,
) {
    init {
        require(index >= 0) { "버블 index 는 음수일 수 없다: $index" }
        require(speechPlanRef.isNotBlank()) { "speechPlanRef 는 비어 있을 수 없다" }
        require(!gapAfter.isNegative) { "gapAfter 는 음수일 수 없다: $gapAfter" }
    }
}

/**
 * 버스트 계획 결과(NEXA-P14-T022, sealed). 계획이 만들어지거나(Planned), 검증 실패로 거부된다(Rejected).
 */
sealed interface PlanResult {
    /** 유효한 버스트 계획(actionruntime executor 가 매핑 후 전송 — OBSERVE_ONLY 면 hard block). */
    data class Planned(
        val burstPlan: SpeechBurstPlan,
    ) : PlanResult

    /** 검증 실패로 계획 없음 — fallback(T016)이 침묵으로 하강. */
    data class Rejected(
        val rejection: PlanRejection,
    ) : PlanResult
}

/** 버스트 계획 거부 사유(NEXA-P14-T022). */
enum class PlanRejection {
    /** 비어 있는 버블(보낼 내용 없음). */
    EMPTY_BUBBLE,

    /** Discord 단일 메시지 길이 초과. */
    LENGTH_OVERFLOW,

    /** 정책/안전 상한을 넘는 버블 수. */
    TOO_MANY_BUBBLES,

    /** reaction-only 형태 — 발화 버스트로 만들지 않는다(리액션으로 하강). */
    REACTION_ONLY,
}
