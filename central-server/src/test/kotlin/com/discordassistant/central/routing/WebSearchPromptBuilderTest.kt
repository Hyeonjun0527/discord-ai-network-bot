package com.discordassistant.central.routing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebSearchPromptBuilderTest {
    private val results =
        listOf(
            WebResult("코틀린 공식", "https://kotlinlang.org", "Kotlin is a modern language."),
            WebResult("위키", "https://en.wikipedia.org/wiki/Kotlin", "Kotlin programming language."),
        )

    @Test
    fun `결과가 있으면 인용 지시·출처·질문을 포함한 프롬프트로 증강`() {
        val aug = WebSearchPromptBuilder.build("코틀린이 뭐야?", results)
        assertTrue(aug.prompt.contains("웹 검색 결과"))
        assertTrue(aug.prompt.contains("[1]"))
        assertTrue(aug.prompt.contains("https://kotlinlang.org"))
        assertTrue(aug.prompt.contains("질문: 코틀린이 뭐야?"))
        assertEquals(listOf("https://kotlinlang.org", "https://en.wikipedia.org/wiki/Kotlin"), aug.sources)
    }

    @Test
    fun `결과가 없으면 원본 프롬프트 그대로`() {
        val aug = WebSearchPromptBuilder.build("안녕", emptyList())
        assertEquals("안녕", aug.prompt)
        assertTrue(aug.sources.isEmpty())
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
}
