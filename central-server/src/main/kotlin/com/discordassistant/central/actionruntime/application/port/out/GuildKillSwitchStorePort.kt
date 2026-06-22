package com.discordassistant.central.actionruntime.application.port.out

import java.time.Instant

/**
 * 길드 kill switch 상태·audit 아웃바운드 포트(NEXA-P18-T013, application 레이어).
 *
 * 어느 길드가 현재 kill 상태인지(활성 집합)를 영속화하고, kill switch 발동/해제 audit 를 **append-only** 로 남긴다.
 * 구현(JPA)이 표(`nexa_guild_kill_switch`)와 audit(`nexa_guild_kill_switch_audit`)를 채운다.
 *
 * **즉시성(acceptance T013)**: [engage] 는 활성 집합에 즉시 반영돼야 하고, [activeKilledGuilds] 가 그 SSOT 다 —
 * 발동 즉시 다음 결정/예약/전송 호출이 BLOCK 을 본다.
 *
 * 순수성 경계: application 레이어 — 표준 타입만. Spring/JPA/JDA 미참조(어댑터가 채운다).
 */
interface GuildKillSwitchStorePort {
    /** 현재 kill 상태인 길드 가명 집합을 돌려준다(결정 코어가 BLOCK 판정에 쓴다). */
    fun activeKilledGuilds(): Set<String>

    /**
     * [guildPseudonym] 을 kill 상태로 만든다(멱등 — 이미 active 면 유지). [actor]·[reason]·[cancelledPending]·[at] 으로
     * audit 를 남긴다([cancelledPending] = 이 발동으로 취소된 pending 행동 수).
     */
    fun engage(
        guildPseudonym: String,
        actor: String,
        reason: String,
        cancelledPending: Int,
        at: Instant,
    )

    /**
     * [guildPseudonym] 의 kill 을 해제한다(멱등 — 이미 비활성이면 무시). [actor]·[at] 으로 해제 audit 를 남긴다.
     */
    fun disengage(
        guildPseudonym: String,
        actor: String,
        at: Instant,
    )

    /** [guildPseudonym] 의 kill switch audit 사건을 **시간순**으로 돌려준다(원문 없이 — 발동/해제·actor·reason·시각). */
    fun auditFor(guildPseudonym: String): List<GuildKillSwitchAuditEvent>
}

/** kill switch audit 사건(append-only, 원문 비포함). */
data class GuildKillSwitchAuditEvent(
    val guildPseudonym: String,
    val action: GuildKillSwitchAction,
    /** 발동/해제 주체(운영자 식별 코드 — 원문 user id 가 아니라 audit 식별자). */
    val actor: String,
    /** 발동 사유(저카디널리티 코드/짧은 설명 — 원문 대화 비포함). 해제 시 빈 문자열 허용. */
    val reason: String,
    /** kill 발동으로 취소된 pending 행동 수(해제 사건이면 0). */
    val cancelledPending: Int,
    val at: Instant,
)

/** kill switch audit 의 사건 종류. */
enum class GuildKillSwitchAction {
    ENGAGE,
    DISENGAGE,
}
