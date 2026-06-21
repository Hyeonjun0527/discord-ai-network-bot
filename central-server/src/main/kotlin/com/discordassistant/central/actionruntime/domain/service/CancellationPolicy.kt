package com.discordassistant.central.actionruntime.domain.service

import com.discordassistant.central.actionruntime.domain.model.ScheduledActionType
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction

/**
 * 예약 SPEAK 의 **취소 판정** 순수 도메인 서비스(NEXA-P13-T012/T013).
 *
 * 예약 후 due 도래 전(또는 due 시점 재평가 직전)에, 장면이 바뀌어 NEXA 가 더는 말할 필요가 없어졌는지를
 * **scene evidence 로** 판단한다. P08 의 CANCEL 결정과 일관: "다른 인간이 충분히 답함" / "주제(focus thread)가
 * 전환됨" / "대상 자체가 만료됨" 일 때만 취소 후보로 만든다.
 *
 * **acceptance(T012) — 단순 새 메시지 하나만으로 무조건 취소하지 않고 scene evidence 를 사용한다**:
 * - 새 인간 응답 [SceneEvidence.humanRepliesSinceSchedule] 1건만으로는 취소하지 않는다 — [sufficientHumanReplies]
 *   임계 이상이어야 [CancellationVerdict.CANCEL_OTHER_HUMAN_ANSWERED]. (말이 묻히지 않게 보수적으로.)
 *
 * **acceptance(T013) — 다른 동시 thread 의 활동이 잘못된 취소를 만들지 않는다**:
 * - focus 변경은 **같은 채널**에서 focus thread 가 [ScheduledSocialAction.target] 의 threadId 에서 **다른 thread
 *   로 이동**했을 때만 취소한다([SceneEvidence.currentFocusThreadId] 가 target thread 와 다를 때). 다른 채널·다른
 *   동시 thread 의 활동은 [SceneEvidence] 가 대상 thread 기준 evidence 만 싣게 해 영향을 주지 않는다.
 * - target expiry([SceneEvidence.targetExpired])는 대상 thread/채널이 사라진 명시 신호일 때만 취소.
 *
 * REACT 등 비-SPEAK 는 이 정책의 대상이 아니다(말이 묻히는 문제는 발화에만 해당). [decide] 가 SPEAK 외에는
 * 항상 [CancellationVerdict.KEEP] 를 돌려준다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 도메인 타입만(actionruntime.domain 규칙).
 */
class CancellationPolicy(
    /** 다른 인간 응답이 "충분" 하다고 볼 임계(이 수 이상이면 취소 후보). 1 이면 새 메시지 하나로도 취소되어
     *  acceptance T012 를 위반하므로 기본 2(보수적). */
    private val sufficientHumanReplies: Int = DEFAULT_SUFFICIENT_HUMAN_REPLIES,
) {
    init {
        require(sufficientHumanReplies >= 2) {
            "sufficientHumanReplies 는 2 이상이어야 한다(새 메시지 하나로 무조건 취소 금지 — T012): $sufficientHumanReplies"
        }
    }

    /**
     * [action] 을 [evidence] 기준으로 취소할지 판정한다. SPEAK 가 아니면 항상 [CancellationVerdict.KEEP].
     * 여러 사유가 동시에 성립하면 우선순위(대상 만료 > 다른 인간 응답 > 주제 전환) 중 가장 강한 사유를 돌려준다.
     */
    fun decide(
        action: ScheduledSocialAction,
        evidence: SceneEvidence,
    ): CancellationVerdict {
        if (action.type != ScheduledActionType.SPEAK) return CancellationVerdict.KEEP

        // 대상 자체가 사라진 경우(가장 강한 신호) — 더 평가할 것 없이 취소.
        if (evidence.targetExpired) return CancellationVerdict.CANCEL_TARGET_EXPIRED

        // 다른 인간이 충분히 답한 경우 — 새 메시지 하나가 아니라 임계 이상일 때만(T012 보수성).
        if (evidence.humanRepliesSinceSchedule >= sufficientHumanReplies) {
            return CancellationVerdict.CANCEL_OTHER_HUMAN_ANSWERED
        }

        // focus thread 가 같은 채널 안에서 target thread 밖으로 이동한 경우(주제 전환 — T013).
        // null(focus 관찰 없음)이거나 여전히 target thread 면 취소하지 않는다(다른 동시 thread 활동 무관).
        val focus = evidence.currentFocusThreadId
        if (focus != null && focus != action.target.threadId) {
            return CancellationVerdict.CANCEL_TOPIC_SWITCHED
        }

        return CancellationVerdict.KEEP
    }

    companion object {
        /** 기본 "충분한 인간 응답" 임계(2 — 새 메시지 하나로 무조건 취소하지 않도록 보수적, T012). */
        const val DEFAULT_SUFFICIENT_HUMAN_REPLIES: Int = 2
    }
}

/**
 * 취소 판정에 쓰는 장면 evidence(NEXA-P13-T012/T013, 순수 도메인 값 객체·불변). **대상 thread 기준** evidence 만
 * 싣는다 — 다른 동시 thread 의 활동은 여기 들어오지 않아 잘못된 취소를 만들지 않는다(T013 acceptance).
 */
data class SceneEvidence(
    /** 예약 이후 **대상 thread 에** 생긴 인간 응답 수(다른 thread 활동 비포함). 단순 1건은 취소 사유 아님(T012). */
    val humanRepliesSinceSchedule: Int,
    /** 현재 채널의 focus thread 식별자(없으면 null). target thread 와 다르면 주제 전환(T013). */
    val currentFocusThreadId: String?,
    /** 대상 thread/채널이 만료(삭제)됐는가 — 명시 신호(가장 강한 취소 사유). */
    val targetExpired: Boolean,
) {
    init {
        require(humanRepliesSinceSchedule >= 0) {
            "humanRepliesSinceSchedule 는 음수일 수 없다: $humanRepliesSinceSchedule"
        }
    }
}

/**
 * 취소 판정 결과(순수 도메인 enum). [KEEP] 외에는 모두 예약 행동을 [com.discordassistant.central.actionruntime
 * .domain.model.ActionStatus.CANCELLED] 로 보낼 후보다 — 어떤 사유였는지 보존(감사·로그).
 */
enum class CancellationVerdict(
    /** 취소를 동반하는가 — [KEEP] 만 false. */
    val cancels: Boolean,
) {
    /** 유지 — 예약대로 진행. */
    KEEP(cancels = false),

    /** 취소: 다른 인간이 충분히 답함(T012). */
    CANCEL_OTHER_HUMAN_ANSWERED(cancels = true),

    /** 취소: focus thread 전환(주제 전환 — T013). */
    CANCEL_TOPIC_SWITCHED(cancels = true),

    /** 취소: 대상 thread/채널 만료(T013). */
    CANCEL_TARGET_EXPIRED(cancels = true),
}
