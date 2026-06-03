package com.discordassistant.central.routing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class WebResultRerankerTest {
    @Test
    fun `질의 적합한 결과를 앞으로 올린다`() {
        val results =
            listOf(
                WebResult("무관한 페이지", "https://a", "고양이 사진 모음과 강아지 영상."),
                WebResult("코틀린 코루틴 가이드", "https://b", "코틀린 코루틴 사용법과 suspend 함수 설명."),
                WebResult("일반 블로그", "https://c", "오늘 점심 메뉴 추천."),
            )
        val ranked = WebResultReranker.rerank("코틀린 코루틴", results)
        assertEquals("https://b", ranked.first().url) // 가장 적합한 결과가 1위
        assertEquals(3, ranked.size) // 누락 없음
    }

    @Test
    fun `제목 일치를 본문 일치보다 강하게 가중`() {
        val results =
            listOf(
                WebResult("잡담", "https://body", "redis 캐시 관련 설명이 본문에 한 번 등장한다."),
                WebResult("Redis 캐시 입문", "https://title", "데이터 저장소 개요."),
            )
        val ranked = WebResultReranker.rerank("redis 캐시", results)
        assertEquals("https://title", ranked.first().url) // 제목 일치가 우선
    }

    @Test
    fun `질의어가 없거나 결과 1개 이하면 원본 그대로`() {
        val one = listOf(WebResult("t", "https://x", "s"))
        assertSame(one, WebResultReranker.rerank("아무거나", one))
        val two = listOf(WebResult("a", "https://a", "x"), WebResult("b", "https://b", "y"))
        assertSame(two, WebResultReranker.rerank("   ", two)) // 토큰화 후 비면 그대로
    }

    @Test
    fun `동점이면 원래 순서 유지(stable)`() {
        val results =
            listOf(
                WebResult("first", "https://1", "no match here"),
                WebResult("second", "https://2", "also nothing"),
            )
        val ranked = WebResultReranker.rerank("zzzz", results) // 어느 문서에도 없는 질의어
        assertEquals(listOf("https://1", "https://2"), ranked.map { it.url })
    }
}
