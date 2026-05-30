package com.discordassistant.central.discord

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 프로바이더 온보딩 안내문 — 토큰만이 아니라 단계별 가이드를 준다. */
class ProviderOnboardingTest {
    @Test
    fun `relay URL 있으면 실행 명령에 토큰·주소 포함`() {
        val m = ProviderOnboarding.message("TOK123", "wss://central.dailyting.cloud/agent")
        assertTrue(m.contains("프로바이더로 승인되었습니다")) // DM 트리거 마커
        assertTrue(m.contains("ollama")) // 1) Ollama 단계
        assertTrue(m.contains("discord-ai-provider-agent")) // 2) 실행
        assertTrue(m.contains("TOK123")) // 토큰
        assertTrue(m.contains("wss://central.dailyting.cloud/agent")) // 실제 relay
        // OS별 다운로드 링크(버전관리되는 단일 실행파일)
        assertTrue(m.contains("releases/latest/download/discord-ai-provider-agent-windows.exe"))
        assertTrue(m.contains("discord-ai-provider-agent-macos"))
        assertTrue(m.contains("discord-ai-provider-agent-linux"))
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
