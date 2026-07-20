package com.discordassistant.central.speech.knowledge

import com.discordassistant.central.knowledge.application.WebAugmentation
import com.discordassistant.central.knowledge.application.WebSearchAugmenter
import com.discordassistant.central.speech.adapter.outbound.knowledge.WebSearchSpeechFactualGroundingAdapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WebSearchSpeechFactualGroundingAdapterTest {
    @Test
    fun `활성 웹 검색의 근거와 출처를 speech 계약으로 전달한다`() {
        val adapter =
            WebSearchSpeechFactualGroundingAdapter(
                object : WebSearchAugmenter {
                    override fun isEnabled(): Boolean = true

                    override fun augment(prompt: String): WebAugmentation =
                        WebAugmentation("검색 근거: $prompt", listOf("https://example.test/official"))
                },
            )

        val result = adapter.verify("엽떡 7단계가 있나")

        assertThat(result.verified).isTrue()
        assertThat(result.evidence).contains("엽떡 7단계가 있나")
        assertThat(result.sourceRefs).containsExactly("https://example.test/official")
    }

    @Test
    fun `검색 백엔드가 비활성이면 검증 성공으로 위장하지 않는다`() {
        val adapter =
            WebSearchSpeechFactualGroundingAdapter(
                object : WebSearchAugmenter {
                    override fun isEnabled(): Boolean = false

                    override fun augment(prompt: String): WebAugmentation = error("호출되면 안 됨")
                },
            )

        assertThat(adapter.verify("질문").verified).isFalse()
    }
}
