package com.discordassistant.central.shared

/** 최신 정보가 필요한 니아의 콘텐츠 대화를 모델 판단에 맡기지 않고 웹 검색으로 보낸다. */
object NiaMediaWebSearchPolicy {
    private val mediaTopic =
        Regex(
            "(애니메이션|애니(?!멀)|anime|유튜브|유투브|유튭|유튜버|유투버|youtube|youtu\\.be|" +
                "넷플(?:릭스)?|netflix|버튜버|vtuber)",
            RegexOption.IGNORE_CASE,
        )
    private val recommendationRequest =
        Regex(
            "(추천|볼\\s*만한|뭐\\s*볼까|뭐\\s*보지|골라\\s*줘|recommend|what\\s+(?:should|can)\\s+i\\s+watch)",
            RegexOption.IGNORE_CASE,
        )
    private val watchedContentQuestion =
        Regex(
            "(봤어|봤냐|봤니|본\\s*적|줄거리|스포|결말|have\\s+you\\s+seen|plot|spoiler|ending)",
            RegexOption.IGNORE_CASE,
        )

    fun requiresWebSearch(text: String): Boolean =
        text.isNotBlank() &&
            (
                mediaTopic.containsMatchIn(text) ||
                    recommendationRequest.containsMatchIn(text) ||
                    watchedContentQuestion.containsMatchIn(text)
            )
}
