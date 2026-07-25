package com.discordassistant.central.participation.application.judge

import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NiaJudgeOutputParserTest {
    private val mapper = jacksonObjectMapper()
    private val parser = NiaJudgeOutputParser(mapper)

    @Test
    fun `parser accepts exactly the five judge actions`() {
        val parsedByAction =
            listOf("IGNORE", "WAIT", "REACT", "SPEAK", "CANCEL").associateWith { action ->
                parser.parse(output(action)).accepted()
            }

        assertThat(parsedByAction["IGNORE"]!!.decision.action).isEqualTo(SocialActionKind.IGNORE)
        assertThat(parsedByAction["WAIT"]!!.decision.action).isEqualTo(SocialActionKind.WAIT)
        assertThat(parsedByAction["WAIT"]!!.decision.delay.millis).isEqualTo(2_000)
        assertThat(parsedByAction["REACT"]!!.decision.action).isEqualTo(SocialActionKind.REACT)
        assertThat(parsedByAction["REACT"]!!.decision.reactionCandidate!!.reactionCode).isEqualTo("soft_ack")
        assertThat(parsedByAction["SPEAK"]!!.decision.action).isEqualTo(SocialActionKind.SPEAK)
        assertThat(parsedByAction["SPEAK"]!!.decision.speechIntent!!.intentSummary).contains("acknowledge")
        assertThat(parsedByAction["SPEAK"]!!.decision.speechIntent!!.interactionReading)
            .isEqualTo("the repeated knowledge questions look like a social test")
        assertThat(parsedByAction["SPEAK"]!!.decision.speechIntent!!.informationDepth)
            .isEqualTo("acknowledge the pattern and give one concise fact")
        assertThat(parsedByAction["SPEAK"]!!.decision.speechIntent!!.continuityRefs)
            .containsExactly("msg_1", "msg_3")
        assertThat(parsedByAction["SPEAK"]!!.decision.speechIntent!!.responseTargetRef).isEqualTo("msg_3")
        assertThat(parsedByAction["SPEAK"]!!.decision.speechIntent!!.responseObligation)
            .isEqualTo(JudgeResponseObligation.REQUIRED)
        assertThat(parsedByAction["SPEAK"]!!.decision.speechIntent!!.groundingNeed)
            .isEqualTo(JudgeGroundingNeed.WEB_VERIFY)
        assertThat(parsedByAction["SPEAK"]!!.decision.speechIntent!!.bubbleCount).isEqualTo(3)
        assertThat(parsedByAction["SPEAK"]!!.decision.speechIntent!!.maxBubbleChars).isEqualTo(900)
        assertThat(parsedByAction["CANCEL"]!!.decision.action).isEqualTo(SocialActionKind.CANCEL_PENDING)
    }

    @Test
    fun `parser rejects unknown action values`() {
        val result = parser.parse(output("EMOTIONAL_SUPPORT"))

        assertThat(result).isInstanceOf(NiaJudgeOutputParseResult.Rejected::class.java)
        assertThat((result as NiaJudgeOutputParseResult.Rejected).message).contains("unknown judge action")
    }

    @Test
    fun `parser rejects SPEAK output that contains final response text`() {
        val result =
            parser.parse(
                output(
                    "SPEAK",
                    extra = mapOf("text" to "괜찮아 내가 위로해줄게"),
                ),
            )

        assertThat(result).isInstanceOf(NiaJudgeOutputParseResult.Rejected::class.java)
        assertThat((result as NiaJudgeOutputParseResult.Rejected).message).contains("final response text")
    }

    @Test
    fun `parser rejects non-IGNORE without evidence refs`() {
        val result = parser.parse(output("SPEAK", evidenceRefs = emptyList()))

        assertThat(result).isInstanceOf(NiaJudgeOutputParseResult.Rejected::class.java)
        assertThat((result as NiaJudgeOutputParseResult.Rejected).message).contains("evidenceRefs")
    }

    @Test
    fun `parser rejects malformed json instead of forcing SPEAK`() {
        val result = parser.parse("{not-json")

        assertThat(result).isInstanceOf(NiaJudgeOutputParseResult.Rejected::class.java)
    }

    @Test
    fun `parser rejects speech bubble counts outside the supported range`() {
        val invalid =
            mapper.readValue(output("SPEAK"), MutableMap::class.java).also { root ->
                @Suppress("UNCHECKED_CAST")
                (root["speechIntent"] as MutableMap<String, Any?>)["bubbleCount"] = 5
            }

        val result = parser.parse(mapper.writeValueAsString(invalid))

        assertThat(result).isInstanceOf(NiaJudgeOutputParseResult.Rejected::class.java)
        assertThat((result as NiaJudgeOutputParseResult.Rejected).message).contains("bubbleCount")
    }

    @Test
    fun `parser rejects speech bubble limits outside the supported range`() {
        val invalid =
            mapper.readValue(output("SPEAK"), MutableMap::class.java).also { root ->
                @Suppress("UNCHECKED_CAST")
                (root["speechIntent"] as MutableMap<String, Any?>)["maxBubbleChars"] = 1_801
            }

        val result = parser.parse(mapper.writeValueAsString(invalid))

        assertThat(result).isInstanceOf(NiaJudgeOutputParseResult.Rejected::class.java)
        assertThat((result as NiaJudgeOutputParseResult.Rejected).message).contains("maxBubbleChars")
    }

    @Test
    fun `older speech intent without max bubble limit uses the safe chat default`() {
        val compatible =
            mapper.readValue(output("SPEAK"), MutableMap::class.java).also { root ->
                @Suppress("UNCHECKED_CAST")
                (root["speechIntent"] as MutableMap<String, Any?>).remove("maxBubbleChars")
            }

        val parsed = parser.parse(mapper.writeValueAsString(compatible)).accepted()

        assertThat(parsed.decision.speechIntent!!.maxBubbleChars).isEqualTo(JudgeSpeechIntent.DEFAULT_MAX_BUBBLE_CHARS)
    }

    @Test
    fun `explicit null matches missing for optional root fields`() {
        val nullable =
            mutableOutput("IGNORE").also { root ->
                listOf(
                    "reasonCode",
                    "evidenceRefs",
                    "reactionCode",
                    "speechIntent",
                    "toneAxes",
                    "riskFlags",
                    "reevaluateAfterMs",
                    "beliefUpdates",
                ).forEach { root[it] = null }
            }

        val parsed = parser.parse(mapper.writeValueAsString(nullable)).accepted()

        assertThat(parsed.evidenceRefs).isEmpty()
        assertThat(parsed.riskFlags).isEmpty()
        assertThat(parsed.reevaluateAfterMs).isZero()
        assertThat(parsed.decision.reasonCode.code).isEqualTo("judge.ignore")
        assertThat(parsed.decision.toneAxes).isEqualTo(JudgeToneAxes.NEUTRAL)
        assertThat(parsed.decision.beliefDelta).isEqualTo(JudgeBeliefDelta.EMPTY)
    }

    @Test
    fun `explicit null matches missing for optional nested fields`() {
        val nullable =
            mutableOutput("SPEAK").also { root ->
                @Suppress("UNCHECKED_CAST")
                val speechIntent = root["speechIntent"] as MutableMap<String, Any?>
                listOf(
                    "actHint",
                    "bubbleCount",
                    "maxBubbleChars",
                    "interactionReading",
                    "informationDepth",
                    "continuityRefs",
                ).forEach { speechIntent[it] = null }
                root["toneAxes"] =
                    mapOf(
                        "warmth" to null,
                        "playfulness" to null,
                        "directness" to null,
                        "emotionalIntensity" to null,
                    )
                root["beliefUpdates"] =
                    mapOf(
                        "commonGround" to null,
                        "intentHypotheses" to null,
                        "commitments" to null,
                    )
            }

        val parsed = parser.parse(mapper.writeValueAsString(nullable)).accepted()
        val speechIntent = parsed.decision.speechIntent!!

        assertThat(speechIntent.actHint).isNull()
        assertThat(speechIntent.bubbleCount).isEqualTo(JudgeSpeechIntent.MIN_BUBBLE_COUNT)
        assertThat(speechIntent.maxBubbleChars).isEqualTo(JudgeSpeechIntent.DEFAULT_MAX_BUBBLE_CHARS)
        assertThat(speechIntent.interactionReading).isEqualTo(speechIntent.intentSummary)
        assertThat(speechIntent.informationDepth).isEqualTo(speechIntent.sceneDirection)
        assertThat(speechIntent.continuityRefs).isEmpty()
        assertThat(parsed.decision.toneAxes).isEqualTo(JudgeToneAxes.NEUTRAL)
        assertThat(parsed.decision.beliefDelta).isEqualTo(JudgeBeliefDelta.EMPTY)
    }

    @Test
    fun `explicit null remains invalid for required fields`() {
        val missingReason =
            mutableOutput("IGNORE").also { root ->
                root["reason"] = null
            }
        val missingResponseTarget =
            mutableOutput("SPEAK").also { root ->
                @Suppress("UNCHECKED_CAST")
                (root["speechIntent"] as MutableMap<String, Any?>)["responseTargetRef"] = null
            }

        assertThat(parser.parse(mapper.writeValueAsString(missingReason)))
            .isInstanceOf(NiaJudgeOutputParseResult.Rejected::class.java)
        assertThat(parser.parse(mapper.writeValueAsString(missingResponseTarget)))
            .isInstanceOf(NiaJudgeOutputParseResult.Rejected::class.java)
    }

    @Test
    fun `parser accepts fenced provider json and normalizes uppercase reason code`() {
        val fenced =
            """
            ```json
            ${output("SPEAK", extra = mapOf("reasonCode" to "DIRECT_CALL_ACK"))}
            ```
            """.trimIndent()

        val parsed = parser.parse(fenced).accepted()

        assertThat(parsed.decision.action).isEqualTo(SocialActionKind.SPEAK)
        assertThat(parsed.decision.reasonCode.code).isEqualTo("direct_call_ack")
    }

    @Test
    fun `parser accepts evidence-backed commitment updates`() {
        val result =
            parser
                .parse(
                    output(
                        "SPEAK",
                        extra =
                            mapOf(
                                "beliefUpdates" to
                                    mapOf(
                                        "commitments" to
                                            listOf(
                                                mapOf(
                                                    "commitmentRef" to "promise_1",
                                                    "topic" to "재미있는 이야기",
                                                    "socialAct" to "TELL_STORY",
                                                    "evidenceRefs" to listOf("msg_1"),
                                                    "confidence" to 0.9,
                                                    "status" to "ACTIVE",
                                                ),
                                            ),
                                    ),
                            ),
                    ),
                ).accepted()

        val commitment =
            result.decision.beliefDelta.commitments
                .single()
        assertThat(commitment.commitmentRef).isEqualTo("promise_1")
        assertThat(commitment.socialAct).isEqualTo("TELL_STORY")
        assertThat(commitment.status).isEqualTo(JudgeCommitmentStatus.ACTIVE)
    }

    @Test
    fun `parser rejects commitment updates without evidence`() {
        val result =
            parser.parse(
                output(
                    "SPEAK",
                    extra =
                        mapOf(
                            "beliefUpdates" to
                                mapOf(
                                    "commitments" to
                                        listOf(
                                            mapOf(
                                                "commitmentRef" to "promise_1",
                                                "topic" to "재미있는 이야기",
                                                "socialAct" to "TELL_STORY",
                                                "evidenceRefs" to emptyList<String>(),
                                                "confidence" to 0.9,
                                                "status" to "ACTIVE",
                                            ),
                                        ),
                                ),
                        ),
                ),
            )

        assertThat(result).isInstanceOf(NiaJudgeOutputParseResult.Rejected::class.java)
        assertThat((result as NiaJudgeOutputParseResult.Rejected).message).contains("근거 ref")
    }

    @Test
    fun `parser rejects commitment updates without confidence`() {
        val result =
            parser.parse(
                output(
                    "SPEAK",
                    extra =
                        mapOf(
                            "beliefUpdates" to
                                mapOf(
                                    "commitments" to
                                        listOf(
                                            mapOf(
                                                "commitmentRef" to "promise_1",
                                                "topic" to "재미있는 이야기",
                                                "socialAct" to "TELL_STORY",
                                                "evidenceRefs" to listOf("msg_1"),
                                                "status" to "ACTIVE",
                                            ),
                                        ),
                                ),
                        ),
                ),
            )

        assertThat(result).isInstanceOf(NiaJudgeOutputParseResult.Rejected::class.java)
    }

    private fun output(
        action: String,
        evidenceRefs: List<String> = if (action == "IGNORE") emptyList() else listOf("msg_1"),
        extra: Map<String, Any?> = emptyMap(),
    ): String {
        val base =
            linkedMapOf<String, Any?>(
                "schema" to "nia.participation-judge-output.v1",
                "action" to action,
                "reason" to "synthetic judge reason",
                "reasonCode" to "judge.synthetic",
                "evidenceRefs" to evidenceRefs,
                "confidence" to 0.82,
                "riskFlags" to emptyList<String>(),
                "reevaluateAfterMs" to if (action == "WAIT") 2_000 else 0,
            )
        if (action == "REACT") base["reactionCode"] = "soft_ack"
        if (action == "SPEAK") {
            base["speechIntent"] =
                mapOf(
                    "intentSummary" to "acknowledge direct request",
                    "sceneDirection" to "one short sentence, no over-comforting",
                    "deliveryMode" to "CHANNEL",
                    "actHint" to "acknowledge",
                    "bubbleCount" to 3,
                    "maxBubbleChars" to 900,
                    "interactionReading" to "the repeated knowledge questions look like a social test",
                    "informationDepth" to "acknowledge the pattern and give one concise fact",
                    "continuityRefs" to listOf("msg_1", "msg_3"),
                    "responseTargetRef" to "msg_3",
                    "responseObligation" to "REQUIRED",
                    "groundingNeed" to "WEB_VERIFY",
                )
        }
        base.putAll(extra)
        return mapper.writeValueAsString(base)
    }

    @Suppress("UNCHECKED_CAST")
    private fun mutableOutput(action: String): MutableMap<String, Any?> =
        mapper.readValue(output(action), MutableMap::class.java) as MutableMap<String, Any?>

    private fun NiaJudgeOutputParseResult.accepted(): NiaJudgeParsedDecision {
        assertThat(this).isInstanceOf(NiaJudgeOutputParseResult.Accepted::class.java)
        return (this as NiaJudgeOutputParseResult.Accepted).parsed
    }
}
