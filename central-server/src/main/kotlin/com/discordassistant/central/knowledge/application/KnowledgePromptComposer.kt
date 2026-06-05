package com.discordassistant.central.knowledge.application

import com.discordassistant.central.shared.ResponseMode
import org.springframework.stereotype.Component

/**
 * RAG 프롬프트 컨텍스트 조립·문자 예산(budget) 계산 협력자 — 읽기 전용·순수 계산
 * (@Transactional·write·repo 의존 없음).
 *
 * 검색 결과를 [KnowledgePromptContext.entries] 로 채우는 budget 루프, 스니펫/소스 ref 포매팅,
 * response-mode 정규화와 RAG budget 매핑을 한곳에 모은다. 본문은 [KnowledgeSearchService] 에서
 * 1바이트 불변으로 이동했으며 budget 공식(MIN_CONTEXT_SNIPPET_CHARS, ragBudgetFor 값,
 * "off" 전용 분기)·사용자 노출 문구는 변경하지 않는다.
 */
@Component
class KnowledgePromptComposer {
    /**
     * `search.results` 와 이미 산정된 [budget] 으로 프롬프트 컨텍스트 본문을 조립한다. budget 루프·스니펫
     * 절단·구분자 계산은 [KnowledgeSearchService.promptContext] 에서 그대로 이동했다(동작 동일).
     */
    fun assemble(
        results: List<KnowledgeSearchResult>,
        budget: Int,
    ): PromptComposition {
        val entries = mutableListOf<KnowledgePromptEntry>()
        val sourceRefs = mutableListOf<KnowledgeSourceRef>()
        val lines = mutableListOf<String>()
        var used = 0
        for (result in results) {
            val ref = "S${entries.size + 1}"
            val prefix = "- [$ref] "
            val separatorChars = if (lines.isEmpty()) 0 else 1
            val snippetBudget = budget - used - separatorChars - prefix.length
            if (snippetBudget < MIN_CONTEXT_SNIPPET_CHARS) break
            val text = result.toPromptSnippet().take(snippetBudget)
            val line = "$prefix$text"
            val nextUsed = used + separatorChars + line.length
            if (nextUsed > budget) break
            entries +=
                KnowledgePromptEntry(
                    sourceId = result.sourceId,
                    knowledgeSpaceId = result.knowledgeSpaceId,
                    title = result.title,
                    sourceType = result.sourceType,
                    sourceUri = result.sourceUri,
                    snippet = text,
                )
            sourceRefs += result.toSourceRef(ref)
            lines += line
            used = nextUsed
        }
        val contextText = lines.joinToString("\n")
        return PromptComposition(
            entries = entries,
            sourceRefs = sourceRefs,
            contextText = contextText,
            usedChars = used,
        )
    }

    fun normalizeResponseMode(value: String): String =
        when (value.trim().lowercase()) {
            // "off"(RAG 비활성)는 ResponseMode 에 없는 RAG 전용 모드라 여기서만 처리.
            "off", "none", "disabled", "끄기", "비활성" -> "off"
            else -> ResponseMode.normalize(value).wire
        }

    fun ragBudgetFor(responseMode: String): Int =
        when (responseMode) {
            "off" -> 0
            "saving" -> 500
            "fast" -> 800
            "deep" -> 2_400
            else -> 1_200
        }

    private fun KnowledgeSearchResult.toSourceRef(ref: String): KnowledgeSourceRef =
        KnowledgeSourceRef(
            ref = ref,
            sourceId = sourceId,
            knowledgeSpaceId = knowledgeSpaceId,
            title = title,
            sourceType = sourceType,
            sourceUri = sourceUri,
            visibility = "channel_scoped",
        )

    private fun KnowledgeSearchResult.toPromptSnippet(): String =
        listOfNotNull(
            title.take(180),
            contentPreview?.take(500),
            sourceUri?.take(240),
            "type=$sourceType",
        ).joinToString(" · ")

    private companion object {
        const val MIN_CONTEXT_SNIPPET_CHARS = 40
    }
}

/**
 * [KnowledgePromptComposer.assemble] 결과 — budget 루프가 만든 entries/refs/본문/사용량 묶음.
 * 파사드가 fallbackReason 계산에 그대로 사용한다.
 */
data class PromptComposition(
    val entries: List<KnowledgePromptEntry>,
    val sourceRefs: List<KnowledgeSourceRef>,
    val contextText: String,
    val usedChars: Int,
)
