package com.discordassistant.central.discord

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** OS별 복붙 설치 명령(차수 19). 순수 함수라 JDA 없이 검증 — Ollama 설치 + 에이전트 실행 + 토큰/relay 포함. */
class ProviderOnboardingInstallTest {
    @Test
    fun `macOS — GUI 앱(brew cask) 설치 + 토큰 붙여넣기`() {
        val s = ProviderOnboarding.installCommand("mac", "TOK-123", "wss://relay.example/agent")
        assertTrue(s.contains("brew install ollama"), s)
        assertTrue(s.contains("brew install --cask yeon-intergation-platform/tap/nyassistant"), s) // 맥 데스크톱 앱
        assertTrue(s.contains("냥시스턴트"), s) // 앱 이름
        assertTrue(s.contains("디스코드 로그인"), s) // 앱에서 서버 선택/연결
        assertTrue(s.contains("TOK-123"), s) // 토큰(앱에 붙여넣기)
        // 옛 CLI 바이너리 다운로드 방식은 제거됨.
        assertFalse(s.contains("curl -L -o discord-ai-network-bot-macos"), s)
        assertFalse(s.contains("releases/latest/download/discord-ai-network-bot-macos"), s)
        assertTrue(s.contains("⌘ Space"), s)
    }

    @Test
    fun `Windows — GUI 앱(winget) 설치 + 토큰 붙여넣기`() {
        val s = ProviderOnboarding.installCommand("windows", "TOK", "wss://r/agent")
        assertTrue(s.contains("winget install"), s)
        assertTrue(s.contains("Nyassistant.DiscordAiNetworkBot"), s) // GUI 앱(맥과 동일)
        assertTrue(s.contains("냥시스턴트"), s) // 앱 이름
        assertTrue(s.contains("TOK"), s) // 토큰(앱에 붙여넣기)
        // 옛 CLI exe 다운로드 방식은 제거됨.
        assertFalse(s.contains("discord-ai-network-bot-windows.exe"), s)
        assertFalse(s.contains("releases/latest/download"), s)
        assertTrue(s.contains("PowerShell"))
        assertTrue(s.contains("Win + X"), s)
    }

    @Test
    fun `Linux — install_sh + linux 바이너리`() {
        val s = ProviderOnboarding.installCommand("linux", "TOK", "")
        assertTrue(s.contains("ollama.com/install.sh"), s)
        assertTrue(s.contains("releases/latest/download/discord-ai-network-bot-linux"), s)
        assertFalse(s.contains("discord-ai.yeon.world/download"), s)
        assertTrue(s.contains("curl -L -o discord-ai-network-bot-linux"), s)
        assertTrue(s.contains("chmod +x discord-ai-network-bot-linux"), s)
        assertTrue(s.contains("./discord-ai-network-bot-linux"), s)
        assertFalse(s.contains("discord-ai-network-bot-macos"), s)
        assertFalse(s.contains("discord-ai-network-bot-windows.exe"), s)
        assertTrue(s.contains("Ctrl + Alt + T"), s)
        assertTrue(s.contains("앱 메뉴"), s)
        assertTrue(s.contains("관리자에게 문의")) // relay 미설정 시 플레이스홀더
    }

    @Test
    fun `알 수 없는 OS 는 전체 안내로 폴백`() {
        val s = ProviderOnboarding.installCommand("solaris", "TOK", "wss://r/agent")
        assertTrue(s.contains("승인되었습니다"), s)
        assertTrue(s.contains("TOK"))
    }
}
