package com.discordassistant.central.discord

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** OS별 복붙 설치 명령(차수 19). 순수 함수라 JDA 없이 검증 — Ollama 설치 + 에이전트 실행 + 토큰/relay 포함. */
class ProviderOnboardingInstallTest {
    @Test
    fun `macOS — brew + 바이너리 + 토큰·relay`() {
        val s = ProviderOnboarding.installCommand("mac", "TOK-123", "wss://relay.example/agent")
        assertTrue(s.contains("brew install ollama"), s)
        assertTrue(s.contains("discord-ai-provider-agent-macos"))
        assertTrue(s.contains("--token TOK-123"))
        assertTrue(s.contains("wss://relay.example/agent"))
    }

    @Test
    fun `Windows — winget + PowerShell(관리자) + exe`() {
        val s = ProviderOnboarding.installCommand("windows", "TOK", "wss://r/agent")
        assertTrue(s.contains("winget install"), s)
        assertTrue(s.contains("provider-agent.exe"))
        assertTrue(s.contains("PowerShell"))
        assertTrue(s.contains("--token TOK"))
    }

    @Test
    fun `Linux — install_sh + linux 바이너리`() {
        val s = ProviderOnboarding.installCommand("linux", "TOK", "")
        assertTrue(s.contains("ollama.com/install.sh"), s)
        assertTrue(s.contains("discord-ai-provider-agent-linux"))
        assertTrue(s.contains("관리자에게 문의")) // relay 미설정 시 플레이스홀더
    }

    @Test
    fun `알 수 없는 OS 는 전체 안내로 폴백`() {
        val s = ProviderOnboarding.installCommand("solaris", "TOK", "wss://r/agent")
        assertTrue(s.contains("설치 가이드") || s.contains("승인되었습니다"), s)
        assertTrue(s.contains("--token TOK"))
    }
}
