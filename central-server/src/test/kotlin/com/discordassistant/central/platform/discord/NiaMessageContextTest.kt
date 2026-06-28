package com.discordassistant.central.platform.discord

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NiaMessageContextTest {
    @Test
    fun `니아야 이름 호명은 직접 질문 프롬프트로 잡는다`() {
        assertThat(niaDirectAddressPrompt("니아야")).isEqualTo("니아야")
        assertThat(niaDirectAddressPrompt("니아야 왜 답 안 해")).isEqualTo("왜 답 안 해")
        assertThat(niaDirectAddressPrompt("무슨소란 니아야")).isEqualTo("무슨소란")
        assertThat(niaDirectAddressPrompt("니아? 지금 있어?")).isEqualTo("니아? 지금 있어?")
        assertThat(niaDirectAddressPrompt("니아야!!!")).isEqualTo("니아야!!!")
    }

    @Test
    fun `내용 없는 니아 호명은 빠른 AI 응답 대상으로 구분한다`() {
        assertThat(isBareNiaDirectAddress("니아야")).isTrue()
        assertThat(isBareNiaDirectAddress("니아야?")).isTrue()
        assertThat(isBareNiaDirectAddress("니아야!!!")).isTrue()
        assertThat(isBareNiaDirectAddress("니아야 왜 답 안 해")).isFalse()
        assertThat(isBareNiaDirectAddress("니아? 지금 있어?")).isFalse()
    }

    @Test
    fun `짧은 니아 호명은 없는 소란을 지어내지 말라는 힌트를 추가한다`() {
        val prompt = buildBareNiaDirectAddressPrompt("니아야?", recentBareCallCount = 1)

        assertThat(prompt).startsWith("니아야?")
        assertThat(prompt).contains("없는 사건이나 소란을 지어내지 말고")
        assertThat(prompt).doesNotContain("최근 10번 연속")
    }

    @Test
    fun `반복 니아 호명은 모델 입력에 버스트 상황을 힌트로 추가한다`() {
        val prompt = buildBareNiaDirectAddressPrompt("니아야?", recentBareCallCount = 10)

        assertThat(prompt).startsWith("니아야?")
        assertThat(prompt).contains("최근 10번 연속")
        assertThat(prompt).contains("지금 한 번만 사람처럼 반응")
        assertThat(prompt).contains("없는 사건이나 소란을 지어내지 말고")
        assertThat(prompt).doesNotContain("왜 이렇게 불러댐")
    }

    @Test
    fun `니아 답변 직후 후속 발화 후보는 의미 판단 힌트로 감싼다`() {
        val prompt = buildNiaContinuationPrompt("무슨소란\n니아야")

        assertThat(prompt).contains("[현재 사용자의 원문 메시지]\n무슨소란\n니아야")
        assertThat(prompt).contains("최근 채널 대화 원문을 기준")
        assertThat(prompt).contains("단순 조건문처럼 판단하지 말고")
    }

    @Test
    fun `니아 답변 직후 발화는 의미 판단용 후속 후보로 감싼다`() {
        val prompt =
            buildNiaContinuationPromptFromRecentMessages(
                messages =
                    listOf(
                        msg(id = 1, authorId = 10, authorLabel = "HJ", content = "니아야"),
                        msg(id = 2, authorId = 99, authorLabel = "니아", bot = true, content = "어디서 소란 피우는 거야."),
                        msg(id = 3, authorId = 10, authorLabel = "HJ", content = "무슨소란"),
                    ),
                currentMessageId = 3,
                botUserId = 99,
                nowEpochMillis = 3,
            )

        assertThat(prompt).isNotNull
        assertThat(prompt!!).contains("[현재 사용자의 원문 메시지]\n무슨소란")
        assertThat(prompt).contains("최근 채널 대화 원문을 기준")
    }

    @Test
    fun `직접 호명 프롬프트는 현재 원문과 호명 제거 내용을 함께 전달한다`() {
        val prompt = buildNiaAddressedPrompt("무슨소란   니아야\n진짜?", "무슨소란   \n진짜?")

        assertThat(prompt).contains("[현재 사용자의 원문 메시지]\n무슨소란   니아야\n진짜?")
        assertThat(prompt).contains("[니아 호명 제거 후 핵심 내용]\n무슨소란   \n진짜?")
    }

    @Test
    fun `최근 bare 호명 답변 직후에는 추가 응답을 억제한다`() {
        assertThat(shouldSuppressBareNiaDirectAddress(nowEpochMillis = 10_000, lastResponseEpochMillis = 2_500)).isTrue()
        assertThat(shouldSuppressBareNiaDirectAddress(nowEpochMillis = 10_000, lastResponseEpochMillis = 1_999)).isFalse()
        assertThat(shouldSuppressBareNiaDirectAddress(nowEpochMillis = 10_000, lastResponseEpochMillis = null)).isFalse()
    }

    @Test
    fun `문장 속 니아 언급은 직접 호명으로 오인하지 않는다`() {
        assertThat(niaDirectAddressPrompt("너는 니아야")).isNull()
        assertThat(niaDirectAddressPrompt("니아는 어떤 캐릭터야?")).isNull()
        assertThat(niaDirectAddressPrompt("\"니아야\" 라고 불렀어")).isNull()
    }

    @Test
    fun `최근 채널 맥락은 원문을 수정하지 않고 멀티턴으로 전달한다`() {
        val turns =
            buildDiscordRecentContextTurns(
                messages =
                    listOf(
                        msg(id = 1, authorId = 10, authorLabel = "HJ", content = "니아야\n니아야   니아야"),
                        msg(id = 2, authorId = 99, authorLabel = "니아", bot = true, content = "응?\n왜 불러."),
                        msg(id = 3, authorId = 11, authorLabel = "yeon", content = "니아야 싸가지가 없네"),
                        msg(id = 4, authorId = 10, authorLabel = "HJ", content = "CURRENT_TRIGGER_니아야"),
                    ),
                currentMessageId = 4,
                botUserId = 99,
            )

        assertThat(turns.map { it.role }).containsExactly("user", "assistant", "user", "user")
        assertThat(turns[0].content).contains("speaker=HJ")
        assertThat(turns[0].content).contains("content:\n니아야\n니아야   니아야")
        assertThat(turns[1].content).isEqualTo("응?\n왜 불러.")
        assertThat(turns[2].content).contains("speaker=yeon")
        assertThat(turns[2].content).contains("content:\n니아야 싸가지가 없네")
        assertThat(turns.joinToString("\n") { it.content }).doesNotContain("CURRENT_TRIGGER_니아야")
        assertThat(turns.last().content).contains("최근 채널 대화 원문을 그대로 참고")
    }

    private fun msg(
        id: Long,
        authorId: Long,
        authorLabel: String,
        content: String,
        bot: Boolean = false,
    ) = DiscordRecentPromptMessage(
        id = id,
        authorId = authorId,
        authorLabel = authorLabel,
        bot = bot,
        content = content,
        createdAtEpochMillis = id,
    )
}
