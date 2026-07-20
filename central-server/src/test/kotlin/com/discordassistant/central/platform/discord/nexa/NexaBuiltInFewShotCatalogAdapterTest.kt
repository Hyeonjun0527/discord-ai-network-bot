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
        assertThat(catalog.judgeExamples.map { it.title })
            .contains("연속된 지식 질문이 니아를 시험하는 흐름으로 바뀜")
            .allMatch { title -> title.any { it in '가'..'힣' } }
        assertThat(catalog.judgeExamples.map { it.reason })
            .allMatch { reason -> reason.any { it in '가'..'힣' } }
        assertThat(catalog.judgeExamples.map { it.badAlternative.whyBad })
            .allMatch { whyBad -> whyBad.any { it in '가'..'힣' } }
        assertThat(catalog.speechExamples.map { it.title }).contains("알고리즘 질문이 구술시험처럼 이어진 장면")
    }
}
