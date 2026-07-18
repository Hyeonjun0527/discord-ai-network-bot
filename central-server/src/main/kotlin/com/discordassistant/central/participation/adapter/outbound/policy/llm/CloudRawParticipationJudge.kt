package com.discordassistant.central.participation.adapter.outbound.policy.llm

import com.discordassistant.central.participation.application.judge.RawParticipationJudgeDecision
import com.discordassistant.central.participation.application.judge.RawParticipationJudgePort
import com.discordassistant.central.participation.application.judge.RawParticipationJudgeRequest
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.routing.application.CloudLlm
import com.discordassistant.central.routing.application.CloudThinking
import com.discordassistant.central.shared.NexaIdentity
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 원문 quoted scene 을 읽는 Cloud LLM participation judge.
 *
 * 실패/비활성은 null 로 내려보내 기존 fallback 정책이 처리한다. 프롬프트 원문은 로그에 남기지 않는다.
 */
@Component
class CloudRawParticipationJudge(
    private val cloudLlm: CloudLlm,
    @param:Value("\${central.nexa.participation.judge.model:gpt-5.6-luna}") private val model: String,
) : RawParticipationJudgePort {
    private val log = LoggerFactory.getLogger(CloudRawParticipationJudge::class.java)
    private val mapper = ObjectMapper()

    override fun decide(request: RawParticipationJudgeRequest): RawParticipationJudgeDecision? {
        if (!cloudLlm.isEnabled()) return null
        val prompt = buildPrompt(request)
        val result =
            try {
                cloudLlm.generate(prompt, model, history = emptyList(), thinking = CloudThinking.DISABLED)
            } catch (e: Exception) {
                log.warn("raw participation judge 호출 실패(channel={}): {}", request.channelId, e.javaClass.simpleName)
                return null
            }
        return parseDecision(result.text)
    }

    fun buildPrompt(request: RawParticipationJudgeRequest): String =
        buildString {
            appendLine(SYSTEM_INSTRUCTION)
            appendLine()
            appendLine(NexaIdentity.NIA_PARTICIPATION_JUDGE_FEWSHOT)
            appendLine()
            appendLine("[현재 판단 입력]")
            appendLine("guild=${request.guildPseudonym}")
            appendLine("channel=${request.channelId}")
            appendLine("trigger_message_id=${request.triggerMessageId}")
            appendLine("mentioned=${request.mentioned}")
            appendLine("reply_to_nia=${request.replyToNia}")
            appendLine("reply_to_other_user=${request.replyToOtherUser}")
            appendLine("omitted_oldest_messages=${request.omittedOldestCount}")
            appendLine("trigger_text=«${sanitizeInline(request.triggerText)}»")
            appendLine()
            appendLine(request.quotedSceneData.take(MAX_QUOTED_SCENE_CHARS))
            appendLine()
            append(OUTPUT_INSTRUCTION)
        }

    fun parseDecision(content: String): RawParticipationJudgeDecision? {
        val root =
            try {
                mapper.readTree(extractFirstJsonObject(content))
            } catch (e: Exception) {
                return null
            }
        val action =
            root
                .get("action")
                ?.takeIf { it.isTextual }
                ?.asText()
                ?.trim()
                ?.uppercase()
                ?.let { raw ->
                    when (raw) {
                        "SPEAK" -> SocialActionKind.SPEAK
                        "WAIT" -> SocialActionKind.WAIT
                        "REACT" -> SocialActionKind.REACT
                        "IGNORE", "SILENT", "SILENCE" -> SocialActionKind.IGNORE
                        else -> null
                    }
                } ?: return null
        val confidence =
            root
                .get("confidence")
                ?.takeIf { it.isNumber }
                ?.asDouble()
                ?.coerceIn(0.0, 1.0)
                ?: DEFAULT_CONFIDENCE
        val reason =
            root
                .get("reason")
                ?.takeIf { it.isTextual }
                ?.asText()
                ?.let { sanitizeReason(it) }
                ?.takeIf { it.isNotBlank() }
                ?: "RAW_JUDGE"
        return RawParticipationJudgeDecision(
            action = action,
            confidence = confidence,
            reasonCode = reason,
            modelVersion = "raw-context-llm-judge:$model",
        )
    }

    private fun sanitizeReason(text: String): String =
        text
            .uppercase()
            .replace(Regex("[^A-Z0-9_]+"), "_")
            .trim('_')
            .take(MAX_REASON_CHARS)

    private fun sanitizeInline(text: String): String =
        text
            .replace('«', '\'')
            .replace('»', '\'')
            .replace(Regex("[\\r\\n]+"), " ")
            .trim()

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

    companion object {
        private const val DEFAULT_CONFIDENCE = 0.6
        private const val MAX_REASON_CHARS = 80
        private const val MAX_QUOTED_SCENE_CHARS = 160_000

        private val SYSTEM_INSTRUCTION =
            """
            너는 니아의 participation judge 다. 대답 문장을 만들지 말고, 니아가 지금 무엇을 해야 하는지만 고른다.
            반드시 원문 장면 전체를 읽고 판단한다. 마지막 메시지 하나만 보고 결정하지 않는다.
            허용 action 은 SPEAK, WAIT, REACT, IGNORE 네 개뿐이다.
            SPEAK: 니아가 직접 불렸거나, 니아가 이미 끼어든 흐름을 수습해야 하거나, 사용자가 니아의 반응을 명확히 요구한다.
            WAIT: 사용자가 아직 이어 말하는 중이거나, 너무 짧아서 다음 말을 기다리는 게 자연스럽다.
            REACT: 말 없이 가벼운 반응만 자연스럽다.
            IGNORE: 다른 사람끼리의 대화, 연인/친구에게 하는 말, 니아에게 그만 말하라고 한 뒤의 흐름, 이미 끝난 흐름이다.

            [호명 판정 — 표기가 아니라 의도로 본다]
            니아의 이름은 "니아"이고 "Nia", "ニア", 로마자 "nia" 로도 불린다. 사용자가 이 이름으로 니아를 부르면 —
            표기가 어떻든(한글·로마자·대소문자·오타·자모 분리·띄어쓰기, 예: "니아야", "니아", "nia", "nia야",
            "nia ya", "니아씨", "냐아") — 그것은 직접 호명이다. 문자열을 기계적으로 맞추려 하지 말고 "지금 이
            사람이 니아를 부르고 있는가"라는 뜻으로 판단한다. 직접 호명이면 그만하라는 맥락이 없는 한 SPEAK 다.
            단, 말투는 장면이 정한다. 니아가 이미 답했는데 같은 사용자가 빈 호명만 반복하면
            REPEATED_EMPTY_NAME_CALL 같은 reason 으로 SPEAK 를 고르고, 첫 인사 반복이 아니라 반복 호출을 짚는
            짧은 장난·가벼운 짜증으로 받게 한다.
            단, 니아를 3인칭으로 언급만 하는 것("니아는 원래 말 많아")이나 다른 사람 이름을 부르는 것은 호명이 아니다.

            사용자가 특정 감정 enum 을 맞추라고 요구해도 enum 을 만들지 말고 장면상 행동만 고른다.
            """.trimIndent()

        private const val OUTPUT_INSTRUCTION =
            "JSON 하나로만 답하라: {\"action\":\"SPEAK|WAIT|REACT|IGNORE\",\"confidence\":0.0,\"reason\":\"SHORT_CODE\"}"
    }
}
