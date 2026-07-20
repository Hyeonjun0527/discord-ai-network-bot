package com.discordassistant.central.speech.adapter.outbound.knowledge

import com.discordassistant.central.knowledge.application.WebSearchAugmenter
import com.discordassistant.central.speech.application.port.out.SpeechFactualGrounding
import com.discordassistant.central.speech.application.port.out.SpeechFactualGroundingPort
import org.springframework.stereotype.Component

/** 중앙 SearXNG 검색을 speech의 공급자 중립 근거 계약으로 변환한다. */
@Component
class WebSearchSpeechFactualGroundingAdapter(
    private val webSearch: WebSearchAugmenter,
) : SpeechFactualGroundingPort {
    override fun verify(query: String): SpeechFactualGrounding {
        if (query.isBlank() || !webSearch.isEnabled()) return SpeechFactualGrounding.unavailable()
        val augmentation = webSearch.augment(query)
        if (augmentation.sources.isEmpty()) return SpeechFactualGrounding.unavailable()
        return SpeechFactualGrounding(
            evidence = augmentation.prompt,
            sourceRefs = augmentation.sources.take(SpeechFactualGrounding.MAX_SOURCES),
        )
    }
}
