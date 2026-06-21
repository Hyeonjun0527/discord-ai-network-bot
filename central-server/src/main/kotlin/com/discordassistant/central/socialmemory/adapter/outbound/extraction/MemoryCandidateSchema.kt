package com.discordassistant.central.socialmemory.adapter.outbound.extraction

import com.discordassistant.central.socialmemory.application.extraction.MemoryExtractionRequest
import com.discordassistant.central.socialmemory.domain.model.extraction.CandidateKind
import com.discordassistant.central.socialmemory.domain.model.extraction.MemoryCandidate
import com.discordassistant.central.socialmemory.domain.model.extraction.StatementModality
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * 추출 프롬프트 빌더 + 응답 스키마 검증(NEXA-P07-T015, **순수 함수** — 외부 서비스 불필요·테스트 가능).
 *
 * **payload 최소화(acceptance T015)**: 프롬프트는 원문이 아니라 scene 식별자·가시성 종류·참여 가명·구조화 cue 만
 * 담는다(data-categories.md: 원문 비전송). 모델에는 닫힌 스키마(JSON array of {kind,subject,predicate,object,
 * modality,sensitive})로만 답하라고 지시한다.
 *
 * **schema validation(acceptance T015)**: [parse] 는 모델 응답에서 첫 JSON array 만 허용하고, 각 원소가 닫힌 필드·
 * enum 을 만족할 때만 후보로 받아들인다. 모르는 kind/modality, 빈 필드, 비배열은 **조용히 버린다**(스키마를 못
 * 맞춘 후보는 저장 후보로 올리지 않는다 — fail-safe). 어떤 후보도 여기서 사실로 확정되지 않는다(낮은 confidence).
 *
 * glm/Z.AI 타입 미참조 — Jackson 으로 일반 JSON 만 다룬다(anti-corruption: 어댑터는 CloudLlm 포트로 텍스트만 받는다).
 */
object MemoryCandidateSchema {
    /** 모델에 보낼 추출 지시(닫힌 스키마 강제). 원문 미포함 — 구조화 cue 만. */
    fun buildPrompt(request: MemoryExtractionRequest): String {
        val visibilityKind = request.visibility::class.simpleName ?: "Guild"
        val cues = request.observedCues.take(MAX_CUES).joinToString("; ") { it.take(MAX_CUE_LEN) }
        // 원문이 아니라 구조화 메타·cue 만 전달한다(payload 최소화). 닫힌 스키마로만 답하도록 지시.
        return buildString {
            append("관찰된 대화 단서에서 안정적 사실/관계 후보만 구조화해 추출하라. ")
            append("농담·부정·인용·가정은 modality 로 표시하고 사실로 단정하지 마라. ")
            append("정치·종교·성적지향·건강 같은 민감 추론은 sensitive=true 로 표시하라. ")
            append("출력은 오직 JSON 배열. 각 원소: ")
            append("{\"kind\":\"TEMPORAL_FACT|EPISODIC|RELATIONSHIP|PENDING_INTENT\",")
            append("\"subject\":\"가명\",\"predicate\":\"닫힌키\",\"object\":\"짧은값\",")
            append("\"modality\":\"ASSERTED|JOKE|NEGATED|QUOTED|HYPOTHETICAL\",\"sensitive\":false}. ")
            append("scope=").append(visibilityKind)
            append("; participants=").append(request.participants.size)
            if (cues.isNotBlank()) append("; cues=").append(cues)
        }
    }

    /**
     * 모델 응답 [content] 에서 후보를 파싱한다. 첫 JSON array 만 허용하고, 닫힌 enum·비빈 필드를 만족하는 원소만
     * [MemoryCandidate] 로 만든다. 스키마를 못 맞춘 원소·전체 비배열은 조용히 버린다(fail-safe, 사실 미확정).
     */
    fun parse(
        content: String,
        request: MemoryExtractionRequest,
        mapper: ObjectMapper,
        now: Instant,
    ): List<MemoryCandidate> {
        val array =
            try {
                mapper.readTree(extractFirstJsonArray(content))
            } catch (e: Exception) {
                return emptyList()
            }
        if (!array.isArray) return emptyList()
        val source =
            MemorySource(
                sourceEventIds = setOf(request.sceneId),
                extractionVersion = request.extractionVersion,
                consentGranted = request.consentGranted,
                createdAt = now,
            )
        return array.mapNotNull { node -> toCandidate(node, request, source) }.take(MAX_CANDIDATES)
    }

    private fun toCandidate(
        node: JsonNode,
        request: MemoryExtractionRequest,
        source: MemorySource,
    ): MemoryCandidate? {
        if (!node.isObject) return null
        val kind = node.text("kind")?.let { enumOrNull<CandidateKind>(it) } ?: return null
        val subject = node.text("subject")?.takeIf { it.isNotBlank() }?.take(MAX_FIELD) ?: return null
        val predicate = node.text("predicate")?.takeIf { it.isNotBlank() }?.take(MAX_FIELD) ?: return null
        val obj = node.text("object")?.takeIf { it.isNotBlank() }?.take(MAX_FIELD) ?: return null
        val modality =
            node.text("modality")?.let { enumOrNull<StatementModality>(it) } ?: StatementModality.ASSERTED
        val sensitive = node.get("sensitive")?.takeIf { it.isBoolean }?.asBoolean() ?: false
        return MemoryCandidate(
            kind = kind,
            visibility = request.visibility,
            subject = subject,
            predicate = predicate,
            obj = obj,
            source = source,
            modality = modality,
            sensitive = sensitive,
        )
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isTextual }?.asText()?.trim()

    private inline fun <reified E : Enum<E>> enumOrNull(raw: String): E? =
        enumValues<E>().firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }

    /** 코드펜스를 벗기고 첫 `[ … ]` array 만 남긴다(CloudLlmResponseParser.extractFirstJsonObject 와 같은 형태). */
    private fun extractFirstJsonArray(text: String): String {
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
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start in 0 until end) cleaned = cleaned.substring(start, end + 1)
        return cleaned
    }

    private const val MAX_CUES = 8
    private const val MAX_CUE_LEN = 64
    private const val MAX_FIELD = 256
    private const val MAX_CANDIDATES = 32
}
