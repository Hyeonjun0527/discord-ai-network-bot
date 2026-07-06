package com.discordassistant.central.speech.adapter.outbound.routing

import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * GLM 응답 schema parser(NEXA-P14-T012, adapter/outbound/routing).
 *
 * 후보 버블 배열·style tags·uncertainty 를 **strict parser** 로 읽는다. 코드펜스가 섞여도 첫 JSON object 만
 * 허용하고, 스키마가 깨지면 빈 목록으로 **안전하게 실패**한다(예외를 던지지 않는다 — 상위 fallback T016 이 빈 결과를
 * 무발화/리액션으로 다룬다).
 *
 * **acceptance(T012) — malformed JSON/텍스트 응답이 안전한 실패로 처리된다**: 비-JSON·필드 누락·잘못된 타입은
 * 모두 [emptyList] 로 흡수한다. 적어도 빈 문자열 아닌 bubble 하나를 가진 후보만 통과한다.
 *
 * 순수 객체(외부 의존 없음·테스트 가능). glm/zai 타입을 노출하지 않는다(OpenAI 호환 content 문자열만 받음).
 */
object SpeechCandidateResponseParser {
    /**
     * 모델이 돌려준 content 문자열에서 후보를 추출한다. [candidateIdPrefix] 로 candidate ID 를 부여한다(추적용).
     * 파싱 불가·스키마 위반은 빈 목록.
     */
    fun parse(
        content: String,
        mapper: ObjectMapper,
        candidateIdPrefix: String,
    ): List<SpeechCandidate> {
        val root =
            try {
                mapper.readTree(extractFirstJsonObject(content))
            } catch (e: Exception) {
                return emptyList()
            }
        val candidatesNode = root.get("candidates")?.takeIf { it.isArray } ?: return emptyList()
        val result = mutableListOf<SpeechCandidate>()
        candidatesNode.forEachIndexed { index, node ->
            parseCandidate(node, "$candidateIdPrefix-$index")?.let { result.add(it) }
        }
        return result
    }

    private fun parseCandidate(
        node: JsonNode,
        candidateId: String,
    ): SpeechCandidate? {
        if (!node.isObject) return null
        val bubbles =
            node
                .get("bubbles")
                ?.takeIf { it.isArray }
                ?.mapNotNull { it.takeIf { b -> b.isTextual }?.asText()?.let(::normalizeBubble) }
                ?.filter { it.isNotBlank() }
                ?: return null
        if (bubbles.isEmpty()) return null // 빈 발화 후보는 채택하지 않는다(무발화는 fallback 이 다룸).
        val styleTags =
            node
                .get("style_tags")
                ?.takeIf { it.isArray }
                ?.mapNotNull { it.takeIf { t -> t.isTextual }?.asText()?.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        val uncertainty =
            node
                .get("uncertainty")
                ?.takeIf { it.isNumber }
                ?.asDouble()
                ?.coerceIn(0.0, 1.0)
                ?: 0.0
        return SpeechCandidate(
            candidateId = candidateId,
            bubbles = bubbles,
            styleTags = styleTags,
            uncertainty = uncertainty,
        )
    }

    private fun normalizeBubble(text: String): String {
        val trimmed = text.trim()
        val stripped =
            trimmed
                .trimEnd { it == '.' || it == '。' }
                .trimEnd()
        // "..." 같은 순수 말줄임표는 니아 페르소나가 의도한 침묵비트 버블이다(NexaIdentity NIA_CHAT_FEWSHOT 의
        // `니아: ...`). 끝 마침표만 다듬다 통째로 비워지면 의도된 발화가 사라지므로 원문을 유지한다.
        return stripped.ifBlank { trimmed }
    }

    /** 코드펜스(```json … ```)를 벗기고 첫 `{ … }` object 만 남긴다(CloudLlmResponseParser 와 동일 결). */
    private fun extractFirstJsonObject(text: String): String {
        var cleaned = text.trim()
        if (cleaned.startsWith("```")) {
            cleaned =
                cleaned
                    .removePrefix("```")
                    .removePrefix("json")
                    .trim()
                    .removeSuffix("```")
                    .trim()
        }
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start in 0 until end) cleaned = cleaned.substring(start, end + 1)
        return cleaned
    }
}
