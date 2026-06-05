package com.discordassistant.central.discord

import com.discordassistant.central.platform.discord.ProviderOnboarding
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 프로바이더 온보딩 안내문 — 토큰만이 아니라 단계별 가이드를 준다. */
class ProviderOnboardingTest {
    @Test
    fun `GUI 앱 설치(brew·winget) + 토큰 붙여넣기 안내`() {
        val m = ProviderOnboarding.message("TOK123", "wss://discord-ai.yeon.world/agent")
        assertTrue(m.contains("프로바이더로 승인되었습니다")) // DM 트리거 마커
        assertTrue(m.contains("discord-ai.yeon.world/install")) // 정제된 가이드 페이지
        assertTrue(m.contains("TOK123")) // 토큰(앱에 붙여넣기)
        // GUI 앱 설치는 패키지 매니저로(맥 데스크톱 앱과 동일). relay-url 은 앱에 내장돼 노출하지 않는다.
        assertTrue(m.contains("brew install --cask")) // macOS 앱
        assertTrue(m.contains("Nexa.Nexa")) // winget GUI 앱
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
