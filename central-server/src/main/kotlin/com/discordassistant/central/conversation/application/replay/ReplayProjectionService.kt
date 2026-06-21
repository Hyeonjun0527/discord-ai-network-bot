package com.discordassistant.central.conversation.application.replay

import com.discordassistant.central.conversation.application.dispatch.ProjectionDeduplicator
import com.discordassistant.central.conversation.application.port.out.ProjectionLedgerPort
import org.springframework.stereotype.Service

/**
 * 내부 replay 유스케이스(NEXA-P03-T019). guild/channel/time range 와 projection version 을 받아 저장된 이벤트를
 * **새 projection 으로 재생**한다. 운영/디버깅용 내부 도구이며 외부 노출은 dev 게이트([central.dev.enabled]) 안에서만 한다.
 *
 * **외부 전송 side effect 절대 없음(acceptance T019)**: 이 서비스는 읽기 소스([ReplayEventSourcePort])와
 * projection 재적용 sink([ReplayProjectionSink])·dedup 원장([ProjectionLedgerPort])에만 의존한다 — Discord
 * 전송 포트(actionruntime/JDA)를 **타입 수준에서 참조하지 않는다**. 따라서 replay 중 운영 Discord 전송이
 * 일어날 경로가 존재하지 않는다(컴파일 보장 + ArchUnit conversation→하류 금지).
 *
 * **멱등 재생**: 같은 (eventId, projectionVersion) 은 [ProjectionDeduplicator] 가 한 번만 통과시킨다 — 같은
 * 범위를 여러 번 replay 해도 그 version 의 읽기 모델은 한 번만 갱신된다. 새 projectionVersion 으로는 다시
 * 적용된다(재투영의 목적).
 */
@Service
class ReplayProjectionService(
    private val source: ReplayEventSourcePort,
    private val sink: ReplayProjectionSink,
    private val ledger: ProjectionLedgerPort,
) {
    /**
     * [criteria] 범위의 이벤트를 결정론적 순서로 읽어 dedup 통과분만 [criteria] 의 projectionVersion 으로
     * 재적용한다. 읽은 수·재적용 수·중복 skip 수를 [ReplayReport] 로 돌려준다(운영 가시성).
     */
    fun replay(criteria: ReplayCriteria): ReplayReport {
        val dedup = ProjectionDeduplicator(ledger, criteria.projectionVersion)
        val keys = source.streamForReplay(criteria)
        var reapplied = 0
        var skippedDuplicate = 0
        for (key in keys) {
            if (dedup.shouldApply(key.eventId)) {
                sink.reapply(key, criteria.projectionVersion)
                reapplied += 1
            } else {
                skippedDuplicate += 1
            }
        }
        return ReplayReport(
            scanned = keys.size,
            reapplied = reapplied,
            skippedDuplicate = skippedDuplicate,
            projectionVersion = criteria.projectionVersion,
        )
    }
}

/** replay 실행 결과 요약(운영 가시성). 원문 미포함 — 건수와 대상 version 만. */
data class ReplayReport(
    val scanned: Int,
    val reapplied: Int,
    val skippedDuplicate: Int,
    val projectionVersion: Int,
)
