package com.discordassistant.central.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NiaPromptSourceTest {
    @Test
    fun `니아 ask 입력은 공통 정체성과 ask 원칙을 한 번씩 조립한다`() {
        val prompt =
            CodeNiaPromptSource.renderNiaAskPrompt(
                userMessage = "안녕",
                relation = "[이 사용자와의 관계] 현재 단계: 낯섦 — 정중하고 친절하게",
            )

        assertThat(prompt)
            .contains(
                "[우선순위 1: 안전]",
                NexaIdentity.NIA_CHARACTER_PROFILE,
                "[/ask 대화 원칙]",
                "[상대 발화]\n안녕",
            ).containsOnlyOnce("ASCII 마침표(.)")
            .containsOnlyOnce("비서식 자기소개")
            .doesNotContain("[자발 대화 원칙]", "[발화 생성 지침]")
        assertThat(prompt.length).isLessThan(1_250)
        println("NIA_ASK_COST_FIXTURE fullChars=${prompt.length}")
    }

    @Test
    fun `니아 ask 조립기는 관리형 문서와 동적 문맥을 같은 경로로 렌더링한다`() {
        val source =
            NiaPromptSource {
                NiaPromptDefaults.documents +
                    mapOf(
                        NiaPromptKey.IDENTITY_PERSONA to "관리형 정체성",
                        NiaPromptKey.VOICE_PRINCIPLES to "관리형 말투",
                    )
            }

        val prompt =
            source.renderNiaAskPrompt(
                userMessage = "질문",
                relation = "관계 문맥",
                managedFewShot = "관리형 예시",
            )

        assertThat(prompt).contains("관리형 정체성", "관리형 말투", "관계 문맥", "관리형 예시", "[상대 발화]\n질문")
        assertThat(prompt).containsOnlyOnce(NexaIdentity.NIA_CHARACTER_PROFILE)
    }
}
