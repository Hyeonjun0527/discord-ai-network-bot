package com.discordassistant.central.socialmemory.domain.service.retrieval

/**
 * retrieval **다양성·중복 억제** 규칙(NEXA-P07-T020, 순수 도메인 서비스). 같은 사건에서 파생된 유사 기억이 prompt 를
 * 점령하지 않도록 **source cluster 별 개수 제한**을 둔다.
 *
 * **acceptance(T020) — top-k 가 하나의 오래된 사건 복제본으로 채워지지 않는다**: [diversify] 는 랭킹된 기억을 점수
 * 순으로 받되, 같은 cluster(원천 이벤트 ID 가 겹치거나 같은 subject/predicate 인 유사 기억)에서 최대 [perCluster]
 * 개까지만 통과시킨다 — 한 사건의 복제본이 top-k 를 독점하지 못하고 서로 다른 사건의 기억이 자리를 얻는다.
 *
 * 순수성: Spring/JPA/JDA 미참조.
 */
object MemoryDiversityFilter {
    /**
     * 점수 내림차순으로 정렬됐다고 가정한 [ranked] 에서 cluster 당 [perCluster] 개까지만 골라 [topK] 개를 돌려준다.
     * cluster 키는 같은 원천 이벤트를 공유하거나(같은 사건) 같은 (subject·predicate)(같은 주장 복제)면 같다.
     */
    fun diversify(
        ranked: List<RankedMemory>,
        topK: Int,
        perCluster: Int = DEFAULT_PER_CLUSTER,
    ): List<RankedMemory> {
        require(topK > 0) { "topK 는 양수여야 한다" }
        require(perCluster > 0) { "perCluster 는 양수여야 한다" }

        val selected = ArrayList<RankedMemory>(topK)
        // 대표 cluster id → 채워진 개수. 같은 source event 를 공유하면 같은 대표로 합친다(union 대신 first-seen 대표).
        val clusterCounts = HashMap<String, Int>()
        val eventToCluster = HashMap<String, String>()

        for (item in ranked) {
            if (selected.size >= topK) break
            val clusterId = clusterIdFor(item, eventToCluster)
            val count = clusterCounts.getOrDefault(clusterId, 0)
            if (count >= perCluster) continue // 이 사건 복제본은 이미 한도 — 다른 사건에 자리를 양보(T020).
            selected.add(item)
            clusterCounts[clusterId] = count + 1
            // 이 기억의 모든 원천 이벤트를 같은 cluster 로 묶는다(이후 같은 사건 복제본 탐지).
            item.fact.source.sourceEventIds
                .forEach { eventToCluster.putIfAbsent(it, clusterId) }
        }
        return selected
    }

    /**
     * 이 기억의 cluster 대표 id 를 정한다: 이미 같은 원천 이벤트가 어떤 cluster 에 묶여 있으면 그 대표(같은 사건
     * 복제본)를, 없으면 이 기억의 첫 원천 이벤트로 새 대표를 만든다 — 서로 다른 사건은 다른 cluster 다(같은
     * 주장이라도 사건이 다르면 합치지 않는다). 원천 이벤트가 없는 방어 케이스만 (subject·predicate) claim 키로 떨어진다.
     */
    private fun clusterIdFor(
        item: RankedMemory,
        eventToCluster: Map<String, String>,
    ): String {
        item.fact.source.sourceEventIds
            .firstNotNullOfOrNull { eventToCluster[it] }
            ?.let { return it }
        item.fact.source.sourceEventIds
            .minOrNull()
            ?.let { return "event:$it" }
        return "claim:${item.fact.visibility.guildPseudonym}:${item.fact.subject}:${item.fact.predicate}"
    }

    const val DEFAULT_PER_CLUSTER = 1
}
