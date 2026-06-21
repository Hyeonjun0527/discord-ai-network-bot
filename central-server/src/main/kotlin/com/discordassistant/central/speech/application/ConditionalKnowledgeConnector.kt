package com.discordassistant.central.speech.application

import com.discordassistant.central.speech.application.port.out.FactualKnowledgePort
import com.discordassistant.central.speech.domain.model.SpeechSocialAct

/**
 * 조건부 knowledge RAG 연결자(NEXA-P15-T012, speech application).
 *
 * speech plan 의 social act 가 **사실 조회를 요구할 때만**([SpeechSocialAct.requiresFactualLookup])
 * [FactualKnowledgePort] 로 knowledge 검색을 수행한다.
 *
 * **acceptance(T012) — 잡담·반응 후보마다 BM25/web search 를 실행하지 않는다**:
 *  - [ASK]/[CORRECT](질문·사실 정정)만 retrieve 를 호출한다.
 *  - [ACKNOWLEDGE]/[AGREE]/[TEASE]/[SELF_DISCLOSE]/[CHANGE_TOPIC]/[UNKNOWN] 등 잡담/반응성 발화는 **포트를 한 번도
 *    호출하지 않는다** → BM25/web search 미실행(비용 절감).
 *
 * 순수성: application — 도메인 enum·port·표준 타입만. Spring/JPA/JDA·knowledge 내부 미참조.
 */
class ConditionalKnowledgeConnector(
    private val knowledge: FactualKnowledgePort,
) {
    /**
     * [act] 가 사실 조회를 요구하면 [query] 로 knowledge 를 검색하고, 아니면 **호출하지 않고** 빈 결과를 돌려준다.
     * [retrieved] 로 실제 검색 수행 여부를 노출한다(테스트·비용 관찰).
     */
    fun retrieveIfFactual(
        act: SpeechSocialAct,
        guildId: Long,
        query: String,
    ): KnowledgeRetrievalResult {
        if (!act.requiresFactualLookup) {
            return KnowledgeRetrievalResult(snippets = emptyList(), retrieved = false) // 잡담/반응 → 검색 미실행
        }
        return KnowledgeRetrievalResult(snippets = knowledge.retrieve(guildId, query), retrieved = true)
    }
}

/**
 * 조건부 knowledge 검색 결과(NEXA-P15-T012). [retrieved] 가 false 면 BM25/web search 를 **한 번도** 호출하지 않은
 * 증거다(비용 발생 없음).
 */
data class KnowledgeRetrievalResult(
    /** 검색된 사실 스니펫(검색 미실행이면 빈 목록). */
    val snippets: List<String>,
    /** 실제로 knowledge 포트를 호출했는가(false = 잡담/반응 → 미실행). */
    val retrieved: Boolean,
)
