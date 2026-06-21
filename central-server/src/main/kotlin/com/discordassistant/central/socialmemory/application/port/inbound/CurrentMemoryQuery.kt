package com.discordassistant.central.socialmemory.application.port.inbound

import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.extraction.CandidateKind
import com.discordassistant.central.socialmemory.domain.model.fact.TemporalFact
import java.time.Instant

/**
 * **현재 유효 기억 조회** 인바운드 포트(NEXA-P07-T018, 헥사고날). speech/participation 이 "이 시점·이 스코프·이 주제의
 * 유효 기억"을 읽는 단일 진입점이다.
 *
 * **acceptance(T018) — 현재 조회와 과거 시점 조회가 서로 다른 결과를 낼 수 있다**: [MemoryQueryCriteria.asOf] 가
 * valid-at 시점을 정한다. supersession(T008)으로 닫힌 과거 사실은 과거 [asOf] 에서는 유효(보임)하지만 현재
 * [asOf] 에서는 validTo 가 지나 제외된다 — 같은 subject/predicate 라도 asOf 에 따라 다른 object 가 나온다.
 *
 * 순수 application: 도메인 타입·표준 타입만 본다 — JPA/JDA 타입 미참조. 구현은 application 서비스가 retrieval
 * ranking(T019)·diversity(T020) 와 결합한다.
 */
interface CurrentMemoryQuery {
    /** [criteria] 에 맞는 유효 사실을 valid-at·스코프·threshold 로 필터해 돌려준다(랭킹·다양성은 구현이 적용). */
    fun query(criteria: MemoryQueryCriteria): List<TemporalFact>
}

/**
 * 기억 조회 기준(NEXA-P07-T018). [asOf] valid-at 시점, [requesterScope] 가시성, [subject]/[kind] 좁힘,
 * [minConfidence] 신뢰 하한, [topK] 상한.
 */
data class MemoryQueryCriteria(
    /** 유효 시점(valid-at). 현재 조회면 now, 과거 회상이면 과거 시각 — 결과가 달라질 수 있다(acceptance T018). */
    val asOf: Instant,
    /** 요청자 가시성 스코프(이 스코프가 기억 스코프를 포함할 때만 노출, T011). */
    val requesterScope: VisibilityScope,
    /** 특정 주어로 좁힘(null 이면 스코프 내 전체 주어). */
    val subject: String? = null,
    /** 특정 기억 유형으로 좁힘(null 이면 전체 — 현재는 TEMPORAL_FACT 만 조회 대상). */
    val kind: CandidateKind? = null,
    /** 신뢰 하한 [0,1]. 이 미만 confidence 기억은 제외(약한 근거 차단). */
    val minConfidence: Double = 0.0,
    /** 결과 상한(top-k). diversity 적용 후 이 개수까지(T019/T020). */
    val topK: Int = DEFAULT_TOP_K,
) {
    init {
        require(minConfidence in 0.0..1.0) { "minConfidence 는 [0,1] 범위여야 한다" }
        require(topK > 0) { "topK 는 양수여야 한다" }
        require(subject == null || subject.isNotBlank()) { "subject 는 빈 문자열일 수 없다" }
    }

    companion object {
        const val DEFAULT_TOP_K = 8
    }
}
