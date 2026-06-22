package com.discordassistant.central.actionruntime.application.port.out

import com.discordassistant.central.actionruntime.application.port.inbound.RevocationScope
import com.discordassistant.central.actionruntime.domain.model.ActionIdentity

/**
 * 동의 철회 시 pending 예약·생성 content 즉시 제거 아웃바운드 포트(NEXA-P13-T014, application 레이어).
 *
 * [com.discordassistant.central.actionruntime.application.port.inbound.ConsentRevocationListener] 가 철회 범위를 받으면
 * 이 포트로 해당 범위의 **아직 종결되지 않은(non-terminal)** 예약 행동을 찾아 취소 종결하고, 그 행동에 묶인 생성
 * content(speech 가 만든 문구 등)를 제거한다. 구현(JPA)이 범위 조건과 content 삭제를 채운다.
 *
 * 순수성 경계: application 레이어 — 도메인/application 타입만. Spring/JPA/JDA 미참조.
 */
interface PendingActionPurgePort {
    /**
     * [scope] 범위의 종결되지 않은 예약 행동 식별자들을 돌려준다(취소 대상). 더 좁은 식별자(channelId/userPseudonym)
     * 가 있으면 그 수준으로 좁힌다.
     */
    fun findPendingIn(scope: RevocationScope): List<ActionIdentity>

    /**
     * [identity] 예약을 CANCELLED 로 종결하고, 그 예약에 묶인 **생성 content 를 제거**한다(T014 — content 도 같이
     * 지운다). 이미 terminal 이면 content 제거만 보장한다(idempotent).
     */
    fun purge(identity: ActionIdentity)
}
