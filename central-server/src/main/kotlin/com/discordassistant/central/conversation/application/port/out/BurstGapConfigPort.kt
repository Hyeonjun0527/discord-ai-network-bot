package com.discordassistant.central.conversation.application.port.out

import com.discordassistant.central.conversation.domain.model.burst.BurstGapPolicy
import com.discordassistant.central.conversation.domain.model.event.ChannelId

/**
 * 채널별 버스트 gap 설정의 아웃바운드 포트(NEXA-P04-T005, 헥사고날). segmenter 가 한 조각을 판정할 때 그 채널의
 * gap 정책을 이 포트로 조회한다 — 구현 어댑터(인메모리/JPA)는 adapter.outbound 에 둔다.
 *
 * 순수성 경계: 포트 계약은 application 레이어 소속이라 도메인 타입([BurstGapPolicy]/[ChannelId])만 본다 —
 * Spring/JPA/JDA 타입을 참조하지 않는다(어댑터가 채운다).
 *
 * **acceptance(T005)**:
 * - 설정 부재 시 안전한 기본값: [policyFor] 가 채널 설정이 없으면 [BurstGapPolicy.DEFAULT] 를 돌려줘야 한다
 *   (null 을 흘리지 않는다 — fail-safe). [resolve] 가 그 fallback 을 캡슐화한다.
 * - 런타임 변경은 새 fragment 부터 반영: segmenter 는 **각 조각을 판정하는 시점에** 이 포트를 조회한다. 따라서
 *   조회 후 정책이 바뀌어도 이미 판정된 조각은 영향받지 않고, 다음 조각부터 새 정책이 적용된다(포트 자체는 상태를
 *   캐싱하지 않는다 — 매 조회가 현재 값을 반환).
 */
interface BurstGapConfigPort {
    /**
     * [channelId] 의 gap 정책을 돌려준다. 채널별 설정이 없으면 null — 호출자는 [resolve] 로 안전 기본을 적용한다.
     * 구현은 매 호출에 **현재** 설정을 반환해야 한다(런타임 변경이 다음 조회부터 보이도록 캐싱 금지).
     */
    fun policyFor(channelId: ChannelId): BurstGapPolicy?

    /** 설정이 없으면 안전 기본([BurstGapPolicy.DEFAULT])을 적용한 정책(fail-safe). segmenter 가 직접 쓰는 입구. */
    fun resolve(channelId: ChannelId): BurstGapPolicy = policyFor(channelId) ?: BurstGapPolicy.DEFAULT
}
