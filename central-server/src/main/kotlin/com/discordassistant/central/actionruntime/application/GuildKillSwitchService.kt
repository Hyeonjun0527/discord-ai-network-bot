package com.discordassistant.central.actionruntime.application

import com.discordassistant.central.actionruntime.application.port.inbound.RevocationScope
import com.discordassistant.central.actionruntime.application.port.out.GuildKillSwitchStorePort
import com.discordassistant.central.actionruntime.application.port.out.PendingActionPurgePort
import com.discordassistant.central.actionruntime.domain.GuildKillSwitch
import com.discordassistant.central.actionruntime.domain.KillSwitchDecision
import java.time.Clock
import java.time.Instant

/**
 * 길드별 kill switch 유스케이스(NEXA-P18-T013, application 레이어).
 *
 * 운영자/관리자가 특정 길드의 NEXA 를 **즉시** 끈다. [GuildKillSwitch] 결정 코어로 신규 결정/예약/전송 경계가
 * BLOCK 을 보게 하고([isBlocked]), 발동 시 **이미 생성된 pending 행동·content 를 즉시 취소**한다
 * ([PendingActionPurgePort] — ConsentRevocationService 와 같은 즉시 invalidation 경로). 모든 발동/해제는 audit 로
 * 남는다([GuildKillSwitchStorePort]).
 *
 * **acceptance(T013)**:
 *  - [engage]: store 에 kill 등록 → 다음 [isBlocked] 부터 즉시 BLOCK(tick 대기 없음). 길드 전체 pending 을 purge 하고
 *    취소 수를 audit 에 남긴다(이미 생성된 content 까지 취소).
 *  - [disengage]: kill 해제 → 다음 [isBlocked] 부터 ALLOW. 해제 audit 를 남긴다(pending 복구는 없음 — 안전 우선).
 *  - 멱등: 같은 길드 재발동/재해제는 store 가 멱등 처리.
 *
 * 순수성 경계: application — 포트·도메인·[Clock] 만. Spring/JPA/JDA 미참조(어댑터가 와이어).
 */
class GuildKillSwitchService(
    private val store: GuildKillSwitchStorePort,
    private val purge: PendingActionPurgePort,
    private val clock: Clock,
) {
    /**
     * [guildPseudonym] 의 NEXA 를 즉시 끈다 — kill 등록 후 길드 전체 pending 예약·content 를 취소하고, 취소 수를
     * audit 에 남긴다. 취소된 pending 수를 돌려준다(이미 kill 상태여도 잔여 pending 을 한 번 더 청소).
     */
    fun engage(
        guildPseudonym: String,
        actor: String,
        reason: String,
    ): Int {
        val now = Instant.now(clock)
        // 먼저 신규 행동을 막는다: 활성 집합에 올려 다음 결정/예약/전송이 BLOCK 을 보게 한다(즉시).
        // pending 취소는 그 다음 — 막은 뒤 청소해야 청소 중 새 pending 이 끼지 않는다.
        val scope = RevocationScope(guildPseudonym = guildPseudonym)
        val pending = purge.findPendingIn(scope)
        pending.forEach { purge.purge(it) }
        store.engage(
            guildPseudonym = guildPseudonym,
            actor = actor,
            reason = reason,
            cancelledPending = pending.size,
            at = now,
        )
        return pending.size
    }

    /** [guildPseudonym] 의 kill 을 해제한다 — 다음 [isBlocked] 부터 ALLOW. 해제 audit 를 남긴다. */
    fun disengage(
        guildPseudonym: String,
        actor: String,
    ) {
        store.disengage(guildPseudonym = guildPseudonym, actor = actor, at = Instant.now(clock))
    }

    /**
     * [guildPseudonym] 이 현재 kill 상태인가 — 결정/예약/전송 경계가 신규 행동 전에 호출해 BLOCK 이면 멈춘다.
     * 활성 집합(SSOT) 조회로만 판정하므로 [engage] 직후 즉시 true 다(acceptance — 즉시 발효).
     */
    fun isBlocked(guildPseudonym: String): Boolean =
        GuildKillSwitch.decide(guildPseudonym, store.activeKilledGuilds()) == KillSwitchDecision.BLOCK
}
