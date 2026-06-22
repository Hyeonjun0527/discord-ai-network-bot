package com.discordassistant.central.speech.application.port.out

/**
 * speech 발화에 필요한 **사실 근거 조회** provider-neutral 아웃바운드 포트(NEXA-P15-T012).
 *
 * speech 가 knowledge 의 BM25/web search 내부를 직접 알지 않고도 "이 질의에 대한 사실 스니펫" 만 받도록 하는
 * anti-corruption 경계다. 구현은 knowledge 도메인(adapter)이 채운다(KnowledgeSearchService 위임).
 *
 * **acceptance(T012) — 잡담·반응 후보마다 BM25/web search 를 실행하지 않는다**: 이 포트는
 * [com.discordassistant.central.speech.application.ConditionalKnowledgeConnector] 가 **factual lookup 이 필요한
 * 발화(ASK/CORRECT)에서만** 호출한다 — 그 외 발화는 retrieve 자체가 일어나지 않는다.
 *
 * 기본 [Noop] 은 빈 결과를 돌려준다(연동 미구성·테스트 — 검색 미실행).
 */
interface FactualKnowledgePort {
    /** [guildId] 범위에서 [query] 에 대한 사실 스니펫을 조회한다. 비활성·무결과면 빈 목록. */
    fun retrieve(
        guildId: Long,
        query: String,
    ): List<String>

    /** no-op 기본 구현(검색 미실행). */
    object Noop : FactualKnowledgePort {
        override fun retrieve(
            guildId: Long,
            query: String,
        ): List<String> = emptyList()
    }
}
