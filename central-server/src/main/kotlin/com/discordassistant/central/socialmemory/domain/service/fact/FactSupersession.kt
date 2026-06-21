package com.discordassistant.central.socialmemory.domain.service.fact

import com.discordassistant.central.socialmemory.domain.model.MemoryStatus
import com.discordassistant.central.socialmemory.domain.model.fact.TemporalFact
import java.time.Instant

/**
 * 새 [TemporalFact] 가 같은 주장(subject+predicate)의 이전 fact 를 **대체**하는 규칙(NEXA-P07-T008, 순수 도메인 서비스).
 *
 * **acceptance(T008) — 이전 fact 를 물리 삭제하지 않고 현재 조회에서 제외한다**: [supersede] 는 이전 fact 를 지우지 않고
 * [validTo] 를 [supersededAt] 으로 채우고 status 를 [MemoryStatus.SUPERSEDED] 로 바꾼 **새 값**을 돌려준다. 그리고
 * [SupersessionResult.supersedesEdge] 에 (이전 id → 새 id) edge 를 남긴다 — lineage 보존, 현재 조회는 ACTIVE 만 본다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 java.time 만 쓴다.
 */
object FactSupersession {
    /**
     * [previous] 를 [next] 로 대체한다. 같은 주장이 아니면(subject/predicate/guild 불일치) [IllegalArgumentException].
     * object 가 같으면(같은 값 재진술) 대체가 아니라 reinforce 대상이므로 호출하지 않는 것을 권장하지만, 규칙상 같은
     * object 도 validTo 를 닫아 이력화한다(중복 ACTIVE 방지). 이전 fact 는 물리 삭제하지 않는다.
     */
    fun supersede(
        previous: TemporalFact,
        next: TemporalFact,
        supersededAt: Instant,
    ): SupersessionResult {
        require(previous.sameClaimAs(next)) { "다른 주장(subject/predicate/guild)은 대체할 수 없다" }
        require(previous.id != next.id) { "자기 자신을 대체할 수 없다" }
        require(!supersededAt.isBefore(previous.validFrom)) { "supersededAt 은 이전 fact 의 validFrom 이전일 수 없다" }
        val closed =
            previous.copy(
                validTo = supersededAt,
                status = MemoryStatus.SUPERSEDED,
            )
        return SupersessionResult(
            superseded = closed,
            current = next,
            supersedesEdge = SupersedesEdge(supersededFactId = previous.id, supersedingFactId = next.id),
        )
    }
}

/**
 * supersession 결과(NEXA-P07-T008). [superseded] 는 물리 삭제되지 않고 validTo·SUPERSEDED 로 닫힌 이전 fact,
 * [current] 는 새 현재 fact, [supersedesEdge] 는 둘 사이의 lineage edge 다.
 */
data class SupersessionResult(
    val superseded: TemporalFact,
    val current: TemporalFact,
    val supersedesEdge: SupersedesEdge,
)

/** "어느 fact 가 어느 fact 를 대체했는가" lineage edge(NEXA-P07-T008). 물리 삭제 대신 이 edge 로 이력을 추적한다. */
data class SupersedesEdge(
    val supersededFactId: String,
    val supersedingFactId: String,
)
