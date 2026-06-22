package com.discordassistant.central.actionruntime.application.port.inbound

/**
 * 동의 철회 즉시 취소 인바운드 포트(NEXA-P13-T014, application 레이어).
 *
 * guild/channel/user 동의가 철회되면 privacy/consent 도메인이 이 포트를 호출해, 해당 범위의 **pending 예약 행동과
 * 이미 생성된 content 를 즉시 제거**한다. **다음 scheduler tick 을 기다리지 않는** 즉시 invalidation 경로다
 * (acceptance T014) — 철회와 동시에 동기적으로 취소된다.
 *
 * 순수성 경계: application 레이어 — 표준 타입만. Spring/JPA/JDA 미참조(adapter 가 이벤트/호출을 와이어).
 */
interface ConsentRevocationListener {
    /**
     * [scope] 의 동의가 철회됐을 때 호출한다 — 해당 범위의 모든 pending 예약 행동을 취소하고 생성된 content 를
     * 제거한다. 취소된 행동 수를 돌려준다(감사 로그용). 즉시 동기 실행(scheduler tick 비대기).
     */
    fun onConsentRevoked(scope: RevocationScope): Int
}

/**
 * 동의 철회 범위(application 값 객체·불변). 어느 수준(길드 전체/채널/특정 사용자)의 동의가 철회됐는지 식별한다.
 * 더 좁은 식별자가 null 이면 상위 범위 전체를 뜻한다(예: [channelId]·[userPseudonym] 둘 다 null 이면 길드 전체).
 */
data class RevocationScope(
    /** 철회된 길드 가명(필수 — 모든 철회는 길드 범위 안에서 일어난다). */
    val guildPseudonym: String,
    /** 특정 채널만 철회면 채널 식별자, 길드 전체면 null. */
    val channelId: String? = null,
    /** 특정 사용자만 철회면 사용자 가명, 그 외 null. */
    val userPseudonym: String? = null,
) {
    init {
        require(guildPseudonym.isNotBlank()) { "guildPseudonym 은 비어 있을 수 없다" }
        require(channelId == null || channelId.isNotBlank()) { "channelId 는 빈 문자열일 수 없다" }
        require(userPseudonym == null || userPseudonym.isNotBlank()) { "userPseudonym 은 빈 문자열일 수 없다" }
    }
}
