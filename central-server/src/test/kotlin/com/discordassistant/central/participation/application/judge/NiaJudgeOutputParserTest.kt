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
                    "actHint" to "acknowledge",
                )
        }
        base.putAll(extra)
        return mapper.writeValueAsString(base)
    }

    private fun NiaJudgeOutputParseResult.accepted(): NiaJudgeParsedDecision {
        assertThat(this).isInstanceOf(NiaJudgeOutputParseResult.Accepted::class.java)
        return (this as NiaJudgeOutputParseResult.Accepted).parsed
    }
}
