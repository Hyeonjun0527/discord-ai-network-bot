package com.discordassistant.central.actionruntime.application.execution

/**
 * 판단 모델의 안정 reaction 코드를 Discord가 받는 이모지로 제한 변환한다.
 * 임의 문자열을 JDA에 전달하지 않도록 이 목록 밖 코드는 거부한다.
 */
class ReactionCodeResolver(
    private val allowed: Map<String, String> = DEFAULT_CODES,
) {
    fun resolve(code: String?): String? = code?.trim()?.lowercase()?.let(allowed::get)

    companion object {
        private val DEFAULT_CODES =
            mapOf(
                "ack" to "👍",
                "thumbs_up" to "👍",
                "smile" to "🙂",
                "laugh" to "😂",
                "eyes" to "👀",
                "thinking" to "🤔",
                "unamused" to "😑",
                "heart" to "❤️",
            )
    }
}
