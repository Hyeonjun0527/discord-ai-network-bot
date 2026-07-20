package com.discordassistant.central.participation.domain.model.fewshot

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class NiaFewShotModelsTest {
    @Test
    fun `lookup candidates preserve narrow-to-global precedence`() {
        val candidates =
            NiaFewShotLookupScope(
                guildId = 100,
                channelId = 200,
                persona = "nia",
            ).candidates()

        assertThat(candidates.map { it.stableKey })
            .containsExactly(
                "channel:100:200:nia",
                "guild:100:nia",
                "persona:nia",
                "global",
            )
    }

    @Test
    fun `scope rejects impossible guild and channel combinations`() {
        assertThatThrownBy {
            NiaFewShotScope(NiaFewShotScopeType.GLOBAL, guildId = 1)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            NiaFewShotLookupScope(guildId = null, channelId = 10)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `example evidence must point at raw source messages`() {
        assertThatThrownBy {
            example(evidenceRefs = setOf("missing"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("rawMessages ref")
    }

    @Test
    fun `bad alternative must be a real rejected alternative`() {
        assertThatThrownBy {
            example(
                expectedAction = NiaFewShotAction.SPEAK,
                badAlternative = NiaFewShotBadAlternative(NiaFewShotAction.SPEAK, "same action is not an alternative"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("expectedAction")
    }

    @Test
    fun `expected replies are accepted only for speak examples`() {
        assertThat(example(expectedReplies = listOf("응 무슨 일인데")).expectedReplies).containsExactly("응 무슨 일인데")
        assertThatThrownBy {
            example(expectedAction = NiaFewShotAction.IGNORE, expectedReplies = listOf("끼어들기"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("SPEAK")
    }

    @Test
    fun `bad replies preserve contrastive output examples`() {
        val result = example(badReplies = listOf("알았으니까 얘기해봐 ㅋㅋ"))

        assertThat(result.badReplies).containsExactly("알았으니까 얘기해봐 ㅋㅋ")
        assertThatThrownBy {
            example(expectedAction = NiaFewShotAction.IGNORE, badReplies = listOf("불필요한 끼어들기"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("SPEAK")
    }

    @Test
    fun `action payloads are accepted only by their matching action`() {
        assertThat(
            example(
                expectedAction = NiaFewShotAction.REACT,
                expectedReactionCode = "eyes",
                badAlternative = NiaFewShotBadAlternative(NiaFewShotAction.SPEAK, "a message would interrupt"),
            ).expectedReactionCode,
        ).isEqualTo("eyes")
        assertThat(
            example(
                expectedAction = NiaFewShotAction.WAIT,
                expectedReevaluateAfterMs = 1_500,
                badAlternative = NiaFewShotBadAlternative(NiaFewShotAction.SPEAK, "the member is still typing"),
            ).expectedReevaluateAfterMs,
        ).isEqualTo(1_500)
        assertThatThrownBy { example(expectedReactionCode = "eyes") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("REACT")
        assertThatThrownBy {
            example(
                expectedAction = NiaFewShotAction.REACT,
                expectedReactionCode = "party_parrot",
                badAlternative = NiaFewShotBadAlternative(NiaFewShotAction.SPEAK, "a message would interrupt"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("지원하지 않는")
    }

    private fun example(
        expectedAction: NiaFewShotAction = NiaFewShotAction.SPEAK,
        evidenceRefs: Set<String> = setOf("m1"),
        expectedReplies: List<String> = emptyList(),
        badReplies: List<String> = emptyList(),
        expectedReactionCode: String? = null,
        expectedReevaluateAfterMs: Long? = null,
        badAlternative: NiaFewShotBadAlternative = NiaFewShotBadAlternative(NiaFewShotAction.WAIT, "waiting ignores a direct ask"),
    ): NiaFewShotExample =
        NiaFewShotExample(
            title = "direct consolation request",
            rawMessages =
                listOf(
                    NiaFewShotRawMessage(
                        ref = "m1",
                        authorRole = "member",
                        offsetMs = 0,
                        text = "야 이럴땐 위로해줘야지",
                    ),
                ),
            expectedAction = expectedAction,
            expectedReplies = expectedReplies,
            badReplies = badReplies,
            expectedReactionCode = expectedReactionCode,
            expectedReevaluateAfterMs = expectedReevaluateAfterMs,
            reason = "The user is explicitly asking Nia for a response, so the judge should speak.",
            evidenceRefs = evidenceRefs,
            badAlternative = badAlternative,
            tags = setOf("direct-ask"),
            priority = 10,
        )
}
