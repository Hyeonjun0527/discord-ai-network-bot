package com.discordassistant.central.participation.domain.service

/**
 * Attention Gate — "지금 두뇌(GLM 3표결/발화 생성)를 깨울 **타이밍**인가"를 공짜 규칙으로 결정한다
 * (core `attention_gate.py` 1:1 이식, 순수·결정적 도메인 서비스).
 *
 * [CoreInterventionRules] 가 "낄까 말까"(끼어들기 **판단**)를 결정한다면, 이 게이트는 그 앞단에서 "언제 깨우나"
 * (타이밍=규칙)만 결정한다 — 사람의 '주의'가 반사적인 것과 같다. 둘은 직교한다.
 *
 * **이식 범위(core attention_gate.py 항목 C2·C3·C4·C5·C6·C7·C8·C10·D1~D5)**:
 *  - 상수 전부(attention.yaml): [AttentionGateConstants] (idle_min/max·pingpong_window·min_gap·typing_grace·gap_window).
 *  - per-channel 상태([ChannelAttentionState]): last_nia_ts·last_message_ts·typing_until·recent_gaps(median, window 8)·
 *    pending idle deadline. core `ChannelAttentionState` 와 동형이며 [decide]/[onTyping] 가 제자리 갱신한다.
 *  - [decide] 한 message 이벤트 결정 + 증거([WakeDecision]). core `decide()` 의 message 분기 전체:
 *    * **NO_WAKE-on-nia(C8)**: 니아 자기 발화면 깨우지 않고 pingpong 앵커(last_nia_ts)만 갱신.
 *    * **HARD_RESPOND_NOW**: hard_policy 가 RESPOND_NOW(멘션/호명/reply/continuation)면 즉시 WAKE_NOW(대기 안 함).
 *    * **pingpong wake(C2)**: 니아 직전 발화 + 트리거가 pingpong_window 내 → 즉시 WAKE_NOW(대기 안 함).
 *    * **min_gap debounce(C4·D4) / typing grace(C3·D5)**: 직전 메시지와 간격이 min_gap 미만이거나 typing 중이면 WAIT.
 *    * **dynamic_idle_ms(C6) + WAKE_AFTER_IDLE(C5·C7)**: 일반 CANDIDATE 는 idle(정적) 후 평가하도록 deadline 을 둔다.
 *  - [dynamicIdleMs] 채널 템포(recent_gaps median)로 idle_min..idle_max 선형 보간(C6).
 *  - [idleDue]/[clearPending] idle 마감 도래 판정·소비(C5·C7 — 능동 타이머 없이 디바운스로 등가).
 *
 * **NEXA 통합에서의 idle 처리 방식(택한 방법 + 이유)**: NEXA 의 emit 경로는 메시지마다 **동기** 평가한다(능동 타이머
 * 부재). core 의 WAKE_AFTER_IDLE 은 "마지막 메시지 후 N초 정적이면 깨움"이라 본래 타이머가 필요하지만, 능동 스케줄러를
 * 새로 들이는 것은 이 단계(SHADOW/안전 우선)에 과하고 위험하다. 그래서 **디바운스 등가**를 택했다:
 *  - WAKE_NOW(멘션/호명/reply/continuation/pingpong)는 즉시 통과 — idle 대기 없음(사람이 바로 받아치는 타이밍).
 *  - WAIT(min_gap 연타·typing)는 이번 턴 발화 보류 — core 의 "묶음이 이어지는 중" 과 동일 효과.
 *  - WAKE_AFTER_IDLE 은 "이번 메시지가 묶음의 끝일 수 있음"을 뜻하므로 통과시키되(능동 타이머라면 정적 후 깨웠을 것),
 *    바로 다음 빠른 연타는 위 WAIT(min_gap) 가 잡는다. 즉 deadline 은 [idleDue] 로 노출은 하되 emit 경로는 WAIT 보류로
 *    과발화를 막는 등가 동작을 낸다. 능동 타이머 도입은 LIVE 승격 단계의 후속 작업으로 남긴다.
 *
 * 순수성: Spring/JPA/JDA/adapter 미참조(participation.domain 규칙). 표준 타입만. 시각은 호출자가 ts(ms)로 주입한다
 * (Date.now/random 금지 — 같은 이벤트 시퀀스 → 항상 같은 깨움 타임라인, 재현 가능).
 */
object ChannelAttentionGate {
    /** core hard_policy enum 문자열(attention_gate.py 가 event["hard_policy"] 로 받는 값). */
    const val HARD_RESPOND_NOW: String = "RESPOND_NOW"

    /** core hard_policy DROP — 봇/시스템/중복 등 깨우지 않을 트리거. */
    const val HARD_DROP: String = "DROP"

    /** core hard_policy CANDIDATE — idle 후 평가할 일반 후보. */
    const val HARD_CANDIDATE: String = "CANDIDATE"

    /**
     * 채널별 경량 attention 상태(core `ChannelAttentionState` 동형). [decide]/[onTyping] 가 제자리(in-place) 갱신한다.
     * 가변(var) 필드 — 호출자가 채널별 인스턴스를 보관(예: ConcurrentHashMap)하고 같은 인스턴스를 재주입한다.
     *
     * @property lastMessageTsMs   직전(사람 또는 니아) 메시지 시각(ms). 첫 메시지 전엔 null.
     * @property lastNiaTsMs       니아 직전 발화 시각(ms) — pingpong 앵커. 없으면 null.
     * @property typingUntilMs     typing 유예 만료 시각(ms). 이 시각 전이면 "작성 중". 없으면 null.
     * @property pendingIdleDeadlineMs idle 마감 시각(ms) — [idleDue] 판정용. 없으면 null.
     * @property recentGapsMs      최근 사람 메시지 간 간격(ms) 표본(최대 [AttentionGateConstants.GAP_WINDOW] 개).
     */
    data class ChannelAttentionState(
        var lastMessageTsMs: Long? = null,
        var lastNiaTsMs: Long? = null,
        var typingUntilMs: Long? = null,
        var pendingIdleDeadlineMs: Long? = null,
        val recentGapsMs: MutableList<Long> = mutableListOf(),
    )

    /** Attention Gate 한 이벤트 결정 + 증거(core `WakeDecision` 동형). */
    data class WakeDecision(
        val action: String,
        val reasonCode: String,
        val idleDeadlineMs: Long? = null,
    )

    /**
     * 한 message 이벤트에 대해 깨울 타이밍을 결정하고 [state] 를 제자리 갱신한다(core `decide()` message 분기 1:1).
     *
     * @param tsMs       이벤트 절대 시각(ms) — 호출자 주입(Date.now 금지).
     * @param isNia      이 이벤트가 니아 자신의 발화인지(핑퐁 앵커 갱신용). true 면 깨우지 않고 앵커만 갱신(C8).
     * @param hardPolicy core hard_policy 결과([HARD_RESPOND_NOW]/[HARD_DROP]/[HARD_CANDIDATE]). null 이면 CANDIDATE 취급.
     * @param state      채널별 상태(제자리 갱신).
     */
    fun decide(
        tsMs: Long,
        isNia: Boolean,
        hardPolicy: String?,
        state: ChannelAttentionState,
    ): WakeDecision {
        // 니아 자신의 발화면 핑퐁 기준점만 갱신하고 깨우지 않는다(C8 NO_WAKE-on-nia).
        if (isNia) {
            state.lastNiaTsMs = tsMs
            state.lastMessageTsMs = tsMs
            return WakeDecision(action = AttentionGateConstants.NO_WAKE, reasonCode = "NIA_SELF")
        }

        // 직전 메시지와의 간격(템포 추정/디바운스용). 첫 메시지면 null.
        val prevTs = state.lastMessageTsMs
        val gapMs = if (prevTs != null) tsMs - prevTs else null
        if (gapMs != null && gapMs >= 0) {
            state.recentGapsMs.add(gapMs)
            if (state.recentGapsMs.size > AttentionGateConstants.GAP_WINDOW) {
                state.recentGapsMs.removeAt(0)
            }
        }
        // 메시지가 도착했으니 이전 idle 마감은 무효(새 메시지가 묶음을 이어감). 데드라인은 아래 분기에서 재설정한다.
        state.lastMessageTsMs = tsMs

        // DROP → 깨우지 않음(타이머 변화 없음).
        if (hardPolicy == HARD_DROP) {
            return WakeDecision(action = AttentionGateConstants.NO_WAKE, reasonCode = "DROP")
        }

        // RESPOND_NOW(멘션/호명/reply/continuation) → 즉시 깨움. idle 대기 해제.
        if (hardPolicy == HARD_RESPOND_NOW) {
            state.pendingIdleDeadlineMs = null
            return WakeDecision(action = AttentionGateConstants.WAKE_NOW, reasonCode = "HARD_RESPOND_NOW")
        }

        // 핑퐁(니아 직전 발화 후 pingpong_window 내 응답) → 즉시 깨움(C2).
        if (isPingpong(tsMs, state)) {
            state.pendingIdleDeadlineMs = null
            return WakeDecision(action = AttentionGateConstants.WAKE_NOW, reasonCode = "PINGPONG")
        }

        // typing 진행 중이거나 직전 메시지와 간격이 min_gap 미만 → WAIT, idle 타이머 리셋(연장)(C3·C4·D4·D5).
        val idleMs = dynamicIdleMs(state)
        val deadline = tsMs + idleMs
        if (typingActive(tsMs, state) || (gapMs != null && gapMs < AttentionGateConstants.MIN_GAP_MS)) {
            state.pendingIdleDeadlineMs = deadline
            return WakeDecision(
                action = AttentionGateConstants.WAIT,
                reasonCode = "DEBOUNCE",
                idleDeadlineMs = deadline,
            )
        }

        // 일반 CANDIDATE → 청크가 끝나면 깨움. 새 메시지마다 deadline 갱신(C5·C7 WAKE_AFTER_IDLE).
        state.pendingIdleDeadlineMs = deadline
        return WakeDecision(
            action = AttentionGateConstants.WAKE_AFTER_IDLE,
            reasonCode = "CHUNK_END",
            idleDeadlineMs = deadline,
        )
    }

    /** typing 이벤트 → typing_until 갱신(core decide() typing 분기). idle 타이머는 건드리지 않는다(WAIT 연장). */
    fun onTyping(
        tsMs: Long,
        state: ChannelAttentionState,
    ): WakeDecision {
        state.typingUntilMs = tsMs + AttentionGateConstants.TYPING_GRACE_MS
        return WakeDecision(action = AttentionGateConstants.WAIT, reasonCode = "TYPING")
    }

    /**
     * 채널 템포(최근 간격 median)로 idle_min..idle_max 사이를 선형 보간한다(core `dynamic_idle_ms`, C6).
     * 표본이 없으면 중앙값으로. 간격이 idle_min 이하면 idle_min, idle_max 이상이면 idle_max.
     */
    fun dynamicIdleMs(state: ChannelAttentionState): Int {
        val lo = AttentionGateConstants.IDLE_MIN_MS
        val hi = AttentionGateConstants.IDLE_MAX_MS
        if (hi <= lo) return lo
        val gap = median(state.recentGapsMs) ?: return (lo + hi) / 2
        if (gap <= lo) return lo
        if (gap >= hi) return hi
        val frac = (gap - lo).toDouble() / (hi - lo)
        return Math.round(lo + frac * (hi - lo)).toInt()
    }

    /**
     * 현재 시각이 pending idle 마감을 지났으면 깨울 때(idle 발화)인지 반환한다(core `idle_due`, C5·C7).
     * 호출자는 깨운 뒤 [clearPending] 으로 마감을 소비해 중복 발화를 막아야 한다.
     */
    fun idleDue(
        nowMs: Long,
        state: ChannelAttentionState,
    ): Boolean {
        val dl = state.pendingIdleDeadlineMs ?: return false
        return nowMs >= dl
    }

    /** idle 발화를 소비했음을 표시(중복 깨움 방지)(core `clear_pending`). */
    fun clearPending(state: ChannelAttentionState) {
        state.pendingIdleDeadlineMs = null
    }

    /** 니아 직전 발화 후 pingpong_window 내에 응답이면 핑퐁(core `_is_pingpong`, C2). */
    private fun isPingpong(
        tsMs: Long,
        state: ChannelAttentionState,
    ): Boolean {
        val lastNia = state.lastNiaTsMs ?: return false
        val delta = tsMs - lastNia
        return delta in 0..AttentionGateConstants.PINGPONG_WINDOW_MS.toLong()
    }

    /** typing 유예 내인지(core `_typing_active`, C3·D5). */
    private fun typingActive(
        tsMs: Long,
        state: ChannelAttentionState,
    ): Boolean {
        val until = state.typingUntilMs ?: return false
        return tsMs < until
    }

    /** 정수 중앙값(core `_median`). 짝수 개면 두 중앙값 평균(정수 나눗셈). 빈 목록이면 null. */
    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val ordered = values.sorted()
        val mid = ordered.size / 2
        return if (ordered.size % 2 == 1) {
            ordered[mid]
        } else {
            (ordered[mid - 1] + ordered[mid]) / 2
        }
    }
}
