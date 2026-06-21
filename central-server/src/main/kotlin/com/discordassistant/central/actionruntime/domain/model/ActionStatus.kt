package com.discordassistant.central.actionruntime.domain.model

/**
 * 예약된 사회적 행동의 상태 머신(NEXA-P13-T002, 순수 도메인 enum·상태 전이 SSOT).
 *
 * actionruntime 은 participation 이 낸 결정(SPEAK/REACT/WAIT)을 **예약**하고, due 시점에 (이 묶음 다음 단계인
 * T015~T017 의) executor 가 실행한다. 이 enum 은 한 예약 행동이 거치는 모든 단계와 **합법 전이**를 고정한다 —
 * 불법 전이는 [ScheduledSocialAction] 의 도메인 메서드가 거부한다(acceptance T001).
 *
 * **acceptance(T002) — 모든 terminal 상태와 재시도 가능 상태가 문서화된다**:
 *
 * | 상태 | 의미 | terminal? | retry 가능? |
 * | --- | --- | --- | --- |
 * | [CONSIDERING] | 결정 직후, 아직 due index 에 예약 전(초기 상태) | no | n/a |
 * | [SCHEDULED] | due index 에 예약됨(scheduler 가 claim 대상으로 봄) | no | n/a |
 * | [REEVALUATING] | due 도래·claim 후 contextVersion 재평가 중(T011) | no | n/a |
 * | [TYPING] | 재평가 통과·전송 직전 typing indicator 표시 중(P12) | no | n/a(in-flight) |
 * | [PARTIALLY_SENT] | 멀티 버블 중 일부만 전송됨(부분 실패) | no | n/a(recovery 검사 대상 T010) |
 * | [COMPLETED] | 모든 버블 전송 완료(성공 종결) | **yes** | no |
 * | [CANCELLED] | 취소됨(다른 인간 응답/주제 전환/동의 철회/stale — T012~T014) | **yes** | no |
 * | [FAILED] | 영구 실패(분류 후 더는 재시도 안 함 — T009) | **yes** | no |
 *
 * **재시도 가능 상태**: terminal 이 아닌 상태([isTerminal]=false)는 lease 가 만료되면 recovery 가 다시 claim 할 수
 * 있다(T007/T010). 단 [TYPING]/[PARTIALLY_SENT] 는 "실행 중간" 이라 recovery 가 **재전송이 아니라 재평가 후
 * 이어서/취소** 로 다룬다(T010 — 같은 버블 이중 전송 방지). transient 실패의 재시도(bounded)는 [SCHEDULED] 로
 * 되돌리는 형태이고(T009), 영구 실패는 [FAILED] 로 종결한다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 도메인 enum 만(actionruntime.domain 규칙, NexaArchitectureTest.nexaDomainsArePure).
 */
enum class ActionStatus {
    /** 결정 직후 초기 상태 — 아직 due index 에 예약 전. */
    CONSIDERING,

    /** due index 에 예약됨 — scheduler 가 due 시점에 claim 대상으로 본다. */
    SCHEDULED,

    /** due 도래·claim 후 contextVersion 재평가 중(stale 발화 방지 — T011). */
    REEVALUATING,

    /** 재평가 통과·전송 직전 typing indicator 표시 중(P12). */
    TYPING,

    /** 멀티 버블 중 일부만 전송됨(부분 실패 — recovery 가 이중 전송 없이 다룬다 T010). */
    PARTIALLY_SENT,

    /** 모든 버블 전송 완료(성공 종결, terminal). */
    COMPLETED,

    /** 취소됨(다른 인간 응답/주제 전환/동의 철회/stale, terminal — T012~T014). */
    CANCELLED,

    /** 영구 실패(분류 후 더는 재시도 안 함, terminal — T009). */
    FAILED,
    ;

    /** terminal 상태인가 — 더는 전이가 일어나지 않는 최종 상태(COMPLETED/CANCELLED/FAILED). */
    val isTerminal: Boolean
        get() = this == COMPLETED || this == CANCELLED || this == FAILED

    /**
     * [target] 으로의 전이가 합법인가. terminal 에서는 어떤 전이도 불가(=false). 합법 전이 그래프는 위 표를 따른다.
     * scheduler/실행/취소/recovery 가 모두 이 한 곳을 통해 합법성을 판단한다(전이 규칙 SSOT — DRY).
     */
    fun canTransitionTo(target: ActionStatus): Boolean {
        if (isTerminal) return false
        if (target == this) return false
        return target in ALLOWED[this].orEmpty()
    }

    companion object {
        // 합법 전이 그래프(SSOT). terminal 상태는 키에 없다(어떤 곳으로도 못 감).
        private val ALLOWED: Map<ActionStatus, Set<ActionStatus>> =
            mapOf(
                // 결정 직후 → 예약 또는 즉시 취소(예: 결정 직후 동의 철회).
                CONSIDERING to setOf(SCHEDULED, CANCELLED),
                // 예약됨 → claim 후 재평가, 또는 취소(취소 후보가 됨 T012~T014).
                SCHEDULED to setOf(REEVALUATING, CANCELLED),
                // 재평가 중 → 통과하면 typing, stale 면 취소, transient 면 재예약(SCHEDULED), 영구실패면 FAILED.
                REEVALUATING to setOf(TYPING, SCHEDULED, CANCELLED, FAILED),
                // typing 중 → 전송 시작(부분/완전), 취소, 실패, 또는 transient 재예약.
                TYPING to setOf(PARTIALLY_SENT, COMPLETED, SCHEDULED, CANCELLED, FAILED),
                // 부분 전송 → 나머지 완료, 취소(잔여 버블 취소), 또는 영구 실패.
                PARTIALLY_SENT to setOf(COMPLETED, CANCELLED, FAILED),
            )
    }
}
