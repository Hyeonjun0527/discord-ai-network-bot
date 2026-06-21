package com.discordassistant.central.conversation.domain.service.burst

import com.discordassistant.central.conversation.domain.model.burst.BurstGapPolicy
import com.discordassistant.central.conversation.domain.model.burst.BurstSession
import com.discordassistant.central.conversation.domain.model.burst.MessageFragment
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
import com.discordassistant.central.conversation.domain.model.event.GuildId
import java.time.Instant

/**
 * 고정 간격 baseline 버스트 segmenter(NEXA-P04-T004, 순수 함수형 도메인 서비스). 같은 작성자·같은 위치의 짧은 간격
 * 연속 조각을 하나의 버스트로 묶는 단순 기준선이다. 경계 **결정의 단일 소유자** — 동적 feature(T006)·각 경계 규칙
 * (T007~T010)은 신호만 제공하고, 합쳐 join/finalize 를 정하는 곳은 여기다(acceptance T006: 결정은 baseline 규칙).
 *
 * 순수성: conversation.domain 규칙(NexaArchitectureTest.nexaDomainsArePure)을 위해 Spring/JPA/JDA/application/adapter
 * 타입을 일절 참조하지 않는다. gap 설정은 application 이 [com.discordassistant.central.conversation.application.port.out.BurstGapConfigPort]
 * 로 **해소한 [BurstGapPolicy] 값을 인자로 주입**받는다 — 도메인 서비스는 포트를 모르고 값만 본다(헥사고날 순수성).
 * 매 조각 판정 시점에 application 이 현재 정책을 넘기므로 런타임 설정 변경이 다음 조각부터 반영된다(T005).
 *
 * **acceptance(T004) — 경계 직전/직후 분리**: 두 조각 간격이 effective gap 이하면 같은 버스트(join), 초과하면 경계라
 * 기존 버스트를 finalize 하고 새 버스트를 연다. nickname-burst fixture 처럼 1초 간격 연속은 묶이고, 다른 작성자 개입
 * (T007)·reply target 변경(T008)·thread 이동(T009)도 경계로 처리한다. typing 연장(T010)은 effective gap 을 늘린다.
 */
class FixedGapBurstSegmenter {
    /**
     * 한 작성자 조각 [next] 를 받아 그 작성자의 OPEN 버스트에 대해 경계 결정을 내린다(상태는 바꾸지 않고 결정만 반환).
     *
     * @param session 이 조각이 속한 위치의 현재 세션(작성자별 OPEN 버스트 추적, T003).
     * @param policy 이 조각의 채널에 적용되는 gap 정책(application 이 [BurstGapConfigPort] 로 해소해 주입, T005).
     * @param typingExpiresAt 이 작성자의 가장 최근 관찰된 typing 만료 시각(없으면 null, T010). 미래 비참조.
     */
    fun decide(
        guildId: GuildId,
        session: BurstSession,
        next: MessageFragment,
        policy: BurstGapPolicy,
        typingExpiresAt: Instant? = null,
    ): SegmentDecision {
        val open =
            session.openBurstOf(next.authorId)
                ?: return SegmentDecision.StartNew(UtteranceBurst.open(guildId, next))

        // 위치(thread)·reply target 변경은 시간과 무관한 즉시 경계(T008/T009).
        if (BurstBoundaryRules.crossesLocationBoundary(open, next) ||
            BurstBoundaryRules.changesReplyTarget(open, next)
        ) {
            return SegmentDecision.FinalizeThenStart(
                finalized = open.finalize(),
                started = UtteranceBurst.open(guildId, next),
            )
        }

        // 시간 간격 경계 — typing 연장(T010) 반영한 effective gap 과 비교(T004/T006).
        val effectiveGap =
            BurstBoundaryRules.effectiveGapWithTyping(
                policy = policy,
                baselineGap = policy.defaultGap,
                typingExpiresAt = typingExpiresAt,
                at = next.occurredAt,
            )
        if (BurstBoundaryRules.exceedsGap(open, next, effectiveGap)) {
            return SegmentDecision.FinalizeThenStart(
                finalized = open.finalize(),
                started = UtteranceBurst.open(guildId, next),
            )
        }

        return SegmentDecision.Append(open.append(next))
    }

    /**
     * 다른 작성자 [intruder] 개입이 OPEN 버스트를 종료시키는지 판정한다(T007). 종료 대상 작성자들의 finalize 된
     * 버스트 목록을 돌려준다(빈 목록이면 종료 없음). [intruder] 자신의 버스트 진행은 [decide] 가 따로 처리한다.
     */
    fun finalizeOnIntrusion(
        session: BurstSession,
        intruder: MessageFragment,
    ): List<UtteranceBurst> =
        session.openBursts.values
            .filter { BurstBoundaryRules.otherAuthorEndsBurst(it, intruder) }
            .map { it.finalize() }
}

/**
 * segmenter 의 경계 결정 결과(sealed). 호출자(application)가 이 결정을 세션·이벤트 발행에 적용한다 — 도메인 서비스는
 * 순수 결정만 만들고 부수효과(저장·발행)는 application 이 소유한다.
 */
sealed interface SegmentDecision {
    /** 작성자에게 진행 중 버스트가 없어 새 OPEN 버스트를 시작한다(경계 아님 — 첫 조각). */
    data class StartNew(
        val started: UtteranceBurst,
    ) : SegmentDecision

    /** 조각이 기존 OPEN 버스트에 이어붙는다(같은 버스트 유지). */
    data class Append(
        val updated: UtteranceBurst,
    ) : SegmentDecision

    /** 경계라 기존 버스트를 [finalized](BurstFinalized 발행 대상) 하고 새 버스트 [started] 를 연다. */
    data class FinalizeThenStart(
        val finalized: UtteranceBurst,
        val started: UtteranceBurst,
    ) : SegmentDecision
}
