package com.discordassistant.central.platform.discord.nexa

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NexaBuiltInFewShotCatalogAdapterTest {
    @Test
    fun `admin catalog exposes the exact built in judge and speech examples used by runtime`() {
        val catalog = NexaBuiltInFewShotCatalogAdapter().catalog()

        assertThat(catalog.judgeSetId).isEqualTo(9_000_000_000_001L)
        assertThat(catalog.judgeVersion).isEqualTo(9)
        assertThat(catalog.judgeExamples).hasSize(11)
        assertThat(catalog.speechExamples).hasSize(4)
        assertThat(catalog.judgeExamples.map { it.title }).contains("consecutive knowledge questions become a social test")
        assertThat(catalog.speechExamples.map { it.title }).contains("알고리즘 질문이 구술시험처럼 이어진 장면")
    }
}
