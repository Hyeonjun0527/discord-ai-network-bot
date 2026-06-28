package com.discordassistant.central.socialmemory.adapter.outbound.extraction

import com.discordassistant.central.routing.application.CloudLlm
import com.discordassistant.central.socialmemory.application.extraction.MemoryCandidateExtractorPort
import com.discordassistant.central.socialmemory.application.extraction.MemoryExtractionRequest
import com.discordassistant.central.socialmemory.domain.model.extraction.MemoryCandidate
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * GLM 기반 기억 후보 추출 어댑터(NEXA-P07-T015). routing [CloudLlm] 포트로만 모델을 호출하고, socialmemory 에는
 * glm/Z.AI 타입을 노출하지 않는다(speech 와 동일 anti-corruption, ADR 0006·module-dag 금지 의존 #3).
 *
 * **payload 최소화·timeout·schema validation(acceptance T015)**:
 * - payload 최소화: [MemoryCandidateSchema.buildPrompt] 가 원문이 아닌 scene 메타·구조화 cue·닫힌 스키마 지시만 보낸다.
 * - timeout: 외부 HTTP timeout 은 CloudLlm 구현(ZaiCloudLlm, `central.cloud.llm-timeout-seconds`)이 강제한다 — 어댑터는
 *   포트만 호출하므로 timeout 정책을 중복 구현하지 않는다(SSOT).
 * - schema validation: [MemoryCandidateSchema.parse] 가 닫힌 enum·비빈 필드를 만족하는 원소만 후보로 받는다.
 *
 * **사실 확정 권한 없음(deliverable T015)**: 결과는 모두 GLM 약한 근거 후보(낮은 confidence)일 뿐 — 검증·승격(T016)을
 * 거쳐야 저장된다. CloudLlm 비활성·호출/파싱 실패는 **빈 리스트**로 흡수해 추출 실패가 응답·consolidation 을 막지
 * 않게 한다(fire-and-forget, acceptance T014).
 */
@Component
class CloudLlmMemoryCandidateExtractor(
    private val cloudLlm: CloudLlm,
    private val clock: Clock = Clock.systemUTC(),
) : MemoryCandidateExtractorPort {
    private val log = LoggerFactory.getLogger(CloudLlmMemoryCandidateExtractor::class.java)
    private val mapper = ObjectMapper()

    override fun extract(request: MemoryExtractionRequest): List<MemoryCandidate> {
        if (!request.isExtractable) return emptyList()
        if (!cloudLlm.isEnabled()) return emptyList()
        return try {
            val result = cloudLlm.generate(MemoryCandidateSchema.buildPrompt(request), EXTRACTION_MODEL)
            MemoryCandidateSchema.parse(result.text, request, mapper, clock.instant())
        } catch (e: Exception) {
            // 추출 실패는 사실을 만들지 않는다 — 상세는 로그로만, 빈 후보로 흡수(consolidation 다음 batch 진행).
            log.warn("기억 후보 추출 실패(빈 후보): scene={} {}", request.sceneId, e.javaClass.simpleName)
            emptyList()
        }
    }

    companion object {
        /** 추출에 쓰는 기본 모델(라우팅은 CloudLlm 구현이 z.ai 로 매핑). */
        const val EXTRACTION_MODEL = "glm-5.1"
    }
}
