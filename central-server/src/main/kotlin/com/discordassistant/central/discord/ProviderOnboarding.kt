package com.discordassistant.central.discord

/**
 * 프로바이더 온보딩 안내문(차수 12/13 UX). 승인 시 봇이 토큰만 주는 게 아니라
 * "이 토큰으로 무엇을 하면 되는지"를 단계별로 알려준다. 순수 함수라 단위 테스트 가능.
 * Discord 2000자 제한 안에서 핵심만.
 */
object ProviderOnboarding {
    private const val DOCS = "https://github.com/Hyeonjun0527/discord-assistant/blob/main/provider-agent/README.md"

    fun message(
        token: String,
        relayUrl: String,
    ): String {
        val relay = relayUrl.ifBlank { "wss://<관리자에게 문의>/agent" }
        val sb = StringBuilder()
        sb.append("🖥️ **프로바이더로 승인되었습니다!** 내 PC의 로컬 AI(Ollama)를 풀에 연결하는 방법:\n\n")
        sb.append("**1) Ollama 준비** — https://ollama.com 설치 후\n")
        sb.append("```\nollama serve\nollama pull llama3.1:8b\n```\n")
        sb.append("**2) 에이전트 실행** (토큰은 ⏳ **10분·1회용**):\n")
        sb.append("```\ndiscord-ai-provider-agent \\\n  --token $token \\\n  --relay-url $relay\n```\n")
        sb.append("에이전트 설치(처음이면): $DOCS\n\n")
        sb.append("연결되면 `/provider-status` 로 상태 확인. ")
        if (relayUrl.isBlank()) {
            sb.append("⚠️ 아직 연결 주소가 설정 전이면 관리자에게 `relay-url` 을 받으세요. ")
        }
        sb.append("비밀번호·API 키 등 **민감정보는 절대 입력하지 마세요.**")
        return sb.toString()
    }
}
