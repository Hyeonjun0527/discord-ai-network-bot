package com.discordassistant.central.discord

/**
 * 프로바이더 온보딩 안내문(차수 12/13 UX). 승인 시 봇이 토큰만 주는 게 아니라
 * "이 토큰으로 무엇을 하면 되는지"를 단계별로 알려준다. 순수 함수라 단위 테스트 가능.
 * Discord 2000자 제한 안에서 핵심만.
 */
object ProviderOnboarding {
    // 버전관리되는 단일 실행파일 다운로드(GitHub Releases latest). agent-build 워크플로가 OS별 자산 첨부.
    private const val DL = "https://github.com/Hyeonjun0527/discord-assistant/releases/latest/download"

    fun message(
        token: String,
        relayUrl: String,
    ): String {
        val relay = relayUrl.ifBlank { "wss://<관리자에게 문의>/agent" }
        val sb = StringBuilder()
        sb.append("🖥️ **프로바이더로 승인되었습니다!** 내 PC 로컬 AI 를 풀에 연결하는 3단계:\n\n")
        sb.append("**1) Ollama 설치** — https://ollama.com → 설치 후 `ollama pull llama3.1:8b`\n\n")
        sb.append("**2) 에이전트 다운로드** (내 OS 파일 1개):\n")
        sb.append("• Windows: $DL/discord-ai-provider-agent-windows.exe\n")
        sb.append("• macOS: $DL/discord-ai-provider-agent-macos\n")
        sb.append("• Linux: $DL/discord-ai-provider-agent-linux\n\n")
        sb.append("**3) 받은 파일을 토큰과 함께 실행** (터미널 · 토큰 ⏳ **10분·1회용**):\n")
        sb.append("```\n# Windows\ndiscord-ai-provider-agent-windows.exe --token $token --relay-url $relay\n")
        sb.append("# macOS/Linux (먼저 chmod +x)\n./discord-ai-provider-agent-macos --token $token --relay-url $relay\n```\n")
        sb.append("연결되면 `/provider-status` 로 확인. ")
        if (relayUrl.isBlank()) {
            sb.append("⚠️ 연결 주소 미설정 시 관리자에게 `relay-url` 문의. ")
        }
        sb.append("비밀번호·API 키 등 **민감정보는 절대 입력하지 마세요.**")
        return sb.toString()
    }
}
