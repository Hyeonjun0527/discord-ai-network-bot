package com.discordassistant.central.participation.domain.model.state

import java.time.Duration
import java.time.Instant

/**
 * NEXA(에이전트)의 채널별 참여 상태(NEXA-P06-T002, 순수 도메인 값 객체·불변).
 *
 * NEXA **자신이 한 관찰 가능한 행동**의 집계만 담는다 — 최근 발화 포화도([recentBurstCount]), 마지막 행동 시각
 * ([lastActedAt]), 아직 끝맺지 못한 행동 수([pendingActionCount]). 사람을 프로파일링하지 않으며(에이전트 자기 상태),
 * 어떤 값도 **감정으로 사용자에게 표현되지 않는다**(observable-state-policy 금지 추론 부재).
 *
 * **acceptance(T002)**:
 * - "감정이라고 사용자에게 표현되지 않는다": 필드는 카운트·시각뿐이며 기분/성격/관계감정 라벨이 없다.
 * - "Clock 기반 갱신이 가능하다": 모든 갱신은 호출자가 주입한 [Instant] 로 일어난다([recordActedAt]/[decayed]).
 *   도메인은 Clock 을 보유하지 않는다(순수성) — 시각을 인자로 받는다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 java.time 만 쓴다.
 */
data class AgentParticipationState(
    /** 이 상태가 속한 guild·channel 스코프(가명, guild-scoped). */
    val scope: ChannelScope,
    /**
     * 최근 관측 창에서 NEXA 가 만든 burst 수(발화 포화도의 원천 카운트). 사람 평균 메시지 수가 아니라 NEXA 자신의
     * burst 수다(T011 saturation 입력). 0 이면 최근 발화 없음.
     */
    val recentBurstCount: Int = 0,
    /** NEXA 가 마지막으로 행동(발화 등)한 시각. null 이면 아직 이 채널에서 행동한 적 없음. */
    val lastActedAt: Instant? = null,
    /** 아직 끝맺지 못한 NEXA 행동 수(예: 말하다 취소·약속한 후속). 영구 누적 금지(만료/해결로 줄어든다, T015). */
    val pendingActionCount: Int = 0,
) {
    init {
        require(recentBurstCount >= 0) { "recentBurstCount 는 음수일 수 없다" }
        require(pendingActionCount >= 0) { "pendingActionCount 는 음수일 수 없다" }
    }

    /** 이 채널에서 한 번이라도 행동했는가(관찰 사실). */
    val hasActed: Boolean
        get() = lastActedAt != null

    /**
     * NEXA 가 [at] 시각에 burst 를 1회 더 했음을 반영한 새 상태(불변 복제). 호출자가 시각을 주입하므로 Clock 기반
     * 갱신이 가능하다(acceptance T002).
     */
    fun recordActedAt(at: Instant): AgentParticipationState = copy(recentBurstCount = recentBurstCount + 1, lastActedAt = at)

    /** pending action 1건을 더한 새 상태. */
    fun addPendingAction(): AgentParticipationState = copy(pendingActionCount = pendingActionCount + 1)

    /** pending action 1건을 해결(만료/완료)한 새 상태. 0 이면 그대로(음수 방지). */
    fun resolvePendingAction(): AgentParticipationState =
        if (pendingActionCount == 0) this else copy(pendingActionCount = pendingActionCount - 1)

    /**
     * 시간 유효성(감쇠) — [now] 기준으로 마지막 행동이 [window] 보다 오래됐으면 최근 발화 포화 카운트를 0 으로 재설정한
     * 새 상태를 돌려준다(영구 낙인 금지, observable-state-policy 불변식 3·체크리스트 #6). 창 안이면 그대로.
     */
    fun decayed(
        now: Instant,
        window: Duration,
    ): AgentParticipationState {
        val last = lastActedAt ?: return this
        val stale = Duration.between(last, now) > window
        return if (stale) copy(recentBurstCount = 0) else this
    }

    companion object {
        /** 아직 아무 행동도 없는 초기 상태(주어진 스코프). */
        fun empty(scope: ChannelScope): AgentParticipationState = AgentParticipationState(scope = scope)
    }
}
