package com.discordassistant.central.speech.adapter.outbound.evaluation

import com.discordassistant.central.routing.application.CloudLlm
import com.discordassistant.central.routing.application.CloudThinking
import com.discordassistant.central.shared.CodeNiaPromptSource
import com.discordassistant.central.shared.NiaPromptKey
import com.discordassistant.central.shared.NiaPromptSource
import com.discordassistant.central.shared.NiaPromptTemplate
import com.discordassistant.central.speech.application.generation.CompleteActionSelector
import com.discordassistant.central.speech.application.port.out.CompleteActionEvaluation
import com.discordassistant.central.speech.application.port.out.CompleteActionEvaluationPort
import com.discordassistant.central.speech.application.port.out.CompleteActionEvaluationRequest
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** 모호한 장면의 완전 행동 후보를 Cloud LLM으로 비교한다. 실행 가능 후보의 범위는 앞선 AI judge 계약을 따른다. */
@Component
class CloudCompleteActionEvaluationAdapter(
    private val cloudLlm: CloudLlm,
    @param:Value("\${central.nexa.speech.action-evaluator.model:gpt-5.6-luna}") private val model: String,
    private val promptSource: NiaPromptSource = CodeNiaPromptSource,
) : CompleteActionEvaluationPort {
    private val log = LoggerFactory.getLogger(CloudCompleteActionEvaluationAdapter::class.java)
    private val mapper = ObjectMapper()

    override fun select(request: CompleteActionEvaluationRequest): CompleteActionEvaluation? {
        if (!cloudLlm.isEnabled()) return null
        val result =
            runCatching {
                cloudLlm.generate(prompt(request), model, history = emptyList(), thinking = CloudThinking.DISABLED)
            }.getOrElse { error ->
                log.warn("완전 행동 평가 실패(focus={}): {}", request.focusThreadKey, error::class.simpleName)
                return failedEvaluation("EVALUATOR_REQUEST_FAILED")
            }
        return parse(result.text, request) ?: failedEvaluation("EVALUATOR_OUTPUT_INVALID")
    }

    private fun prompt(request: CompleteActionEvaluationRequest): String {
        val recentScene =
            request.recentTurns.takeLast(MAX_TURNS).joinToString("\n") {
                "«${quoteData(it.speakerLabel, 120)}: ${quoteData(it.text, MAX_TURN_CHARS)}»"
            }
        val candidates =
            request.candidates.joinToString("\n") { candidate ->
                "id=${candidate.candidateId}; kind=${candidate.kind}; reaction=${candidate.reactionCode.orEmpty()}\n" +
                    candidate.bubbles.joinToString("\n") { "  bubble=«${it.take(MAX_BUBBLE_CHARS)}»" }
            }
        return NiaPromptTemplate.render(
            promptSource.text(NiaPromptKey.ACTION_EVALUATOR_TEMPLATE),
            mapOf(
                "speechIntent" to request.speechIntent.orEmpty().take(MAX_INTENT_CHARS),
                "socialAct" to request.socialAct.wireName,
                "provisionalDecision" to request.provisionalDecision,
                "provisionalConfidence" to request.provisionalConfidence.toString(),
                "contextVersion" to request.contextVersion.toString(),
                "seed" to request.seed.toString(),
                "triggerMessageRef" to request.triggerMessageRef.orEmpty(),
                "stateRefs" to request.stateRefs.joinToString(","),
                "enforcement" to request.enforcementConstraints.sorted().joinToString(","),
                "recentScene" to recentScene,
                "rawContext" to request.rawContextSceneData?.let { "«${quoteData(it, MAX_RAW_SCENE_CHARS)}»" }.orEmpty(),
                "candidates" to candidates,
            ),
        )
    }

    private fun parse(
        raw: String,
        request: CompleteActionEvaluationRequest,
    ): CompleteActionEvaluation? =
        runCatching {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val root = mapper.readTree(raw.substring(start, end + 1))
            val selected = root.path("selected_candidate_id").asText()
            if (request.candidates.none { it.candidateId == selected }) return null
            CompleteActionEvaluation(
                selectedCandidateId = selected,
                predictedOutcome = root.path("predicted_outcome").asText().take(400),
                reasonCode =
                    root
                        .path("reason_code")
                        .asText()
                        .uppercase()
                        .replace(Regex("[^A-Z0-9_]+"), "_")
                        .trim('_')
                        .take(80),
                confidence = root.path("confidence").asDouble(Double.NaN),
            )
        }.getOrNull()

    private fun failedEvaluation(reasonCode: String): CompleteActionEvaluation =
        CompleteActionEvaluation(
            selectedCandidateId = CompleteActionSelector.IGNORE_CANDIDATE_ID,
            predictedOutcome = "평가기 실패로 실행 가능한 행동을 보류한다",
            reasonCode = reasonCode,
            confidence = 0.0,
        )

    private fun quoteData(
        value: String,
        limit: Int,
    ): String =
        value
            .replace('«', '‹')
            .replace('»', '›')
            .replace(Regex("[\\p{Cc}&&[^\\n\\t]]"), " ")
            .take(limit)

    private companion object {
        const val MAX_INTENT_CHARS: Int = 1_000
        const val MAX_TURNS: Int = 24
        const val MAX_TURN_CHARS: Int = 1_000
        const val MAX_RAW_SCENE_CHARS: Int = 16_000
        const val MAX_BUBBLE_CHARS: Int = 2_000
    }
}
