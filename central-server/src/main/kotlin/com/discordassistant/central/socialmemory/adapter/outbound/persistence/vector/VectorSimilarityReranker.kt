package com.discordassistant.central.socialmemory.adapter.outbound.persistence.vector

import com.discordassistant.central.socialmemory.domain.service.retrieval.RankedMemory

/**
 * pgvector **보조** 의미 유사도 reranker(NEXA-P07-T023, **순수 함수** — DB 불필요·테스트 가능).
 *
 * **validity 우회 금지(acceptance T023)**: [rerank] 의 입력 [structurallyFiltered] 는 이미 정형 필터(status=ACTIVE·
 * valid 구간·visibility·confidence, T019)를 통과한 후보 집합이다. 이 함수는 그 집합 **안에서만** 임베딩 코사인
 * 유사도로 순서를 보조 조정한다 — expired/conflicted/deleted·다른 스코프 기억을 새로 끌어오지 않는다(추가 불가,
 * 입력에 없는 기억은 결과에도 없다). 임베딩이 없는 기억은 기존 랭킹 점수를 유지한다(fallback).
 *
 * pgvector 가 없으면(인코딩 임베딩만 있으면) 이 선형 코사인 보조 스캔이 fallback 이다. 있으면 운영에서 ANN 인덱스가
 * 후보를 좁힌 뒤 같은 의미로 정렬한다 — 결과 계약은 동일하다(보조, validity 는 정형 필터가 이미 강제).
 */
object VectorSimilarityReranker {
    /**
     * 정형 필터를 통과한 [structurallyFiltered] 를, [queryEmbedding] 과의 코사인 유사도와 기존 점수를 결합해 다시
     * 정렬한다. [embeddingsByMemoryId] 에 임베딩이 있는 기억만 유사도 가중을 받고, 없으면 기존 점수만 쓴다.
     * 결과 집합은 입력 집합의 **부분집합·재정렬**일 뿐 — 새 기억을 추가하지 않는다(validity 우회 불가).
     */
    fun rerank(
        structurallyFiltered: List<RankedMemory>,
        queryEmbedding: FloatArray?,
        embeddingsByMemoryId: Map<String, FloatArray>,
        vectorWeight: Double = DEFAULT_VECTOR_WEIGHT,
    ): List<RankedMemory> {
        if (queryEmbedding == null || queryEmbedding.isEmpty()) return structurallyFiltered
        require(vectorWeight in 0.0..1.0) { "vectorWeight 는 [0,1] 범위여야 한다" }
        return structurallyFiltered
            .map { item ->
                val embedding = embeddingsByMemoryId[item.fact.id]
                val similarity =
                    if (embedding != null && embedding.size == queryEmbedding.size) {
                        cosine(queryEmbedding, embedding)
                    } else {
                        null // 임베딩 없음 — 유사도 가중 없이 기존 점수 유지(fallback).
                    }
                val combined =
                    if (similarity == null) {
                        item.score
                    } else {
                        ((1.0 - vectorWeight) * item.score) + (vectorWeight * similarity.coerceIn(0.0, 1.0))
                    }
                item.copy(score = combined)
            }.sortedWith(compareByDescending<RankedMemory> { it.score }.thenBy { it.fact.id })
    }

    /** 코사인 유사도([-1,1] → 음수는 0 으로 절단해 [0,1]). 영벡터면 0. */
    private fun cosine(
        a: FloatArray,
        b: FloatArray,
    ): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i].toDouble()
            na += a[i].toDouble() * a[i].toDouble()
            nb += b[i].toDouble() * b[i].toDouble()
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return (dot / (Math.sqrt(na) * Math.sqrt(nb))).coerceIn(-1.0, 1.0).coerceAtLeast(0.0)
    }

    const val DEFAULT_VECTOR_WEIGHT = 0.3
}
