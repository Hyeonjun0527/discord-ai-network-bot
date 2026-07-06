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
    fun `NFD 자모 분리로 들어온 호명도 인식한다`() {
        // macOS 등 일부 클라이언트는 "니아야"를 자모 분해(NFD)해 보내 "니아" 매칭이 실패했다(호명 무응답 원인).
        val decomposed = java.text.Normalizer.normalize("니아야", java.text.Normalizer.Form.NFD)
        assertThat(decomposed).isNotEqualTo("니아야") // 실제로 분해됐는지 선확인
        assertThat(niaDirectAddressPrompt(decomposed)).isEqualTo("니아야")
        val decomposedWithBody = java.text.Normalizer.normalize("니아야 왜 답 안 해", java.text.Normalizer.Form.NFD)
        assertThat(niaDirectAddressPrompt(decomposedWithBody)).isEqualTo("왜 답 안 해")
        assertThat(isBareNiaDirectAddress(decomposed)).isTrue()
    }

    @Test
    fun `내용 없는 니아 호명은 마지막 트리거가 비어 있음을 구분한다`() {
        assertThat(isBareNiaDirectAddress("니아야")).isTrue()
        assertThat(isBareNiaDirectAddress("니아야?")).isTrue()
        assertThat(isBareNiaDirectAddress("니아야!!!")).isTrue()
        assertThat(isBareNiaDirectAddress("니아야 왜 답 안 해")).isFalse()
        assertThat(isBareNiaDirectAddress("니아? 지금 있어?")).isFalse()
    }

    @Test
    fun `짧은 니아 호명도 되묻기 전용 땜빵이 아니라 전체 원문 장면 계약을 탄다`() {
        val prompt = buildNiaAddressedPrompt("니아야?", "니아야?")

        assertThat(prompt).contains("[현재 트리거 원문]\n니아야?")
        assertThat(prompt).contains("최근 채널 대화 원문 전체를 1차 소스")
        assertThat(prompt).contains("앞 요구에 답한다")
        assertThat(prompt).contains("그 말의 뜻을 설명하거나")
        assertThat(prompt).contains("문장 끝에 ASCII 마침표(.)를 붙이지 않는다")
        assertThat(prompt).doesNotContain("왜 불렀는지 되물으세요")
        assertThat(prompt).doesNotContain("최근 10번 연속")
    }

    @Test
    fun `니아 답변 직후 후속 발화 후보는 자기 발화 수습 계약으로 감싼다`() {
        val prompt = buildNiaContinuationPrompt("??? 어휘력 없음이 뭔말이야")

        assertThat(prompt).contains("[현재 트리거 원문]\n??? 어휘력 없음이 뭔말이야")
        assertThat(prompt).contains("사용자가 니아의 직전 말을 되묻거나 따지면")
        assertThat(prompt).contains("같은 말을 반복하지 말고")
        assertThat(prompt).contains("오타·짧은 말·거친 말")
    }

    @Test
    fun `니아 답변 직후 발화는 의미 판단용 후속 후보로 감싼다`() {
        val prompt =
            buildNiaContinuationPromptFromRecentMessages(
                messages =
                    listOf(
                        msg(id = 1, authorId = 10, authorLabel = "HJ", content = "너머함"),
                        msg(id = 2, authorId = 99, authorLabel = "니아", bot = true, content = "어휘력 없음"),
                        msg(id = 3, authorId = 10, authorLabel = "HJ", content = "??? 어휘력 없음이 뭔말이야"),
                    ),
                currentMessageId = 3,
                botUserId = 99,
                nowEpochMillis = 3,
            )

        assertThat(prompt).isNotNull
        assertThat(prompt!!).contains("[현재 트리거 원문]\n??? 어휘력 없음이 뭔말이야")
        assertThat(prompt).contains("표현 의도를 설명")
    }

    @Test
    fun `직접 호명 프롬프트는 현재 원문과 호명 제거 내용을 함께 전달한다`() {
        val prompt = buildNiaAddressedPrompt("무슨소란   니아야\n진짜?", "무슨소란   \n진짜?")

        assertThat(prompt).contains("[현재 트리거 원문]\n무슨소란   니아야\n진짜?")
        assertThat(prompt).contains("[현재 트리거에서 분리한 직접 요청]\n무슨소란   \n진짜?")
    }

    @Test
    fun `자동응답 프롬프트도 마지막 메시지가 아니라 원문 장면 계약을 탄다`() {
        val prompt = buildNiaAutoRespondPrompt("돈들어")

        assertThat(prompt).contains("[트리거 출처]\nauto-respond-channel")
        assertThat(prompt).contains("마지막 트리거 하나만 보지 말고")
    }

    @Test
    fun `니아 채팅 스타일은 문장 끝 ASCII 마침표를 제거하되 숫자 구조는 보존한다`() {
        val reply =
            Reply(
                content = "그거 내가 먼저 한 말인데.\n버전 v1.2는 그대로.",
                ephemeral = false,
                pseudoStream = ReplyPseudoStream(editIntervalMs = 1, snapshots = listOf("뭐 갑자기.", "아니... 왜")),
            ).withNiaChatStyle()

        assertThat(reply.content).isEqualTo("그거 내가 먼저 한 말인데\n버전 v1.2는 그대로")
        assertThat(reply.pseudoStream!!.snapshots).containsExactly("뭐 갑자기", "아니 왜")
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
                        msg(id = 2, authorId = 99, authorLabel = "니아", bot = true, content = "응?\n왜 불러"),
                        msg(id = 3, authorId = 11, authorLabel = "yeon", content = "니아야 싸가지가 없네"),
                        msg(id = 4, authorId = 10, authorLabel = "HJ", content = "CURRENT_TRIGGER_니아야"),
                    ),
                currentMessageId = 4,
                botUserId = 99,
            )

        assertThat(turns.map { it.role }).containsExactly("user", "assistant", "user", "user")
        assertThat(turns[0].content).contains("speaker=HJ")
        assertThat(turns[0].content).contains("content:\n니아야\n니아야   니아야")
        assertThat(turns[1].content).isEqualTo("응?\n왜 불러")
        assertThat(turns[2].content).contains("speaker=yeon")
        assertThat(turns[2].content).contains("content:\n니아야 싸가지가 없네")
        assertThat(turns.joinToString("\n") { it.content }).doesNotContain("CURRENT_TRIGGER_니아야")
        assertThat(turns.last().content).contains("최근 채널 대화 원문을 그대로 참고")
    }

    @Test
    fun `최근 채널 맥락은 오래된 원문부터 char budget 밖이면 버린다`() {
        val turns =
            buildDiscordRecentContextTurns(
                messages =
                    listOf(
                        msg(id = 1, authorId = 10, authorLabel = "HJ", content = "오래된말".repeat(80)),
                        msg(id = 2, authorId = 11, authorLabel = "yeon", content = "바로 앞 말"),
                        msg(id = 3, authorId = 10, authorLabel = "HJ", content = "CURRENT"),
                    ),
                currentMessageId = 3,
                botUserId = 99,
                maxTurns = 10,
                maxRawChars = 140,
            )

        val joined = turns.joinToString("\n") { it.content }
        assertThat(joined).contains("바로 앞 말")
        assertThat(joined).doesNotContain("오래된말")
        assertThat(joined).doesNotContain("CURRENT")
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
