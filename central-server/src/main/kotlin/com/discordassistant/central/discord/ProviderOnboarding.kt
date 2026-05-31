package com.discordassistant.central.discord

/**
 * 프로바이더 온보딩 안내문(차수 12/13 UX). 승인 시 봇이 토큰만 주는 게 아니라
 * "이 토큰으로 무엇을 하면 되는지"를 단계별로 알려준다. 순수 함수라 단위 테스트 가능.
 * Discord 2000자 제한 안에서 핵심만.
 */
object ProviderOnboarding {
    // 단일 실행파일 다운로드 — 우리 도메인에서 직접 서빙(레포 비공개 유지). agent-build 가 원격에 배치.
    private const val DL = "https://central.dailyting.cloud/download"

    /**
     * OS 선택(버튼) 후 보여줄 **복붙용 설치 명령**(차수 19 UX). Ollama 설치 → 모델 받기 → 에이전트 실행까지 한 블록.
     * macOS/Linux 는 터미널, Windows 는 PowerShell(관리자) 기준. 토큰은 ⏳ 10분·1회용.
     */
    fun installCommand(
        os: String,
        token: String,
        relayUrl: String,
    ): String {
        val relay = relayUrl.ifBlank { "wss://<관리자에게 문의>/agent" }
        val note = "\n토큰은 ⏳ **10분·1회용**. 연결되면 `/내상태`(provider-status)로 확인하세요. **민감정보 입력 금지.**"
        return when (os.lowercase()) {
            "mac", "macos" ->
                "🍎 **macOS** — 터미널에 그대로 붙여넣기:\n" +
                    "```bash\n" +
                    "brew install ollama\n" +
                    "brew services start ollama\n" +
                    "ollama pull llama3.1:8b\n" +
                    "curl -L -o provider-agent $DL/discord-ai-provider-agent-macos && chmod +x provider-agent\n" +
                    "./provider-agent --token $token --relay-url $relay\n" +
                    "```" + note
            "windows", "win" ->
                "🪟 **Windows** — PowerShell(관리자)에 붙여넣기:\n" +
                    "```powershell\n" +
                    "winget install --id Ollama.Ollama -e --accept-source-agreements\n" +
                    "ollama pull llama3.1:8b\n" +
                    "Invoke-WebRequest $DL/discord-ai-provider-agent-windows.exe -OutFile provider-agent.exe\n" +
                    ".\\provider-agent.exe --token $token --relay-url $relay\n" +
                    "```" + note
            "linux" ->
                "🐧 **Linux** — 터미널에 붙여넣기:\n" +
                    "```bash\n" +
                    "curl -fsSL https://ollama.com/install.sh | sh\n" +
                    "ollama pull llama3.1:8b\n" +
                    "curl -L -o provider-agent $DL/discord-ai-provider-agent-linux && chmod +x provider-agent\n" +
                    "./provider-agent --token $token --relay-url $relay\n" +
                    "```" + note
            else -> message(token, relayUrl) // 알 수 없는 OS → 전체 안내로 폴백
        }
    }

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
