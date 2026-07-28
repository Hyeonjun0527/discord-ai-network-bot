package com.discordassistant.central.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NiaMediaWebSearchPolicyTest {
    @Test
    fun `애니 유튜브와 추천 요청은 웹 검색을 강제한다`() {
        listOf(
            "요즘 볼 만한 애니 추천해줘",
            "침착맨 같은 유튜버 누구 있어?",
            "https://youtu.be/example 이 영상 어때?",
            "넷플릭스에서 뭐 볼까?",
            "귀멸의 칼날 봤어?",
            "다른 것도 추천해줘",
            "What should I watch on Netflix?",
        ).forEach { assertThat(NiaMediaWebSearchPolicy.requiresWebSearch(it)).describedAs(it).isTrue() }
    }

    @Test
    fun `일상 대화와 디스코드 채널 언급은 불필요한 검색을 만들지 않는다`() {
        listOf(
            "오늘 늦게 잘 것 같아",
            "디스코드 채널 정책 알려줘",
            "나 어떻게 생각해?",
            "애니멀 사진 귀엽다",
            "안녕",
        ).forEach { assertThat(NiaMediaWebSearchPolicy.requiresWebSearch(it)).describedAs(it).isFalse() }
    }
}
