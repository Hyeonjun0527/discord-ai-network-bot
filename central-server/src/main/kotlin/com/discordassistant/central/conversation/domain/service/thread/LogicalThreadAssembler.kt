package com.discordassistant.central.conversation.domain.service.thread

import com.discordassistant.central.conversation.domain.model.burst.BurstId
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
import com.discordassistant.central.conversation.domain.model.thread.ConversationThreadId
import com.discordassistant.central.conversation.domain.model.thread.ReplyGraph
import com.discordassistant.central.conversation.domain.model.thread.ReplyTarget

/**
 * 논리 스레드 생성 baseline(NEXA-P05-T010, 순수 함수). burst 들을 **reply edge 우선, mention/adjacency 보조**
 * 로 묶어 논리 대화 스레드([ConversationThreadId])에 배치한다. union-find 식 병합으로 연결된 burst 를 한 스레드로 모은다.
 *
 * 병합 우선순위(KISS baseline):
 * 1. reply edge(Resolved target) — 가장 강한 연결. 두 burst 를 같은 스레드로 합친다.
 * 2. mention edge(DIRECT addressee 후보) — 보조 연결.
 * 3. adjacency edge(시간 인접 score > 0) — 가장 약한 연결.
 * tombstone reply target 은 burst 가 아니므로 병합에 쓰지 않는다(연결 사실은 그래프에 남지만 스레드 병합 X).
 *
 * **acceptance(T010) — 동시에 진행되는 두 대화가 하나로 합쳐지지 않는다**:
 * 각 대화가 자기들끼리만 reply/mention/adjacency 로 이어지면(서로 교차 신호 없음) 두 connected component 가
 * 분리되어 **서로 다른 [ConversationThreadId]** 를 받는다. 교차 신호가 없으면 병합되지 않는다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 상태 없음(모든 입력을 인자로 받는다).
 */
object LogicalThreadAssembler {
    /**
     * [bursts] 를 reply/mention/adjacency 신호로 병합해 각 burst 가 속한 논리 스레드 id 를 돌려준다.
     * 같은 connected component 의 burst 들은 같은 [ConversationThreadId] 를, 분리된 component 는 다른 id 를 받는다.
     *
     * @param bursts 배치 대상 burst 들(같은 위치/채널 전제 — 위치 키는 id 생성에만 사용).
     * @param replyGraph reply edge(우선).
     * @param mentionLinks mention 보조 연결 — DIRECT mention 으로 이어지는 burst 쌍(상류가 MentionGraph 에서 도출).
     * @param adjacencyEdges 시간 인접 edge(보조).
     */
    fun assemble(
        bursts: List<UtteranceBurst>,
        replyGraph: ReplyGraph = ReplyGraph.EMPTY,
        mentionLinks: List<Pair<BurstId, BurstId>> = emptyList(),
        adjacencyEdges: List<AdjacencyEdge> = emptyList(),
    ): Map<BurstId, ConversationThreadId> {
        val ids = bursts.map { it.burstId }
        val uf = UnionFind(ids)

        // 1. reply edge 우선 — Resolved target 만 병합(tombstone 은 burst 가 아님).
        replyGraph.edges.forEach { edge ->
            val target = edge.target
            if (target is ReplyTarget.Resolved) {
                uf.union(edge.source, target.burstId)
            }
        }
        // 2. mention 보조 — 명시된 burst 간 링크만 병합.
        mentionLinks.forEach { (a, b) -> uf.union(a, b) }
        // 3. adjacency 보조 — score > 0 인 edge 병합.
        adjacencyEdges.forEach { edge ->
            if (edge.score > 0.0) uf.union(edge.from, edge.to)
        }

        // 각 component 대표(root)에 결정론적 스레드 id 부여 — 입력 순서대로 ordinal.
        val rootToThread = LinkedHashMap<BurstId, ConversationThreadId>()
        val byId = bursts.associateBy { it.burstId }
        var ordinal = 0
        return ids.associateWith { id ->
            val root = uf.find(id)
            rootToThread.getOrPut(root) {
                val location = byId.getValue(root).location
                ConversationThreadId.of(location, ordinal++)
            }
        }
    }
}

/**
 * 최소 union-find(논리 스레드 병합 전용, 순수 도메인 내부 헬퍼). path compression 으로 같은 component 를 합친다.
 * conversation.domain 안에 두어 외부 의존을 만들지 않는다(KISS — 외부 그래프 라이브러리 미사용).
 */
private class UnionFind(
    ids: List<BurstId>,
) {
    private val parent: MutableMap<BurstId, BurstId> = ids.associateWith { it }.toMutableMap()

    fun find(id: BurstId): BurstId {
        var root = id
        while (parent.getValue(root) != root) {
            root = parent.getValue(root)
        }
        // path compression.
        var cur = id
        while (parent.getValue(cur) != root) {
            val next = parent.getValue(cur)
            parent[cur] = root
            cur = next
        }
        return root
    }

    fun union(
        a: BurstId,
        b: BurstId,
    ) {
        if (a !in parent || b !in parent) return
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[rb] = ra
    }
}
