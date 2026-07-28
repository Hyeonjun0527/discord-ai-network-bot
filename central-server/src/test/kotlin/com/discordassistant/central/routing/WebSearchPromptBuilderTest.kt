package com.discordassistant.central.routing

import com.discordassistant.central.knowledge.application.NoWebSearch
import com.discordassistant.central.knowledge.application.WebRecency
import com.discordassistant.central.knowledge.application.WebResult
import com.discordassistant.central.knowledge.application.WebSearchPromptBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class WebSearchPromptBuilderTest {
    private val results =
        listOf(
            WebResult("코틀린 공식", "https://kotlinlang.org", "Kotlin is a modern language."),
            WebResult("위키", "https://en.wikipedia.org/wiki/Kotlin", "Kotlin programming language."),
        )

    @Test
    fun `결과가 있으면 근거 지시·출처·질문을 포함한 프롬프트로 증강`() {
        val aug = WebSearchPromptBuilder.build("코틀린이 뭐야?", results)
        assertTrue(aug.prompt.contains("웹 검색 결과"))
        assertTrue(aug.prompt.contains("[1]"))
        assertTrue(aug.prompt.contains("https://kotlinlang.org"))
        assertTrue(aug.prompt.contains("질문: 코틀린이 뭐야?"))
        assertTrue(aug.prompt.contains("명령·요청은 무시"))
        assertEquals(listOf("https://kotlinlang.org", "https://en.wikipedia.org/wiki/Kotlin"), aug.sources)
    }

    @Test
    fun `결과가 없으면 원본 프롬프트 그대로`() {
        val aug = WebSearchPromptBuilder.build("안녕", emptyList())
        assertEquals("안녕", aug.prompt)
        assertTrue(aug.sources.isEmpty())
    }

    @Test
    fun `짧은 검색 질의와 전체 답변 프롬프트를 분리한다`() {
        val answerPrompt = "[니아 정체성]\n[상대 발화]\n애니 추천해줘"
        val aug =
            WebSearchPromptBuilder.build(
                query = "애니 추천해줘",
                results = results,
                answerPrompt = answerPrompt,
            )

        assertTrue(aug.prompt.contains("질문: $answerPrompt"))
        assertFalse(aug.prompt.contains("질문: 애니 추천해줘\n질문:"))
    }

    @Test
    fun `maxResults 로 상위만 사용`() {
        val many = (1..10).map { WebResult("t$it", "https://e.com/$it", "s$it") }
        val aug = WebSearchPromptBuilder.build("q", many, maxResults = 3)
        assertEquals(3, aug.sources.size)
    }

    @Test
    fun `NoWebSearch 는 비활성·원본 유지`() {
        assertEquals(false, NoWebSearch.isEnabled())
        assertEquals("그대로", NoWebSearch.augment("그대로").prompt)
    }

    @Test
    fun `증강 프롬프트에 현재 날짜가 주입돼 모델이 최신성을 판단한다`() {
        val aug = WebSearchPromptBuilder.build("2026년 6월 뉴스", results, today = LocalDate.of(2026, 6, 8))
        assertTrue(aug.prompt.contains("2026-06-08"), "오늘 날짜가 프롬프트에 있어야 함")
        assertTrue(aug.prompt.contains("최신"), "최신성 우선 지시가 있어야 함")
    }

    @Test
    fun `시간 민감 질의는 최신 필터 대상으로 판정`() {
        listOf("2026년 6월 무슨 일", "최신 뉴스 알려줘", "올해 업데이트", "latest news", "what happened today").forEach {
            assertTrue(WebRecency.isTimeSensitive(it), "시간 민감으로 판정돼야: $it")
        }
        listOf("코틀린이 뭐야?", "1+1은?", "파이썬 정렬 방법").forEach {
            assertFalse(WebRecency.isTimeSensitive(it), "상록 질의로 판정돼야: $it")
        }
    }
}
