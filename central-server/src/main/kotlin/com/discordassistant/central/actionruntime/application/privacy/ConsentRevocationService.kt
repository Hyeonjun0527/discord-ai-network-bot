package com.discordassistant.central.actionruntime.application.privacy

import com.discordassistant.central.actionruntime.application.port.inbound.ConsentRevocationListener
import com.discordassistant.central.actionruntime.application.port.inbound.RevocationScope
import com.discordassistant.central.actionruntime.application.port.out.PendingActionPurgePort

/**
 * 동의 철회 즉시 취소 유스케이스(NEXA-P13-T014, application 레이어).
 *
 * [ConsentRevocationListener] 구현 — guild/channel/user 동의 철회 시 해당 범위의 pending 예약 행동과 생성 content 를
 * **즉시 동기 제거**한다([PendingActionPurgePort]). scheduler 의 다음 tick 을 기다리지 않는다(acceptance T014 —
 * 즉시 invalidation 경로). 철회와 동시에 호출되므로, 다음 due 도래 전에 행동이 사라진다.
 *
 * 실제 Discord 전송은 이 묶음 범위 밖이고, 이 서비스는 **예약 취소·content 제거** 까지만 한다(shadow 경계 유지).
 *
 * 순수성 경계: application 레이어 — 포트만. Spring/JPA/JDA 미참조.
 */
class ConsentRevocationService(
    private val purge: PendingActionPurgePort,
) : ConsentRevocationListener {
    /**
     * [scope] 의 동의 철회를 처리한다 — 범위의 모든 pending 예약을 찾아 즉시 취소·content 제거하고 취소 건수를
     * 돌려준다. 범위에 pending 이 없으면 0. 같은 철회 재호출은 이미 종결돼 idempotent(purge 가 멱등).
     */
    override fun onConsentRevoked(scope: RevocationScope): Int {
        val pending = purge.findPendingIn(scope)
        pending.forEach { purge.purge(it) }
        return pending.size
    }
}
