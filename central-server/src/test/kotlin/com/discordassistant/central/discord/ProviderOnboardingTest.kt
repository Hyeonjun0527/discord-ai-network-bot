package com.discordassistant.central.discord

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 프로바이더 온보딩 안내문 — 토큰만이 아니라 단계별 가이드를 준다. */
class ProviderOnboardingTest {
    @Test
    fun `relay URL 있으면 토큰·주소 + 정제된 설치 페이지 안내`() {
        val m = ProviderOnboarding.message("TOK123", "wss://central.dailyting.cloud/agent")
        assertTrue(m.contains("프로바이더로 승인되었습니다")) // DM 트리거 마커
        assertTrue(m.contains("central.dailyting.cloud/install")) // 원시 링크 나열 대신 정제된 가이드 페이지로 안내
        assertTrue(m.contains("TOK123")) // 토큰
        assertTrue(m.contains("wss://central.dailyting.cloud/agent")) // 실제 relay
        assertTrue(m.contains("민감정보")) // 안전 고지
        assertTrue(m.length <= 2000) // Discord 한도
    }

    @Test
    fun `relay URL 비면 안내 플레이스홀더`() {
        val m = ProviderOnboarding.message("TOK", "")
        assertTrue(m.contains("관리자에게 문의"))
        assertTrue(m.contains("relay-url 을 받으세요") || m.contains("relay-url"))
    }
}
