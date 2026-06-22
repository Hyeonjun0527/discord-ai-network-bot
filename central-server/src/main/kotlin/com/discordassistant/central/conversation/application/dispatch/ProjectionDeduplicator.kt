package com.discordassistant.central.conversation.application.dispatch

import com.discordassistant.central.conversation.application.port.out.ProjectionLedgerPort
import com.discordassistant.central.conversation.domain.model.event.EventId

/**
 * 중복 이벤트 dedup 처리(NEXA-P03-T016). projection worker 가 한 이벤트를 projection 에 적용하기 **직전**에
 * 통과시켜, 같은 [EventId] 가 같은 projection version 으로 두 번 적용되지 않게 막는다([ProjectionLedgerPort]).
 *
 * **acceptance(T016)**: 같은 fixture 를 10 회 재생해도 projection input 수가 1 회와 같다 — [shouldApply] 가
 * 처음 한 번만 true 를 돌려주고, 이후 9 번은 [markApplied] 가 false(이미 적용)라 projection 으로 흘러가지
 * 않는다. at-least-once 전달(outbox)·replay 가 겹쳐도 projection 상태가 중복 변경되지 않는다(멱등).
 *
 * 순수성: application.dispatch 소속이며 포트([ProjectionLedgerPort])로만 결합한다(adapter 구현 미참조).
 */
class ProjectionDeduplicator(
    private val ledger: ProjectionLedgerPort,
    /** 이 projection 의 버전 — 같은 이벤트라도 새 projection version 으로는 다시 적용한다(replay/재투영). */
    private val projectionVersion: Int,
) {
    /**
     * [eventId] 를 이 projection version 에 적용해야 하는가. 처음이면 true(원장에 기록하고 적용하라),
     * 이미 적용됐으면 false(중복 — 건너뛰라). 호출 자체가 기록을 남기므로 두 번째부터는 항상 false 다.
     */
    fun shouldApply(eventId: EventId): Boolean = ledger.markApplied(eventId, projectionVersion)

    /** 적용 없이 중복 여부만 확인(관측·조건 분기용). 부작용 없음. */
    fun isDuplicate(eventId: EventId): Boolean = ledger.isApplied(eventId, projectionVersion)
}
