package com.discordassistant.central.provider.domain.model

/**
 * 프로바이더 상태머신 (specs: `DM-S-ProviderState`, 10상태).
 *
 * 전이는 도메인 서비스가 가드한다(불가 전이 거부). [terminal] 은 더 이상 전이가 없는 상태.
 */
enum class ProviderState {
    /** 아직 프로바이더가 아님. */
    UNREGISTERED,

    /** 등록 요청됨(관리자 승인 대기). */
    PENDING,

    /** 관리자가 승인함(아직 Agent 미연결 가능). */
    APPROVED,

    /** 연결되어 요청 받을 수 있음. */
    ONLINE_IDLE,

    /** 현재 요청 처리 중. */
    ONLINE_BUSY,

    /** 프로바이더가 직접 일시정지함. */
    PAUSED,

    /** 하루 한도 또는 부하 제한에 걸림. */
    LIMITED,

    /** Agent 연결 없음. */
    OFFLINE,

    /** 최근 실패가 많아 자동 제외됨. */
    UNHEALTHY,

    /** 서버에서 제거됨. */
    REMOVED,
    ;

    val isOnline: Boolean get() = this == ONLINE_IDLE || this == ONLINE_BUSY
    val isTerminal: Boolean get() = this == REMOVED

    /** 라우팅 후보가 될 수 있는 상태인가(온라인 + idle). */
    val isRoutable: Boolean get() = this == ONLINE_IDLE

    /** 이 상태에서 [next] 로 전이가 허용되는가(specs 상태머신 가드). */
    fun canTransitionTo(next: ProviderState): Boolean = next in ALLOWED[this].orEmpty()

    companion object {
        private val ALLOWED: Map<ProviderState, Set<ProviderState>> =
            mapOf(
                UNREGISTERED to setOf(PENDING, APPROVED),
                PENDING to setOf(APPROVED, REMOVED),
                APPROVED to setOf(ONLINE_IDLE, OFFLINE, REMOVED),
                ONLINE_IDLE to setOf(ONLINE_BUSY, PAUSED, LIMITED, OFFLINE, UNHEALTHY, REMOVED),
                ONLINE_BUSY to setOf(ONLINE_IDLE, PAUSED, LIMITED, OFFLINE, UNHEALTHY, REMOVED),
                PAUSED to setOf(ONLINE_IDLE, OFFLINE, REMOVED),
                LIMITED to setOf(ONLINE_IDLE, OFFLINE, REMOVED),
                OFFLINE to setOf(ONLINE_IDLE, APPROVED, REMOVED),
                UNHEALTHY to setOf(ONLINE_IDLE, OFFLINE, REMOVED),
                REMOVED to emptySet(),
            )
    }
}
