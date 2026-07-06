package com.discordassistant.central.participation.adapter.outbound.rollout

import com.discordassistant.central.actionruntime.application.port.inbound.RevocationScope
import com.discordassistant.central.actionruntime.application.port.out.PendingActionPurgePort
import com.discordassistant.central.participation.application.rollout.PendingActionCancellationPort
import org.springframework.stereotype.Component

/**
 * Canary 자동 중단의 pending 취소 아웃바운드 어댑터(NEXA-P18-T023, participation adapter).
 *
 * participation-로컬 추상 [PendingActionCancellationPort] 를 actionruntime 의 공개 application 포트
 * [PendingActionPurgePort] 로 위임한다 — 강등된 길드의 종결되지 않은 예약을 모두 찾아 취소 종결하고 생성 content
 * 까지 제거한다(동의철회 즉시취소 경로 T014 와 같은 purge 를 재사용). 길드 전체 범위이므로 channel/user 는 null.
 *
 * 경계: participation adapter 는 actionruntime 의 **application 포트**(adapter 구현 아님)만 참조한다 —
 * NexaArchitectureTest 금지 의존 #2(participation→actionruntime.adapter) 위반이 아니다.
 */
@Component
class ActionRuntimePendingCancellationAdapter(
    private val purge: PendingActionPurgePort,
) : PendingActionCancellationPort {
    /**
     * [guildPseudonym] 길드 전체의 종결되지 않은 pending 예약을 모두 취소(+content 제거)하고 취소 건수를 돌려준다.
     * 개별 purge 는 멱등이므로 재호출해도 안전하다.
     */
    override fun cancelPendingFor(guildPseudonym: String): Int {
        val pending =
            purge.findPendingIn(
                RevocationScope(guildPseudonym = guildPseudonym, channelId = null, userPseudonym = null),
            )
        pending.forEach { purge.purge(it) }
        return pending.size
    }
}
