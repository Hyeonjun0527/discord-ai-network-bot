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

    private fun example(
        expectedAction: NiaFewShotAction = NiaFewShotAction.SPEAK,
        evidenceRefs: Set<String> = setOf("m1"),
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
            reason = "The user is explicitly asking Nia for a response, so the judge should speak.",
            evidenceRefs = evidenceRefs,
            badAlternative = badAlternative,
            tags = setOf("direct-ask"),
            priority = 10,
        )
}
