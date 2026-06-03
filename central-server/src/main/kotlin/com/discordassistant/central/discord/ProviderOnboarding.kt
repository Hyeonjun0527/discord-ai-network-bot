package com.discordassistant.central.discord

/**
 * 프로바이더 온보딩 안내문(차수 12/13 UX). 승인 시 봇이 토큰만 주는 게 아니라
 * "이 토큰으로 무엇을 하면 되는지"를 단계별로 알려준다. 순수 함수라 단위 테스트 가능.
 * Discord 2000자 제한 안에서 핵심만.
 */
object ProviderOnboarding {
    // 단일 실행파일 다운로드 — 사용자가 신뢰할 수 있게 GitHub Releases 자산으로 안내한다.
    private const val DL = "https://github.com/Hyeonjun0527/discord-ai-network-bot/releases/latest/download"

    // 설치 랜딩 페이지(차수 19) — OS별 복붙 명령 + 소스 코드 버튼. 링크만 던지지 않고 정제된 가이드로 안내.
    const val INSTALL_PAGE = "https://discord-ai.yeon.world/install"

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
        val note =
            "\n토큰은 ⏳ **10분·1회용**. 연결되면 `/내상태`(provider-status)로 확인하세요. " +
                "📄 웹 가이드·GitHub Release: $INSTALL_PAGE · **민감정보 입력 금지.**" +
                "\n🖼️ (선택) **이미지 생성도 제공**하려면 로컬 Stable Diffusion(A1111 등)을 켜고 " +
                "에이전트에 `--enable-image` 를 추가하세요. 그러면 `/imagine` 요청을 받을 수 있어요. " +
                "누구나 이미지 프로바이더가 될 수 있습니다."
        return when (os.lowercase()) {
            "mac", "macos" ->
                "🍎 **macOS** — 먼저 터미널을 여세요: `⌘ Space` → `Terminal` 입력 → Enter / 또는 Finder → 응용 프로그램 → 유틸리티 → 터미널.\n" +
                    "그다음 아래를 그대로 붙여넣기:\n" +
                    "```bash\n" +
                    "brew install ollama\n" +
                    "brew services start ollama\n" +
                    "ollama pull llama3.1:8b\n" +
                    "curl -L -o discord-ai-network-bot-macos $DL/discord-ai-network-bot-macos " +
                    "&& chmod +x discord-ai-network-bot-macos\n" +
                    "./discord-ai-network-bot-macos --token $token --relay-url $relay\n" +
                    "```" + note
            "windows", "win" ->
                "🪟 **Windows** — 먼저 PowerShell(관리자)을 여세요: `Win + X` → 터미널(관리자) / 또는 시작 메뉴에서 PowerShell 검색 → 우클릭 → 관리자 권한 실행.\n" +
                    "그다음 아래를 그대로 붙여넣기:\n" +
                    "```powershell\n" +
                    "winget install --id Ollama.Ollama -e --accept-source-agreements\n" +
                    "ollama pull llama3.1:8b\n" +
                    "Invoke-WebRequest $DL/discord-ai-network-bot-windows.exe -OutFile discord-ai-network-bot-windows.exe\n" +
                    ".\\discord-ai-network-bot-windows.exe --token $token --relay-url $relay\n" +
                    "```" + note
            "linux" ->
                "🐧 **Linux** — 먼저 터미널을 여세요: `Ctrl + Alt + T` / 또는 앱 메뉴에서 Terminal(터미널) 검색.\n" +
                    "그다음 아래를 그대로 붙여넣기:\n" +
                    "```bash\n" +
                    "curl -fsSL https://ollama.com/install.sh | sh\n" +
                    "ollama pull llama3.1:8b\n" +
                    "curl -L -o discord-ai-network-bot-linux $DL/discord-ai-network-bot-linux " +
                    "&& chmod +x discord-ai-network-bot-linux\n" +
                    "./discord-ai-network-bot-linux --token $token --relay-url $relay\n" +
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
        sb.append("🖥️ **프로바이더로 승인되었습니다!** 내 PC 로컬 AI 를 풀에 연결하기:\n\n")
        sb.append("**1) 설치 가이드 열기** → $INSTALL_PAGE\n")
        sb.append("   내 OS 탭(macOS/Windows/Linux)에서 **GitHub Release 다운로드 명령**을 그대로 복사하세요.\n\n")
        sb.append("**2) 명령의 토큰 자리에 아래 값을 넣어 실행** (⏳ **10분·1회용**):\n")
        sb.append("```\n--token $token --relay-url $relay\n```\n")
        sb.append("연결되면 `/provider-status` 로 확인. ")
        if (relayUrl.isBlank()) {
            sb.append("⚠️ 연결 주소 미설정 시 관리자에게 `relay-url` 문의. ")
        }
        sb.append("비밀번호·API 키 등 **민감정보는 절대 입력하지 마세요.**")
        return sb.toString()
    }
}
