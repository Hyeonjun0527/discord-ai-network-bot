package com.discordassistant.central.discord

import com.discordassistant.central.platform.discord.ProviderOnboarding
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** OS별 복붙 설치 명령(차수 19). 순수 함수라 JDA 없이 검증 — Ollama 설치 + 에이전트 실행 + 토큰/relay 포함. */
class ProviderOnboardingInstallTest {
    @Test
    fun `macOS — GUI 앱(brew cask) 설치 + 토큰 붙여넣기`() {
        val s = ProviderOnboarding.installCommand("mac", "TOK-123", "wss://relay.example/agent")
        assertTrue(s.contains("brew install ollama"), s)
        assertTrue(s.contains("brew install --cask yeon-intergation-platform/tap/nexa"), s) // 맥 데스크톱 앱
        assertTrue(s.contains("NEXA"), s) // 앱 이름
        assertTrue(s.contains("디스코드 로그인"), s) // 앱에서 서버 선택/연결
        assertTrue(s.contains("TOK-123"), s) // 토큰(앱에 붙여넣기)
        // 옛 CLI 바이너리 다운로드 방식은 제거됨.
        assertFalse(s.contains("curl -L -o nexa-agent-macos"), s)
        assertFalse(s.contains("releases/latest/download/nexa-agent-macos"), s)
        assertTrue(s.contains("⌘ Space"), s)
    }

    @Test
    fun `Windows — GUI 앱(winget) 설치 + 토큰 붙여넣기`() {
        val s = ProviderOnboarding.installCommand("windows", "TOK", "wss://r/agent")
        assertTrue(s.contains("winget install"), s)
        assertTrue(s.contains("Nexa.Nexa"), s) // GUI 앱(맥과 동일)
        assertTrue(s.contains("NEXA"), s) // 앱 이름
        assertTrue(s.contains("TOK"), s) // 토큰(앱에 붙여넣기)
        // 옛 CLI exe 다운로드 방식은 제거됨.
        assertFalse(s.contains("nexa-agent-windows.exe"), s)
        assertFalse(s.contains("releases/latest/download"), s)
        assertTrue(s.contains("PowerShell"))
        assertTrue(s.contains("Win + X"), s)
    }

    @Test
    fun `Linux — 미지원(GUI 앱 없음) → 폴백 안내`() {
        // GUI 데스크톱 앱(NEXA)은 mac/Windows 만 배포 — Linux 가이드/다운로드는 폐기, 전체 안내로 폴백.
        val s = ProviderOnboarding.installCommand("linux", "TOK", "")
        assertTrue(s.contains("승인되었습니다"), s) // message() 폴백
        assertTrue(s.contains("TOK"), s)
        // 옛 Linux CLI 바이너리 다운로드 흔적이 전혀 없어야 한다.
        assertFalse(s.contains("nexa-agent-linux"), s)
        assertFalse(s.contains("ollama.com/install.sh"), s)
    }

    @Test
    fun `알 수 없는 OS 는 전체 안내로 폴백`() {
        val s = ProviderOnboarding.installCommand("solaris", "TOK", "wss://r/agent")
        assertTrue(s.contains("승인되었습니다"), s)
        assertTrue(s.contains("TOK"))
    }
}
