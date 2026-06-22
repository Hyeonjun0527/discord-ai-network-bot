package com.discordassistant.central.actionruntime.domain

/**
 * 길드별 kill switch 의 **순수 결정 코어**(NEXA-P18-T013, 순수 도메인 서비스).
 *
 * 운영자/관리자가 특정 길드의 NEXA 를 즉시 끄면, 그 길드의 **신규 결정·예약·전송이 모두 차단**된다. shadow
 * hard-block([OutboundGuard])이 단계(mode) 기반 전송 차단이라면, kill switch 는 **단계와 무관한 길드 단위 즉시
 * 정지**다 — LIVE 길드라도 kill 되면 모든 신규 행동이 멈춘다.
 *
 * **acceptance(T013) — 관리자/운영자가 즉시 신규 결정·예약·전송을 중단하고, 이미 생성된 pending content 까지
 * 취소되며 audit 가 남는다**:
 *  - [decide] 는 kill 된 길드면 [KillSwitchDecision.BLOCK] 을 돌려준다 — 호출자(결정/예약/전송 경계)가 이 결과로
 *    신규 행동을 막는다. pending 취소·audit 는 application 서비스(GuildKillSwitchService)가 책임진다.
 *  - 즉시성: 활성 집합 조회 결과로만 판정하는 순수 함수라, 활성화 즉시 다음 호출부터 BLOCK 이다(tick 대기 없음).
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 타입만.
 */
object GuildKillSwitch {
    /**
     * [guildPseudonym] 이 [activeKilledGuilds] 에 있으면 BLOCK, 없으면 ALLOW. 활성 집합은 호출자(서비스)가 SSOT
     * 에서 로드해 넘긴다.
     */
    fun decide(
        guildPseudonym: String,
        activeKilledGuilds: Set<String>,
    ): KillSwitchDecision = if (guildPseudonym in activeKilledGuilds) KillSwitchDecision.BLOCK else KillSwitchDecision.ALLOW
}

/**
 * kill switch 판정(순수 도메인 enum). [BLOCK] 이면 결정/예약/전송 경계가 신규 NEXA 행동을 멈춘다.
 */
enum class KillSwitchDecision {
    /** 정상 — 신규 행동 허용. */
    ALLOW,

    /** kill 됨 — 신규 결정·예약·전송 차단. */
    BLOCK,
    ;

    val isBlocked: Boolean
        get() = this == BLOCK
}
