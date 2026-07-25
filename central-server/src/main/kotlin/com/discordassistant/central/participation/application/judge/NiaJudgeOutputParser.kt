package com.discordassistant.central.participation.application.judge

import com.discordassistant.central.participation.application.port.out.NiaJudgeLlmRequest
import com.discordassistant.central.participation.application.port.out.NiaJudgeLlmResponse
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.Locale

class NiaJudgeOutputParser(
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) {
    fun parse(response: NiaJudgeLlmResponse): NiaJudgeOutputParseResult = parse(response.content)

    fun parse(content: String): NiaJudgeOutputParseResult =
        runCatching {
            val root = mapper.readTree(jsonObjectFrom(content))
            parseRoot(root)
        }.getOrElse { error ->
            NiaJudgeOutputParseResult.Rejected(
                code = "invalid_judge_output",
                message = error.message ?: error::class.simpleName.orEmpty(),
            )
        }

    private fun jsonObjectFrom(content: String): String {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        require(start >= 0 && end > start) { "judge output must contain a JSON object" }
        return content.substring(start, end + 1)
    }

    private fun parseRoot(root: JsonNode): NiaJudgeOutputParseResult {
        require(root.isObject) { "judge output must be a JSON object" }
        rejectFinalTextFields(root)
        rejectUnknownFields(root)

        val schema = root.requiredText("schema")
        require(schema == NiaJudgeLlmRequest.OUTPUT_SCHEMA) { "unsupported judge output schema: $schema" }

        val rawAction = root.requiredText("action").uppercase(Locale.ROOT)
        val action = rawAction.toSocialActionKind()
        val reason = root.requiredText("reason")
        val confidence = root.requiredDouble("confidence")
        require(confidence in 0.0..1.0) { "confidence must be in [0,1]: $confidence" }

        val evidenceRefs = root.optionalTextArray("evidenceRefs")
        if (action != SocialActionKind.IGNORE) {
            require(evidenceRefs.isNotEmpty()) { "non-IGNORE judge output requires evidenceRefs" }
        }
        evidenceRefs.forEach { require(it.isStableRef()) { "invalid evidence ref: $it" } }

        val riskFlags = root.optionalTextArray("riskFlags")
        riskFlags.forEach { require(it.isStableCode()) { "invalid risk flag: $it" } }

        val reevaluateAfterMs = root.optionalLong("reevaluateAfterMs", default = 0L)
        require(reevaluateAfterMs >= 0) { "reevaluateAfterMs must be non-negative: $reevaluateAfterMs" }

        val toneAxes = root.optionalToneAxes()
        val beliefDelta = root.optionalBeliefDelta()
        val reasonCode = root.optionalStableCode("reasonCode") ?: "judge.${rawAction.lowercase(Locale.ROOT)}"
        val decision =
            SingleJudgeDecision(
                action = action,
                confidence = confidence,
                delay = action.delayFrom(reevaluateAfterMs),
                reactionCandidate = action.reactionCandidateFrom(root),
                speechIntent = action.speechIntentFrom(root),
                toneAxes = toneAxes,
                reasonCode = JudgeReasonCode(reasonCode),
                beliefDelta = beliefDelta,
            )

        return NiaJudgeOutputParseResult.Accepted(
            parsed =
                NiaJudgeParsedDecision(
                    decision = decision,
                    reason = reason,
                    evidenceRefs = evidenceRefs,
                    riskFlags = riskFlags,
                    rawAction = rawAction,
                    reevaluateAfterMs = reevaluateAfterMs,
                ),
        )
    }

    private fun rejectUnknownFields(root: JsonNode) {
        val names = root.fieldNames().asSequence().toSet()
        val unknown = names - TOP_LEVEL_FIELDS
        require(unknown.isEmpty()) { "unknown judge output fields: ${unknown.sorted()}" }
    }

    private fun rejectFinalTextFields(root: JsonNode) {
        val banned =
            root
                .fieldNames()
                .asSequence()
                .filter { it in FINAL_TEXT_FIELDS }
                .toList()
        require(banned.isEmpty()) { "judge output must not include final response text fields: $banned" }
        root["speechIntent"]?.takeUnless { it.isNull }?.let { speechIntent ->
            val nestedBanned =
                speechIntent
                    .fieldNames()
                    .asSequence()
                    .filter { it in FINAL_TEXT_FIELDS }
                    .toList()
            require(nestedBanned.isEmpty()) {
                "judge speechIntent must not include final response text fields: $nestedBanned"
            }
        }
    }

    private fun String.toSocialActionKind(): SocialActionKind =
        when (this) {
            "IGNORE" -> SocialActionKind.IGNORE
            "WAIT" -> SocialActionKind.WAIT
            "REACT" -> SocialActionKind.REACT
            "SPEAK" -> SocialActionKind.SPEAK
            "CANCEL" -> SocialActionKind.CANCEL_PENDING
            else -> throw IllegalArgumentException("unknown judge action: $this")
        }

    private fun SocialActionKind.delayFrom(reevaluateAfterMs: Long): JudgeDecisionDelay =
        when (this) {
            SocialActionKind.WAIT -> {
                require(reevaluateAfterMs > 0) { "WAIT judge output requires positive reevaluateAfterMs" }
                JudgeDecisionDelay(millis = reevaluateAfterMs, wakeUpHint = "judge_wait")
            }
            else -> JudgeDecisionDelay(millis = reevaluateAfterMs)
        }

    private fun SocialActionKind.reactionCandidateFrom(root: JsonNode): JudgeReactionCandidate? =
        when (this) {
            SocialActionKind.REACT -> JudgeReactionCandidate(root.requiredText("reactionCode"))
            else -> null
        }

    private fun SocialActionKind.speechIntentFrom(root: JsonNode): JudgeSpeechIntent? =
        when (this) {
            SocialActionKind.SPEAK -> {
                val speechIntent = root.requiredObject("speechIntent")
                rejectSpeechIntentUnknownFields(speechIntent)
                JudgeSpeechIntent(
                    intentSummary = speechIntent.requiredText("intentSummary"),
                    sceneDirection = speechIntent.requiredText("sceneDirection"),
                    actHint = speechIntent.optionalText("actHint"),
                    bubbleCount = speechIntent.optionalInt("bubbleCount", JudgeSpeechIntent.MIN_BUBBLE_COUNT),
                    maxBubbleChars =
                        speechIntent.optionalInt(
                            "maxBubbleChars",
                            JudgeSpeechIntent.DEFAULT_MAX_BUBBLE_CHARS,
                        ),
                    interactionReading =
                        speechIntent.optionalText("interactionReading")
                            ?: speechIntent.requiredText("intentSummary"),
                    informationDepth =
                        speechIntent.optionalText("informationDepth")
                            ?: speechIntent.requiredText("sceneDirection"),
                    continuityRefs = speechIntent.optionalTextArray("continuityRefs").toSet(),
                    responseTargetRef = speechIntent.requiredText("responseTargetRef"),
                    responseObligation =
                        JudgeResponseObligation.valueOf(
                            speechIntent.requiredText("responseObligation").uppercase(Locale.ROOT),
                        ),
                    groundingNeed =
                        JudgeGroundingNeed.valueOf(
                            speechIntent.requiredText("groundingNeed").uppercase(Locale.ROOT),
                        ),
                    deliveryMode =
                        SpeechDeliveryMode.valueOf(
                            speechIntent.requiredText("deliveryMode").uppercase(Locale.ROOT),
                        ),
                )
            }
            else -> null
        }

    private fun rejectSpeechIntentUnknownFields(speechIntent: JsonNode) {
        val unknown = speechIntent.fieldNames().asSequence().toSet() - SPEECH_INTENT_FIELDS
        require(unknown.isEmpty()) { "unknown speechIntent fields: ${unknown.sorted()}" }
    }

    private fun JsonNode.optionalToneAxes(): JudgeToneAxes {
        val tone = this["toneAxes"]?.takeUnless { it.isNull } ?: return JudgeToneAxes.NEUTRAL
        require(tone.isObject) { "toneAxes must be an object" }
        val unknown = tone.fieldNames().asSequence().toSet() - TONE_AXIS_FIELDS
        require(unknown.isEmpty()) { "unknown toneAxes fields: ${unknown.sorted()}" }
        return JudgeToneAxes(
            warmth = tone.optionalDouble("warmth", default = JudgeToneAxes.NEUTRAL.warmth),
            playfulness = tone.optionalDouble("playfulness", default = JudgeToneAxes.NEUTRAL.playfulness),
            directness = tone.optionalDouble("directness", default = JudgeToneAxes.NEUTRAL.directness),
            emotionalIntensity =
                tone.optionalDouble("emotionalIntensity", default = JudgeToneAxes.NEUTRAL.emotionalIntensity),
        )
    }

    private fun JsonNode.optionalBeliefDelta(): JudgeBeliefDelta {
        val belief = this["beliefUpdates"]?.takeUnless { it.isNull } ?: return JudgeBeliefDelta.EMPTY
        require(belief.isObject) { "beliefUpdates must be an object" }
        val unknown = belief.fieldNames().asSequence().toSet() - BELIEF_UPDATE_FIELDS
        require(unknown.isEmpty()) { "unknown beliefUpdates fields: ${unknown.sorted()}" }

        val commonGround =
            belief.optionalObjectArray("commonGround").map { update ->
                rejectUnknownNestedFields(update, COMMON_GROUND_FIELDS, "commonGround")
                JudgeCommonGroundUpdate(
                    code = update.requiredText("code"),
                    confidence = update.requiredDouble("confidence"),
                    evidenceRefs = update.optionalTextArray("evidenceRefs").toSet(),
                    status = update.optionalBeliefStatus(),
                )
            }
        val hypotheses =
            belief.optionalObjectArray("intentHypotheses").map { update ->
                rejectUnknownNestedFields(update, HYPOTHESIS_FIELDS, "intentHypotheses")
                JudgeIntentHypothesisUpdate(
                    participantRef = update.requiredText("participantRef"),
                    code = update.requiredText("code"),
                    probability = update.requiredDouble("probability"),
                    evidenceRefs = update.optionalTextArray("evidenceRefs").toSet(),
                    status = update.optionalBeliefStatus(),
                )
            }
        val commitments =
            belief.optionalObjectArray("commitments").map { update ->
                rejectUnknownNestedFields(update, COMMITMENT_FIELDS, "commitments")
                JudgeCommitmentUpdate(
                    commitmentRef = update.requiredText("commitmentRef"),
                    topic = update.requiredText("topic"),
                    socialAct = update.requiredText("socialAct").uppercase(Locale.ROOT),
                    evidenceRefs = update.optionalTextArray("evidenceRefs").toSet(),
                    confidence = update.requiredDouble("confidence"),
                    status = JudgeCommitmentStatus.valueOf(update.requiredText("status").uppercase(Locale.ROOT)),
                )
            }
        hypotheses
            .filter { it.status == JudgeBeliefStatus.ACTIVE }
            .groupBy(JudgeIntentHypothesisUpdate::participantRef)
            .forEach { (participant, values) ->
                require(values.sumOf(JudgeIntentHypothesisUpdate::probability) <= 1.000001) {
                    "active intent hypothesis probabilities exceed 1 for $participant"
                }
            }
        return JudgeBeliefDelta(commonGround = commonGround, intentHypotheses = hypotheses, commitments = commitments)
    }

    private fun JsonNode.optionalBeliefStatus(): JudgeBeliefStatus {
        val raw = optionalText("status") ?: return JudgeBeliefStatus.ACTIVE
        return JudgeBeliefStatus.valueOf(raw.uppercase(Locale.ROOT))
    }

    private fun rejectUnknownNestedFields(
        node: JsonNode,
        allowed: Set<String>,
        label: String,
    ) {
        require(node.isObject) { "$label entry must be an object" }
        val unknown = node.fieldNames().asSequence().toSet() - allowed
        require(unknown.isEmpty()) { "unknown $label fields: ${unknown.sorted()}" }
    }

    private fun JsonNode.requiredText(field: String): String {
        val value = this[field]
        require(value != null && value.isTextual && value.asText().isNotBlank()) { "required text field missing: $field" }
        return value.asText()
    }

    private fun JsonNode.optionalText(field: String): String? {
        val value = this[field] ?: return null
        if (value.isNull) return null
        require(value.isTextual) { "optional text field must be textual: $field" }
        return value.asText().takeIf { it.isNotBlank() }
    }

    private fun JsonNode.requiredObject(field: String): JsonNode {
        val value = this[field]
        require(value != null && value.isObject) { "required object field missing: $field" }
        return value
    }

    private fun JsonNode.requiredDouble(field: String): Double {
        val value = this[field]
        require(value != null && value.isNumber) { "required numeric field missing: $field" }
        return value.asDouble()
    }

    private fun JsonNode.optionalDouble(
        field: String,
        default: Double,
    ): Double {
        val value = this[field] ?: return default
        if (value.isNull) return default
        require(value.isNumber) { "optional numeric field must be numeric: $field" }
        return value.asDouble()
    }

    private fun JsonNode.optionalLong(
        field: String,
        default: Long,
    ): Long {
        val value = this[field] ?: return default
        if (value.isNull) return default
        require(value.isNumber) { "optional long field must be numeric: $field" }
        return value.asLong()
    }

    private fun JsonNode.optionalInt(
        field: String,
        default: Int,
    ): Int {
        val value = this[field] ?: return default
        if (value.isNull) return default
        require(value.isIntegralNumber) { "optional int field must be an integer: $field" }
        return value.asInt()
    }

    private fun JsonNode.optionalStableCode(field: String): String? {
        val value = optionalText(field)?.lowercase(Locale.ROOT) ?: return null
        require(value.isStableCode()) { "optional stable code field is invalid: $field" }
        return value
    }

    private fun JsonNode.optionalTextArray(field: String): List<String> {
        val value = this[field] ?: return emptyList()
        if (value.isNull) return emptyList()
        require(value.isArray) { "field must be an array: $field" }
        return value.map { node ->
            require(node.isTextual && node.asText().isNotBlank()) { "array field must contain nonblank text: $field" }
            node.asText()
        }
    }

    private fun JsonNode.optionalObjectArray(field: String): List<JsonNode> {
        val value = this[field] ?: return emptyList()
        if (value.isNull) return emptyList()
        require(value.isArray) { "field must be an array: $field" }
        return value.map { node ->
            require(node.isObject) { "array field must contain objects: $field" }
            node
        }
    }

    companion object {
        private val TOP_LEVEL_FIELDS =
            setOf(
                "schema",
                "action",
                "reason",
                "reasonCode",
                "evidenceRefs",
                "reactionCode",
                "speechIntent",
                "toneAxes",
                "confidence",
                "riskFlags",
                "reevaluateAfterMs",
                "beliefUpdates",
            )
        private val SPEECH_INTENT_FIELDS =
            setOf(
                "intentSummary",
                "sceneDirection",
                "actHint",
                "bubbleCount",
                "maxBubbleChars",
                "interactionReading",
                "informationDepth",
                "continuityRefs",
                "responseTargetRef",
                "responseObligation",
                "groundingNeed",
                "deliveryMode",
            )
        private val TONE_AXIS_FIELDS = setOf("warmth", "playfulness", "directness", "emotionalIntensity")
        private val BELIEF_UPDATE_FIELDS = setOf("commonGround", "intentHypotheses", "commitments")
        private val COMMON_GROUND_FIELDS = setOf("code", "confidence", "evidenceRefs", "status")
        private val HYPOTHESIS_FIELDS = setOf("participantRef", "code", "probability", "evidenceRefs", "status")
        private val COMMITMENT_FIELDS = setOf("commitmentRef", "topic", "socialAct", "evidenceRefs", "confidence", "status")
        private val FINAL_TEXT_FIELDS = setOf("text", "message", "content", "utterance", "finalResponse", "final_response")
    }
}

sealed interface NiaJudgeOutputParseResult {
    data class Accepted(
        val parsed: NiaJudgeParsedDecision,
    ) : NiaJudgeOutputParseResult {
        override fun toString(): String = "Accepted($parsed)"
    }

    data class Rejected(
        val code: String,
        val message: String,
    ) : NiaJudgeOutputParseResult
}

data class NiaJudgeParsedDecision(
    val decision: SingleJudgeDecision,
    val reason: String,
    val evidenceRefs: List<String>,
    val riskFlags: List<String>,
    val rawAction: String,
    val reevaluateAfterMs: Long,
) {
    override fun toString(): String =
        "NiaJudgeParsedDecision(action=${decision.action}, confidence=${decision.confidence}, " +
            "reasonLength=${reason.length}, evidenceRefs=$evidenceRefs, riskFlags=$riskFlags, rawAction=$rawAction, " +
            "reevaluateAfterMs=$reevaluateAfterMs)"
}

private fun String.isStableRef(): Boolean = matches(Regex("[A-Za-z0-9_:.=-]{1,160}"))

private fun String.isStableCode(): Boolean = matches(Regex("[a-z0-9][a-z0-9_.-]{0,159}"))
