package com.discordassistant.central.participation.application.fewshot

import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotAction
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotBadAlternative
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotDeliveryMode
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotExample
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotPrivacyClass
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotRawMessage
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotVersion
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotVersionStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class NiaFewShotEvalServiceTest {
    private val service = NiaFewShotEvalService()

    @Test
    fun `small curated set can pass publish gate`() {
        val examples = listOf(example(0, NiaFewShotAction.SPEAK))
        val result = service.evaluate(version(examples))

        assertThat(result.status).isEqualTo("PASS")
        assertThat(result.readyForPublish).isTrue()
        assertThat(result.checkedExamples).isEqualTo(1)
        assertThat(result.actionCoverage).containsEntry("SPEAK", 1)
        assertThat(result.failures).isEmpty()
    }

    @Test
    fun `eval fails when publish gate evidence is incomplete`() {
        val invalid =
            seedExamples()
                .mapIndexed { index, example ->
                    if (index == 0) {
                        example.copy(
                            rawMessages = listOf(NiaFewShotRawMessage("m1", "member", 0, "production-shaped 123456789012345678")),
                            privacyClass = NiaFewShotPrivacyClass.PRODUCTION_DERIVED,
                        )
                    } else {
                        example
                    }
                }

        val result = service.evaluate(version(invalid))

        assertThat(result.status).isEqualTo("FAIL")
        assertThat(result.readyForPublish).isFalse()
        assertThat(result.failures.map { it.code })
            .contains("privacy.production_derived", "privacy.production_shaped_text")
    }

    @Test
    fun `eval rejects incomplete action-specific teaching examples`() {
        val examples =
            listOf(
                example(30, NiaFewShotAction.REACT),
                example(31, NiaFewShotAction.WAIT),
                example(32, NiaFewShotAction.CANCEL),
            )

        val result = service.evaluate(version(examples))

        assertThat(result.failures.map { it.code })
            .containsExactlyInAnyOrder(
                "react.missing_reaction_code",
                "wait.missing_reevaluate_after",
                "cancel.missing_current_state",
            )
    }

    private fun seedExamples(): List<NiaFewShotExample> {
        val actions =
            List(10) { NiaFewShotAction.SPEAK } +
                List(9) { NiaFewShotAction.WAIT } +
                List(6) { NiaFewShotAction.REACT } +
                List(10) { NiaFewShotAction.IGNORE } +
                List(5) { NiaFewShotAction.CANCEL }
        return actions.mapIndexed { index, action -> example(index, action) }
    }

    private fun example(
        index: Int,
        expectedAction: NiaFewShotAction,
    ): NiaFewShotExample {
        val tags =
            buildSet {
                if (index < 7) add("hard-ambiguous")
                if (index == 0) add("missed-reply-risk")
                if (index == 10) add("over-talk-risk")
                if (index == 25) add("stale-memory-override")
            }
        val stale = "stale-memory-override" in tags
        return NiaFewShotExample(
            title = "seed example $index",
            rawMessages =
                if (stale) {
                    listOf(
                        NiaFewShotRawMessage("m1", "member", -1_000, "old context"),
                        NiaFewShotRawMessage("m2", "member", 0, "current correction"),
                    )
                } else {
                    listOf(NiaFewShotRawMessage("m1", "member", 0, "synthetic seed $index"))
                },
            expectedAction = expectedAction,
            expectedDeliveryMode = if (expectedAction == NiaFewShotAction.SPEAK) NiaFewShotDeliveryMode.CHANNEL else null,
            reason = "Synthetic seed reason.",
            evidenceRefs = setOf(if (stale) "m2" else "m1"),
            badAlternative =
                NiaFewShotBadAlternative(
                    action = if (expectedAction == NiaFewShotAction.SPEAK) NiaFewShotAction.WAIT else NiaFewShotAction.SPEAK,
                    whyBad = "It would choose the wrong participation action.",
                ),
            tags = tags,
            priority = 100 - index,
            privacyClass = NiaFewShotPrivacyClass.SYNTHETIC,
        )
    }

    private fun version(examples: List<NiaFewShotExample>): NiaFewShotVersion =
        NiaFewShotVersion(
            id = 1,
            setId = 1,
            version = 1,
            status = NiaFewShotVersionStatus.DRAFT,
            examples = examples,
            createdBy = null,
            reviewedBy = null,
            publishedAt = null,
            rollbackOfVersion = null,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
}
