package com.discordassistant.central.socialmemory.application.retrieval

import com.discordassistant.central.socialmemory.application.port.inbound.CurrentMemoryQuery
import com.discordassistant.central.socialmemory.application.port.inbound.MemoryQueryCriteria
import com.discordassistant.central.socialmemory.application.port.out.TemporalFactReadPort
import com.discordassistant.central.socialmemory.domain.model.extraction.CandidateKind
import com.discordassistant.central.socialmemory.domain.model.fact.TemporalFact
import com.discordassistant.central.socialmemory.domain.service.retrieval.MemoryDiversityFilter
import com.discordassistant.central.socialmemory.domain.service.retrieval.MemoryRetrievalRanking

/**
 * [CurrentMemoryQuery] 구현 유스케이스(NEXA-P07-T018/T019/T020, application). 읽기 포트로 후보를 가져와 랭킹(T019)·
 * 다양성(T020)·threshold·scope·asOf 를 결합해 결과를 만든다.
 *
 * **acceptance(T018) — 현재/과거 시점 조회가 다른 결과**: [MemoryQueryCriteria.asOf] 를 [MemoryRetrievalRanking.rank]
 * 의 now 로 넘긴다 — 과거 asOf 면 그때 유효했던(validTo 가 그 이후) 사실이, 현재 asOf 면 닫힌 과거 사실이 제외돼
 * 같은 주장이라도 다른 결과가 나온다.
 *
 * 순수 application: 인바운드/아웃바운드 포트·도메인 서비스만 본다 — JPA/JDA 타입 미참조. 읽기 아웃바운드 어댑터가
 * 붙으면 Spring 빈으로 승격한다(현재는 포트만 정의, 단위 테스트는 fake reader 로 검증).
 */
class RetrieveCurrentMemoryService(
    private val reader: TemporalFactReadPort,
) : CurrentMemoryQuery {
    override fun query(criteria: MemoryQueryCriteria): List<TemporalFact> {
        // 현재는 TEMPORAL_FACT 만 조회 대상(다른 유형은 추후 포트 확장). kind 가 다른 유형이면 빈 결과.
        if (criteria.kind != null && criteria.kind != CandidateKind.TEMPORAL_FACT) return emptyList()

        val candidates = reader.findCandidates(criteria.requesterScope.guildPseudonym)
        val ranked =
            MemoryRetrievalRanking
                .rank(
                    facts = candidates,
                    requesterScope = criteria.requesterScope,
                    now = criteria.asOf,
                    subjectFilter = criteria.subject,
                ).filter { it.fact.confidence.value >= criteria.minConfidence }
        return MemoryDiversityFilter.diversify(ranked, criteria.topK).map { it.fact }
    }
}
