package com.discordassistant.central.participation.application.fewshot

import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotAction
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotExample
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotPrivacyClass
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotVersion
import org.springframework.stereotype.Service

@Service
class NiaFewShotEvalService {
    fun evaluate(version: NiaFewShotVersion): NiaFewShotEvalResult = evaluate(version.examples)

    fun evaluate(examples: List<NiaFewShotExample>): NiaFewShotEvalResult {
        val failures = mutableListOf<NiaFewShotEvalFailure>()
        val actionCoverage = examples.groupingBy { it.expectedAction }.eachCount().toSortedMap(compareBy { it.name })

        val hardAmbiguousCount = examples.count { HARD_AMBIGUOUS_TAG in it.tags }

        examples.forEachIndexed { index, example ->
            val ref = example.reference(index)
            if (example.privacyClass == NiaFewShotPrivacyClass.PRODUCTION_DERIVED) {
                failures +=
                    NiaFewShotEvalFailure("privacy.production_derived", ref, "publish gate accepts only synthetic/anonymized seed data")
            }
            example.rawMessages.forEach { message ->
                PRODUCTION_SHAPED_PATTERNS
                    .firstNotNullOfOrNull { (name, pattern) ->
                        name.takeIf { pattern.containsMatchIn(message.text) }
                    }?.let { patternName ->
                        failures += NiaFewShotEvalFailure("privacy.production_shaped_text", ref, "matched=$patternName")
                    }
            }
            if (OVER_TALK_TAG in example.tags && example.expectedAction == NiaFewShotAction.SPEAK) {
                failures += NiaFewShotEvalFailure("over_talk.speak", ref, "over-talk risk example cannot expect SPEAK")
            }
            if (MISSED_REPLY_TAG in example.tags && example.expectedAction != NiaFewShotAction.SPEAK) {
                failures += NiaFewShotEvalFailure("under_talk.non_speak", ref, "missed-reply risk example must expect SPEAK")
            }
            if (HARD_AMBIGUOUS_TAG in example.tags && example.badAlternative.whyBad.isBlank()) {
                failures += NiaFewShotEvalFailure("ambiguous_contrast.blank_why_bad", ref, "hard ambiguous example needs whyBad")
            }
            if (STALE_MEMORY_TAG in example.tags && !example.citesLatestMessage()) {
                failures += NiaFewShotEvalFailure("stale_memory.latest_not_cited", ref, "latest raw message must be evidence")
            }
        }

        return NiaFewShotEvalResult(
            status = if (failures.isEmpty()) "PASS" else "FAIL",
            readyForPublish = failures.isEmpty(),
            checkedExamples = examples.size,
            actionCoverage = actionCoverage.mapKeys { it.key.name },
            hardAmbiguousCount = hardAmbiguousCount,
            failures = failures,
        )
    }

    private fun NiaFewShotExample.reference(index: Int): String = id?.let { "example:$it" } ?: "example_index:${index + 1}"

    private fun NiaFewShotExample.citesLatestMessage(): Boolean {
        val latestOffset = rawMessages.maxOfOrNull { it.offsetMs } ?: return false
        val latestRefs = rawMessages.filter { it.offsetMs == latestOffset }.map { it.ref }.toSet()
        return evidenceRefs.any { it in latestRefs }
    }

    companion object {
        const val HARD_AMBIGUOUS_TAG = "hard-ambiguous"
        const val OVER_TALK_TAG = "over-talk-risk"
        const val MISSED_REPLY_TAG = "missed-reply-risk"
        const val STALE_MEMORY_TAG = "stale-memory-override"

        private val PRODUCTION_SHAPED_PATTERNS: Map<String, Regex> =
            mapOf(
                "discord_snowflake" to Regex("\\b\\d{17,20}\\b"),
                "discord_user_mention" to Regex("<@!?\\d+>"),
                "discord_channel_mention" to Regex("<#\\d+>"),
                "discord_message_url" to Regex("https?://(?:canary\\.|ptb\\.)?discord(?:app)?\\.com/channels/\\S+"),
            )
    }
}

data class NiaFewShotEvalResult(
    val status: String,
    val readyForPublish: Boolean,
    val checkedExamples: Int,
    val actionCoverage: Map<String, Int>,
    val hardAmbiguousCount: Int,
    val failures: List<NiaFewShotEvalFailure>,
)

data class NiaFewShotEvalFailure(
    val code: String,
    val exampleRef: String?,
    val detail: String,
) {
    fun toMessage(): String =
        buildString {
            append(code)
            exampleRef?.let { append(":").append(it) }
            append(" (").append(detail).append(")")
        }
}
