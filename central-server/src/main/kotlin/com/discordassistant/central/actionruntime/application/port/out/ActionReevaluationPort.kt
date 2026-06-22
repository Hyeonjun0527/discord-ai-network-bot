package com.discordassistant.central.actionruntime.application.port.out

/**
 * 예약 행동의 contextVersion 재평가 아웃바운드 포트(NEXA-P13-T011, application 레이어).
 *
 * 예약 당시 장면 버전과 due 시점 **현재 scene version** 이 다르면(stale), participation 에 "지금 다시 판단하면 여전히
 * 이 행동을 할 것인가?" 를 묻는다. 구현은 participation 의 공개 application port 만 호출한다(adapter 가 와이어 —
 * module-dag 금지 의존 #2 와 일관, participation 구현/하류 adapter 직접 참조 금지).
 *
 * 순수성 경계: application 레이어 — 표준 타입만. Spring/JPA/JDA 미참조.
 */
interface ActionReevaluationPort {
    /**
     * [target] thread 의 현재 contextVersion 을 돌려준다(없으면 null — 장면 자체가 사라짐 → stale 취급). 예약 당시
     * 버전과 다르면 [com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction.isStale] 가 true.
     */
    fun currentContextVersion(target: ReevaluationTarget): Long?

    /**
     * stale 한 [decisionId] 행동을 **현재 맥락에서 다시 판단**하면 여전히 유효한지 묻는다(participation 재평가).
     * true 면 실행을 이어가고(TYPING), false 면 취소한다 — stale 한 action 을 그대로 실행하는 경로가 없다(T011).
     */
    fun stillValid(
        decisionId: String,
        target: ReevaluationTarget,
        scheduledContextVersion: Long,
        currentContextVersion: Long,
    ): Boolean
}

/**
 * 재평가 대상 장면 식별(application 값 객체·불변). 어느 길드/채널/thread 의 현재 장면 버전을 볼지의 식별 참조만 담는다.
 */
data class ReevaluationTarget(
    val guildPseudonym: String,
    val channelId: String,
    val threadId: String,
) {
    init {
        require(guildPseudonym.isNotBlank()) { "guildPseudonym 은 비어 있을 수 없다" }
        require(channelId.isNotBlank()) { "channelId 는 비어 있을 수 없다" }
        require(threadId.isNotBlank()) { "threadId 는 비어 있을 수 없다" }
    }
}
