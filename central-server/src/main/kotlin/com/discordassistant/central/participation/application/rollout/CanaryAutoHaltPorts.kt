package com.discordassistant.central.participation.application.rollout

/**
 * Canary 자동 중단의 부수효과 아웃바운드 포트들(NEXA-P18-T023, application 레이어).
 *
 * 결정 코어([CanaryHalt])가 중단을 판정하면 서비스([CanaryAutoHaltService])가 이 포트들로
 *  1) 강등된 길드의 pending 예약 행동을 취소하고([PendingActionCancellationPort]),
 *  2) 운영자에게 알린다([OperatorAlertPort]).
 *
 * 순수성 경계: application 레이어 — 표준 타입만. Spring/JPA/JDA·하류 adapter 미참조(어댑터가 와이어한다).
 * actionruntime 의 PendingActionPurgePort 를 직접 참조하지 않는 participation-로컬 추상이다(컨텍스트 경계 유지).
 */
interface PendingActionCancellationPort {
    /**
     * [guildPseudonym] 의 종결되지 않은 pending 예약 행동을 모두 취소하고(생성 content 포함), 취소 건수를 돌려준다.
     * acceptance(T023) — 중단 후 pending action 도 취소된다. 구현(어댑터)이 actionruntime 취소 경로로 위임한다.
     */
    fun cancelPendingFor(guildPseudonym: String): Int
}

/**
 * 운영자 알림 아웃바운드 포트(NEXA-P18-T023). 자동 중단이 일어나면 당직 운영자에게 통지한다(acceptance — 운영자
 * 알림이 간다). 원문/PII 비포함 — 가명·사유·단계만.
 */
interface OperatorAlertPort {
    /** 자동 중단 알림을 보낸다([alert] = 무엇이/왜/어디로 강등됐는지). */
    fun notifyAutoHalt(alert: CanaryHaltAlert)
}

/**
 * 자동 중단 알림(application 값 객체·불변). 운영자에게 보내는 통지 본문(원문 비포함 — 가명·단계·사유 코드만).
 */
data class CanaryHaltAlert(
    val guildPseudonym: String,
    /** 강등 대상 단계(OFF 또는 SHADOW_PREDICT). */
    val target: com.discordassistant.central.participation.domain.model.shadow.ShadowMode,
    /** 자동 중단 사유 코드들. */
    val reasons: List<HaltReason>,
    /** 이 중단으로 취소된 pending 행동 수. */
    val cancelledPending: Int,
) {
    init {
        require(guildPseudonym.isNotBlank()) { "guildPseudonym 은 비어 있을 수 없다" }
        require(reasons.isNotEmpty()) { "reasons 는 비어 있을 수 없다(중단 알림은 사유가 있어야 한다)" }
    }
}
