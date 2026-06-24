package com.discordassistant.central.socialmemory.adapter.outbound.appraisal

import com.discordassistant.central.routing.application.CloudLlm
import com.discordassistant.central.socialmemory.domain.model.appraisal.Appraisal
import com.discordassistant.central.socialmemory.domain.model.appraisal.RelationshipLens
import com.discordassistant.central.socialmemory.domain.service.appraisal.AppraiserProvider
import com.discordassistant.central.socialmemory.domain.service.appraisal.SocialAppraiser
import org.springframework.stereotype.Component

/**
 * Social Appraiser 의 GLM 호출 어댑터 — core `GlmAppraiserProvider`(B5) 이식의 NEXA wiring.
 *
 * 도메인([SocialAppraiser])이 만든 프롬프트를 NEXA [CloudLlm](z.ai GLM)로 실호출하고, 응답을 등급
 * [Appraisal] 로 파싱한다. 실패·비활성은 보수 폴백([Appraisal.conservativeDefault])으로 표면화한다
 * (과민반응보다 무반응이 안전 — 부록 C). I2(등급만)·I3(기분 미입력)는 도메인이 보장한다.
 *
 * 선례: `CloudLlmMemoryCandidateExtractor` 와 동일 패턴(@Component·`generate(prompt, model)`·`parse(text)`).
 * 시스템 프롬프트는 [CloudLlm.generate] 규약대로 prompt 본문 앞에 합쳐 보낸다.
 */
@Component
class CloudLlmAppraiserProvider(
    private val cloudLlm: CloudLlm,
) : AppraiserProvider {
    override fun appraise(
        messages: List<String>,
        speakerPersonId: String,
        lens: RelationshipLens,
        candidatePersonIds: List<String>?,
    ): Appraisal {
        if (!cloudLlm.isEnabled()) {
            return Appraisal.conservativeDefault(error = "cloud-llm-disabled")
        }
        val prompt =
            SocialAppraiser.SYSTEM_PROMPT + "\n\n" +
                SocialAppraiser.buildUserPrompt(messages, speakerPersonId, lens, candidatePersonIds)
        return try {
            val result = cloudLlm.generate(prompt, APPRAISER_MODEL)
            SocialAppraiser.parse(result.text)
        } catch (e: Exception) {
            Appraisal.conservativeDefault(error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    companion object {
        /** judge/speech 와 같은 빠른 등급 판정 모델(core: glm-4.5-air). */
        const val APPRAISER_MODEL: String = "glm-4.5-air"
    }
}
