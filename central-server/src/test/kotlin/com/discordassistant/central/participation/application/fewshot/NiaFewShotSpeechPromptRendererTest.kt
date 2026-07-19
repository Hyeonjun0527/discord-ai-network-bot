package com.discordassistant.central.participation.application.fewshot

import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotAction
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotBadAlternative
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotExample
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotRawMessage
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotVersion
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotVersionStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class NiaFewShotSpeechPromptRendererTest {
    @Test
    fun `published speak examples render scene and expected replies`() {
        val prompt =
            NiaFewShotSpeechPromptRenderer.renderForParticipation(
                version(
                    example(
                        expectedReplies = listOf("그건 서연이한테 말해"),
                        badReplies = listOf("뭐가 궁금한데 ㅋㅋ"),
                    ),
                ),
            )

        assertThat(prompt).contains(
            "서연아 내 고민 좀 들어줘",
            "좋은 니아 답변: 그건 서연이한테 말해",
            "피해야 할 니아 답변: 뭐가 궁금한데 ㅋㅋ",
            "알고리즘 질문이 구술시험처럼 이어진 장면",
        )
    }

    @Test
    fun `participation-only managed examples leave baseline trajectory examples active`() {
        val prompt = NiaFewShotSpeechPromptRenderer.renderForParticipation(version(example(expectedReplies = emptyList())))

        assertThat(prompt)
            .doesNotContain("서연아 내 고민 좀 들어줘")
            .contains(
                "다익스트라 알고리즘 말해봐",
                "다음은 이거 물어볼 줄 알았음",
                "세 문제 연속으로 시험 보듯 물어놓고 그 결론이냐",
                "아니 따봉 얘기하다가 갑자기 홀로코스트냐 ㅋㅋ",
                "야야야 갑자기 홀로코스트는 ㅋㅋㅋㅋㅋ",
                "근데 왜 V-1번 도는지 코드랑 같이 진짜 설명해줘",
            )
    }

    @Test
    fun `baseline examples are available without an admin version`() {
        val prompt = NiaFewShotSpeechPromptRenderer.renderForParticipation(null)

        assertThat(prompt).contains(
            "관리자 예시는 해당 서버의 말투와 밈에 우선",
            "플로이드 워셜은 모든 정점 사이 거리를 한 번에 갱신하는 방식이야",
            "들켰네 ㅋㅋ 그래도 이렇게 말 잘 통하는 AI면 꽤 괜찮지 않냐",
            "아니야 나 진짜 사람이야",
        )
    }

    @Test
    fun `managed-only renderer does not inject participation baselines into ask mode`() {
        assertThat(NiaFewShotSpeechPromptRenderer.render(null)).isNull()
        assertThat(NiaFewShotSpeechPromptRenderer.render(version(example(expectedReplies = emptyList())))).isNull()
    }

    private fun example(
        expectedReplies: List<String>,
        badReplies: List<String> = emptyList(),
    ) = NiaFewShotExample(
        title = "다른 사람에게 하는 말",
        rawMessages = listOf(NiaFewShotRawMessage("m1", "member", 0, "서연아 내 고민 좀 들어줘")),
        expectedAction = NiaFewShotAction.SPEAK,
        expectedReplies = expectedReplies,
        badReplies = badReplies,
        reason = "서버 밈에 맞는 응답",
        evidenceRefs = setOf("m1"),
        badAlternative = NiaFewShotBadAlternative(NiaFewShotAction.WAIT, "이 예시에서는 응답이 기준이다"),
    )

    private fun version(example: NiaFewShotExample) =
        NiaFewShotVersion(
            id = 1,
            setId = 1,
            version = 1,
            status = NiaFewShotVersionStatus.ACTIVE,
            examples = listOf(example),
            createdBy = null,
            reviewedBy = null,
            publishedAt = Instant.EPOCH,
            rollbackOfVersion = null,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
}
