package com.discordassistant.central.global.observability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * NEXA-P18-T002 acceptance: event→burst→scene→decision→action→GLM→Discord 가 단일 correlationId 로 엮이고,
 * 각 단계가 없어도 chain 이 깨지지 않으며, 컨텍스트에 원문이 없다.
 */
class NexaCorrelationContextTest {
    @Test
    fun `single correlation id threads the full chain in order`() {
        val ctx =
            NexaCorrelationContext
                .start("corr-1")
                .withEvent("e")
                .withBurst("b")
                .withScene("s")
                .withDecision("d")
                .withAction("a")
                .withGlm("g")
                .withDiscord("m")

        assertEquals("corr-1", ctx.correlationId)
        assertEquals(
            listOf(
                NexaStage.EVENT,
                NexaStage.BURST,
                NexaStage.SCENE,
                NexaStage.DECISION,
                NexaStage.ACTION,
                NexaStage.GLM,
                NexaStage.DISCORD,
            ),
            ctx.stages(),
        )
    }

    @Test
    fun `chain does not break when stages are missing (IGNORE ends at decision)`() {
        // IGNORE 흐름: action/glm/discord 없음 — chain 이 끊기지 않고 도달한 단계만 같은 correlationId 로 이어진다.
        val ctx =
            NexaCorrelationContext
                .start("corr-2")
                .withEvent("e")
                .withBurst("b")
                .withDecision("d") // scene 도 건너뛰었다.

        assertEquals(listOf(NexaStage.EVENT, NexaStage.BURST, NexaStage.DECISION), ctx.stages())
        assertEquals("corr-2", ctx.correlationId) // 빠진 단계가 있어도 같은 키로 이어진다.
    }

    @Test
    fun `partial mid-stage gaps still continue under same id`() {
        // scene 없이 burst→decision→action 로 건너뛰어도 chain 유지(다음 단계가 같은 correlationId).
        val ctx =
            NexaCorrelationContext
                .start("corr-3")
                .withBurst("b")
                .withDecision("d")
                .withAction("a")
        assertEquals(listOf(NexaStage.BURST, NexaStage.DECISION, NexaStage.ACTION), ctx.stages())
    }

    @Test
    fun `empty context has no stages but keeps id`() {
        val ctx = NexaCorrelationContext.start("corr-4")
        assertTrue(ctx.stages().isEmpty())
        assertEquals("corr-4", ctx.correlationId)
    }

    @Test
    fun `rejects blank correlation id`() {
        assertThrows<IllegalArgumentException> { NexaCorrelationContext.start("  ") }
    }

    @Test
    fun `with-methods are immutable and return new instances`() {
        val base = NexaCorrelationContext.start("corr-5")
        val next = base.withEvent("e")
        assertFalse(base === next)
        assertTrue(base.stages().isEmpty()) // 원본 불변.
        assertEquals(listOf(NexaStage.EVENT), next.stages())
    }
}
